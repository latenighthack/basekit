package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContains

class AppleHostedNavigatorGeneratorTest {
    // A single action carrying two @NavigateTo (fan-out) must keep BOTH targets: previously the edge
    // name was derived from source+method only, so distinctBy collapsed the two call sites and one
    // target's navigate method silently vanished (leaving the Apple navigator non-conforming).
    @Test
    fun fanOutActionKeepsEveryTargetWithDisambiguatedEdges() {
        val output = RecordingCodeGenerator()
        val destinations = listOf(
            destination(
                simpleName = "HomeViewModel",
                qualifiedName = "sample.HomeViewModel",
                navName = "home",
                edges = listOf(NavEdge("onOpenScan", "sample.ScanViewModel")),
            ),
            destination(
                simpleName = "ContactsViewModel",
                qualifiedName = "sample.ContactsViewModel",
                navName = "contacts",
                edges = listOf(
                    NavEdge("onSearchChanged", "sample.ScanViewModel"),
                    NavEdge("onSearchChanged", "sample.RoomViewModel"),
                ),
            ),
            destination("ScanViewModel", "sample.ScanViewModel", "scan"),
            destination("RoomViewModel", "sample.RoomViewModel", "room"),
        )

        AppleHostedNavigatorGenerator(output, Dependencies(false), "sample")
            .generate(destinations)

        val source = output.source()
        // both fan-out targets survive, as separate navigate methods
        assertContains(source, "override fun navigateToScan(")
        assertContains(source, "override fun navigateToRoom(")
        // and their shared base edge name is disambiguated by target so the flat enum stays unique
        assertContains(source, "CONTACTS_ON_SEARCH_CHANGED_TO_SCAN")
        assertContains(source, "CONTACTS_ON_SEARCH_CHANGED_TO_ROOM")
        // the non-colliding edge keeps its plain name
        assertContains(source, "HOME_ON_OPEN_SCAN")
    }

    private fun destination(
        simpleName: String,
        qualifiedName: String,
        navName: String,
        edges: List<NavEdge> = emptyList(),
    ) = DestinationInfo(
        simpleName = simpleName,
        qualifiedName = qualifiedName,
        navName = navName,
        argsQualifiedName = null,
        argsSwiftName = null,
        responseQualifiedName = null,
        responseSwiftName = null,
        routePath = null,
        routeArgs = emptyList(),
        edges = edges,
    )
}

private class RecordingCodeGenerator : CodeGenerator {
    private val output = ByteArrayOutputStream()

    fun source(): String = output.toString(Charsets.UTF_8.name())

    override fun createNewFile(
        dependencies: Dependencies,
        packageName: String,
        fileName: String,
        extensionName: String,
    ): OutputStream = output

    override fun createNewFileByPath(
        dependencies: Dependencies,
        path: String,
        extensionName: String,
    ): OutputStream = output

    override fun associate(
        sources: List<KSFile>,
        packageName: String,
        fileName: String,
        extensionName: String,
    ) = Unit

    override fun associateByPath(sources: List<KSFile>, path: String, extensionName: String) = Unit

    override fun associateWithClasses(
        classes: List<KSClassDeclaration>,
        packageName: String,
        fileName: String,
        extensionName: String,
    ) = Unit

    override val generatedFile: Collection<File> = emptyList()
}
