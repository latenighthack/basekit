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
    implementation("com.latenighthack.basekit:basekit-viewmodel-annotations:0.3.1")
    implementation("com.latenighthack.basekit:basekit-viewmodel:0.3.1")
    ksp("com.latenighthack.basekit:basekit-viewmodel-ksp:0.3.1")
}
kotlin { jvmToolchain(17) }
''')
    source = work / 'src/main/kotlin'
    source.mkdir(parents=True)
    specimen = source / 'Spec.kt'
    specimen.write_text('''
import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.*
enum class Mode { A, @ReactEnumName("A") B }
@ViewModelSpec interface Invalid : ViewModel<Invalid.State> {
    data class State(val value: String, val mode: Mode)
    @ViewModelIdentity var key: Int?
    @ChildViewModel val child: Invalid?
    @ViewModelList(Invalid::class) val rows: String
    suspend fun multi(a: String, b: Int)
    suspend fun returning(): String
    @CodegenIgnore suspend fun intentionallyUnbound(a: String, b: Int)
}
''')
    result = subprocess.run([str(root / 'gradlew'), '-p', str(work), 'compileKotlin', '--console=plain'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    expected = ['multiple parameters', 'returning Unit', 'stable non-null val', 'must be Flow<Delta<ChildVm>>', 'public non-null String val', 'A, B -> "A"']
    if result.returncode == 0 or any(message not in result.stdout for message in expected):
        raise SystemExit(result.stdout)
    if 'Action intentionallyUnbound' in result.stdout: raise SystemExit(result.stdout)
    print('KSP rejected all unsupported signatures at source locations; @CodegenIgnore was respected.')
    specimen.write_text('''
import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.*
interface ParentActions { suspend fun refresh() }
@ViewModelSpec interface Valid : ViewModel<Valid.State>, ParentActions {
    data class State(val note: String?)
    @ViewModelIdentity val key: String
    @CodegenIgnore @ViewModelIdentity val ignoredKey: Int
    suspend fun setNote(value: String?)
    @CodegenIgnore suspend fun deliberatelyUnbound(a: String, b: Int)
    @CodegenIgnore suspend fun ignoredAction()
    suspend fun `save$draft`()
}
@CodegenIgnore @ViewModelSpec interface Ignored
''')
    subprocess.run([str(root / 'gradlew'), '-p', str(work), 'compileKotlin', '--console=plain'], check=True)
    generated = next((work / 'build/generated/ksp').rglob('ObservingValid.kt')).read_text()
    assert 'override suspend fun `refresh`' in generated
    assert 'override suspend fun `save$draft`' in generated
    assert 'override suspend fun `ignoredAction`' not in generated

# A destination without a @ViewModelInject implementation fails the build, naming it and its declaration.
result = subprocess.run([str(root / 'gradlew'), ':demo-core:kspCommonMainKotlinMetadata', '-Pbasekit.demo.orphanDestination',
                         '--console=plain'], cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
expected = 'Destination com.latenighthack.basekit.demo.OrphanViewModel (declared at '
if result.returncode == 0 or expected not in result.stdout or 'OrphanViewModel.kt:' not in result.stdout:
    raise SystemExit(result.stdout)
print('KSP rejected a destination without a @ViewModelInject implementation.')
# A route must set every argument it does not capture; an Int has no default.
result = subprocess.run([str(root / 'gradlew'), ':demo-core:kspCommonMainKotlinMetadata', '-Pbasekit.demo.uncapturedRouteArg',
                         '--console=plain'], cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
expected = 'UncapturedRouteArgViewModel.Args.count is not captured by "/uncaptured" and has no default'
if result.returncode == 0 or expected not in result.stdout or 'UncapturedRouteArgViewModel.kt:' not in result.stdout:
    raise SystemExit(result.stdout)
print('KSP rejected a route that leaves an argument without a default.')
# Two routes of the same shape: neither is more specific, so only registration order would choose.
result = subprocess.run([str(root / 'gradlew'), ':demo-core:kspCommonMainKotlinMetadata', '-Pbasekit.demo.indistinctRoute',
                         '--console=plain'], cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
expected = '"/detail/{key}" on IndistinctRouteViewModel match the same addresses and neither is more specific'
if result.returncode == 0 or expected not in result.stdout or 'IndistinctRouteViewModel.kt:' not in result.stdout:
    raise SystemExit(result.stdout)
print('KSP rejected two routes of the same shape.')
# Two routes whose paths cross: each is literal where the other has a parameter.
result = subprocess.run([str(root / 'gradlew'), ':demo-core:kspCommonMainKotlinMetadata', '-Pbasekit.demo.crossingRoutes',
                         '--console=plain'], cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
expected = '"/teams/new/{section}" on NewTeamSectionViewModel and "/teams/{teamId}/settings" on TeamSettingsViewModel cross'
if result.returncode == 0 or expected not in result.stdout or 'CrossingRouteViewModels.kt:' not in result.stdout:
    raise SystemExit(result.stdout)
print('KSP rejected two routes whose paths cross.')
# A fake build fails at a State field whose type no fake can be built for, and says how to supply one.
result = subprocess.run([str(root / 'gradlew'), ':demo-core:kspCommonMainKotlinMetadata', '-Pbasekit.demo.fakeMissingProvider',
                         '-Pbasekit.fakes=true', '--console=plain'], cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
expected = 'No fake value for com.latenighthack.basekit.demo.Clock (clock): add a @FakeProvider function returning it'
if result.returncode == 0 or expected not in result.stdout or 'UnfakeableViewModel.kt:' not in result.stdout:
    raise SystemExit(result.stdout)
print('KSP rejected a fake State field with no way to build its value.')
