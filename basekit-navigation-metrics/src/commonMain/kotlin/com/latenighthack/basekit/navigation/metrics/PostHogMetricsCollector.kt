package com.latenighthack.basekit.navigation.metrics

import com.latenighthack.basekit.navigation.NavigationEvent
import com.latenighthack.basekit.navigation.NavigationObserver
import com.latenighthack.basekit.navigation.NavigationScreenId
import com.latenighthack.basekit.viewmodel.ViewModelActionEvent
import com.latenighthack.basekit.viewmodel.ViewModelActionObserver

/** Raw observations for application-owned property, naming and filtering extensions. */
public sealed class MetricsObservation {
    public data class Action(val event: ViewModelActionEvent) : MetricsObservation()
    public data class Navigation(val event: NavigationEvent) : MetricsObservation()
    public data class Screen(val name: String, val properties: Map<String, Any>) : MetricsObservation()
}

/** Callbacks run synchronously on the invoking thread; keep them fast and safe for concurrent calls. */
public class PostHogMetricsOptions(
    public val properties: Map<String, Any> = emptyMap(),
    public val propertyProviders: List<(MetricsObservation) -> Map<String, Any>> = emptyList(),
    public val filter: ((MetricsObservation) -> Boolean)? = null,
    /** Applies to custom events. Screen views always use the reporter's standard screen operation. */
    public val eventName: ((MetricsObservation, String) -> String)? = null,
    public val screenName: ((NavigationScreenId) -> String)? = null,
    public val onError: ((Throwable) -> Unit)? = null,
) {
    /** Explicit zero-argument constructor for Swift consumers. */
    public constructor() : this(emptyMap(), emptyList(), null, null, null, null)
}

/**
 * Optional, stateless observer. SDK identity, consent, queues and lifetime belong to the application.
 * Navigation screen events describe requests, emitted before presentation by the concrete navigator.
 */
public class PostHogMetricsCollector(
    private val reporter: PostHogReporter,
    private val options: PostHogMetricsOptions,
) : NavigationObserver, ViewModelActionObserver {
    public constructor(reporter: PostHogReporter) : this(reporter, PostHogMetricsOptions())

    private val properties = options.properties.toMap()
    private val providers = options.propertyProviders.toList()

    override fun onAction(event: ViewModelActionEvent) {
        report(MetricsObservation.Action(event))
    }

    override fun onNavigation(event: NavigationEvent) {
        report(MetricsObservation.Navigation(event))
    }

    /**
     * Call once when a screen outside BaseKit navigation is displayed. Screens a generated router shows or
     * dismisses (roots, deep links, back gestures) are reported through it when it has this collector.
     */
    public fun recordScreen(name: String, properties: Map<String, Any> = emptyMap()) {
        report(MetricsObservation.Screen(name, properties))
    }

    private fun report(observation: MetricsObservation) {
        try {
            if (options.filter?.invoke(observation) == false) return
            val metadata = linkedMapOf<String, Any>()
            var screen: String? = null
            val defaultName = when (observation) {
                is MetricsObservation.Action -> {
                    metadata["view_model"] = observation.event.viewModelName
                    metadata["action"] = observation.event.actionName
                    "action_invoked"
                }
                is MetricsObservation.Navigation -> when (val event = observation.event) {
                    is NavigationEvent.NavigatedTo -> {
                        screen = name(event.screen)
                        metadata["navigation_operation"] = "navigate"
                        (event.source as? Enum<*>)?.let { metadata["source"] = it.name }
                        "\$screen"
                    }
                    is NavigationEvent.Closed -> {
                        metadata["screen"] = name(event.screen)
                        metadata["navigation_operation"] = "close"
                        "navigation_closed"
                    }
                    is NavigationEvent.Responded -> {
                        metadata["screen"] = name(event.screen)
                        metadata["navigation_operation"] = "respond"
                        "navigation_responded"
                    }
                }
                is MetricsObservation.Screen -> {
                    screen = observation.name
                    "\$screen"
                }
            }
            val eventName = if (screen == null) options.eventName?.invoke(observation, defaultName) ?: defaultName else defaultName
            require(eventName.isNotBlank()) { "Metrics event name must not be blank" }
            screen?.let {
                require(it.isNotBlank()) { "Metrics screen name must not be blank" }
                metadata["screen"] = it
                metadata["\$screen_name"] = it
            }
            val extras = properties.toMutableMap()
            if (observation is MetricsObservation.Screen) extras.putAll(observation.properties)
            providers.forEach { extras.putAll(it(observation)) }
            // Providers may override one another, but cannot forge the identifying metadata.
            extras.keys.removeAll { it in RESERVED_PROPERTIES }
            extras.putAll(metadata)
            val snapshot = jsonProperties(extras)
            if (screen != null) reporter.screen(screen, snapshot) else reporter.capture(eventName, snapshot)
        } catch (error: Throwable) {
            try { options.onError?.invoke(error) } catch (_: Throwable) { /* Reporting never changes application control flow. */ }
        }
    }

    private fun name(screen: NavigationScreenId): String = options.screenName?.invoke(screen)
        ?: (screen as? Enum<*>)?.name ?: "unknown"
}

/** The built-in properties: static properties and providers cannot set them. */
private val RESERVED_PROPERTIES = setOf("view_model", "action", "screen", "navigation_operation", "source", "\$screen_name")

/** Validate and copy before crossing SDK boundaries; never stringify arbitrary domain objects. */
internal fun jsonProperties(properties: Map<String, Any>): Map<String, Any> =
    properties.mapValues { (_, value) -> jsonValue(value, emptyList())!! }

private fun jsonValue(value: Any?, ancestors: List<Any>): Any? {
    require(ancestors.size < 64 && ancestors.none { it === value }) { "Cyclic or excessively deep metrics properties" }
    return when (value) {
        null, is String, is Boolean, is Byte, is Short, is Int, is Long -> value
        is Float -> value.also { require(it.isFinite()) { "Metrics numbers must be finite" } }
        is Double -> value.also { require(it.isFinite()) { "Metrics numbers must be finite" } }
        is List<*> -> value.map { jsonValue(it, ancestors + listOf(value)) }
        is Map<*, *> -> value.entries.associate { (key, child) ->
            require(key is String) { "Metrics property keys must be strings" }
            key to jsonValue(child, ancestors + listOf(value))
        }
        else -> error("Metrics properties must contain only JSON values")
    }
}
