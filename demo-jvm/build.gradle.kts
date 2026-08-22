// Runnable JVM app proving the TUI slice end-to-end. It depends on demo-core (the ViewModels +
// destinations + generated navigators) and the TUI runtime; the basekit.tui plugin runs the TUI +
// kotlin-inject processors over the scanned package to generate the screens, component, and TuiApp.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("basekit.tui")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":demo-core"))
    implementation(project(":basekit-tui"))
    // basekit-tui declares TamboUI compileOnly, so the consuming app supplies it (and picks the
    // version). The generated screens return TamboUI Elements, so the toolkit is a compile dep;
    // a backend must be on the runtime classpath for its ServiceLoader to find a terminal.
    implementation(libs.tamboui.toolkit)
    runtimeOnly(libs.tamboui.jline3.backend)
}

ksp {
    // The package (in the demo-core dependency) the TUI processor scans for @ViewModelSpec/@TuiScreen.
    arg("Basekit_TuiPackage", "com.latenighthack.basekit.demo")
    // The package the navigation slice emitted its navigator/route interfaces into.
    arg("Basekit_NavigationPackage", "com.latenighthack.basekit.demo")
}

application {
    mainClass.set("com.latenighthack.basekit.demo.MainKt")
}
