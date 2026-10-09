plugins { id("basekit.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":basekit-navigation"))
            api(project(":basekit-viewmodel"))
        }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
    }
}
