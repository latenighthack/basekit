package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/** The protocol lives in the app's exported Kotlin framework, so the conformance must be compiled there. */
class ApplePostHogBridgeGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val frameworkImports: List<String>,
) {
    fun generate() {
        codeGenerator.createNewFile(dependencies, "", "BasekitPostHogBridge", "swift").use { it.writeln(render()) }
    }

    internal fun render(): String = buildString {
        frameworkImports.forEach { appendLine("import $it") }
        appendLine("import Foundation")
        appendLine("import PostHog")
        appendLine("import BasekitNavigationPostHog")
        appendLine()
        appendLine("public final class BasekitPostHogReporter: NSObject, PostHogReporter {")
        appendLine("    private let adapter: PostHogReportingAdapter")
        appendLine("    public init(client: PostHogSDK) { adapter = PostHogReportingAdapter(client: client); super.init() }")
        appendLine("    public func capture(event: String, properties: [String: Any]) { adapter.capture(event: event, properties: properties) }")
        appendLine("    public func screen(name: String, properties: [String: Any]) { adapter.screen(name: name, properties: properties) }")
        appendLine("}")
        appendLine()
        appendLine("public func postHogMetrics(client: PostHogSDK, options: PostHogMetricsOptions = PostHogMetricsOptions()) -> PostHogMetricsCollector {")
        appendLine("    PostHogMetricsCollector(reporter: BasekitPostHogReporter(client: client), options: options)")
        appendLine("}")
    }
}
