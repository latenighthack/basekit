package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.Dependencies
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class RouteTableGeneratorTest {
    @Test
    fun aliasesRegisterTheSameArgsFactory() {
        val output = RecordingCodeGenerator()
        val detail = DestinationInfo(
            simpleName = "DetailViewModel",
            qualifiedName = "sample.DetailViewModel",
            navName = "detail",
            argsQualifiedName = "sample.DetailViewModel.Args",
            argsSwiftName = "DetailViewModelArgs",
            responseQualifiedName = null,
            responseSwiftName = null,
            routePath = "/detail/{id}",
            routeArgs = listOf("id"),
            edges = emptyList(),
            routeAliases = listOf("/items/{id}"),
        )
        RouteTableGenerator(output, Dependencies(false), "sample").generate(listOf(detail))
        val source = output.source()
        for (path in listOf("/detail/{id}", "/items/{id}")) {
            assertContains(source, "table.register(\"$path\") { params ->\n            sample.DetailViewModel.Args().apply {\n                id = params.getValue(\"id\")")
        }
    }

    @Test
    fun routeParamsReadsEveryCapture() {
        assertEquals(setOf("id", "tab"), routeParams("/detail/{id}/{tab}"))
        assertEquals(emptySet(), routeParams("/settings/people"))
    }
}
