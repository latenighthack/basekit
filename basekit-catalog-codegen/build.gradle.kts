plugins {
    id("basekit.jvm-library")
    application
}

// Reads a catalog.yaml (catalog/v1) and writes Kotlin spec interfaces, implementations and a
// kotlin-inject component. Run it with `java -jar`, the application distribution, or the
// `generateFromBasekitCatalog` task of the com.latenighthack.basekit.catalog plugin.
dependencies {
    implementation(libs.snakeyaml)
    testImplementation(kotlin("test-junit5"))
}

application {
    mainClass.set("com.latenighthack.basekit.catalog.tool.MainKt")
    applicationName = "basekit-catalog-codegen"
}

tasks.named<Jar>("jar") {
    manifest { attributes("Main-Class" to "com.latenighthack.basekit.catalog.tool.MainKt") }
}
