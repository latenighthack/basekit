@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package com.latenighthack.basekit.navigation.posthog

import com.latenighthack.basekit.navigation.NavigationEvent
import com.latenighthack.basekit.navigation.metrics.*
import kotlin.js.JsExport

/** Structural subset of the real posthog-js client, including independently configured instances. */
@JsExport
public external interface PostHogClient {
    public fun capture(event: String, properties: dynamic)
}

public class JavaScriptPostHogReporter(private val client: PostHogClient) : PostHogReporter {
    override fun capture(event: String, properties: Map<String, Any>) {
        client.capture(event, toJs(properties))
    }

    override fun screen(name: String, properties: Map<String, Any>) {
        client.capture("\$screen", toJs(properties + ("\$screen_name" to name)))
    }
}

/** Exported configuration handle. The app's Kotlin/JS client factory calls [asCollector]. */
@JsExport
public class JavaScriptPostHogMetrics internal constructor(public val sdkClient: PostHogClient, public val configuration: dynamic) {
    private val manualCollector = asCollector()

    public fun recordScreen(name: String, properties: dynamic = null) {
        // Conversion is part of reporting, so errors follow the same non-interfering path.
        try {
            manualCollector.recordScreen(name, if (properties == null) emptyMap() else fromJs(properties))
        } catch (error: Throwable) {
            try { if (configuration != null && configuration.onError != null) configuration.onError(error) } catch (_: Throwable) { }
        }
    }
}

/**
 * Construct in the app's compiled Kotlin/JS module so navigation's sealed types share its runtime.
 * A separately installed npm package may contain another copy of those Kotlin classes; only the
 * client and plain configuration cross that boundary, never navigation events or observer instances.
 */
public fun JavaScriptPostHogMetrics.asCollector(): PostHogMetricsCollector {
    // Read exported property names explicitly. Kotlin's private field names are minified differently
    // in independently compiled distributions, even when they originate in the same KLIB.
    val handle = asDynamic()
    return PostHogMetricsCollector(JavaScriptPostHogReporter(handle.sdkClient.unsafeCast<PostHogClient>()), options(handle.configuration))
}

@JsExport
public fun createPostHogMetrics(client: PostHogClient, options: dynamic = null): JavaScriptPostHogMetrics =
    JavaScriptPostHogMetrics(client, options)

private fun options(value: dynamic): PostHogMetricsOptions {
    if (value == null) return PostHogMetricsOptions()
    val providers = if (value.propertyProviders == null) emptyList() else
        (value.propertyProviders as Array<dynamic>).map { provider ->
            { observation: MetricsObservation -> fromJs(provider(observationJs(observation))) }
        }
    return PostHogMetricsOptions(
        properties = if (value.properties == null) emptyMap() else fromJs(value.properties),
        propertyProviders = providers,
        filter = if (value.filter == null) null else { observation -> value.filter(observationJs(observation)) as Boolean },
        eventName = if (value.eventName == null) null else { observation, name -> value.eventName(observationJs(observation), name) as String },
        screenName = if (value.screenName == null) null else { screen -> value.screenName((screen as? Enum<*>)?.name ?: "unknown") as String },
        onError = if (value.onError == null) null else { error -> value.onError(error) },
    )
}

private fun observationJs(observation: MetricsObservation): dynamic {
    val result = js("({})")
    result.raw = observation
    when (observation) {
        is MetricsObservation.Action -> {
            result.kind = "action"
            result.viewModelName = observation.event.viewModelName
            result.actionName = observation.event.actionName
            result.viewModel = observation.event.viewModel
        }
        is MetricsObservation.Navigation -> {
            result.kind = "navigation"
            val event = observation.event
            val screen = when (event) {
                is NavigationEvent.NavigatedTo -> { result.operation = "navigate"; event.screen }
                is NavigationEvent.Closed -> { result.operation = "close"; event.screen }
                is NavigationEvent.Responded -> { result.operation = "respond"; event.screen }
            }
            result.screenName = (screen as? Enum<*>)?.name ?: "unknown"
        }
        is MetricsObservation.Screen -> { result.kind = "screen"; result.screenName = observation.name }
    }
    return result
}

private fun toJs(value: Any?): dynamic = when (value) {
    is Map<*, *> -> {
        val result = js("Object.create(null)")
        value.forEach { (key, child) -> result[key as String] = toJs(child) }
        result
    }
    is List<*> -> value.map { toJs(it) }.toTypedArray()
    is Long -> value.toDouble()
    else -> value
}

private fun fromJs(value: dynamic): Map<String, Any> {
    require(value != null && js("typeof value === 'object' && !Array.isArray(value)") as Boolean) { "Metrics properties must be an object" }
    require(js("Object.getPrototypeOf(value) === Object.prototype || Object.getPrototypeOf(value) === null") as Boolean) { "Metrics properties must be a plain JSON object" }
    val result = linkedMapOf<String, Any>()
    val keys = js("Object.keys(value)").unsafeCast<Array<String>>()
    keys.forEach { key ->
        val child = value[key]
        // Null/undefined optional properties are omitted consistently at the top level.
        if (child != null) result[key] = fromJsValue(child, emptyList())!!
    }
    return result
}

private fun fromJsValue(value: dynamic, ancestors: List<Any>): Any? {
    if (value == null) return null
    require(ancestors.size < 64 && ancestors.none { it === value }) { "Cyclic or excessively deep metrics properties" }
    return when (js("typeof value") as String) {
        "string" -> value as String
        "boolean" -> value as Boolean
        "number" -> value as Double
        "object" -> {
            val parents = ancestors + listOf(value.unsafeCast<Any>())
            if (js("Array.isArray(value)") as Boolean) (value as Array<dynamic>).map { fromJsValue(it, parents) }
            else {
                require(js("Object.getPrototypeOf(value) === Object.prototype || Object.getPrototypeOf(value) === null") as Boolean) { "Metrics properties must contain only JSON values" }
                js("Object.keys(value)").unsafeCast<Array<String>>().associateWith { fromJsValue(value[it], parents) }
            }
        }
        else -> error("Metrics properties must contain only JSON values")
    }
}
