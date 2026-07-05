package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated

/** Runs a set of [SymbolProcessor]s as one, so future codegen slices can slot in alongside navigation. */
class MultiSymbolProcessor(
    private val processors: List<SymbolProcessor>
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> =
        processors.flatMap { it.process(resolver) }

    override fun finish() {
        super.finish()
        processors.forEach { it.finish() }
    }

    override fun onError() {
        super.onError()
        processors.forEach { it.onError() }
    }
}

class NavigationProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        MultiSymbolProcessor(
            listOf(
                NavigationProcessor(
                    environment.codeGenerator,
                    environment.logger,
                    environment.options
                )
            )
        )
}
