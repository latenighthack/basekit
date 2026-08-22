import com.latenighthack.basekit.gradle.plugin.basekitProcessor
import com.latenighthack.basekit.gradle.plugin.KOTLIN_INJECT_VERSION

// Wires the Basekit TUI codegen into a plain Kotlin/JVM module: the Basekit TUI processor (reads
// @ViewModelSpec/@TuiScreen and the @Destination graph from `ksp { arg("Basekit_TuiPackage", "<pkg>") }`
// and emits the TamboUI screens + the kotlin-inject @Component + the TuiApp entry) plus kotlin-inject's
// own processor, which wires that generated component's create().
//
// kotlin-inject's compiler is pinned to the version Basekit builds against; a consumer on a different
// kotlin-inject version should wire the TUI processors by hand (see the README) rather than apply this.
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    add("ksp", basekitProcessor(":basekit-tui-ksp", "basekit-tui-ksp"))
    add("ksp", "me.tatarka.inject:kotlin-inject-compiler-ksp:$KOTLIN_INJECT_VERSION")
}
