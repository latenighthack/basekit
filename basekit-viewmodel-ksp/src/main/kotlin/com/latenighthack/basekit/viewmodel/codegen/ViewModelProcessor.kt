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
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import java.io.File

private const val VIEWMODEL_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec"
private const val VIEWMODEL_LIST_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelList"
private const val CHILD_VIEWMODEL_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ChildViewModel"
private const val CODEGEN_IGNORE_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.CodegenIgnore"
private const val VIEWMODEL_INJECT_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelInject"
private const val IDENTITY_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelIdentity"
private const val REACT_ADAPTER_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ReactBindingAdapter"
private const val VIEWMODEL_INTERFACE = "com.latenighthack.basekit.viewmodel.ViewModel"
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
                .filterNot { it.hasAnnotation(CODEGEN_IGNORE_ANNOTATION) }
                .toList()

            sourceFiles = symbols.mapNotNull { it.containingFile }
            symbols.mapNotNullTo(viewModels) { buildViewModel(it) }
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

    private fun buildViewModel(declaration: KSClassDeclaration): VmInfo? {
        if (declaration.classKind != ClassKind.INTERFACE || !declaration.isPublicBinding()) {
            logger.error("@ViewModelSpec must declare a public interface with a handwritten implementation", declaration)
            return null
        }
        val simpleName = declaration.simpleName.asString()
        val qualifiedName = declaration.qualifiedName?.asString() ?: return null
        val packageName = declaration.packageName.asString()

        val stateType = declaration.getAllSuperTypes()
            .firstOrNull { it.declaration.qualifiedName?.asString() == VIEWMODEL_INTERFACE }
            ?.arguments?.firstOrNull()?.type?.resolve()
        val stateDecl = stateType?.declaration as? KSClassDeclaration
        if (stateDecl == null) {
            logger.error("@ViewModelSpec $simpleName must implement ViewModel<State>", declaration)
            return null
        }

        val stateProperties = stateDecl.getAllProperties().filter { it.isPublicBinding() }.mapNotNull { prop ->
            val resolved = prop.type.resolve()
            val type = resolved.declaration
            val typeQn = type.qualifiedName?.asString() ?: return@mapNotNull null
            // For a List<E>/MutableList<E>, peel the element type so the platform generators can emit a
            // typed collection rather than the erased-object fallback.
            val elementQn = if (typeQn == "kotlin.collections.List" || typeQn == "kotlin.collections.MutableList") {
                resolved.arguments.firstOrNull()?.type?.resolve()?.declaration?.qualifiedName?.asString()
            } else {
                null
            }
            VmStateProperty(prop.simpleName.asString(), type.simpleName.asString(), typeQn, resolved.isMarkedNullable, elementQn, resolved.bindingType(), prop.reactAdapter())
        }.toList()

        val boundFunctions = declaration.getAllFunctions()
            .filter { fn ->
                fn.isPublicBinding() && fn.modifiers.contains(Modifier.SUSPEND) &&
                    !fn.simpleName.asString().startsWith("<") &&
                    fn.annotations.none { it.qualifiedName() == CODEGEN_IGNORE_ANNOTATION }
            }
            .toList()

        // Zero-arg suspend functions are actions; single-arg ones are mutators (they set one State
        // field and, on SwiftUI, back a two-way Binding). Anything with more parameters is unbound.
        val actions = boundFunctions
            .filter { it.parameters.isEmpty() }
            .map { VmAction(it.simpleName.asString()) }

        val mutators = boundFunctions
            .filter { it.parameters.size == 1 }
            .mapNotNull { fn ->
                val param = fn.parameters.first()
                val resolvedParam = param.type.resolve()
                val paramType = resolvedParam.declaration
                val paramQn = paramType.qualifiedName?.asString() ?: return@mapNotNull null
                VmMutator(
                    name = fn.simpleName.asString(),
                    paramName = param.name?.asString() ?: "value",
                    paramTypeSimpleName = paramType.simpleName.asString(),
                    paramTypeQualifiedName = paramQn,
                    paramTypeNullable = resolvedParam.isMarkedNullable,
                    type = resolvedParam.bindingType(),
                    reactAdapter = param.reactAdapter(),
                )
            }

        boundFunctions
            .filter { it.parameters.size > 1 }
            .forEach { fn ->
                logger.error("Action ${fn.simpleName.asString()} on $simpleName has multiple parameters; use one immutable command argument or @CodegenIgnore", fn)
            }

        boundFunctions.forEach { fn ->
            if (fn.typeParameters.isNotEmpty() || fn.extensionReceiver != null || fn.parameters.any { it.isVararg } ||
                fn.returnType?.resolve()?.declaration?.qualifiedName?.asString() != "kotlin.Unit") {
                logger.error("Binding ${fn.simpleName.asString()} must be a non-generic, non-extension suspend function returning Unit without varargs; use @CodegenIgnore", fn)
            }
        }
        boundFunctions.groupBy { it.simpleName.asString() }.filterValues { it.size > 1 }.forEach { (name, functions) ->
            logger.error("Overloaded binding $name on $simpleName is ambiguous; rename or use @CodegenIgnore", functions.first())
        }

        // The Swift wrapper adds an `@objc {action}Action(_:)` target-action thunk per zero-arg
        // action (see targetActionThunks). If the ViewModel already declares a member with that
        // name, the generated file would not compile — flag it here, where the source location is
        // still known, rather than in the consumer's Xcode build.
        val boundNames = (actions.map { it.name } + mutators.map { it.name }).toSet()
        actions
            .map { targetActionThunkName(it.name) }
            .filter { it in boundNames }
            .forEach { collision ->
                logger.error("$simpleName declares `$collision`, which collides with the generated target-action thunk for the action it would shadow; rename one of them")
            }

        val properties = declaration.getAllProperties().filter { it.isPublicBinding() }.toList()

        val lists = properties
            .filter { it.hasAnnotation(VIEWMODEL_LIST_ANNOTATION) }
            .mapNotNull { prop -> buildList(simpleName, prop) }

        val children = properties
            .filter { it.hasAnnotation(CHILD_VIEWMODEL_ANNOTATION) }
            .mapNotNull { prop ->
                val resolved = prop.type.resolve()
                val decl = resolved.declaration
                if (resolved.isMarkedNullable || prop.isMutable || !decl.hasAnnotation(VIEWMODEL_ANNOTATION) || decl.hasAnnotation(CODEGEN_IGNORE_ANNOTATION)) {
                    logger.error("@ChildViewModel must be a stable non-null val of a generated @ViewModelSpec type", prop)
                    return@mapNotNull null
                }
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
            stateSwiftName = stateDecl.swiftExportName(),
            stateProperties = stateProperties,
            actions = actions,
            mutators = mutators,
            lists = lists,
            children = children,
            identityProperty = identityProperty(declaration),
        )
    }

    private fun buildList(ownerName: String, prop: com.google.devtools.ksp.symbol.KSPropertyDeclaration): VmList? {
        val flow = prop.type.resolve()
        val delta = flow.arguments.singleOrNull()?.type?.resolve()
        if (flow.isMarkedNullable || flow.declaration.qualifiedName?.asString() != "kotlinx.coroutines.flow.Flow" ||
            delta?.declaration?.qualifiedName?.asString() != "com.latenighthack.deltalist.Delta" || delta.isMarkedNullable) {
            logger.error("@ViewModelList ${prop.simpleName.asString()} on $ownerName must be Flow<Delta<ChildVm>>", prop)
            return null
        }
        val elementType = delta.arguments.singleOrNull()?.type?.resolve()
        val elementDecl = elementType?.declaration as? KSClassDeclaration
        if (elementDecl == null || elementType.isMarkedNullable) {
            logger.error("@ViewModelList ${prop.simpleName.asString()} on $ownerName must declare a concrete non-null child type", prop)
            return null
        }
        val elementQn = elementDecl.qualifiedName?.asString() ?: return null

        val annotation = prop.annotations.first { it.qualifiedName() == VIEWMODEL_LIST_ANNOTATION }
        val declaredTypes = (annotation.arguments
            .firstOrNull { it.name?.asString() == "possibleTypes" }
            ?.value as? List<*>)
            .orEmpty()
            .mapNotNull { it as? KSType }

        if (declaredTypes.isEmpty()) {
            logger.error("@ViewModelList ${prop.simpleName.asString()} on $ownerName must declare its exact possibleTypes")
            return null
        }

        val invalidTypes = declaredTypes.filterNot { elementType.isAssignableFrom(it) }
        if (invalidTypes.isNotEmpty()) {
            logger.error(
                "@ViewModelList ${prop.simpleName.asString()} on $ownerName declares types outside $elementQn: " +
                    invalidTypes.joinToString { it.declaration.qualifiedName?.asString().orEmpty() }
            )
            return null
        }

        val distinctTypes = declaredTypes.distinctBy { it.declaration.qualifiedName?.asString() }
        if (distinctTypes.size != declaredTypes.size) {
            logger.error("@ViewModelList ${prop.simpleName.asString()} on $ownerName contains duplicate possibleTypes")
            return null
        }

        val unboundTypes = distinctTypes
            .mapNotNull { it.declaration as? KSClassDeclaration }
            .filterNot { it.hasAnnotation(VIEWMODEL_ANNOTATION) && !it.hasAnnotation(CODEGEN_IGNORE_ANNOTATION) }
        if (unboundTypes.isNotEmpty()) {
            logger.error(
                "@ViewModelList ${prop.simpleName.asString()} on $ownerName declares child types without " +
                    "@ViewModelSpec bindings: ${unboundTypes.joinToString { it.qualifiedName?.asString().orEmpty() }}"
            )
            return null
        }

        // A base type alongside one of its subtypes would make classification order-dependent and
        // therefore not precise. Exact polymorphic lists must declare a non-overlapping type set.
        for (leftIndex in distinctTypes.indices) {
            for (rightIndex in (leftIndex + 1)..<distinctTypes.size) {
                val left = distinctTypes[leftIndex]
                val right = distinctTypes[rightIndex]
                if (left.isAssignableFrom(right) || right.isAssignableFrom(left)) {
                    logger.error(
                        "@ViewModelList ${prop.simpleName.asString()} on $ownerName has overlapping possibleTypes " +
                            "${left.declaration.qualifiedName?.asString()} and ${right.declaration.qualifiedName?.asString()}"
                    )
                    return null
                }
            }
        }

        val possibleTypes = distinctTypes.mapNotNull { type ->
            val declaration = type.declaration as? KSClassDeclaration ?: return@mapNotNull null
            val qn = declaration.qualifiedName?.asString() ?: return@mapNotNull null
            val stateDeclaration = declaration.getAllSuperTypes()
                .firstOrNull { it.declaration.qualifiedName?.asString() == VIEWMODEL_INTERFACE }
                ?.arguments?.firstOrNull()?.type?.resolve()
                ?.declaration as? KSClassDeclaration
            // An `id` only serves as SwiftUI/DeltaList identity if it bridges to a statically
            // Hashable Swift type. Value-class / object ids erase to `AnyObject?`, which is not
            // `Hashable`, so those fall back to per-wrapper `ObjectIdentifier` identity instead.
            val idProperty = stateDeclaration?.getDeclaredProperties()
                ?.firstOrNull { it.simpleName.asString() == "id" }
            val idIsHashable = idProperty?.let { prop ->
                val resolved = prop.type.resolve()
                val idQn = resolved.declaration.qualifiedName?.asString() ?: return@let false
                idQn in setOf("kotlin.String", "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Boolean", "kotlin.Float", "kotlin.Double")
            } == true
            VmListElementType(
                simpleName = declaration.simpleName.asString(),
                qualifiedName = qn,
                hasId = idIsHashable,
                stateQualifiedName = stateDeclaration?.qualifiedName?.asString(),
                identityProperty = identityProperty(declaration),
                reactId = idProperty?.type?.resolve()?.let { !it.isMarkedNullable && it.declaration.qualifiedName?.asString() in setOf("kotlin.String", "kotlin.Int", "kotlin.Short", "kotlin.Byte") } == true,
            )
        }
        val caseNames = possibleTypes.map { it.simpleName.toListCaseName() }
        if (caseNames.distinct().size != caseNames.size) {
            logger.error(
                "@ViewModelList ${prop.simpleName.asString()} on $ownerName produces duplicate generated case names: " +
                    caseNames.joinToString()
            )
            return null
        }

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
            possibleTypes = possibleTypes,
        )
    }

    private fun KSAnnotated.isPublicBinding(): Boolean =
        !hasAnnotation(CODEGEN_IGNORE_ANNOTATION) &&
            (this !is com.google.devtools.ksp.symbol.KSDeclaration ||
                modifiers.none { it in setOf(Modifier.PRIVATE, Modifier.PROTECTED, Modifier.INTERNAL) })

    private fun identityProperty(declaration: KSClassDeclaration): String? {
        val ids = declaration.getAllProperties().filter { it.hasAnnotation(IDENTITY_ANNOTATION) && !it.hasAnnotation(CODEGEN_IGNORE_ANNOTATION) }.toList()
        if (ids.size > 1) logger.error("Only one @ViewModelIdentity is allowed", declaration)
        val prop = ids.singleOrNull() ?: return null
        val type = prop.type.resolve()
        if (!prop.isPublicBinding() || prop.isMutable || type.isMarkedNullable || type.declaration.qualifiedName?.asString() != "kotlin.String") {
            logger.error("@ViewModelIdentity must be a public non-null String val", prop)
            return null
        }
        return prop.simpleName.asString()
    }

    private fun KSAnnotated.reactAdapter(): ReactAdapter? {
        val toJs = stringArgument(REACT_ADAPTER_ANNOTATION, "toJs") ?: return null
        return ReactAdapter(toJs, stringArgument(REACT_ADAPTER_ANNOTATION, "fromJs").orEmpty(),
            stringArgument(REACT_ADAPTER_ANNOTATION, "exportedType").orEmpty())
    }

    private fun KSType.bindingType(): VmType {
        val klass = declaration as? KSClassDeclaration
        return VmType(
            qualifiedName = declaration.qualifiedName?.asString() ?: "kotlin.Any",
            nullable = isMarkedNullable,
            arguments = arguments.map { it.type?.resolve()?.bindingType() ?: VmType("kotlin.Any", true) },
            jsName = klass?.jsExportName() ?: declaration.simpleName.asString(),
            swiftName = klass?.swiftExportName() ?: declaration.simpleName.asString(),
            enumCases = klass?.takeIf { it.classKind == ClassKind.ENUM_CLASS }?.declarations
                ?.filterIsInstance<KSClassDeclaration>()?.filter { it.classKind == ClassKind.ENUM_ENTRY }
                ?.map { it.simpleName.asString() }?.toList(),
            jsExported = declaration.hasAnnotation("kotlin.js.JsExport") || declaration.containingFile?.hasAnnotation("kotlin.js.JsExport") == true,
            objcRepresentable = klass != null && Modifier.VALUE !in klass.modifiers && Modifier.INLINE !in klass.modifiers,
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
