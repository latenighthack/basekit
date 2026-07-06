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
}

ksp {
    // The package (in the demo-core dependency) the TUI processor scans for @ViewModel/@TuiScreen.
    arg("Basekit_TuiPackage", "com.latenighthack.basekit.demo")
    // The package the navigation slice emitted its navigator/route interfaces into.
    arg("Basekit_NavigationPackage", "com.latenighthack.basekit.demo")
}

application {
    mainClass.set("com.latenighthack.basekit.demo.MainKt")
}
