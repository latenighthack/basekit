package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.AnnotationUseSiteTarget
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

/**
 * Builds the [VmInfo] model for one `@ViewModelSpec` interface and reports invalid declarations to
 * [logger]. [ViewModelProcessor] and the catalog processor both read specs through this class.
 */
class ViewModelExtractor(private val logger: KSPLogger) {

    fun build(declaration: KSClassDeclaration): VmInfo? {
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
            VmStateProperty(prop.simpleName.asString(), type.simpleName.asString(), typeQn, resolved.isMarkedNullable, elementQn, resolved.bindingType(), prop.reactAdapter(), prop.swiftExportedName())
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
                VmChild(prop.simpleName.asString(), decl.simpleName.asString(), qn, prop.swiftExportedName())
            }

        val identity = identityProperty(declaration)

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
            identityProperty = identity?.simpleName?.asString(),
            identitySwiftName = identity?.swiftExportedName(),
        )
    }

    private fun buildList(ownerName: String, prop: KSPropertyDeclaration): VmList? {
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
                identityProperty = identityProperty(declaration)?.simpleName?.asString(),
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
            swiftName = prop.swiftExportedName(),
        )
    }

    private fun KSAnnotated.isPublicBinding(): Boolean =
        !hasAnnotation(CODEGEN_IGNORE_ANNOTATION) &&
            (this !is KSDeclaration ||
                modifiers.none { it in setOf(Modifier.PRIVATE, Modifier.PROTECTED, Modifier.INTERNAL) })

    private fun identityProperty(declaration: KSClassDeclaration): KSPropertyDeclaration? {
        val ids = declaration.getAllProperties().filter { it.hasAnnotation(IDENTITY_ANNOTATION) && !it.hasAnnotation(CODEGEN_IGNORE_ANNOTATION) }.toList()
        if (ids.size > 1) logger.error("Only one @ViewModelIdentity is allowed", declaration)
        val prop = ids.singleOrNull() ?: return null
        val type = prop.type.resolve()
        if (!prop.isPublicBinding() || prop.isMutable || type.isMarkedNullable || type.declaration.qualifiedName?.asString() != "kotlin.String") {
            logger.error("@ViewModelIdentity must be a public non-null String val", prop)
            return null
        }
        return prop
    }

    private fun KSPropertyDeclaration.swiftExportedName(): String {
        // On a primary-constructor `val`, a bare `@ObjCName` names the parameter; only
        // `@property:ObjCName` names the property.
        val inConstructor = (parentDeclaration as? KSClassDeclaration)?.primaryConstructor?.parameters
            ?.any { (it.isVal || it.isVar) && it.name?.asString() == simpleName.asString() } == true
        val objcName = annotations.firstOrNull {
            it.qualifiedName() == OBJC_NAME_ANNOTATION &&
                (it.useSiteTarget == AnnotationUseSiteTarget.PROPERTY || (it.useSiteTarget == null && !inConstructor))
        }
        fun argument(name: String) = objcName?.arguments?.firstOrNull { it.name?.asString() == name }?.value as? String
        return swiftExportedPropertyName(simpleName.asString(), argument("name"), argument("swiftName"))
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
}
