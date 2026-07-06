package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.getAllSuperTypes
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

private const val VIEWMODEL_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModel"
private const val VIEWMODEL_LIST_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelList"
private const val CHILD_VIEWMODEL_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ChildViewModel"
private const val CODEGEN_IGNORE_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.CodegenIgnore"
private const val VIEWMODEL_INTERFACE = "com.latenighthack.basekit.viewmodel.ViewModel"

/**
 * Discovers `@ViewModel` interfaces and emits native binding wrappers, one platform per KSP pass:
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
    private var collected = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (collected) return emptyList()
        collected = true

        val symbols = resolver.getSymbolsWithAnnotation(VIEWMODEL_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        sourceFiles = symbols.mapNotNull { it.containingFile }
        symbols.mapNotNullTo(viewModels) { buildViewModel(it) }

        return emptyList()
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
            logger.warn("@ViewModel $simpleName does not implement ViewModel<State>; skipping")
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
        if (viewModels.isEmpty()) return

        val dependencies = Dependencies(aggregating = true, *sourceFiles.toTypedArray())
        when (marker.pass()) {
            Pass.ANDROID -> AndroidBindingGenerator(codeGenerator, dependencies).generate(viewModels)
            Pass.IOS -> SwiftKvoGenerator(codeGenerator, dependencies).generate(viewModels)
            Pass.JS -> ReactHookGenerator(codeGenerator, dependencies).generate(viewModels)
            Pass.OTHER -> Unit // metadata / jvm passes produce no platform binding
        }
    }

    private enum class Pass { ANDROID, IOS, JS, OTHER }

    private companion object {
        const val MARKER_PACKAGE = "com.latenighthack.basekit.viewmodel.gen"

        fun File.pass(): Pass {
            val path = invariantSeparatorsPath.lowercase()
            val afterKsp = path.substringAfter("/ksp/", "")
            return when {
                afterKsp.startsWith("metadata/") -> Pass.OTHER
                afterKsp.contains("android") -> Pass.ANDROID
                afterKsp.contains("ios") -> Pass.IOS
                afterKsp.startsWith("js/") || afterKsp.contains("/js") || afterKsp.contains("jsmain") -> Pass.JS
                else -> Pass.OTHER
            }
        }
    }
}
