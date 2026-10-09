package com.latenighthack.basekit.navigation.metrics

import com.latenighthack.basekit.navigation.*
import com.latenighthack.basekit.viewmodel.ViewModelActionEvent
import kotlin.test.*

class PostHogMetricsCollectorTest {
    private enum class Screen : NavigationScreenId { HOME, DETAIL }
    private enum class Source { HOME_OPEN }
    private class PrivateValue(val secret: String = "do not serialize")
    private data class Report(val event: String, val properties: Map<String, Any>)
    private class Recorder : PostHogReporter {
        val reports = mutableListOf<Report>()
        override fun capture(event: String, properties: Map<String, Any>) { reports += Report(event, properties) }
        override fun screen(name: String, properties: Map<String, Any>) {
            assertEquals(name, properties["\$screen_name"])
            reports += Report("\$screen", properties)
        }
    }

    @Test fun mapsEveryObservationWithoutSerializingDomainValues() {
        val sink = Recorder()
        val metrics = PostHogMetricsCollector(sink)
        val privateValue = PrivateValue()
        metrics.onAction(ViewModelActionEvent("app.HomeViewModel", "open", privateValue))
        metrics.onNavigation(NavigationEvent.NavigatedTo(Screen.DETAIL, PrivateValue::class, null, Source.HOME_OPEN, privateValue))
        metrics.onNavigation(NavigationEvent.Closed(Screen.DETAIL, privateValue))
        metrics.onNavigation(NavigationEvent.Responded(Screen.DETAIL, privateValue))
        metrics.onNavigation(NavigationEvent.Responded(Screen.DETAIL, null))
        metrics.recordScreen("HOME")
        assertEquals(listOf("basekit action invoked", "\$screen", "basekit navigation closed", "basekit navigation responded", "basekit navigation responded", "\$screen"), sink.reports.map { it.event })
        assertEquals(mapOf("basekit_viewmodel" to "app.HomeViewModel", "basekit_action" to "open"), sink.reports[0].properties)
        assertEquals("HOME_OPEN", sink.reports[1].properties["basekit_source"])
        assertEquals("DETAIL", sink.reports[1].properties["\$screen_name"])
        assertTrue(sink.reports.none { it.properties.values.any { value -> value === privateValue } })
        assertEquals(sink.reports[3], sink.reports[4])
    }

    @Test fun providersSeeRawEventsAndPrecedenceProtectsMetadata() {
        val sink = Recorder()
        val privateValue = PrivateValue()
        val metrics = PostHogMetricsCollector(sink, PostHogMetricsOptions(
            properties = mapOf("app" to "fixture", "level" to 1, "basekit_action" to "forged"),
            propertyProviders = listOf(
                { observation ->
                    assertSame(privateValue, (observation as MetricsObservation.Action).event.viewModel)
                    mapOf("level" to 2, "domain" to "explicit")
                },
                { mapOf("level" to 3, "basekit_action" to "forged again") },
            ),
            eventName = { _, _ -> "feature activated" },
        ))
        metrics.onAction(ViewModelActionEvent("app.Spec", "activate", privateValue))
        assertEquals(Report("feature activated", mapOf("app" to "fixture", "level" to 3, "domain" to "explicit", "basekit_viewmodel" to "app.Spec", "basekit_action" to "activate")), sink.reports.single())
    }

    @Test fun customScreenIdsNeverSerializeTheirDomainDescription() {
        val customScreen = object : NavigationScreenId {
            override fun toString(): String = error("Domain descriptions must not be read")
        }
        val sink = Recorder()
        PostHogMetricsCollector(sink).onNavigation(NavigationEvent.Closed(customScreen, null))
        assertEquals("unknown", sink.reports.single().properties["basekit_screen"])
        val mapped = PostHogMetricsCollector(sink, PostHogMetricsOptions(screenName = {
            assertSame(customScreen, it)
            "custom"
        }))
        mapped.onNavigation(NavigationEvent.Closed(customScreen, null))
        assertEquals("custom", sink.reports.last().properties["basekit_screen"])
    }

    @Test fun screenMappingFilteringAndManualProperties() {
        val sink = Recorder()
        val metrics = PostHogMetricsCollector(sink, PostHogMetricsOptions(
            filter = { it !is MetricsObservation.Navigation || it.event !is NavigationEvent.Closed },
            screenName = { "screen/" + (it as Screen).name.lowercase() },
            eventName = { _, _ -> error("Screen views must not call the custom event mapper") },
        ))
        metrics.onNavigation(NavigationEvent.NavigatedTo(Screen.DETAIL, PrivateValue::class, null, null, null))
        metrics.onNavigation(NavigationEvent.Closed(Screen.DETAIL, null))
        metrics.recordScreen("initial", mapOf("entry" to "cold", "\$screen_name" to "forged"))
        assertEquals(2, sink.reports.size)
        assertEquals("screen/detail", sink.reports[0].properties["\$screen_name"])
        assertEquals("initial", sink.reports[1].properties["\$screen_name"])
        assertEquals("cold", sink.reports[1].properties["entry"])
    }

    @Test fun extensionsTransportAndErrorHandlerCannotInterruptCallers() {
        val failures = mutableListOf<Throwable>()
        val sink = object : PostHogReporter {
            override fun capture(event: String, properties: Map<String, Any>) { error("SDK failed") }
            override fun screen(name: String, properties: Map<String, Any>) { error("SDK failed") }
        }
        val event = ViewModelActionEvent("Spec", "act", PrivateValue())
        listOf(
            PostHogMetricsOptions(filter = { error("filter failed") }, onError = failures::add),
            PostHogMetricsOptions(propertyProviders = listOf({ error("provider failed") }), onError = failures::add),
            PostHogMetricsOptions(eventName = { _, _ -> error("mapper failed") }, onError = failures::add),
            PostHogMetricsOptions(onError = failures::add),
            PostHogMetricsOptions(onError = { error("error handler failed") }),
        ).forEach { PostHogMetricsCollector(sink, it).onAction(event) }
        assertEquals(listOf("filter failed", "provider failed", "mapper failed", "SDK failed"), failures.map { it.message })
    }

    @Test fun validatesJsonAndSnapshotsMutableProperties() {
        val sink = Recorder()
        val failures = mutableListOf<Throwable>()
        val nested = mutableListOf<Any?>("one", null, mapOf("ok" to true))
        val metrics = PostHogMetricsCollector(sink, PostHogMetricsOptions(properties = mapOf("nested" to nested), onError = failures::add))
        metrics.recordScreen("first")
        nested += "two"
        assertEquals(3, (sink.reports.single().properties["nested"] as List<*>).size)
        metrics.recordScreen("invalid", mapOf("domain" to PrivateValue()))
        metrics.recordScreen("invalid", mapOf("number" to Double.NaN))
        val cyclic = mutableListOf<Any>()
        cyclic.add(cyclic)
        metrics.recordScreen("invalid", mapOf("cycle" to cyclic))
        assertEquals(1, sink.reports.size)
        assertEquals(3, failures.size)
    }
}
