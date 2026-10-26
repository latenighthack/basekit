// Runtime for the fake view models basekit-fake-ksp generates: seeded fake data, data sets, the log of
// fake actions and a headless navigation stack. Added to commonMain only when fakes are switched on.
plugins {
    id("basekit.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":basekit-fake-annotations"))
                api(project(":basekit-navigation"))
                api(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
