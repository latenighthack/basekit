plugins {
    `kotlin-dsl`
}

dependencies {
    // Plugin artifacts the convention plugins apply. Declared here so the precompiled
    // script plugins under src/main/kotlin can reference them by id.
    implementation(libs.plugin.kotlin)
    implementation(libs.plugin.android)
    implementation(libs.plugin.ksp)
    implementation(libs.plugin.mavenPublish)

    // Expose the version catalog's generated accessors (LibrariesForLibs) to the
    // convention plugins so they can reference `libs.*` via the<LibrariesForLibs>().
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
