package com.latenighthack.basekit.fake.codegen

import com.latenighthack.basekit.viewmodel.codegen.kotlinRef

/**
 * Renders one fake class. Its primary constructor takes the fake's data, the destination's Args, one
 * nullable navigation target per destination it or its rows and children navigate to, and a responder;
 * the generated `FakeNavigator` and the fake's own rows use it. When the spec has a real
 * `@ViewModelInject` implementation, a second, `@Inject` constructor takes that implementation's
 * `@Assisted` parameters, so kotlin-inject and the generated factories build the fake in its place.
 */
object FakeViewModelGenerator {

    fun render(spec: FakeSpec): String = buildString {
        if (spec.packageName.isNotEmpty()) {
            appendLine("package ${spec.packageName}")
            appendLine()
        }
        appendLine("@$FAKE_PACKAGE.GeneratedFake(${spec.specQualifiedName}::class)")
        if (spec.inject != null) appendLine("@$VIEWMODEL_INJECT")
        val visibility = if (spec.internal) "internal" else "public"
        appendLine("$visibility class ${spec.className}(")
        appendLine("    override val fakeData: $FAKE_PACKAGE.FakeData,")
        spec.argsType?.let { appendLine("    fakeArgs: $it? = null,") }
        spec.targets.forEach { appendLine("    private val ${it.paramName}: ${it.interfaceType}? = null,") }
        if (spec.responds) appendLine("    private val fakeResponder: $FAKE_PACKAGE.FakeResponder? = null,")
        appendLine(") : ${spec.specQualifiedName}, $FAKE_PACKAGE.FakeViewModel {")

        spec.inject?.let { inject ->
            appendLine()
            appendLine("    @$INJECT")
            appendLine("    public constructor(")
            inject.parameters.forEach { (name, type) -> appendLine("        @$ASSISTED ${name.kotlinRef()}: $type,") }
            val arguments = listOf("$FAKE_PACKAGE.BasekitFakes.data(\"${spec.specSimpleName}\")") + inject.arguments
            appendLine("    ) : this(${arguments.joinToString(", ")})")
        }

        appendLine()
        appendLine("    override val initialState: ${spec.stateType} = ${spec.initialState}")
        appendLine()
        appendLine("    private val fakeState: kotlinx.coroutines.flow.MutableStateFlow<${spec.stateType}> =")
        appendLine("        kotlinx.coroutines.flow.MutableStateFlow(initialState)")
        appendLine()
        if (spec.redirects.isEmpty()) {
            appendLine("    override val state: kotlinx.coroutines.flow.Flow<${spec.stateType}> get() = fakeState")
        } else {
            // A real redirecting screen redirects while its state is collected; so does its fake.
            appendLine("    override val state: kotlinx.coroutines.flow.Flow<${spec.stateType}>")
            appendLine("        get() = kotlinx.coroutines.flow.flow {")
            appendLine("            fakeRedirect()")
            appendLine("            fakeState.collect(this)")
            appendLine("        }")
            appendRedirect(spec)
        }

        spec.lists.forEach { appendList(it) }
        spec.children.forEach { child ->
            appendLine()
            appendLine("    override val ${child.property.kotlinRef()}: ${child.type} = ${child.embedded.create("fakeData.child(\"${child.property}\")")}")
        }
        spec.properties.forEach { property ->
            appendLine()
            val keyword = if (property.mutable) "var" else "val"
            appendLine("    override $keyword ${property.name.kotlinRef()}: ${property.type} = ${property.initializer}")
        }
        spec.functions.forEach { appendFunction(it) }

        appendLine()
        val actions = spec.functions.filter { it.isAction }
        if (actions.isEmpty()) {
            appendLine("    override val fakeActions: List<$FAKE_PACKAGE.FakeAction> get() = emptyList()")
        } else {
            appendLine("    override val fakeActions: List<$FAKE_PACKAGE.FakeAction>")
            appendLine("        get() = listOf(")
            actions.forEach { action ->
                appendLine("            $FAKE_PACKAGE.FakeAction(\"${action.name}\", $FAKE_PACKAGE.FakeAction.Effect.${action.effect}) { ${action.name.kotlinRef()}() },")
            }
            appendLine("        )")
        }
        appendLine()
        val embedded = spec.lists.map { "${it.property}Rows.filterIsInstance<$FAKE_PACKAGE.FakeViewModel>()" } +
            spec.children.map { "listOfNotNull(${it.property.kotlinRef()} as? $FAKE_PACKAGE.FakeViewModel)" }
        val embeddedValue = embedded.joinToString(" + ").ifEmpty { "emptyList()" }
        appendLine("    override val fakeEmbedded: List<$FAKE_PACKAGE.FakeViewModel> get() = $embeddedValue")
        appendLine("}")
    }

    private fun StringBuilder.appendList(list: FakeList) {
        val name = list.property
        appendLine()
        val sections = list.sections
        if (sections == null) {
            appendLine("    private val ${name}Rows: List<${list.elementType}> =")
            appendLine("        fakeData.list(\"$name\", ${list.size.replace("{data}", "fakeData")}) { row -> ${rowExpression(list)} }${placeholder(list, "fakeData")}")
            appendLine()
            appendLine("    override val ${name.kotlinRef()}: ${list.type} =")
            appendLine("        com.latenighthack.deltalist.mutableDeltaListOf<${list.elementType}>(${name}Rows)")
        } else {
            appendLine("    private val ${name}Sections: List<Pair<${list.headerType}, List<${list.elementType}>>> =")
            appendLine("        listOf<${list.headerType}>(${sections.joinToString(", ")}).mapIndexed { index, header ->")
            appendLine("            val section = fakeData.child(\"$name\", index)")
            appendLine("            header to section.list(\"rows\", ${list.size.replace("{data}", "section")}) { row -> ${rowExpression(list)} }${placeholder(list, "section")}")
            appendLine("        }")
            appendLine()
            appendLine("    private val ${name}Rows: List<${list.elementType}> get() = ${name}Sections.flatMap { it.second }")
            appendLine()
            appendLine("    override val ${name.kotlinRef()}: ${list.type} =")
            appendLine("        com.latenighthack.deltalist.operators.sectionedDeltaList(")
            appendLine("            ${name}Sections.map { (header, rows) -> header to com.latenighthack.deltalist.mutableDeltaListOf<${list.elementType}>(rows) },")
            appendLine("        )")
        }
    }

