plugins { id("basekit.kmp-library") }

kotlin {
    js {
        useEsModules()
        generateTypeScriptDefinitions()
        compilations["main"].packageJson { customField("type", "module") }
    }
    sourceSets {
        commonMain.dependencies { api(project(":basekit-navigation-metrics")) }
        androidMain.dependencies { api(libs.posthog.android) }
        androidUnitTest.dependencies { implementation(kotlin("test-junit")) }
        jsMain.dependencies { implementation(npm("posthog-js", libs.versions.posthog.js.get())) }
    }
}

// A typed facade beside the unchanged compiler output, like the generated React package.
tasks.register<Sync>("collectBasekitPostHogJavaScript") {
    dependsOn("jsBrowserProductionLibraryDistribution")
    from(layout.buildDirectory.dir("dist/js/productionLibrary")) { exclude("package.json") }
    from("src/jsMain/npm")
    into(layout.buildDirectory.dir("generated/basekit-posthog-js"))
    val sdkVersion = libs.versions.posthog.js.get()
    inputs.property("sdkVersion", sdkVersion)
    inputs.property("packageVersion", project.version.toString())
    doLast {
        destinationDir.resolve("package.json").writeText("""
            {
              "name": "@latenighthack/basekit-navigation-posthog",
              "version": "${project.version}",
              "type": "module",
              "main": "index.js",
              "types": "index.d.ts",
              "exports": { ".": { "types": "./index.d.ts", "import": "./index.js" } },
              "dependencies": { "posthog-js": "$sdkVersion", "react": "^19.1.0", "react-dom": "^19.1.0" }
            }
        """.trimIndent())
    }
}
