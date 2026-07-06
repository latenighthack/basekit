import org.gradle.accessors.dm.LibrariesForLibs

// Consumer wiring for the TUI codegen. A plain Kotlin/JVM module applies this (alongside the Kotlin
// JVM plugin) to turn on two processors in the single JVM `ksp` pass:
//   - basekit-tui-ksp, which reads @ViewModel/@TuiScreen (and the @Destination graph) from the
//     dependency package named by  ksp { arg("Basekit_TuiPackage", "<pkg>") }  and emits the TamboUI
//     screens + the kotlin-inject @Component + the TuiApp entry;
//   - kotlin-inject's processor, which then wires that generated @Component's create().
plugins {
    id("com.google.devtools.ksp")
}

val libs = the<LibrariesForLibs>()

dependencies {
    add("ksp", project(":basekit-tui-ksp"))
    add("ksp", libs.kotlin.inject.ksp)
}
