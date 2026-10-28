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
    fun aQueryCaptureBindsItsArgLikeAPathCapture() {
        val output = RecordingCodeGenerator()
        val request = DestinationInfo(
            simpleName = "RequestViewModel",
            qualifiedName = "sample.RequestViewModel",
            navName = "request",
            argsQualifiedName = "sample.RequestViewModel.Args",
            argsSwiftName = "RequestViewModelArgs",
            responseQualifiedName = null,
            responseSwiftName = null,
            routePath = "/requests?request={requestId}",
            routeArgs = listOf("requestId"),
            edges = emptyList(),
        )
        RouteTableGenerator(output, Dependencies(false), "sample").generate(listOf(request))
        assertContains(
            output.source(),
            "table.register(\"/requests?request={requestId}\") { params ->\n            sample.RequestViewModel.Args().apply {\n                requestId = params.getValue(\"requestId\")",
        )
    }

    @Test
    fun aRouteSetsEveryArgumentItDoesNotCapture() {
        val output = RecordingCodeGenerator()
        fun destination(name: String, path: String, captured: List<String>) = DestinationInfo(
            simpleName = "${name}ViewModel",
            qualifiedName = "sample.${name}ViewModel",
            navName = name.lowercase(),
            argsQualifiedName = "sample.${name}ViewModel.Args",
            argsSwiftName = "${name}ViewModelArgs",
            responseQualifiedName = null,
            responseSwiftName = null,
            routePath = path,
            routeArgs = captured,
            edges = emptyList(),
            routeDefaults = listOf("isAdmin" to "false", "inviteCode" to "null"),
        )
        RouteTableGenerator(output, Dependencies(false), "sample").generate(
            listOf(destination("Join", "/sign-up", emptyList()), destination("Agent", "/agents/{agentId}", listOf("agentId"))),
        )
        val source = output.source()
        assertContains(source, "table.register(\"/sign-up\") { _ ->\n            sample.JoinViewModel.Args().apply {\n                isAdmin = false\n                inviteCode = null\n            }")
        assertContains(source, "sample.AgentViewModel.Args().apply {\n                agentId = params.getValue(\"agentId\")\n                isAdmin = false\n                inviteCode = null\n            }")
    }

    @Test
    fun routeParamsReadsEveryCapture() {
        assertEquals(setOf("id", "tab"), routeParams("/detail/{id}/{tab}"))
        assertEquals(setOf("teamId", "taskId"), routeParams("/teams/{teamId}/requests?task={taskId}"))
        assertEquals(emptySet(), routeParams("/settings/people"))
        assertEquals(setOf("agentId", "resourceId"), routeParams("/agents/{agentId}?resource={resourceId?}"))
        assertEquals(setOf("agentId"), requiredRouteParams("/agents/{agentId}?resource={resourceId?}"))
    }

    // An optional capture doesn't change which addresses a template must match, so it isn't part of
    // its shape: `/burners` and `/burners?burner={id?}` match the same addresses.
    @Test
    fun routeShapeLeavesOutOptionalCaptures() {
        assertEquals("/burners", routeShape("/burners?burner={burnerSessionId?}"))
        assertEquals("/requests?request", routeShape("/requests?from={from?}&request={requestId}"))
    }

    // Each template binds what it captures: a non-null arg from the match, a String? one from the match
    // or null, and a String? one it doesn't capture is null.
    @Test
    fun eachTemplateBindsTheArgsItCaptures() {
        val output = RecordingCodeGenerator()
        val rules = DestinationInfo(
            simpleName = "RulesViewModel",
            qualifiedName = "sample.RulesViewModel",
            navName = "rules",
            argsQualifiedName = "sample.RulesViewModel.Args",
            argsSwiftName = "RulesViewModelArgs",
            responseQualifiedName = null,
            responseSwiftName = null,
            routePath = "/connections/{connectionId}/rules",
            routeArgs = listOf("connectionId", "agentId", "resourceId"),
            edges = emptyList(),
            routeAliases = listOf("/agents/{agentId}/connections/{connectionId}?resource={resourceId?}"),
            routeDefaults = listOf("isHouseRules" to "false"),
            nullableRouteArgs = setOf("agentId", "resourceId"),
        )
        RouteTableGenerator(output, Dependencies(false), "sample").generate(listOf(rules))
        val source = output.source()
        assertContains(
            source,
            "table.register(\"/connections/{connectionId}/rules\") { params ->\n            sample.RulesViewModel.Args().apply {\n" +
                "                connectionId = params.getValue(\"connectionId\")\n                agentId = null\n                resourceId = null\n" +
                "                isHouseRules = false\n",
        )
        assertContains(
            source,
            "table.register(\"/agents/{agentId}/connections/{connectionId}?resource={resourceId?}\") { params ->\n" +
                "            sample.RulesViewModel.Args().apply {\n                connectionId = params.getValue(\"connectionId\")\n" +
                "                agentId = params[\"agentId\"]\n                resourceId = params[\"resourceId\"]\n                isHouseRules = false\n",
        )
    }

    @Test
    fun routeTemplateProblemsNamesWhatRegisterWouldReject() {
        assertEquals(emptyList(), routeTemplateProblems("/requests?request={requestId}&tab={tab}"))
        assertEquals(emptyList(), routeTemplateProblems("/requests?request={requestId?}"))
        assertEquals(listOf("query part \"request=7\" must be key={name} or key={name?}"), routeTemplateProblems("/requests?request=7"))
        assertEquals(listOf("query part \"={id}\" must be key={name} or key={name?}"), routeTemplateProblems("/requests?={id}"))
        assertEquals(listOf("query part \"request={?}\" must be key={name} or key={name?}"), routeTemplateProblems("/requests?request={?}"))
        assertEquals(
            listOf("path segment \"{id?}\" can't be optional; only a query capture (key={name?}) can"),
            routeTemplateProblems("/requests/{id?}"),
        )
        assertEquals(listOf("\"{id}\" is captured more than once"), routeTemplateProblems("/requests/{id}?request={id}"))
    }
}
