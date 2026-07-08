package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import java.io.File

private const val VIEWMODEL_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec"
private const val VIEWMODEL_LIST_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelList"
private const val CHILD_VIEWMODEL_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ChildViewModel"
private const val CODEGEN_IGNORE_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.CodegenIgnore"
private const val VIEWMODEL_INJECT_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelInject"
private const val VIEWMODEL_INTERFACE = "com.latenighthack.basekit.viewmodel.ViewModel"
private const val INJECT_ANNOTATION = "me.tatarka.inject.annotations.Inject"
private const val ASSISTED_ANNOTATION = "me.tatarka.inject.annotations.Assisted"

/**
 * Discovers `@ViewModelSpec` interfaces and emits native binding wrappers, one platform per KSP pass:
 *
 *  - **android** pass -> [AndroidBindingGenerator] (Kotlin into `androidMain`)
 *  - **ios** passes  -> [SwiftKvoGenerator] (`Kvo{Vm}.swift`)
 *  - **js** pass     -> [ReactHookGenerator] (Kotlin/JS into `jsMain`)
 *
 * The active pass is inferred from the KSP output path (as the reference ViewModel processor does),
 * so no per-target processor wiring is needed beyond adding this to each `ksp<Target>` configuration.
 */
class ViewModelProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private val viewModels = mutableListOf<VmInfo>()
    private var sourceFiles: List<KSFile> = emptyList()
    private var platformCollected = false

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
                .toList()

            sourceFiles = symbols.mapNotNull { it.containingFile }
            symbols.mapNotNullTo(viewModels) { buildViewModel(it) }
        }

        for (impl in resolver.getSymbolsWithAnnotation(VIEWMODEL_INJECT_ANNOTATION).filterIsInstance<KSClassDeclaration>()) {
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

    private fun buildViewModel(declaration: KSClassDeclaration): VmInfo? {
        val simpleName = declaration.simpleName.asString()
        val qualifiedName = declaration.qualifiedName?.asString() ?: return null
        val packageName = declaration.packageName.asString()

        val stateType = declaration.getAllSuperTypes()
            .firstOrNull { it.declaration.qualifiedName?.asString() == VIEWMODEL_INTERFACE }
            ?.arguments?.firstOrNull()?.type?.resolve()
        val stateDecl = stateType?.declaration as? KSClassDeclaration
        if (stateDecl == null) {
            logger.warn("@ViewModelSpec $simpleName does not implement ViewModel<State>; skipping")
            return null
        }

        val stateProperties = stateDecl.getDeclaredProperties().mapNotNull { prop ->
            val type = prop.type.resolve().declaration
            val typeQn = type.qualifiedName?.asString() ?: return@mapNotNull null
            VmStateProperty(prop.simpleName.asString(), type.simpleName.asString(), typeQn)
        }.toList()

        val actions = declaration.getDeclaredFunctions()
            .filter { fn ->
                fn.modifiers.contains(Modifier.SUSPEND) &&
                    !fn.simpleName.asString().startsWith("<") &&
                    fn.annotations.none { it.qualifiedName() == CODEGEN_IGNORE_ANNOTATION }
            }
            .mapNotNull { fn ->
                if (fn.parameters.isNotEmpty()) {
                    logger.warn("Action ${fn.simpleName.asString()} on $simpleName has parameters; only zero-arg actions are bound")
                    null
                } else {
                    VmAction(fn.simpleName.asString())
                }
            }
            .toList()

        val properties = declaration.getDeclaredProperties().toList()

        val lists = properties
            .filter { it.hasAnnotation(VIEWMODEL_LIST_ANNOTATION) }
            .mapNotNull { prop -> buildList(simpleName, prop) }

        val children = properties
            .filter { it.hasAnnotation(CHILD_VIEWMODEL_ANNOTATION) }
            .mapNotNull { prop ->
                val decl = prop.type.resolve().declaration
                val qn = decl.qualifiedName?.asString() ?: return@mapNotNull null
                VmChild(prop.simpleName.asString(), decl.simpleName.asString(), qn)
            }

        return VmInfo(
            simpleName = simpleName,
            qualifiedName = qualifiedName,
            packageName = packageName,
            webPath = declaration.stringArgument(VIEWMODEL_ANNOTATION, "webPath").orEmpty(),
            stateSimpleName = stateDecl.simpleName.asString(),
            stateQualifiedName = stateDecl.qualifiedName?.asString() ?: return null,
            stateProperties = stateProperties,
            actions = actions,
            lists = lists,
            children = children,
        )
    }

    private fun buildList(ownerName: String, prop: com.google.devtools.ksp.symbol.KSPropertyDeclaration): VmList? {
        // Property type is Flow<Delta<ElementVm>>.
        val elementType = prop.type.resolve()          // Flow<Delta<X>>
            .arguments.firstOrNull()?.type?.resolve()  // Delta<X>
            ?.arguments?.firstOrNull()?.type?.resolve() // X
        val elementDecl = elementType?.declaration as? KSClassDeclaration
        if (elementDecl == null) {
            logger.warn("@ViewModelList ${prop.simpleName.asString()} on $ownerName is not Flow<Delta<ChildVm>>; skipping")
            return null
        }
        val elementQn = elementDecl.qualifiedName?.asString() ?: return null

        val elementState = elementDecl.getAllSuperTypes()
            .firstOrNull { it.declaration.qualifiedName?.asString() == VIEWMODEL_INTERFACE }
            ?.arguments?.firstOrNull()?.type?.resolve()
            ?.declaration as? KSClassDeclaration

        return VmList(
            propertyName = prop.simpleName.asString(),
            elementSimpleName = elementDecl.simpleName.asString(),
            elementQualifiedName = elementQn,
            elementStateSimpleName = elementState?.simpleName?.asString(),
            elementStateQualifiedName = elementState?.qualifiedName?.asString(),
        )
    }

    override fun finish() {
        // Emit a marker to learn the output path, then route to the generator for this pass.
        codeGenerator.createNewFile(Dependencies(false), MARKER_PACKAGE, "basekit_viewmodel_marker", "log").close()
        val marker = codeGenerator.generatedFile.firstOrNull() ?: return
        val pass = marker.pass()

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
            Pass.ANDROID -> AndroidBindingGenerator(codeGenerator, dependencies).generate(viewModels)
            Pass.IOS -> SwiftKvoGenerator(codeGenerator, dependencies).generate(viewModels)
            Pass.JS -> ReactHookGenerator(codeGenerator, dependencies).generate(viewModels)
            Pass.METADATA, Pass.OTHER -> Unit // jvm pass produces no platform binding
        }
    }

    private enum class Pass { ANDROID, IOS, JS, METADATA, OTHER }

    private companion object {
        const val MARKER_PACKAGE = "com.latenighthack.basekit.viewmodel.gen"

        fun File.pass(): Pass {
            val path = invariantSeparatorsPath.lowercase()
            val afterKsp = path.substringAfter("/ksp/", "")
            return when {
                afterKsp.startsWith("metadata/") -> Pass.METADATA
                afterKsp.contains("android") -> Pass.ANDROID
                afterKsp.contains("ios") -> Pass.IOS
                afterKsp.startsWith("js/") || afterKsp.contains("/js") || afterKsp.contains("jsmain") -> Pass.JS
                else -> Pass.OTHER
            }
        }
    }
}
