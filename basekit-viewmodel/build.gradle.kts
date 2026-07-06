import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// ViewModel binding runtime. Unlike the navigation slice, this framework leaves SKIE's default
// interop ENABLED (Flow -> AsyncSequence, suspend -> async) so the generated Swift Kvo wrappers can
// `for await` state/lists and call actions as `async`. deltalist-core is exported so Swift sees its
// Delta types and data sources.
plugins {
    id("basekit.kmp-library")
    alias(libs.plugins.skie)
}

kotlin {
    val xcf = XCFramework("BasekitViewModel")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target ->
        target.binaries.framework {
            baseName = "BasekitViewModel"
            isStatic = false
            xcf.add(this)
            export(libs.deltalist.core)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":basekit-viewmodel-annotations"))
                api(libs.deltalist.core)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
                // These appear in the runtime's public API (BaseActivity : ComponentActivity;
                // bindViewModels takes LifecycleOwner + RecyclerView) and in the generated
                // Abstract{Vm}Activity, so consumers need them on their compile classpath: expose as api.
                api(libs.androidx.activity)
                api(libs.androidx.lifecycle.runtime.ktx)
                api(libs.androidx.recyclerview)
                api(libs.deltalist.android.recyclerview)
            }
        }
    }
}
