package com.latenighthack.basekit.gradle.plugin

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

/** Switches generated fake view models on for a module (see `com.latenighthack.basekit.fakes`). */
abstract class BasekitFakesExtension {
    /**
     * Whether this build uses fakes in place of the real view models. Defaults to the Gradle property
     * `basekit.fakes` (`-Pbasekit.fakes=true`), so one flag switches every module of a build.
     */
    abstract val enabled: Property<Boolean>

    /**
     * Sources added to commonMain only when fakes are on, for `@FakeProvider` functions, which use the
     * fake runtime. Defaults to `src/commonFakes/kotlin`.
     */
    abstract val sourceDirectory: DirectoryProperty

    /**
     * The fewest and most rows a list gets when no annotation sizes it, written into the generated fakes.
     * Unset, the fakes read `FakeConfig.listMin`/`listMax` (1 and 1). Also `-Pbasekit.fakes.listMin=…`
     * and `-Pbasekit.fakes.listMax=…`. Data sets still scale them.
     */
    abstract val listMin: Property<Int>
    abstract val listMax: Property<Int>
}
