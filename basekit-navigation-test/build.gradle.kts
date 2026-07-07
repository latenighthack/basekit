// Runtime for the generated ViewModel test harness (see basekit-ksp's TestNavigatorGenerator).
// Pure commonMain: NavigationEvent / NavigationRecorder / TestNavigatorApi / awaitViewModel, plus the
// response-navigation channel (NavigationResponder). Depends only on the navigation runtime and
// coroutines-core, so pulling it into a consumer's commonMain adds ~nil to the production classpath.
plugins {
    id("basekit.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":basekit-navigation"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
