package com.latenighthack.basekit.navigation.posthog

import com.latenighthack.basekit.navigation.metrics.PostHogMetricsCollector
import com.latenighthack.basekit.navigation.metrics.PostHogMetricsOptions
import com.latenighthack.basekit.navigation.metrics.PostHogReporter

/** Shared factory for application-supplied reporters, also available on JVM and Apple targets. */
public fun postHogMetrics(
    reporter: PostHogReporter,
    options: PostHogMetricsOptions = PostHogMetricsOptions(),
): PostHogMetricsCollector = PostHogMetricsCollector(reporter, options)
