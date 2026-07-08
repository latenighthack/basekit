plugins {
    id("basekit.jvm-library")
}

dependencies {
    // Expose the @TuiScreen/@TuiNavigatesTo annotations so consumers (and KSP) see them on the classpath.
    api(project(":basekit-tui-annotations"))

    // NavigationResponder backs the responding-destination (picker) result round-trip in pushForResult.
    api(project(":basekit-navigation"))

    // TamboUI toolkit is part of the public surface (generated screens return its Elements).
    api(libs.tamboui.toolkit)
    // A backend must be on the runtime classpath for TamboUI's ServiceLoader to find a terminal.
    implementation(libs.tamboui.jline3.backend)

    // deltalist supplies Delta<T>; the list holder materializes it into a snapshot.
    api(libs.deltalist.core)
    // Coroutines drive state collection and action dispatch on the runner scope.
    api(libs.kotlinx.coroutines.core)
    // kotlin-inject runtime is exposed so consumers only depend on this module + their core.
    api(libs.kotlin.inject.runtime)

    // kotlin-test mapped to the JUnit5 platform (the convention runs useJUnitPlatform()).
    testImplementation(kotlin("test-junit5"))
    // Drives pushForResult's suspend round-trip on a controllable dispatcher.
    testImplementation(libs.kotlinx.coroutines.test)
}
