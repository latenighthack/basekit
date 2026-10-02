pluginManagement {
    // Internal build conventions (basekit.kmp-library / basekit.jvm-library) live in build-logic.
    includeBuild("build-logic")
    // The published consumer plugins (com.latenighthack.basekit.{navigation,viewmodel,tui}) live in
    // basekit-gradle-plugin. Including it here lets the in-repo demos apply them by id and dogfood the
    // exact plugins that ship to Maven Central; the root gradle.properties sets
    // basekit.useProjectDependencies=true so the demos resolve the processors via project() rather
    // than the published coordinate.
    includeBuild("basekit-gradle-plugin")
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
        // Global Maven Local is intentionally excluded; use -PfhWorkspace for local development.
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

// Also register ordinary module substitution: Fullhouse rewrites buildscript coordinates to an
// immutable development version, which otherwise bypasses pluginManagement's plugin substitution.
includeBuild("basekit-gradle-plugin")

// deltalist is developed alongside basekit. It normally resolves as a published artifact at the
// version pinned in gradle/libs.versions.toml from Maven Central. Paired development uses
// an explicit fh workspace repository; publish the selected deltalist worktree first.
//
// An explicit composite remains available for aligned local toolchains. It is never enabled by
// default and does not replace immutable release or Fullhouse workspace verification.
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
include(":basekit-viewmodel-compose") // optional generated Compose host runtime
include(":basekit-viewmodel")             // viewmodel runtime + platform bindings (KMP, SKIE)
include(":basekit-viewmodel-ksp")         // KSP processor + platform generators (JVM)

// TUI slice — the third codegen slice: generates a TamboUI terminal UI from the ViewModels.
include(":basekit-tui-annotations") // @TuiScreen / @TuiNavigatesTo link annotations (KMP)
include(":basekit-tui")             // TUI runtime: TuiApp, runner, screen/nav abstractions (JVM)
include(":basekit-tui-ksp")         // KSP processor generating TamboUI screens + component (JVM)

include(":demo-core")           // sample consumer proving the codegen end-to-end
include(":demo-jvm")            // runnable JVM app proving the TUI slice end-to-end

// Explicit isolated library development; release builds use published dependencies.
apply(from = "gradle/fh-workspace.settings.gradle")
