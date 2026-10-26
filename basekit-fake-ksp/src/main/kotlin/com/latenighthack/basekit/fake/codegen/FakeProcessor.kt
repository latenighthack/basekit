package com.latenighthack.basekit.fake.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSNode

/**
 * Writes a fake implementation of every `@ViewModelSpec` in the compilation, plus `FakeNavigator`,
 * `FakeArgs`, `FakeViewModels` and `FakeValues`. It runs in the common metadata pass only, and writes in
 * its first round: a fake that replaces a real `@ViewModelInject` implementation is itself
 * `@ViewModelInject`, and the viewmodel processor and kotlin-inject only see it if it exists before their
 * later rounds and per-target passes.
 */
class FakeProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {
    private var done = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (done) return emptyList()
        done = true
        codeGenerator.createNewFile(Dependencies(false), MARKER_PACKAGE, "basekit_fake_marker", "log").close()
        val marker = codeGenerator.generatedFile.firstOrNull()?.invariantSeparatorsPath ?: return emptyList()
        if ("/ksp/metadata/" !in marker) {
            logger.warn("basekit-fake-ksp only runs in the common metadata pass (kspCommonMainMetadata); skipping $marker")
            return emptyList()
        }

        val output = FakeExtractor(resolver, logger, options).extract() ?: return emptyList()
        val dependencies = Dependencies(aggregating = true, *output.sourceFiles.toTypedArray())
        fun write(packageName: String, name: String, content: String) {
            codeGenerator.createNewFile(dependencies, packageName, name, "kt").use { it.write(content.toByteArray()) }
        }

        output.specs.forEach { write(it.packageName, it.className, FakeViewModelGenerator.render(it)) }
        val shared = output.sharedPackage
        if (output.destinations.isNotEmpty()) {
            write(shared, "FakeNavigator", FakeSupportGenerator.renderNavigator(shared, output.destinations, output.navigatorTypes, output.hasClose))
            write(shared, "FakeArgs", FakeSupportGenerator.renderArgs(shared, output.destinations))
        }
        write(shared, "FakeViewModels", FakeSupportGenerator.renderViewModels(shared, output.viewModelFunctions))
        write(shared, "FakeValues", FakeSupportGenerator.renderValues(shared, output.valueImports, output.valueFunctions))
        return emptyList()
    }

    private companion object {
        const val MARKER_PACKAGE = "com.latenighthack.basekit.fake.gen"
    }
}

class FakeProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        FakeProcessor(environment.codeGenerator, environment.logger, environment.options)
}

/** Reads the specs and the graph without repeating the errors their own processors report. */
internal object SilentLogger : KSPLogger {
    override fun logging(message: String, symbol: KSNode?) = Unit
    override fun info(message: String, symbol: KSNode?) = Unit
    override fun warn(message: String, symbol: KSNode?) = Unit
    override fun error(message: String, symbol: KSNode?) = Unit
    override fun exception(e: Throwable) = Unit
}
