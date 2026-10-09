package com.latenighthack.basekit.navigation.posthog

import com.latenighthack.basekit.navigation.metrics.PostHogMetricsCollector
import com.latenighthack.basekit.navigation.metrics.PostHogMetricsOptions
import com.latenighthack.basekit.navigation.metrics.PostHogReporter
import com.posthog.PostHogInterface

/** Wraps an already configured native client without changing its identity, consent or lifetime. */
public class AndroidPostHogReporter(private val client: PostHogInterface) : PostHogReporter {
    override fun capture(event: String, properties: Map<String, Any>) {
        client.capture(event = event, properties = properties)
    }

    override fun screen(name: String, properties: Map<String, Any>) {
        client.screen(screenTitle = name, properties = properties)
    }
}

public fun postHogMetrics(
    client: PostHogInterface,
    options: PostHogMetricsOptions = PostHogMetricsOptions(),
): PostHogMetricsCollector = PostHogMetricsCollector(AndroidPostHogReporter(client), options)
