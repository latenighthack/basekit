#!/usr/bin/env python3
"""Actual KSP consumer compilation, including rejection cases (no mock KS symbols)."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='basekit-diagnostics-') as directory:
    work = Path(directory)
    (work / 'settings.gradle.kts').write_text(f'''
pluginManagement {{ repositories {{ gradlePluginPortal(); mavenCentral(); google() }} }}
rootProject.name = "binding-diagnostics"
includeBuild("{root}")
''')
    (work / 'build.gradle.kts').write_text('''
plugins { kotlin("jvm") version "2.3.10"; id("com.google.devtools.ksp") version "2.3.10" }
repositories { mavenCentral(); google() }
dependencies {
    implementation("com.latenighthack.basekit:basekit-viewmodel-annotations:0.3.0")
    implementation("com.latenighthack.basekit:basekit-viewmodel:0.3.0")
    ksp("com.latenighthack.basekit:basekit-viewmodel-ksp:0.3.0")
}
kotlin { jvmToolchain(17) }
''')
    source = work / 'src/main/kotlin'
    source.mkdir(parents=True)
    specimen = source / 'Spec.kt'
    specimen.write_text('''
import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.*
@ViewModelSpec interface Invalid : ViewModel<Invalid.State> {
    data class State(val value: String)
    @ViewModelIdentity var key: Int?
    @ChildViewModel val child: Invalid?
    @ViewModelList(Invalid::class) val rows: String
    suspend fun multi(a: String, b: Int)
    suspend fun returning(): String
    @CodegenIgnore suspend fun intentionallyUnbound(a: String, b: Int)
}
''')
    result = subprocess.run([str(root / 'gradlew'), '-p', str(work), 'compileKotlin', '--console=plain'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    expected = ['multiple parameters', 'returning Unit', 'stable non-null val', 'must be Flow<Delta<ChildVm>>', 'public non-null String val']
    if result.returncode == 0 or any(message not in result.stdout for message in expected):
        raise SystemExit(result.stdout)
    if 'Action intentionallyUnbound' in result.stdout: raise SystemExit(result.stdout)
    print('KSP rejected all unsupported signatures at source locations; @CodegenIgnore was respected.')
    specimen.write_text('''
import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.*
@ViewModelSpec interface Valid : ViewModel<Valid.State> {
    data class State(val note: String?)
    @ViewModelIdentity val key: String
    @CodegenIgnore @ViewModelIdentity val ignoredKey: Int
    suspend fun setNote(value: String?)
    @CodegenIgnore suspend fun deliberatelyUnbound(a: String, b: Int)
}
@CodegenIgnore @ViewModelSpec interface Ignored
''')
    subprocess.run([str(root / 'gradlew'), '-p', str(work), 'compileKotlin', '--console=plain'], check=True)
