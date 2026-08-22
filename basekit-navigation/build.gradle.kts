import co.touchlab.skie.configuration.EnumInterop
import co.touchlab.skie.configuration.FlowInterop
import co.touchlab.skie.configuration.SealedInterop
import co.touchlab.skie.configuration.SuspendInterop
import com.latenighthack.basekit.gradle.appleXcframework

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
    appleXcframework("BasekitNavigation", isStatic = false)

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":basekit-annotations"))
            }
        }
    }
}
