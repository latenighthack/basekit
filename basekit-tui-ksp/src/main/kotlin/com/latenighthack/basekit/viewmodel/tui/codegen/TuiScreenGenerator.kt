package com.latenighthack.basekit.viewmodel.tui.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/** Generates a compositional terminal screen for a root ViewModel and its nested child ViewModels. */
class TuiScreenGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val rootPackage: String,
) {
    private data class Node(
        val access: String,
        val holder: String,
        val prefix: String,
        val title: String,
        val visible: String,
        val stateProps: List<StateProp>,
        val actions: List<Action>,
        val mutations: List<Mutation>,
        val lists: List<ListInfo>,
    )

    private data class RenderList(
        val index: Int,
        val node: Node,
        val info: ListInfo,
        val holder: String,
        val selected: String,
    )

    fun generate(screens: List<ScreenInfo>) {
        for (screen in screens) {
            codeGenerator.createNewFile(dependencies, rootPackage, screen.screenClassName, "kt").use { out ->
                out.writeln(render(screen))
            }
        }
    }

    private fun render(screen: ScreenInfo): String {
        val nodes = flattenNodes(screen)
        val lists = nodes.flatMap { node -> node.lists.map { node to it } }
            .mapIndexed { index, (node, info) ->
                val cap = info.propertyName.replaceFirstChar { it.uppercase() }
                RenderList(index, node, info, "${node.prefix}${cap}ListHolder", "${node.prefix}${cap}Selected")
            }
        val promptMutations = nodes.flatMap { node ->
            node.mutations.filter { !it.hidden && it.toggleField == null }.map { node to it }
        }
        val usesTransform = nodes.any { node -> node.stateProps.any { !it.hidden && it.transform != Transform.NONE } }

        return buildString {
            appendLine("package $rootPackage")
            appendLine()
            appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiScreen")
            appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiNavigation")
            appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiRender")
            if (usesTransform) appendLine("import com.latenighthack.basekit.viewmodel.tui.Transform")
            appendLine("import com.latenighthack.basekit.viewmodel.tui.StateHolder")
            if (lists.isNotEmpty()) appendLine("import com.latenighthack.basekit.viewmodel.tui.ListHolder")
            if (promptMutations.isNotEmpty()) appendLine("import com.latenighthack.basekit.viewmodel.tui.MutationPrompt")
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
            for (node in nodes) {
                appendLine("    private val ${node.holder} = StateHolder(nav.scope, ${node.access}.initialState, ${node.access}.state)")
            }
            for (list in lists) {
                appendLine("    private val ${list.holder} = ListHolder(nav.scope, ${list.node.access}.${list.info.propertyName})")
                appendLine("    private var ${list.selected} = 0")
            }
            if (lists.isNotEmpty()) appendLine("    private var focusedList = -1")
            if (promptMutations.isNotEmpty()) appendLine("    private var prompt: MutationPrompt? = null")
            appendLine()
            appendLine("    override val title: String = \"${screen.vmSimpleName}\"")
            appendLine()
            if (lists.isNotEmpty()) appendLine(visibleListsMethod(lists))
            appendLine(renderMethod(nodes, lists, promptMutations.isNotEmpty()))
            appendLine()
            appendLine(onKeyMethod(nodes, lists, promptMutations))
            appendLine("}")
        }
    }

    private fun flattenNodes(screen: ScreenInfo): List<Node> {
        val root = Node(
            access = "viewModel",
            holder = "rootStateHolder",
            prefix = "root",
            title = screen.vmSimpleName,
            visible = "true",
            stateProps = screen.stateProps,
            actions = screen.actions,
            mutations = screen.mutations,
            lists = screen.lists,
        )
        val result = mutableListOf(root)
        fun add(children: List<ChildInfo>, parent: Node) {
            for (child in children) {
                val cap = child.propertyName.replaceFirstChar { it.uppercase() }
                val prefix = parent.prefix + cap
                val localVisibility = if (child.visibleWhenField != null && child.visibleWhenValue != null) {
                    "${parent.holder}.value.${child.visibleWhenField}.toString() == \"${child.visibleWhenValue}\""
                } else {
                    "true"
                }
                val visible = if (parent.visible == "true") localVisibility else "(${parent.visible}) && ($localVisibility)"
                val node = Node(
                    access = "${parent.access}.${child.propertyName}",
                    holder = "${prefix}StateHolder",
                    prefix = prefix,
                    title = child.label ?: child.propertyName,
                    visible = visible,
                    stateProps = child.stateProps,
                    actions = child.actions,
                    mutations = child.mutations,
                    lists = child.lists,
                )
                result += node
                add(child.children, node)
            }
        }
        add(screen.children, root)
        return result
    }

    private fun visibleListsMethod(lists: List<RenderList>): String = buildString {
        appendLine("    private fun visibleListIndices(): List<Int> = buildList {")
        for (list in lists) appendLine("        if (${list.node.visible}) add(${list.index})")
        appendLine("    }")
        appendLine()
        appendLine("    private fun normalizeFocusedList(): List<Int> {")
        appendLine("        val visible = visibleListIndices()")
        appendLine("        if (focusedList !in visible) focusedList = visible.firstOrNull() ?: -1")
        appendLine("        return visible")
        append("    }")
    }

    private fun renderMethod(nodes: List<Node>, lists: List<RenderList>, hasPrompt: Boolean): String = buildString {
        if (lists.isNotEmpty()) appendLine("    override fun render(): Element {") else appendLine("    override fun render(): Element {")
        if (lists.isNotEmpty()) appendLine("        normalizeFocusedList()")
        appendLine("        val hints = buildList<String> {")
        for (node in nodes) {
            for (action in node.actions.filterNot { it.hidden }) {
                appendLine("            if (${node.visible}) add(\"[${action.key}] ${action.label ?: action.name}\")")
            }
            for (mutation in node.mutations.filterNot { it.hidden }) {
                appendLine("            if (${node.visible}) add(\"[${mutation.key}] ${mutation.label ?: mutation.toggleField ?: mutation.name}\")")
            }
        }
        if (lists.size > 1) appendLine("            if (visibleListIndices().size > 1) add(\"[Tab] Next list\")")
        for (list in lists) {
            val primary = list.info.possibleTypes.mapNotNull { it.selectionAction }.distinct()
            if (primary.isNotEmpty()) appendLine("            if (focusedList == ${list.index}) add(\"[Enter] ${primary.joinToString("/")}\")")
            val secondary = list.info.possibleTypes.flatMap { it.secondaryActions }.filterNot { it.hidden }.distinctBy { it.name to it.key }
            for (action in secondary) appendLine("            if (focusedList == ${list.index}) add(\"[${action.key}] ${action.label ?: action.name}\")")
        }
        appendLine("        }")
        appendLine("        return Toolkit.column(")
        for (node in nodes) {
            appendLine("            if (${node.visible}) ${stateTable(node)} else Toolkit.text(\"\"),")
            for (list in lists.filter { it.node == node }) {
                val title = list.info.label ?: list.info.propertyName
                appendLine("            if (${node.visible}) TuiRender.selectableList((if (focusedList == ${list.index}) \"▶ \" else \"\") + \"$title\", ${rowMapper(list)}, ${list.selected}) else Toolkit.text(\"\"),")
            }
        }
        appendLine("            TuiRender.actionsBar(hints),")
        if (hasPrompt) appendLine("            prompt?.let { TuiRender.prompt(it.label, it.isBool, it.text) } ?: Toolkit.text(\"\"),")
        appendLine("        )")
        append("    }")
    }

    private fun stateTable(node: Node): String {
        val rows = node.stateProps.filterNot { it.hidden }.joinToString(", ") { prop ->
            "\"${prop.label ?: prop.name}\" to ${valueExpr("${node.holder}.value", prop)}"
        }
        return "TuiRender.stateTable(\"${node.title}\", listOf($rows))"
    }

    private fun valueExpr(state: String, prop: StateProp): String = when (prop.style) {
        FieldStyle.TOGGLE -> "TuiRender.toggle($state.${prop.name})"
        FieldStyle.BAR -> "TuiRender.bar($state.${prop.name}, ${prop.max})"
        else -> {
            val base = "$state.${prop.name}.toString()"
            if (prop.transform != Transform.NONE) "TuiRender.transform($base, Transform.${prop.transform})" else base
        }
    }

    private fun rowMapper(list: RenderList): String {
        val branches = list.info.possibleTypes.joinToString("; ") { type ->
            val body = if (type.stateProps.isEmpty()) {
                "item.toString()"
            } else {
                type.stateProps.joinToString(" + \"  \" + ") { "item.initialState.${it.name}.toString()" }
            }
            "is ${type.qualifiedName} -> $body"
        }
        return "${list.holder}.items.map { item -> when (item) { $branches else -> item.toString() } }"
    }

    private fun onKeyMethod(
        nodes: List<Node>,
        lists: List<RenderList>,
        promptMutations: List<Pair<Node, Mutation>>,
    ): String = buildString {
        appendLine("    override fun onKey(event: KeyEvent): EventResult {")
        if (promptMutations.isNotEmpty()) {
            appendLine("        prompt?.let { active ->")
            appendLine("            if (event.isKey(KeyCode.ESCAPE)) { prompt = null; return EventResult.HANDLED }")
            appendLine("            if (active.isBool) {")
            appendLine("                if (event.isChar('t')) { active.submitBool(true); prompt = null; return EventResult.HANDLED }")
            appendLine("                if (event.isChar('f')) { active.submitBool(false); prompt = null; return EventResult.HANDLED }")
            appendLine("            } else {")
            appendLine("                if (event.isKey(KeyCode.ENTER)) { active.submitText(); prompt = null; return EventResult.HANDLED }")
            appendLine("                if (event.isKey(KeyCode.BACKSPACE)) { active.backspace(); return EventResult.HANDLED }")
            appendLine("                if (event.isKey(KeyCode.CHAR)) { active.type(event.string()); return EventResult.HANDLED }")
            appendLine("            }")
            appendLine("            return EventResult.HANDLED")
            appendLine("        }")
        }
        if (lists.isNotEmpty()) {
            appendLine("        val visibleLists = normalizeFocusedList()")
            appendLine("        if (event.isKey(KeyCode.TAB)) {")
            appendLine("            if (visibleLists.isNotEmpty()) focusedList = visibleLists[(visibleLists.indexOf(focusedList) + 1) % visibleLists.size]")
            appendLine("            return EventResult.HANDLED")
            appendLine("        }")
            appendLine("        if (event.isUp()) {")
            appendLine("            when (focusedList) {")
            for (list in lists) appendLine("                ${list.index} -> if (${list.selected} > 0) ${list.selected}--")
            appendLine("            }")
            appendLine("            return EventResult.HANDLED")
            appendLine("        }")
            appendLine("        if (event.isDown()) {")
            appendLine("            when (focusedList) {")
            for (list in lists) appendLine("                ${list.index} -> if (${list.selected} < ${list.holder}.items.size - 1) ${list.selected}++")
            appendLine("            }")
            appendLine("            return EventResult.HANDLED")
            appendLine("        }")
            appendLine("        if (event.isKey(KeyCode.ENTER)) {")
            appendLine("            when (focusedList) {")
            for (list in lists) {
                appendLine("                ${list.index} -> ${list.holder}.items.getOrNull(${list.selected})?.let { item ->")
                appendLine("                    when (item) {")
                for (type in list.info.possibleTypes.filter { it.selectionAction != null }) {
                    appendLine("                        is ${type.qualifiedName} -> nav.scope.launch { item.${type.selectionAction}() }")
                }
                appendLine("                    }")
                appendLine("                }")
            }
            appendLine("            }")
            appendLine("            return EventResult.HANDLED")
            appendLine("        }")
        }
        for (node in nodes) {
            for (action in node.actions.filterNot { it.hidden }) {
                appendLine("        if (${node.visible} && event.isChar('${action.key}')) { nav.scope.launch { ${node.access}.${action.name}() }; return EventResult.HANDLED }")
            }
            for (mutation in node.mutations.filter { !it.hidden && it.toggleField != null }) {
                appendLine("        if (${node.visible} && event.isChar('${mutation.key}')) { val s = ${node.holder}.value; nav.scope.launch { ${node.access}.${mutation.name}(!s.${mutation.toggleField}) }; return EventResult.HANDLED }")
            }
            for (mutation in node.mutations.filter { !it.hidden && it.toggleField == null }) {
                val factory = when (mutation.paramKind) {
                    MutationParamKind.BOOL -> "MutationPrompt.bool(\"${mutation.label ?: mutation.name}\") { value -> nav.scope.launch { ${node.access}.${mutation.name}(value) } }"
                    MutationParamKind.STRING -> "MutationPrompt.text(\"${mutation.label ?: mutation.name}\") { value -> nav.scope.launch { ${node.access}.${mutation.name}(value) } }"
                }
                appendLine("        if (${node.visible} && event.isChar('${mutation.key}')) { prompt = $factory; return EventResult.HANDLED }")
            }
        }
        for (list in lists) {
            val actions = list.info.possibleTypes.flatMap { type -> type.secondaryActions.map { type to it } }
                .filterNot { it.second.hidden }
            for ((type, action) in actions) {
                appendLine("        if (focusedList == ${list.index} && event.isChar('${action.key}')) {")
                appendLine("            (${list.holder}.items.getOrNull(${list.selected}) as? ${type.qualifiedName})?.let { item -> nav.scope.launch { item.${action.name}() } }")
                appendLine("            return EventResult.HANDLED")
                appendLine("        }")
            }
        }
        appendLine("        return EventResult.UNHANDLED")
        append("    }")
    }
}
