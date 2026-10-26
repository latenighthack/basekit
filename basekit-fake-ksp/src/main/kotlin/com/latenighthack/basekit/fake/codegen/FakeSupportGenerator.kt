package com.latenighthack.basekit.fake.codegen

/** Renders the files shared by every fake: `FakeNavigator`, `FakeArgs`, `FakeViewModels` and `FakeValues`. */
object FakeSupportGenerator {

    /**
     * `FakeNavigator`: a headless navigator implementing every generated navigator interface. Each
     * `start<X>` replaces its stack with the fake of destination X; each `navigateTo<X>` pushes the fake
     * of X, handed this navigator for every target it needs.
     */
    fun renderNavigator(packageName: String, destinations: List<FakeDestination>, navigatorTypes: List<String>, hasClose: Boolean): String = buildString {
        header(packageName)
        val supertypes = (navigatorTypes + destinations.filter { it.navigatedTo }.map { "${it.navCap}NavigationTarget" } +
            listOfNotNull("CloseNavigationTarget".takeIf { hasClose }) + "$FAKE_PACKAGE.FakeRedirector").distinct()
        appendLine("/**")
        appendLine(" * A navigation stack with no UI, holding the fake of each destination it opens. Start it at any")
        appendLine(" * destination with `start…`; every action that navigates then pushes the fake of its target. A")
        appendLine(" * destination with `@RedirectsTo` redirects when it is opened, replacing itself with its target.")
        appendLine(" */")
        appendLine("public class FakeNavigator(")
        appendLine("    public val stack: $FAKE_PACKAGE.FakeStack = $FAKE_PACKAGE.FakeStack(),")
        append(")")
        if (supertypes.isNotEmpty()) append(" : ${supertypes.joinToString(", ")}")
        appendLine(" {")
        appendLine("    /** The view model on top of the stack. */")
        appendLine("    public val top: Any? get() = stack.top")

        for (destination in destinations.filterNot { it.hosted }) {
            appendLine()
            val parameter = destination.argsType?.let { "args: $it = FakeArgs.${destination.argsFunction}()" } ?: ""
            appendLine("    public fun start${destination.navCap}($parameter): ${destination.specType} {")
            appendLine("        val viewModel = ${destination.create(if (destination.argsType != null) "args" else null, null)}")
            appendLine("        stack.root(\"${destination.specSimpleName}\", viewModel)")
            appendLine("        viewModel.fakeRedirect()")
            appendLine("        return viewModel")
            appendLine("    }")
        }

        for (destination in destinations.filter { it.navigatedTo }) {
            appendLine()
            val parameters = buildList {
                destination.argsType?.let { add("args: $it") }
                if (destination.hasSource) add("source: ${destination.navCap}NavigationTarget.${destination.navCap}Source")
                add("context: Any?")
            }.joinToString(", ")
            val args = if (destination.argsType != null) "args" else null
            val responseType = destination.responseType
            if (responseType == null) {
                appendLine("    override fun navigateTo${destination.navCap}($parameters) {")
                appendLine("        val viewModel = ${destination.create(args, null)}")
                appendLine("        stack.push(\"${destination.specSimpleName}\", viewModel)")
                appendLine("        viewModel.fakeRedirect()")
                appendLine("    }")
            } else {
                appendLine("    override suspend fun navigateTo${destination.navCap}($parameters): $responseType? =")
                appendLine("        stack.pushForResult<$responseType>(\"${destination.specSimpleName}\") { responder ->")
                appendLine("            ${destination.create(args, "responder")}")
                appendLine("        }")
            }
        }

        appendLine()
        appendLine("    override fun redirecting(block: () -> Unit) {")
        appendLine("        stack.replacing(block)")
        appendLine("    }")

        if (hasClose) {
            appendLine()
            appendLine("    override fun close(context: Any?) {")
            appendLine("        stack.close()")
            appendLine("    }")
        }
        appendLine("}")
    }

    private fun FakeDestination.create(args: String?, responder: String?): String = buildList {
        add("$FAKE_PACKAGE.BasekitFakes.data(\"$specSimpleName\")")
        if (argsType != null && args != null) add("fakeArgs = $args")
        targets.forEach { add("${it.paramName} = this") }
        if (responds && responder != null && responseValue != null) {
            add("fakeResponder = $FAKE_PACKAGE.FakeResponder { data -> $responder.respond($responseValue) }")
        }
    }.joinToString(", ", "$fakeClass(", ")")

    private val FakeDestination.argsFunction: String get() = navCap.replaceFirstChar { it.lowercase() }

    /** `FakeArgs`: filled Args for each destination, to root a real router at any destination. */
    fun renderArgs(packageName: String, destinations: List<FakeDestination>): String = buildString {
        header(packageName)
        appendLine("/** Args for each destination with every property set to a fake value. */")
        appendLine("public object FakeArgs {")
        destinations.filter { it.argsType != null && it.argsBuilder != null }.forEachIndexed { index, destination ->
            if (index > 0) appendLine()
            appendLine("    public fun ${destination.argsFunction}(")
            appendLine("        fakeData: $FAKE_PACKAGE.FakeData = $FAKE_PACKAGE.BasekitFakes.data(\"${destination.specSimpleName}.args\"),")
            appendLine("    ): ${destination.argsType} = ${destination.argsBuilder}(fakeData)")
        }
        appendLine("}")
    }

    /** `FakeViewModels`: a function per spec making its fake with no navigation, for previews. */
    fun renderViewModels(packageName: String, specs: List<Pair<String, FakeSpec>>): String = buildString {
        header(packageName)
        appendLine("/** The fake of each view model spec, for previews. They navigate nowhere and respond to nothing. */")
        appendLine("public object FakeViewModels {")
        specs.forEachIndexed { index, (function, spec) ->
            if (index > 0) appendLine()
            appendLine("    public fun $function(")
            appendLine("        fakeData: $FAKE_PACKAGE.FakeData = $FAKE_PACKAGE.BasekitFakes.data(\"${spec.specSimpleName}\"),")
            appendLine("    ): ${spec.specQualifiedName} = ${spec.qualifiedClassName}(fakeData)")
        }
        appendLine("}")
    }

    /** `FakeValues`: the functions that build fake values, one per type, and one per provider. */
    fun renderValues(packageName: String, imports: List<String>, functions: List<String>): String = buildString {
        header(packageName)
        imports.forEach { appendLine("import $it") }
        if (imports.isNotEmpty()) appendLine()
        appendLine("/** Builds the fake values of each type the fakes use. */")
        appendLine("internal object FakeValues {")
        functions.forEachIndexed { index, function ->
            if (index > 0) appendLine()
            function.lines().forEach { line -> appendLine(if (line.isEmpty()) "" else "    $line") }
        }
        appendLine("}")
    }

    private fun StringBuilder.header(packageName: String) {
        if (packageName.isNotEmpty()) {
            appendLine("package $packageName")
            appendLine()
        }
    }
}
