package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits the app-wide `NavigationScreen` enum — one entry per `@Destination`, derived from its navName
 * (see [screenName]). It implements [com.latenighthack.basekit.navigation.NavigationScreenId], so it can
 * be carried on a `NavigationEvent` and narrowed by an observer into a compiler-exhaustive `when`.
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
            out.writeln("package $navPackage")
            out.writeln()
            out.writeln("public enum class NavigationScreen : com.latenighthack.basekit.navigation.NavigationScreenId {")
            destinations.forEach { out.writeln("    ${it.screenName},") }
            out.writeln("}")
        }
    }
}
