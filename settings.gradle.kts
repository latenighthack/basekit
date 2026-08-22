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
        // deltalist is published from its own repo; mavenLocal picks up a `publishToMavenLocal`
        // there so basekit can build against an unreleased version (e.g. while adding a platform)
        // before it reaches Central. Ordered after Central so a released version always wins.
        mavenLocal()
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

// deltalist is developed alongside basekit. It normally resolves as a published artifact at the
// version pinned in gradle/libs.versions.toml — from mavenLocal while iterating, from Maven Central
// in CI (single-repo checkout), so publish deltalist first.
//
// Optionally it can be consumed from source instead via a composite build, which substitutes
// core + android-recyclerview + react by group:module with no publish step. That is opt-in
// (`-PdeltalistComposite=true`) rather than automatic, because it currently fails: AGP refuses to
// have two versions in one build and deltalist is on AGP 8.7.3 / Gradle 8.9 against basekit's
// 8.13.2 / 9.5.1. Pure-Kotlin targets substitute fine; anything that resolves an Android variant
// does not. Align deltalist's AGP and Gradle, then this can go back to being automatic.
//
// (The path is ../deltalist — both repos live under the same parent. It read ../../deltalist for a
// long time, which never existed, so the substitution silently never happened and the pinned
// version drifted behind deltalist's actual VERSION_NAME.)
val useDeltalistComposite = providers.gradleProperty("deltalistComposite").orNull == "true"
if (useDeltalistComposite && file("../deltalist").exists()) {
    includeBuild("../deltalist")
}

// Navigation slice — the first codegen slice of the framework.
include(":basekit-annotations") // navigation annotations (KMP)
include(":basekit-navigation")  // navigation runtime + routing (KMP, SKIE)
include(":basekit-ksp")         // KSP processor + generators (JVM)
// Test harness runtime for the generated TestClientNavigator + registry (KMP, commonMain).
include(":basekit-navigation-test")

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
