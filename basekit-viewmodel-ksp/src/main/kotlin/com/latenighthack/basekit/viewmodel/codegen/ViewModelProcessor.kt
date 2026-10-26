package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.Modifier
import java.io.File

internal const val VIEWMODEL_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec"
internal const val VIEWMODEL_LIST_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelList"
internal const val CHILD_VIEWMODEL_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ChildViewModel"
internal const val CODEGEN_IGNORE_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.CodegenIgnore"
private const val VIEWMODEL_INJECT_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelInject"
internal const val IDENTITY_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelIdentity"
internal const val FLOW = "kotlinx.coroutines.flow.Flow"
internal const val DELTA = "com.latenighthack.deltalist.Delta"
internal const val DELTA_LIST = "com.latenighthack.deltalist.DeltaList"
internal const val SECTIONED_DELTA = "com.latenighthack.deltalist.SectionedDelta"
internal const val SECTIONED_DELTA_LIST = "com.latenighthack.deltalist.SectionedDeltaList"
internal const val OBJC_NAME_ANNOTATION = "kotlin.native.ObjCName"
internal const val REACT_ADAPTER_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ReactBindingAdapter"
internal const val VIEWMODEL_INTERFACE = "com.latenighthack.basekit.viewmodel.ViewModel"
internal const val VIEWMODEL_FAILURE_HANDLER = "com.latenighthack.basekit.viewmodel.ViewModelFailureHandler"
private const val INJECT_ANNOTATION = "me.tatarka.inject.annotations.Inject"
private const val ASSISTED_ANNOTATION = "me.tatarka.inject.annotations.Assisted"
/** basekit-fake's marker on a generated fake; it replaces the implementation of the same spec. */
internal const val GENERATED_FAKE_ANNOTATION = "com.latenighthack.basekit.fake.GeneratedFake"

