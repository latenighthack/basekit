import co.touchlab.skie.configuration.EnumInterop
import co.touchlab.skie.configuration.FlowInterop
import co.touchlab.skie.configuration.SealedInterop
import co.touchlab.skie.configuration.SuspendInterop
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("basekit.kmp-library")
    alias(libs.plugins.skie)
}

skie {
    features {
        group {
            FlowInterop.Enabled(false)
            SealedInterop.Enabled(false)
            EnumInterop.Enabled(false)
            SuspendInterop.Enabled(false)
        }
    }
}

kotlin {
    val xcf = XCFramework("BasekitNavigation")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target ->
        target.binaries.framework {
            baseName = "BasekitNavigation"
            isStatic = false
            xcf.add(this)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":basekit-annotations"))
            }
        }
    }
}