    /** `.ifEmpty { listOf(placeholder) }`, as the implementation inserts it, or nothing. */
    private fun placeholder(list: FakeList, data: String): String {
        val placeholder = list.placeholder ?: return ""
        val child = if (data == "fakeData") "fakeData.child(\"${list.property}Placeholder\")" else "$data.child(\"placeholder\")"
        return "\n            .ifEmpty { listOf<${list.elementType}>(${placeholder.create(child)}) }"
    }

    private fun rowExpression(list: FakeList): String {
        val single = list.rows.singleOrNull()
        if (single != null || list.weights == null) return list.rows.first().create("row")
        val cases = list.rows.mapIndexed { index, row ->
            val label = if (index == list.rows.lastIndex) "else" else "$index"
            "$label -> ${row.create("row")}"
        }
        return "when (row.choose(\"type\", ${list.weights.replace("{data}", "fakeData")})) { ${cases.joinToString("; ")} }"
    }

    private fun StringBuilder.appendFunction(function: FakeFunction) {
        val name = function.name
        val counter = "${name}Navigations"
        if (function.navigations.size > 1) {
            appendLine()
            appendLine("    private var $counter = 0")
        }
        appendLine()
        val modifier = if (function.suspend) "suspend " else ""
        val parameters = function.parameters.joinToString(", ") { (parameter, type) -> "${parameter.kotlinRef()}: $type" }
        val returns = function.returnType?.let { ": $it" } ?: ""
        appendLine("    override ${modifier}fun ${name.kotlinRef()}($parameters)$returns {")
        val recorded = function.parameters.singleOrNull()?.first?.kotlinRef()
        appendLine("        fakeData.record(\"$name\"${recorded?.let { ", $it" } ?: ""})")
        function.binds?.let { field ->
            appendLine("        fakeState.value = fakeState.value.copy(${field.kotlinRef()} = ${function.parameters.single().first.kotlinRef()})")
        }
        when (function.navigations.size) {
            0 -> Unit
            1 -> appendNavigation(name, function.navigations.single(), "        ")
            else -> {
                appendLine("        when ($counter++ % ${function.navigations.size}) {")
                function.navigations.forEachIndexed { index, navigation ->
                    val label = if (index == function.navigations.lastIndex) "else" else "$index"
                    appendLine("            $label -> {")
                    appendNavigation(name, navigation, "                ")
                    appendLine("            }")
                }
                appendLine("        }")
            }
        }
        if (function.responds) appendLine("        fakeResponder?.respond(fakeData.child(\"$name\"))")
        function.returnValue?.let { appendLine("        return $it") }
        appendLine("    }")
    }

    private fun StringBuilder.appendRedirect(spec: FakeSpec) {
        appendLine()
        appendLine("    private var fakeRedirected = false")
        appendLine()
        appendLine("    override fun fakeRedirect() {")
        appendLine("        if (fakeRedirected) return")
        appendLine("        fakeRedirected = true")
        val choice = spec.redirectChoice
        if (choice == null) {
            appendNavigation(REDIRECT, spec.redirects.first(), "        ", redirect = true)
        } else {
            appendLine("        when ($choice) {")
            spec.redirects.forEachIndexed { index, navigation ->
                appendLine("            $index -> {")
                appendNavigation(REDIRECT, navigation, "                ", redirect = true)
                appendLine("            }")
            }
            appendLine("            else -> fakeData.record(\"$REDIRECT\", \"stays\")")
            appendLine("        }")
        }
        appendLine("    }")
    }

    private fun StringBuilder.appendNavigation(member: String, navigation: FakeNavigation, indent: String, redirect: Boolean = false) {
        val target = navigation.target
        val args = navigation.args?.let { args ->
            if (navigation.argsCopies.isEmpty()) args
            else "$args.apply { ${navigation.argsCopies.joinToString("; ") { "${it.kotlinRef()} = fakeState.value.${it.kotlinRef()}" }} }"
        }
        val arguments = listOfNotNull(args, navigation.source).joinToString(", ")
        val call = "navigator.navigateTo${target.navCap}($arguments)"
        appendLine("${indent}val navigator = ${target.paramName}")
        appendLine("${indent}if (navigator == null) {")
        appendLine("$indent    fakeData.unreachable(\"$member\", \"${navigation.targetName}\")")
        appendLine("$indent} else {")
        when {
            redirect -> {
                appendLine("$indent    fakeData.record(\"$member\", \"${navigation.targetName}\")")
                appendLine("$indent    $FAKE_PACKAGE.fakeRedirecting(navigator) { $call }")
            }
            navigation.responding -> appendLine("$indent    fakeData.record(\"$member\", $call)")
            else -> appendLine("$indent    $call")
        }
        appendLine("$indent}")
    }

    private const val REDIRECT = "redirect"
    private const val VIEWMODEL_INJECT = "com.latenighthack.basekit.viewmodel.annotations.ViewModelInject"
    private const val INJECT = "me.tatarka.inject.annotations.Inject"
    private const val ASSISTED = "me.tatarka.inject.annotations.Assisted"
}
