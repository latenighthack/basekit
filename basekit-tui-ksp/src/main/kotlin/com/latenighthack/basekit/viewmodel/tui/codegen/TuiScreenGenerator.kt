package com.latenighthack.basekit.viewmodel.tui.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits one `<Vm>Screen` per bound ViewModel: a [com.latenighthack.basekit.viewmodel.tui.TuiScreen]
 * that renders the state as a table, a `@ViewModelList` as a selectable list, and zero-arg actions as
 * key-bound buttons. The screen never navigates itself — Enter on a row invokes the selected element's
 * selection action, and any navigation happens through the navigator injected into that ViewModel.
 */
class TuiScreenGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val rootPackage: String,
) {
    fun generate(screens: List<ScreenInfo>) {
        for (screen in screens) {
            codeGenerator.createNewFile(dependencies, rootPackage, screen.screenClassName, "kt").use { out ->
                out.writeln(render(screen))
            }
        }
    }

    private fun render(screen: ScreenInfo): String = buildString {
        appendLine("package $rootPackage")
        appendLine()
        appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiScreen")
        appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiNavigation")
        appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiRender")
        appendLine("import com.latenighthack.basekit.viewmodel.tui.StateHolder")
        if (screen.list != null) appendLine("import com.latenighthack.basekit.viewmodel.tui.ListHolder")
        appendLine("import dev.tamboui.toolkit.Toolkit")
        appendLine("import dev.tamboui.toolkit.element.Element")
        appendLine("import dev.tamboui.toolkit.event.EventResult")
        appendLine("import dev.tamboui.tui.event.KeyCode")
        appendLine("import dev.tamboui.tui.event.KeyEvent")
        appendLine("import kotlinx.coroutines.launch")
        appendLine()

        appendLine("/** Generated TamboUI screen for [${screen.vmQualifiedName}]. */")
        appendLine("public class ${screen.screenClassName}(")
        appendLine("    private val viewModel: ${screen.vmQualifiedName},")
        appendLine("    private val nav: TuiNavigation,")
        appendLine(") : TuiScreen {")
        appendLine()
        appendLine("    private val stateHolder = StateHolder(nav.scope, viewModel.initialState, viewModel.state)")
        if (screen.list != null) {
            appendLine("    private val listHolder = ListHolder(nav.scope, viewModel.${screen.list.propertyName})")
            appendLine("    private var selectedIndex = 0")
        }
        appendLine()
        appendLine("    override val title: String = \"${screen.vmSimpleName}\"")
        appendLine()
        appendLine(renderMethod(screen))
        appendLine()
        appendLine(onKeyMethod(screen))
        appendLine("}")
    }

    private fun renderMethod(screen: ScreenInfo): String = buildString {
        val statePairs = screen.stateProps.joinToString(", ") { "\"${it.name}\" to state.${it.name}.toString()" }
        val hints = buildList {
            screen.actions.forEach { add("\"[${it.key}] ${it.name}\"") }
            if (screen.list?.selectionAction != null) add("\"[Enter] ${screen.list.selectionAction}\"")
        }.joinToString(", ")

        appendLine("    override fun render(): Element {")
        appendLine("        val state = stateHolder.value")
        appendLine("        return Toolkit.column(")
        appendLine("            TuiRender.stateTable(\"${screen.vmSimpleName}\", listOf($statePairs)),")
        if (screen.list != null) {
            appendLine("            TuiRender.selectableList(\"${screen.list.propertyName}\", ${rowMapper(screen.list)}, selectedIndex),")
        }
        appendLine("            TuiRender.actionsBar(listOf($hints)),")
        appendLine("        )")
        append("    }")
    }

    private fun rowMapper(list: ListInfo): String {
        val body = if (list.elementStateProps.isEmpty()) {
            "item.toString()"
        } else {
            "\"\" + " + list.elementStateProps.joinToString(" + \"  \" + ") { "item.initialState.${it.name}" }
        }
        return "listHolder.items.map { item -> $body }"
    }

    private fun onKeyMethod(screen: ScreenInfo): String = buildString {
        appendLine("    override fun onKey(event: KeyEvent): EventResult {")
        if (screen.list != null) {
            appendLine("        if (event.isUp()) { if (selectedIndex > 0) selectedIndex--; return EventResult.HANDLED }")
            appendLine("        if (event.isDown()) { if (selectedIndex < listHolder.items.size - 1) selectedIndex++; return EventResult.HANDLED }")
        }
        if (screen.list?.selectionAction != null) {
            appendLine("        if (event.isKey(KeyCode.ENTER)) { listHolder.items.getOrNull(selectedIndex)?.let { item -> nav.scope.launch { item.${screen.list.selectionAction}() } }; return EventResult.HANDLED }")
        }
        for (action in screen.actions) {
            appendLine("        if (event.isChar('${action.key}')) { nav.scope.launch { viewModel.${action.name}() }; return EventResult.HANDLED }")
        }
        appendLine("        return EventResult.UNHANDLED")
        append("    }")
    }
}
