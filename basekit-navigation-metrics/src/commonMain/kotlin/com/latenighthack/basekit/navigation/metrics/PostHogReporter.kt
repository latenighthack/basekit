package com.latenighthack.basekit.navigation.metrics

/** App-owned transport. These are the only PostHog operations the shared collector needs. */
public interface PostHogReporter {
    public fun capture(event: String, properties: Map<String, Any>)
    public fun screen(name: String, properties: Map<String, Any>)
}
