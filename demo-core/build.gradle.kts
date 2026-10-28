import com.latenighthack.basekit.gradle.appleXcframework

// Sample consumer of the navigation codegen. Not published — it exists to prove the processor
// generates compilable navigators/routes across every target (android/jvm/js/ios/macos via SKIE).
plugins {
    id("basekit.kmp-library")
    id("com.latenighthack.basekit.navigation")
    id("com.latenighthack.basekit.viewmodel")
    id("com.latenighthack.basekit.catalog")
    id("com.latenighthack.basekit.fakes")
    alias(libs.plugins.ksp)
    alias(libs.plugins.skie)
    alias(libs.plugins.compose.compiler)
}

ksp {
    // Package the generated navigator/route interfaces land in (co-located with the destinations).
    arg("basekit.viewmodel.compose", "true")
    arg("basekit.viewmodel.react", "true")
    arg("basekit.viewmodel.reactPackageVersion", project.version.toString())
    arg("basekit.viewmodel.reactModule", "basekit-demo-core")
    arg("basekit.viewmodel.reactEnumNames", "lowerCamel")
    arg("Basekit_NavigationPackage", "com.latenighthack.basekit.demo")
    // Also generate the ViewModel test harness (TestViewModelRegistry + TestClientNavigator) into
    // com.latenighthack.basekit.demo.test — proves the harness codegen end-to-end.
    arg("Basekit_GenerateTestNavigator", "true")
    // Frameworks the generated Swift wrappers must import. SKIE folds the exported KMP types AND the
    // bundled Swift (KvoViewModel, DeltaList, the collection-view data sources) into DemoCore's own
    // Swift module, so this one import makes the collected wrappers compile standalone — which is
    // what lets CI run `swiftc -typecheck` over them.
    arg("basekit.viewmodel.swiftFrameworkImports", "DemoCore")
    arg("basekit.navigation.swiftFrameworkImports", "DemoCore")
    arg("basekit.navigation.posthog", "true")
}

dependencies {
    // Run kotlin-inject's processor over the jvmTest DemoTestComponent so the harness builds ViewModels
    // through DI (the same path the TUI uses), rather than calling their constructors directly.
    add("kspJvmTest", libs.kotlin.inject.ksp)
}

kotlin {
    js {
        useEsModules()
        generateTypeScriptDefinitions()
        compilations["main"].packageJson { customField("type", "module") }
    }
    appleXcframework("DemoCore", isStatic = true) {
        export(project(":basekit-navigation"))
        export(project(":basekit-viewmodel"))
        export(project(":basekit-navigation-metrics"))
        // Re-export deltalist so Swift sees Delta and the collection-view data sources the
        // generated Kvo wrappers use (UICollectionView on iOS, NSCollectionView on macOS).
        export(libs.deltalist.core)
    }

    sourceSets {
        androidMain.dependencies { implementation(project(":basekit-viewmodel-compose")) }
        jsMain.dependencies { api(project(":basekit-navigation-posthog")) }
        val commonMain by getting {
            dependencies {
                api(project(":basekit-navigation"))
                api(project(":basekit-viewmodel"))
                api(project(":basekit-navigation-metrics"))
                implementation(project(":basekit-annotations"))
                implementation(project(":basekit-viewmodel-annotations"))
                implementation(project(":basekit-tui-annotations"))
                // Runtime backing the generated TestClientNavigator (lands in commonMain via the metadata pass).
                implementation(project(":basekit-navigation-test"))
            }
        }
        // The harness e2e tests exercise Bundle-backed NavigatorArgs, which only work with a real args
        // store — the JVM in-memory actual. (Android needs instrumented tests; the stubbed android.jar in
        // local unit tests throws on Bundle.) So they live in jvmTest, matching the plan's jvmTest run.
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // Route tests against the Bundle-backed args, on a real Bundle.
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.robolectric:robolectric:4.14.1")
            }
        }
    }
}

