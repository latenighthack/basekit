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
import kotlin.test.assertFalse

class AppleSwiftNavigationGeneratorTest {
    @Test
    fun isolatesTheKotlinNavigationHostConformanceToTheMainActor() {
        val output = RecordingSwiftCodeGenerator()
        val destinations = listOf(
            destination(
                simpleName = "HomeViewModel",
                qualifiedName = "sample.HomeViewModel",
                navName = "home",
                edges = listOf(NavEdge("onOpenRoom", "sample.RoomViewModel")),
            ),
            destination(
                simpleName = "RoomViewModel",
                qualifiedName = "sample.RoomViewModel",
                navName = "room",
            ),
        )

        AppleSwiftNavigationGenerator(output, Dependencies(false), listOf("SampleKit"))
            .generate(destinations)

        val source = output.source()
        assertContains(
            source,
            "BasekitNavigationRouter: NSObject, ObservableObject, @MainActor AppleNavigationHost",
        )
        assertFalse("@preconcurrency AppleNavigationHost" in source)
        assertContains(
            source,
            "private static func replacingResponder(in route: BasekitRoute, with responder: NavigationResponder)",
        )
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

private class RecordingSwiftCodeGenerator : CodeGenerator {
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
