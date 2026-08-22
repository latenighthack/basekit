import com.latenighthack.basekit.gradle.appleXcframework

// Sample consumer of the navigation codegen. Not published — it exists to prove the processor
// generates compilable navigators/routes across every target (android/jvm/js/ios/macos via SKIE).
plugins {
    id("basekit.kmp-library")
    id("basekit.navigation")
    id("basekit.viewmodel")
    alias(libs.plugins.ksp)
    alias(libs.plugins.skie)
}

ksp {
    // Package the generated navigator/route interfaces land in (co-located with the destinations).
    arg("Basekit_NavigationPackage", "com.latenighthack.basekit.demo")
    // Also generate the ViewModel test harness (TestViewModelRegistry + TestClientNavigator) into
    // com.latenighthack.basekit.demo.test — proves the harness codegen end-to-end.
    arg("Basekit_GenerateTestNavigator", "true")
    // Frameworks the generated Swift wrappers must import. SKIE folds the exported KMP types AND the
    // bundled Swift (KvoViewModel, DeltaList, the collection-view data sources) into DemoCore's own
    // Swift module, so this one import makes the collected wrappers compile standalone — which is
    // what lets CI run `swiftc -typecheck` over them.
    arg("basekit.viewmodel.swiftFrameworkImports", "DemoCore")
}

dependencies {
    // Run kotlin-inject's processor over the jvmTest DemoTestComponent so the harness builds ViewModels
    // through DI (the same path the TUI uses), rather than calling their constructors directly.
    add("kspJvmTest", libs.kotlin.inject.ksp)
}

kotlin {
    appleXcframework("DemoCore", isStatic = true) {
        export(project(":basekit-navigation"))
        export(project(":basekit-viewmodel"))
        // Re-export deltalist so Swift sees Delta and the collection-view data sources the
        // generated Kvo wrappers use (UICollectionView on iOS, NSCollectionView on macOS).
        export(libs.deltalist.core)
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":basekit-navigation"))
                api(project(":basekit-viewmodel"))
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
    }
}
