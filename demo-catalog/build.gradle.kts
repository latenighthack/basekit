// Round trip for the catalog tools. `generateFromBasekitCatalog` writes Kotlin from catalog.yaml into
// build/generated; the module compiles it with basekit's processors and kotlin-inject; `check` then
// requires the catalog the processors write back to equal catalog.yaml byte for byte. Not published.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.latenighthack.basekit.catalog")
}

val generatedSources = layout.buildDirectory.dir("generated/catalog-codegen/kotlin")

basekitCatalogCodegen {
    catalog.set(layout.projectDirectory.file("catalog.yaml"))
    outputDirectory.set(generatedSources)
    // The tree is build output, so edited regions are rewritten too.
    force.set(true)
}

val generateSources = tasks.named("generateFromBasekitCatalog")

kotlin {
    jvmToolchain(17)
    jvm()
    // basekit's processors run on the common metadata pass, which Kotlin only creates when commonMain
    // is shared by at least two targets.
    macosArm64()

    sourceSets {
        commonMain {
            kotlin.srcDir(generatedSources)
            dependencies {
                implementation(project(":basekit-navigation"))
                implementation(project(":basekit-viewmodel"))
                implementation(project(":basekit-annotations"))
                implementation(project(":basekit-viewmodel-annotations"))
                // The catalog's fake: keys are written as fake annotations.
                implementation(project(":basekit-fake-annotations"))
                implementation(libs.deltalist.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlin.inject.runtime.kmp)
            }
        }
        jvmTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

dependencies {
    // Generates the actual for `@KmpComponentCreate expect fun createViewModelComponent()`.
    add("kspJvm", libs.kotlin.inject.ksp.kmp)
    add("kspMacosArm64", libs.kotlin.inject.ksp.kmp)
}

tasks.matching { it.name.startsWith("ksp") || it.name.startsWith("compile") }.configureEach {
    dependsOn(generateSources)
}

val verifyRoundTrip = tasks.register("verifyCatalogRoundTrip") {
    val generated = layout.buildDirectory.file("generated/basekit-catalog/catalog.yaml")
    val input = layout.projectDirectory.file("catalog.yaml")
    dependsOn("generateBasekitCatalog")
    inputs.file(generated)
    inputs.file(input)
    doLast {
        if (generated.get().asFile.readText() != input.asFile.readText()) {
            throw GradleException(
                "The catalog written from the generated code differs from demo-catalog/catalog.yaml; diff " +
                    "${generated.get().asFile} against it",
            )
        }
    }
}
tasks.named("check") { dependsOn(verifyRoundTrip) }
