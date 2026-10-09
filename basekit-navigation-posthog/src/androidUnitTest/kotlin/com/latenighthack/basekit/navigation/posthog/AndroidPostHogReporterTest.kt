package com.latenighthack.basekit.navigation.posthog

import com.latenighthack.basekit.viewmodel.ViewModelActionEvent
import com.posthog.PostHog
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import kotlin.test.*

class AndroidPostHogReporterTest {
    @Test fun configuredSdkReceivesScreensAndActionsAndRetainsConsentAndIdentity() {
        val events = mutableListOf<PostHogEvent>()
        val config = PostHogConfig("phc_basekit_test", "http://127.0.0.1:1", preloadFeatureFlags = false, remoteConfig = false)
        config.addBeforeSend { event -> events.add(event); null }
        val client = PostHog.with(config)
        try {
            client.optIn()
            val identity = client.distinctId()
            val metrics = postHogMetrics(client)
            metrics.recordScreen("DETAIL", mapOf("domain" to "fixture", "nested" to mapOf("number" to 2, "flag" to true)))
            metrics.onAction(ViewModelActionEvent("app.Detail", "save", this))
            assertEquals(listOf("\$screen", "basekit action invoked"), events.map { it.event })
            assertEquals("DETAIL", events[0].properties?.get("\$screen_name"))
            assertEquals(mapOf("number" to 2, "flag" to true), events[0].properties?.get("nested"))
            assertEquals("save", events[1].properties?.get("basekit_action"))
            assertEquals(identity, client.distinctId())
            client.optOut()
            metrics.recordScreen("opted out")
            assertEquals(2, events.size)
        } finally { client.optIn(); client.close() }
    }
}