/**
 * Discovers `@ViewModelSpec` interfaces and emits native binding wrappers, one platform per KSP pass:
 *
 *  - **android** pass -> [AndroidBindingGenerator] (Kotlin into `androidMain`)
 *  - **apple** passes -> [SwiftKvoGenerator] (`Kvo{Vm}.swift`) + [SwiftUIObservableGenerator]
 *    (`Observable{Vm}.swift`), plus the Kotlin list adapters they collect ([AppleBoundListsGenerator]);
 *    ios/macos/tvos/watchos all take this branch and emit identical files
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

    private val reactEnumNames: ReactEnumNames = ReactEnumNames.parse(options[REACT_ENUM_NAMES_OPTION]) ?: run {
        logger.error("$REACT_ENUM_NAMES_OPTION must be one of ${ReactEnumNames.entries.joinToString { it.option }}, not \"${options[REACT_ENUM_NAMES_OPTION]}\"")
        ReactEnumNames.NAME
    }
    private val extractor = ViewModelExtractor(logger, reactEnumNames)
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

    // Every public @ViewModelInject implementation as a factory of its @Assisted parameters, emitted as
    // GeneratedViewModelFactories on the metadata pass for an app component to extend.
    private val factoryInfos = mutableListOf<ViewModelFactoryInfo>()
    // Every @Destination by qualified name: each needs a factory once the graph uses generated factories.
    private val destinationSites = mutableMapOf<String, DestinationSite>()
    private val factorySourceFiles = mutableListOf<KSFile>()
    private var navigationPackage: String? = null

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (!platformCollected) {
            platformCollected = true
            // By name, not the file system's order KSP reads files in, so every machine writes the same code.
            val symbols = resolver.getSymbolsWithAnnotation(VIEWMODEL_ANNOTATION)
                .filterIsInstance<KSClassDeclaration>()
                .filterNot { it.hasAnnotation(CODEGEN_IGNORE_ANNOTATION) }
                .sortedBy { it.qualifiedName?.asString() }
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

        if (navigationPackage == null) navigationPackage = resolveNavigationPackage(resolver)
        resolver.getSymbolsWithAnnotation(DESTINATION_ANNOTATION).filterIsInstance<KSClassDeclaration>().forEach { destination ->
            val navName = destination.stringArgument(DESTINATION_ANNOTATION, "navName")?.takeIf { it.isNotEmpty() }
                ?: destination.simpleName.asString().toDestinationNavName()
            val qualifiedName = destination.qualifiedName?.asString() ?: return@forEach
            val location = (destination.location as? FileLocation)?.let { "${it.filePath}:${it.lineNumber}" }
            destinationSites[qualifiedName] = DestinationSite(navName.toUpperCamelCase().replaceFirstChar { it.lowercase() }, qualifiedName, location)
        }
        for (impl in resolver.getSymbolsWithAnnotation(VIEWMODEL_INJECT_ANNOTATION).filterIsInstance<KSClassDeclaration>().filterNot { it.hasAnnotation(CODEGEN_IGNORE_ANNOTATION) }.sortedBy { it.qualifiedName?.asString() }) {
            buildFactoryInfo(impl)?.let { info ->
                factoryInfos.add(info)
                impl.containingFile?.let(factorySourceFiles::add)
            }
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

        return InjectVmInfo(interfaceQn, implQn, impl.hasAnnotation(GENERATED_FAKE_ANNOTATION))
    }

    /**
     * The factory for one implementation, or null when it is not public, implements no spec, or has an
     * `@Assisted` parameter whose type cannot be named here.
     */
    private fun buildFactoryInfo(impl: KSClassDeclaration): ViewModelFactoryInfo? {
        val implQn = impl.qualifiedName?.asString() ?: return null
        if (impl.modifiers.any { it in setOf(Modifier.PRIVATE, Modifier.INTERNAL, Modifier.PROTECTED) }) return null
        val spec = impl.getAllSuperTypes()
            .mapNotNull { it.declaration as? KSClassDeclaration }
            .firstOrNull { it.hasAnnotation(VIEWMODEL_ANNOTATION) }
            ?: return null
        val ctor = impl.getConstructors().firstOrNull { it.hasAnnotation(INJECT_ANNOTATION) } ?: impl.primaryConstructor
        val parameterTypes = ctor?.parameters.orEmpty()
            .filter { it.hasAnnotation(ASSISTED_ANNOTATION) }
            .map { parameter ->
                assistedParameterType(parameter, navigationPackage) ?: run {
                    logger.warn(
                        "GeneratedViewModelFactories: cannot name the type of @Assisted ${parameter.name?.asString()} " +
                            "on $implQn; it has no factory there",
                        parameter,
                    )
                    return null
                }
            }
        val navName = spec.stringArgument(DESTINATION_ANNOTATION, "navName")?.takeIf { it.isNotEmpty() }
            ?: spec.simpleName.asString().toDestinationNavName()
        val name = navName.toUpperCamelCase().replaceFirstChar { it.lowercase() } + "Factory"
        return ViewModelFactoryInfo(name, implQn, parameterTypes, factoryRoute(spec, navName, parameterTypes), impl.hasAnnotation(GENERATED_FAKE_ANNOTATION))
    }

    /**
     * How a navigation entry supplies [spec]'s factory: its args, its Apple navigator and its responder.
     * Null when [spec] is not a `@Destination`, or when a parameter is none of those.
     */
    private fun factoryRoute(spec: KSClassDeclaration, navName: String, parameterTypes: List<String>): FactoryRoute? {
        if (!spec.hasAnnotation(DESTINATION_ANNOTATION)) return null
        val superTypes = spec.getAllSuperTypes().toList()
        fun argument(fqn: String, index: Int) = superTypes.firstOrNull { it.declaration.qualifiedName?.asString() == fqn }
            ?.arguments?.getOrNull(index)?.type?.resolve()?.declaration as? KSClassDeclaration
        val responding = superTypes.any { it.declaration.qualifiedName?.asString() == RESPONDING_DESTINATION }
        val args = (argument(RESPONDING_DESTINATION, 0) ?: argument(NAVIGATION_DESTINATION, 0))?.qualifiedName?.asString()
        val parameters = parameterTypes.map { type ->
            val simple = type.substringBefore('<').substringAfterLast('.')
            when {
                type == args -> RouteParameter.ARGS
                type.startsWith(NAVIGATION_RESPONDER) -> RouteParameter.RESPONDER
                simple.endsWith("Navigator") || simple == "CloseNavigationTarget" -> RouteParameter.NAVIGATOR
                else -> return null
            }
        }
        val cap = navName.toUpperCamelCase()
        return FactoryRoute(
            caseName = cap.replaceFirstChar { it.lowercase() },
            specSimpleName = spec.simpleName.asString(),
            navigatorType = "Apple${cap}Navigator",
            hasArgs = args != null,
            responding = responding,
            parameters = parameters,
            specQualifiedName = spec.qualifiedName?.asString() ?: spec.simpleName.asString(),
        )
    }

    /** `Basekit_NavigationPackage`, else the navigation processor's fallback: the destinations' common package. */
    private fun resolveNavigationPackage(resolver: Resolver): String {
        options[NAVIGATION_PACKAGE_OPTION]?.takeIf { it.isNotEmpty() }?.let { return it }
        val packages = resolver.getSymbolsWithAnnotation(DESTINATION_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .map { it.packageName.asString() }
            .toList()
        return commonPackage(packages).ifEmpty { "navigation" }
    }

    override fun finish() {
        // Emit a marker to learn the output path, then route to the generator for this pass.
        codeGenerator.createNewFile(Dependencies(false), MARKER_PACKAGE, "basekit_viewmodel_marker", "log").close()
        val marker = codeGenerator.generatedFile.firstOrNull() ?: return
        val pass = marker.pass()

        // Every pass: a spec declared in a platform source set (a JS-only view model, say) gets its
        // decorator there, since that platform's bindings install it with `observing`.
        if (viewModels.isNotEmpty()) {
            ObservingViewModelGenerator(codeGenerator, Dependencies(aggregating = true, *sourceFiles.toTypedArray()))
                .generate(if (pass == Pass.METADATA) viewModels else viewModels.filterNot { it.qualifiedName in existingActionDecorators })
        }

        // The kotlin-inject bindings module is platform-agnostic: emit it once, on the metadata pass, so
        // it lands in commonMain. (The jvm target is also Pass.OTHER, so keying on METADATA avoids a dup.)
        if (pass == Pass.METADATA) {
            if (injectInfos.isNotEmpty() && injectPackage.isNotEmpty()) {
                val dependencies = Dependencies(aggregating = true, *injectSourceFiles.toTypedArray())
                ViewModelModuleGenerator(codeGenerator, dependencies).generate(uniqueBindings(injectInfos), injectPackage)
            }
            reportMissingDestinationFactories()
            generateFactories()
            return
        }

        if (viewModels.isEmpty()) return

        val dependencies = Dependencies(aggregating = true, *sourceFiles.toTypedArray())
        when (pass) {
            Pass.ANDROID -> {
                AndroidBindingGenerator(codeGenerator, dependencies).generate(viewModels)
                if (options["basekit.viewmodel.compose"] == "true") {
                    ComposeGenerator(codeGenerator, dependencies).generate(viewModels)
                    // The Compose counterpart of the Apple pass's destination models.
                    val routes = uniqueFactories(warn = false).mapNotNull { factory -> factory.route?.let { factory to it } }
                    if (routes.any { (_, route) -> RouteParameter.NAVIGATOR in route.parameters } && !reportMissingDestinationFactories()) {
                        val factoryDependencies = Dependencies(aggregating = true, *(sourceFiles + factorySourceFiles).toTypedArray())
                        val factoriesPackage = commonPackage(uniqueFactories(warn = false).map { it.implQualifiedName.substringBeforeLast('.', "") })
                        ComposeDestinationModelGenerator(codeGenerator, factoryDependencies, navigationPackage.orEmpty(), factoriesPackage).generate(routes)
                    }
                }
            }
            Pass.APPLE -> {
                try {
                    viewModels.forEach { vm ->
                        vm.stateProperties.forEach { it.swiftBindingType() }
                        vm.mutators.forEach { swiftType(it.type) }
                    }
                    AppleBoundListsGenerator(codeGenerator, dependencies).generate(viewModels)
                    SwiftKvoGenerator(codeGenerator, dependencies, swiftFrameworkImports).generate(viewModels)
                    SwiftUIObservableGenerator(codeGenerator, dependencies, swiftFrameworkImports).generate(viewModels)
                    // A factory taking a navigator means the graph has edges, so the navigation processor
                    // generated BasekitRoute and the Apple navigators this file refers to.
                    val routes = uniqueFactories(warn = false).mapNotNull { factory -> factory.route?.let { factory to it } }
                    if (routes.any { (_, route) -> RouteParameter.NAVIGATOR in route.parameters } && !reportMissingDestinationFactories()) {
                        val factoryDependencies = Dependencies(aggregating = true, *(sourceFiles + factorySourceFiles).toTypedArray())
                        DestinationModelGenerator(codeGenerator, factoryDependencies, swiftFrameworkImports).generate(routes)
                    }
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
                    // The web counterpart of the Compose destination models, beside the navigation
                    // processor's web router.
                    var destinationRoutes = emptyList<FactoryRoute>()
                    if (options["basekit.viewmodel.react"] == "true") {
                        val routes = uniqueFactories(warn = false).mapNotNull { factory -> factory.route?.let { factory to it } }
                        if (routes.any { (_, route) -> RouteParameter.NAVIGATOR in route.parameters } && !reportMissingDestinationFactories()) {
                            val factoryDependencies = Dependencies(aggregating = true, *(sourceFiles + factorySourceFiles).toTypedArray())
                            val factoriesPackage = commonPackage(uniqueFactories(warn = false).map { it.implQualifiedName.substringBeforeLast('.', "") })
                            ReactDestinationModelGenerator(codeGenerator, factoryDependencies, navigationPackage.orEmpty(), factoriesPackage).generate(routes)
                            destinationRoutes = routes.map { it.second }
                        }
                    }
                    options["basekit.viewmodel.reactModule"]?.let {
                        ReactTypesGenerator(codeGenerator, dependencies).generate(viewModels, it, options["basekit.viewmodel.reactPackageVersion"] ?: "0.0.0", destinationRoutes)
                    }
                } catch (error: IllegalArgumentException) { logger.error(error.message.orEmpty()) }
                  catch (error: IllegalStateException) { logger.error(error.message.orEmpty()) }
            }
            Pass.METADATA, Pass.OTHER -> Unit // jvm pass produces no platform binding
        }
    }

    private fun uniqueFactories(warn: Boolean): List<ViewModelFactoryInfo> =
        uniqueFactories(factoryInfos) { message -> if (warn) logger.warn(message) }

    /**
     * Once a factory takes a navigator (the graph uses generated destination models), every destination
     * needs a factory a navigation entry can supply. Reports a build error naming each one that has none,
     * on the metadata pass (before any target compiles) and the Apple pass; returns whether any did.
     */
    private fun reportMissingDestinationFactories(): Boolean {
        val factories = uniqueFactories(warn = false)
        if (factories.none { factory -> factory.route?.parameters?.contains(RouteParameter.NAVIGATOR) == true }) return false
        val errors = missingDestinationFactories(destinationSites.values, factories)
        errors.forEach { logger.error(it) }
        return errors.isNotEmpty()
    }

    private fun generateFactories() {
        if (factoryInfos.isEmpty()) return
        val factories = uniqueFactories(warn = true)
        val packageName = commonPackage(factories.map { it.implQualifiedName.substringBeforeLast('.', "") })
        val dependencies = Dependencies(aggregating = true, *factorySourceFiles.toTypedArray())
        ViewModelFactoriesGenerator(codeGenerator, dependencies).generate(factories, packageName)
    }

    private enum class Pass { ANDROID, APPLE, JS, METADATA, OTHER }

    private companion object {
        const val MARKER_PACKAGE = "com.latenighthack.basekit.viewmodel.gen"
        const val SWIFT_FRAMEWORK_IMPORTS_OPTION = "basekit.viewmodel.swiftFrameworkImports"
        const val NAVIGATION_PACKAGE_OPTION = "Basekit_NavigationPackage"
        const val DESTINATION_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.Destination"
        const val NAVIGATION_DESTINATION = "com.latenighthack.basekit.navigation.NavigationDestination"
        const val RESPONDING_DESTINATION = "com.latenighthack.basekit.navigation.RespondingDestination"
        const val NAVIGATION_RESPONDER = "com.latenighthack.basekit.navigation.NavigationResponder"

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
