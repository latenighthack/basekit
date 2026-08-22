package com.latenighthack.basekit.gradle.plugin

import org.gradle.api.Project

/**
 * The dependency notation for a Basekit KSP processor.
 *
 * For an external consumer this is the Maven coordinate at the plugin's own version. When Basekit
 * builds itself (the in-repo demos), the Gradle property `basekit.useProjectDependencies=true`
 * switches to a project dependency so the demos exercise the working-tree processors — the plugin is
 * dogfooded without a publish round-trip. External consumers never set that property.
 */
internal fun Project.basekitProcessor(projectPath: String, artifactId: String): Any =
    if (findProperty("basekit.useProjectDependencies") == "true") {
        dependencies.project(mapOf("path" to projectPath))
    } else {
        "com.latenighthack.basekit:$artifactId:$BASEKIT_VERSION"
    }
