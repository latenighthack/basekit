package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits the app-wide `NavigationScreen` enum — one entry per `@Destination`, derived from its navName
 * (see [screenName]). It implements [com.latenighthack.basekit.navigation.NavigationScreenId], so it can
 * be carried on a `NavigationEvent` and narrowed by an observer into a compiler-exhaustive `when`.
 *
 * Next to it, `HostNavigationEvents` builds the events the generated routers report for screens no
 * navigator opened or closed: a root shown, a deep link, a back gesture. It names each screen's
 * destination interface in Kotlin, so a Swift router can report a `NavigatedTo` without a `KClass`.
 *
 * Emitted in the metadata (commonMain) pass, independent of edges: a leaf destination still gets an
 * entry. navName collisions are already rejected by [NavigationProcessor] before this runs, so entries
 * are unique.
 */
class NavigationScreenGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val navPackage: String,
) {
    fun generate(destinations: List<DestinationInfo>) {
        if (destinations.isEmpty()) return

        codeGenerator.createNewFile(dependencies, navPackage, "NavigationScreen", "kt").use { out ->
            out.writeln(render(destinations))
        }
    }

    internal fun render(destinations: List<DestinationInfo>): String = buildString {
        appendLine("package $navPackage")
        appendLine()
        appendLine("import com.latenighthack.basekit.navigation.NavigationEvent")
        appendLine("import com.latenighthack.basekit.navigation.NavigationPresentation")
        appendLine("import com.latenighthack.basekit.navigation.NavigationPresentationEvent")
        appendLine("import com.latenighthack.basekit.navigation.NavigatorArgs")
        appendLine("import kotlin.reflect.KClass")
        appendLine()
        appendLine("public enum class NavigationScreen : com.latenighthack.basekit.navigation.NavigationScreenId {")
        destinations.forEach { appendLine("    ${it.screenName},") }
        appendLine("}")
        appendLine()
        appendLine("/**")
        appendLine(" * The events a generated router reports for screens it shows or dismisses without a navigator: a")
        appendLine(" * root entry, a deep link, a back gesture or a dismissed sheet. A navigator's own `navigateTo…` and")
        appendLine(" * `close` are reported by its `Observing…Navigator`, so these carry no source and no context.")
        appendLine(" */")
        appendLine("public object HostNavigationEvents {")
        appendLine("    public fun navigatedTo(screen: NavigationScreen, args: NavigatorArgs?): NavigationEvent =")
        appendLine("        NavigationEvent.NavigatedTo(screen, destination(screen), args, null, null)")
        appendLine()
        appendLine("    public fun closed(screen: NavigationScreen): NavigationEvent = NavigationEvent.Closed(screen, null)")
        appendLine()
        appendLine("    /** [presentation] is a [NavigationPresentation] name, so a Swift router can pass it as text. */")
        appendLine("    public fun presented(screen: NavigationScreen, entryId: String, presentation: String, customId: String?): NavigationPresentationEvent =")
        appendLine("        NavigationPresentationEvent.Presented(screen, entryId, NavigationPresentation.valueOf(presentation), customId)")
        appendLine()
        appendLine("    public fun dismissed(screen: NavigationScreen, entryId: String, presentation: String): NavigationPresentationEvent =")
        appendLine("        NavigationPresentationEvent.Dismissed(screen, entryId, NavigationPresentation.valueOf(presentation))")
        appendLine()
        appendLine("    private fun destination(screen: NavigationScreen): KClass<*> = when (screen) {")
        destinations.forEach { appendLine("        NavigationScreen.${it.screenName} -> ${it.qualifiedName}::class") }
        appendLine("    }")
        append("}")
    }
}