// `-Pbasekit.demo.orphanDestination` adds a @Destination with no @ViewModelInject implementation, which
// scripts/verify-binding-diagnostics.py expects the viewmodel processor to reject.
if (providers.gradleProperty("basekit.demo.orphanDestination").isPresent) {
    kotlin.sourceSets.named("commonMain") { kotlin.srcDir("src/orphanDestination/kotlin") }
}
// `-Pbasekit.demo.fakeMissingProvider` adds a spec whose State holds a type no fake can be built for,
// which scripts/verify-binding-diagnostics.py expects basekit-fake-ksp to reject in a fake build.
if (providers.gradleProperty("basekit.demo.fakeMissingProvider").isPresent) {
    kotlin.sourceSets.named("commonMain") { kotlin.srcDir("src/fakeMissingProvider/kotlin") }
}
// `-Pbasekit.demo.uncapturedRouteArg` adds a route that leaves an Int argument uncaptured, which
// scripts/verify-binding-diagnostics.py expects the navigation processor to reject.
if (providers.gradleProperty("basekit.demo.uncapturedRouteArg").isPresent) {
    kotlin.sourceSets.named("commonMain") { kotlin.srcDir("src/uncapturedRouteArg/kotlin") }
}

// `-Pbasekit.demo.unboundRouteArg` adds an alias that doesn't capture a non-null route arg, which
// scripts/verify-binding-diagnostics.py expects the navigation processor to reject.
if (providers.gradleProperty("basekit.demo.unboundRouteArg").isPresent) {
    kotlin.sourceSets.named("commonMain") { kotlin.srcDir("src/unboundRouteArg/kotlin") }
}

// `-Pbasekit.demo.indistinctRoute` adds a route of the same shape as detail's, which
// scripts/verify-binding-diagnostics.py expects the navigation processor to reject.
if (providers.gradleProperty("basekit.demo.indistinctRoute").isPresent) {
    kotlin.sourceSets.named("commonMain") { kotlin.srcDir("src/indistinctRoute/kotlin") }
}

// `-Pbasekit.demo.crossingRoutes` adds two routes whose paths cross, which
// scripts/verify-binding-diagnostics.py expects the navigation processor to reject.
if (providers.gradleProperty("basekit.demo.crossingRoutes").isPresent) {
    kotlin.sourceSets.named("commonMain") { kotlin.srcDir("src/crossingRoutes/kotlin") }
}

// `-Pbasekit.fakes=true` builds the demo with fake view models. The real tests check real data, so
// jvmTest runs the fake tests in src/fakeJvmTest instead.
if (providers.gradleProperty("basekit.fakes").orNull == "true") {
    kotlin.sourceSets.named("jvmTest") { kotlin.setSrcDirs(listOf("src/fakeJvmTest/kotlin")) }
    // Fixtures for the fake tests: a splash that only redirects, and nested lists with a placeholder row.
    kotlin.sourceSets.named("commonMain") { kotlin.srcDir("src/fakeFixtures/kotlin") }

    // Some of the generated fakes are checked in under fakes.golden; `check` fails when they differ. After
    // an intended change, copy the files from build/generated/ksp/metadata/commonMain over them.
    val verifyDemoFakes = tasks.register("verifyDemoFakes") {
        val generated = layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin/com/latenighthack/basekit/demo")
        val golden = layout.projectDirectory.dir("fakes.golden")
        dependsOn("kspCommonMainKotlinMetadata")
        inputs.dir(golden)
        doLast {
            golden.asFile.listFiles().orEmpty().sortedBy { it.name }.forEach { expected ->
                val actual = generated.get().file(expected.name).asFile
                if (!actual.isFile || actual.readText() != expected.readText()) {
                    throw GradleException("Generated ${expected.name} differs from demo-core/fakes.golden; diff $actual against it")
                }
            }
        }
    }
    tasks.named("check") { dependsOn(verifyDemoFakes) }
}

composeCompiler {
    targetKotlinPlatforms.set(setOf(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm))
}

// The demo's catalog.yaml is checked in as catalog.golden.yaml; `check` fails when the generated
// catalog differs. After an intended change, copy build/generated/basekit-catalog/catalog.yaml over it.
val verifyDemoCatalog = tasks.register("verifyDemoCatalog") {
    val generated = layout.buildDirectory.file("generated/basekit-catalog/catalog.yaml")
    val golden = layout.projectDirectory.file("catalog.golden.yaml")
    dependsOn("generateBasekitCatalog")
    inputs.file(generated)
    inputs.file(golden)
    doLast {
        if (generated.get().asFile.readText() != golden.asFile.readText()) {
            throw GradleException(
                "Generated catalog.yaml differs from demo-core/catalog.golden.yaml; diff " +
                    "${generated.get().asFile} against it",
            )
        }
    }
}
tasks.named("check") { dependsOn(verifyDemoCatalog) }
