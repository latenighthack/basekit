package com.latenighthack.basekit.catalog.tool

/**
 * One generated source file. A file with [regions] (a spec file) is merged into the file already at
 * [path] region by region, see [Regions.merge]; a file without is rewritten. [previousImpl] is where
 * basekit-catalog-codegen 0.4.0 wrote this spec's implementation as a file of its own.
 */
data class GeneratedFile(
    val path: String,
    val content: String,
    val regions: List<Region> = emptyList(),
    val packageName: String = "",
    val previousImpl: String? = null,
)

/** Symbols the generated code references, by qualified name. */
object Symbols {
    const val DESTINATION = "com.latenighthack.basekit.navigation.annotations.Destination"
    const val ROUTE = "com.latenighthack.basekit.navigation.annotations.Route"
    const val ROUTE_ARG = "com.latenighthack.basekit.navigation.annotations.RouteArg"
    const val NAVIGATE_TO = "com.latenighthack.basekit.navigation.annotations.NavigateTo"
    const val REDIRECTS_TO = "com.latenighthack.basekit.navigation.annotations.RedirectsTo"
    const val HOSTED_COMPONENT = "com.latenighthack.basekit.navigation.annotations.HostedComponent"
    const val NAVIGATION_DESTINATION = "com.latenighthack.basekit.navigation.NavigationDestination"
    const val RESPONDING_DESTINATION = "com.latenighthack.basekit.navigation.RespondingDestination"
    const val NAVIGATION_RESPONDER = "com.latenighthack.basekit.navigation.NavigationResponder"
    const val NAVIGATOR_ARGS = "com.latenighthack.basekit.navigation.NavigatorArgs"
    const val VIEWMODEL = "com.latenighthack.basekit.viewmodel.ViewModel"
    const val STATEFUL_VIEWMODEL = "com.latenighthack.basekit.viewmodel.StatefulViewModel"
    const val VIEWMODEL_SPEC = "com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec"
    const val VIEWMODEL_LIST = "com.latenighthack.basekit.viewmodel.annotations.ViewModelList"
    const val CHILD_VIEWMODEL = "com.latenighthack.basekit.viewmodel.annotations.ChildViewModel"
    const val VIEWMODEL_IDENTITY = "com.latenighthack.basekit.viewmodel.annotations.ViewModelIdentity"
    const val VIEWMODEL_INJECT = "com.latenighthack.basekit.viewmodel.annotations.ViewModelInject"
    const val FLOW = "kotlinx.coroutines.flow.Flow"
    const val DELTA = "com.latenighthack.deltalist.Delta"
    const val SECTIONED_DELTA_LIST = "com.latenighthack.deltalist.SectionedDeltaList"
    const val TO_DELTA_LIST = "com.latenighthack.deltalist.operators.toDeltaList"
    const val SECTIONED_DELTA_LIST_OF = "com.latenighthack.deltalist.operators.sectionedDeltaList"
    const val FAKE_LIST = "com.latenighthack.basekit.fake.annotations.FakeList"
    const val FAKE_ROWS = "com.latenighthack.basekit.fake.annotations.FakeRows"
    const val FAKE_PLACEHOLDER = "com.latenighthack.basekit.fake.annotations.FakePlaceholder"
    const val FAKE_TEXT = "com.latenighthack.basekit.fake.annotations.FakeText"
    const val FAKE_TEXT_KIND = "com.latenighthack.basekit.fake.annotations.FakeTextKind"
    const val FAKE_NUMBER = "com.latenighthack.basekit.fake.annotations.FakeNumber"
    const val FAKE_NULL = "com.latenighthack.basekit.fake.annotations.FakeNull"
    const val FAKE_CASE = "com.latenighthack.basekit.fake.annotations.FakeCase"
    const val FAKE_VALUE = "com.latenighthack.basekit.fake.annotations.FakeValue"
    const val INJECT = "me.tatarka.inject.annotations.Inject"
    const val ASSISTED = "me.tatarka.inject.annotations.Assisted"
    const val COMPONENT = "me.tatarka.inject.annotations.Component"
    const val KMP_COMPONENT_CREATE = "me.tatarka.inject.annotations.KmpComponentCreate"
}

/** Renders `package`, sorted imports (minus same-package ones) and [body]. */
fun kotlinFile(packageName: String, header: String, imports: Set<String>, body: String): String = buildString {
    appendLine(header)
    appendLine()
    if (packageName.isNotEmpty()) {
        appendLine("package $packageName")
        appendLine()
    }
    val needed = imports.filter { it.substringBeforeLast('.', "") != packageName }.sorted()
    if (needed.isNotEmpty()) {
        needed.forEach { appendLine("import $it") }
        appendLine()
    }
    append(body.trimEnd())
    appendLine()
}

/** `com.example.Home` -> `com/example/Home.kt`. */
fun sourcePath(packageName: String, fileName: String): String =
    (if (packageName.isEmpty()) "" else packageName.replace('.', '/') + "/") + "$fileName.kt"

/** Indents every non-blank line of [text] by [spaces]. */
fun String.indent(spaces: Int): String {
    val pad = " ".repeat(spaces)
    return lines().joinToString("\n") { if (it.isBlank()) "" else pad + it }
}

/** A Kotlin string literal for [value]. */
fun kotlinString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
