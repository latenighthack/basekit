import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// Sample consumer of the navigation codegen. Not published — it exists to prove the processor
// generates compilable navigators/routes across every target (android/jvm/js/ios via SKIE).
plugins {
    id("basekit.kmp-library")
    id("basekit.navigation")
    alias(libs.plugins.ksp)
    alias(libs.plugins.skie)
}

ksp {
    // Package the generated navigator/route interfaces land in (co-located with the destinations).
    arg("Basekit_NavigationPackage", "com.latenighthack.basekit.demo")
}

kotlin {
    val xcf = XCFramework("DemoCore")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target ->
        target.binaries.framework {
            baseName = "DemoCore"
            isStatic = true
            xcf.add(this)
            export(project(":basekit-navigation"))
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":basekit-navigation"))
                implementation(project(":basekit-annotations"))
            }
        }
    }
}
