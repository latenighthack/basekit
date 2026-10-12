package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import java.io.File

internal const val VIEWMODEL_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec"
internal const val VIEWMODEL_LIST_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelList"
internal const val CHILD_VIEWMODEL_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ChildViewModel"
internal const val CODEGEN_IGNORE_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.CodegenIgnore"
private const val VIEWMODEL_INJECT_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelInject"
internal const val IDENTITY_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelIdentity"
internal const val REACT_ADAPTER_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ReactBindingAdapter"
internal const val VIEWMODEL_INTERFACE = "com.latenighthack.basekit.viewmodel.ViewModel"
private const val INJECT_ANNOTATION = "me.tatarka.inject.annotations.Inject"
private const val ASSISTED_ANNOTATION = "me.tatarka.inject.annotations.Assisted"

/**
 * Discovers `@ViewModelSpec` interfaces and emits native binding wrappers, one platform per KSP pass:
 *
 *  - **android** pass -> [AndroidBindingGenerator] (Kotlin into `androidMain`)
 *  - **apple** passes -> [SwiftKvoGenerator] (`Kvo{Vm}.swift`) + [SwiftUIObservableGenerator]
 *    (`Observable{Vm}.swift`); ios/macos/tvos/watchos all take this branch and emit identical files
 *  - **js** pass     -> [ReactHookGenerator] (Kotlin/JS into `jsMain`)
 *
 * The active pass is inferred from the KSP output path (as the reference ViewModel processor does),
 * so no per-target processor wiring is needed beyond adding this to each `ksp<Target>` configuration.
 */
class ViewModelProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String> = emptyMap(),
) : SymbolProcessor {

    // Frameworks the generated Swift wrappers must `import` (comma-separated KSP arg). Needed when the
    // exported KMP types live in their own framework instead of the wrapper's own Swift target.
    private val swiftFrameworkImports: List<String> =
        options[SWIFT_FRAMEWORK_IMPORTS_OPTION]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private val extractor = ViewModelExtractor(logger)
    private val viewModels = mutableListOf<VmInfo>()
    private var sourceFiles: List<KSFile> = emptyList()
    private var platformCollected = false
    private val existingActionDecorators = mutableSetOf<String>()

    // Interface->impl bindings collected from @ViewModelInject, emitted into commonMain on the metadata
    // pass. Only VMs WITHOUT an `@Assisted` (per-screen navigator) parameter get a module binding: those
    // depend only on graph types. Assisted VMs are built by the platform component from kotlin-inject's
    // native `(Navigator) -> Impl` factory, so the module never needs the co-generated navigator type.
    private val injectInfos = mutableListOf<InjectVmInfo>()
    private val injectSourceFiles = mutableListOf<KSFile>()
    private var injectPackage: String = ""

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (!platformCollected) {
            platformCollected = true
            val symbols = resolver.getSymbolsWithAnnotation(VIEWMODEL_ANNOTATION)
                .filterIsInstance<KSClassDeclaration>()
                .filterNot { it.hasAnnotation(CODEGEN_IGNORE_ANNOTATION) }
                .toList()

            sourceFiles = symbols.mapNotNull { it.containingFile }
            symbols.mapNotNullTo(viewModels) { extractor.build(it) }
            viewModels.forEach { vm ->
                val decoratorName = listOf(vm.packageName, "Observing${vm.simpleName}").filter { it.isNotEmpty() }.joinToString(".")
                if (resolver.getClassDeclarationByName(resolver.getKSNameFromString(decoratorName)) != null) {
                    existingActionDecorators.add(vm.qualifiedName)
                }
            }
        }

        for (impl in resolver.getSymbolsWithAnnotation(VIEWMODEL_INJECT_ANNOTATION).filterIsInstance<KSClassDeclaration>().filterNot { it.hasAnnotation(CODEGEN_IGNORE_ANNOTATION) }) {
            buildInjectInfo(impl)?.let { info ->
                injectInfos.add(info)
                impl.containingFile?.let(injectSourceFiles::add)
                if (injectPackage.isEmpty()) injectPackage = impl.packageName.asString()
            }
        }

        return emptyList()
    }

    private fun buildInjectInfo(impl: KSClassDeclaration): InjectVmInfo? {
        val implQn = impl.qualifiedName?.asString() ?: return null

        val ctor = impl.getConstructors().firstOrNull { it.hasAnnotation(INJECT_ANNOTATION) }
            ?: impl.primaryConstructor
        // Assisted VMs are provided by the component via kotlin-inject's native impl factory; no binding here.
        if (ctor?.parameters?.any { it.hasAnnotation(ASSISTED_ANNOTATION) } == true) return null

        val interfaceQn = impl.getAllSuperTypes()
            .mapNotNull { it.declaration as? KSClassDeclaration }
            .firstOrNull { it.hasAnnotation(VIEWMODEL_ANNOTATION) }
            ?.qualifiedName?.asString()
        if (interfaceQn == null) {
            logger.warn("@ViewModelInject $implQn implements no @ViewModel interface; skipping")
            return null
        }

        return InjectVmInfo(interfaceQn, implQn)
    }

    override fun finish() {
        // Emit a marker to learn the output path, then route to the generator for this pass.
        codeGenerator.createNewFile(Dependencies(false), MARKER_PACKAGE, "basekit_viewmodel_marker", "log").close()
        val marker = codeGenerator.generatedFile.firstOrNull() ?: return
        val pass = marker.pass()

        if (viewModels.isNotEmpty() && (pass == Pass.METADATA || pass == Pass.OTHER)) {
            ObservingViewModelGenerator(codeGenerator, Dependencies(aggregating = true, *sourceFiles.toTypedArray()))
                .generate(if (pass == Pass.METADATA) viewModels else viewModels.filterNot { it.qualifiedName in existingActionDecorators })
        }

        // The kotlin-inject bindings module is platform-agnostic: emit it once, on the metadata pass, so
        // it lands in commonMain. (The jvm target is also Pass.OTHER, so keying on METADATA avoids a dup.)
        if (pass == Pass.METADATA) {
            if (injectInfos.isNotEmpty() && injectPackage.isNotEmpty()) {
                val dependencies = Dependencies(aggregating = true, *injectSourceFiles.toTypedArray())
                ViewModelModuleGenerator(codeGenerator, dependencies).generate(injectInfos, injectPackage)
            }
            return
        }

        if (viewModels.isEmpty()) return

        val dependencies = Dependencies(aggregating = true, *sourceFiles.toTypedArray())
        when (pass) {
            Pass.ANDROID -> {
                AndroidBindingGenerator(codeGenerator, dependencies).generate(viewModels)
                if (options["basekit.viewmodel.compose"] == "true") ComposeGenerator(codeGenerator, dependencies).generate(viewModels)
            }
            Pass.APPLE -> {
                try {
                    viewModels.forEach { vm ->
                        vm.stateProperties.forEach { it.swiftBindingType() }
                        vm.mutators.forEach { swiftType(it.type) }
                    }
                    SwiftKvoGenerator(codeGenerator, dependencies, swiftFrameworkImports).generate(viewModels)
                    SwiftUIObservableGenerator(codeGenerator, dependencies, swiftFrameworkImports).generate(viewModels)
                } catch (error: IllegalArgumentException) { logger.error(error.message.orEmpty()) }
                  catch (error: IllegalStateException) { logger.error(error.message.orEmpty()) }
            }
            Pass.JS -> {
                try {
                    if (options.containsKey("basekit.viewmodel.reactModule")) viewModels.forEach { vm ->
                        vm.stateProperties.forEach { reactType(it.type, it.reactAdapter) }
                        vm.mutators.forEach { reactType(it.type, it.reactAdapter); reactFromJs(it.type, it.paramName, it.reactAdapter) }
                    }
                    ReactHookGenerator(codeGenerator, dependencies).generate(viewModels)
                    options["basekit.viewmodel.reactModule"]?.let {
                        ReactTypesGenerator(codeGenerator, dependencies).generate(viewModels, it, options["basekit.viewmodel.reactPackageVersion"] ?: "0.0.0")
                    }
                } catch (error: IllegalArgumentException) { logger.error(error.message.orEmpty()) }
                  catch (error: IllegalStateException) { logger.error(error.message.orEmpty()) }
            }
            Pass.METADATA, Pass.OTHER -> Unit // jvm pass produces no platform binding
        }
    }

    private enum class Pass { ANDROID, APPLE, JS, METADATA, OTHER }

    private companion object {
        const val MARKER_PACKAGE = "com.latenighthack.basekit.viewmodel.gen"
        const val SWIFT_FRAMEWORK_IMPORTS_OPTION = "basekit.viewmodel.swiftFrameworkImports"

        // Every Apple target routes to the one Swift branch. Keeping this coarse is deliberate: the
        // generated Swift already discriminates UIKit vs AppKit with `#if canImport(...)`, and the
        // moment the Kotlin pass knew *which* Apple platform it was, sibling passes would emit
        // different bytes for the same file name — which `collectBasekitViewModelSwift`'s
        // DuplicatesStrategy.EXCLUDE resolves in unspecified walk order. It also means tvOS/watchOS
        // need no processor change. Note "macosarm64" contains no "ios" substring, so before this
        // list existed a macOS pass fell through to OTHER and silently emitted nothing.
        val APPLE_MARKERS = listOf("ios", "macos", "tvos", "watchos")

        fun File.pass(): Pass {
            val path = invariantSeparatorsPath.lowercase()
            val afterKsp = path.substringAfter("/ksp/", "")
            return when {
                afterKsp.startsWith("metadata/") -> Pass.METADATA
                afterKsp.contains("android") -> Pass.ANDROID
                APPLE_MARKERS.any { afterKsp.contains(it) } -> Pass.APPLE
                afterKsp.startsWith("js/") || afterKsp.contains("/js") || afterKsp.contains("jsmain") -> Pass.JS
                else -> Pass.OTHER
            }
        }
    }
}
