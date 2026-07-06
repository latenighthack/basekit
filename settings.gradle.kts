pluginManagement {
    // Convention plugins (basekit.kmp-library / basekit.jvm-library / basekit.navigation)
    // live in the build-logic included build.
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // TamboUI (the `tui` slice's terminal-UI toolkit) is snapshot-only for now.
        maven {
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            mavenContent { snapshotsOnly() }
        }
        // SKIE + KMP toolchains pull the JS/Node distribution from nodejs.org.
        exclusiveContent {
            forRepository {
                ivy("https://nodejs.org/dist/") {
                    name = "Node Distributions at $url"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                    content { includeModule("org.nodejs", "node") }
                }
            }
            filter { includeGroup("org.nodejs") }
        }
    }
}

rootProject.name = "basekit"

// deltalist is developed alongside basekit; consume it from source (composite build) so the list
// bindings (core + android-recyclerview + react) resolve via dependency substitution without a
// separate publish step. Must be a top-level includeBuild (not under pluginManagement) so it
// participates in regular dependency substitution, not just plugin resolution.
includeBuild("../../deltalist")

// Navigation slice — the first codegen slice of the framework.
include(":basekit-annotations") // navigation annotations (KMP)
include(":basekit-navigation")  // navigation runtime + routing (KMP, SKIE)
include(":basekit-ksp")         // KSP processor + generators (JVM)

// ViewModel binding slice — the second codegen slice.
include(":basekit-viewmodel-annotations") // viewmodel annotations (KMP)
include(":basekit-viewmodel")             // viewmodel runtime + platform bindings (KMP, SKIE)
include(":basekit-viewmodel-ksp")         // KSP processor + platform generators (JVM)

// TUI slice — the third codegen slice: generates a TamboUI terminal UI from the ViewModels.
include(":basekit-tui-annotations") // @TuiScreen / @TuiNavigatesTo link annotations (KMP)
include(":basekit-tui")             // TUI runtime: TuiApp, runner, screen/nav abstractions (JVM)
include(":basekit-tui-ksp")         // KSP processor generating TamboUI screens + component (JVM)

include(":demo-core")           // sample consumer proving the codegen end-to-end
include(":demo-jvm")            // runnable JVM app proving the TUI slice end-to-end
