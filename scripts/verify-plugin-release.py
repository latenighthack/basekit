#!/usr/bin/env python3
"""Resolve real plugin markers in a standalone consumer, without includeBuild/Maven Local.

Default: publish only the plugin to a temporary file repository and check all target wiring.
--repository URL: check a released plugin and resolve its processor dependencies as well.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def properties(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines()
                if '=' in line and not line.lstrip().startswith('#'))


def run(*args):
    subprocess.run([str(ROOT / 'gradlew'), '--no-configuration-cache', '--stacktrace', *args],
                   cwd=ROOT, check=True)


def verify(repository, version, resolve, directory):
    catalog = (ROOT / 'gradle/libs.versions.toml').read_text()
    def pin(name):
        return re.search(r'^' + re.escape(name) + r' = "([^"]+)"', catalog, re.M)[1]
    directory.mkdir()
    (directory / 'settings.gradle.kts').write_text('''
pluginManagement {
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(REPOSITORY) } }
            filter { includeGroupByRegex("com[.]latenighthack[.]basekit.*") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(REPOSITORY) } }
            filter { includeGroupByRegex("com[.]latenighthack[.]basekit.*") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "basekit-published-consumer"
include(":bindings", ":terminal")
'''.replace('REPOSITORY', json.dumps(repository)))
    (directory / 'build.gradle.kts').write_text(f'''
plugins {{
    kotlin("multiplatform") version "{pin('kotlin')}" apply false
    kotlin("jvm") version "{pin('kotlin')}" apply false
    id("com.android.library") version "{pin('agp')}" apply false
    id("com.latenighthack.basekit.navigation") version "{version}" apply false
    id("com.latenighthack.basekit.viewmodel") version "{version}" apply false
    id("com.latenighthack.basekit.tui") version "{version}" apply false
}}
''')
    (directory / 'gradle.properties').write_text('android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g\n')
    check = '''
fun verifyProcessor(configurationName: String, artifact: String) {
    val processor = configurations.getByName(configurationName).dependencies.single {
        it.group == "com.latenighthack.basekit" && it.name == artifact
    }
    check(processor is org.gradle.api.artifacts.ExternalModuleDependency) {
        "Expected an external processor, got $processor"
    }
    check(processor.version == "VERSION") { "Wrong processor version: $processor" }
    if (RESOLVE) {
        check(configurations.detachedConfiguration(processor.copy()).resolve().isNotEmpty())
    }
}
'''.replace('VERSION', version).replace('RESOLVE', str(resolve).lower())
    bindings = directory / 'bindings'
    bindings.mkdir()
    (bindings / 'build.gradle.kts').write_text('''
plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("com.latenighthack.basekit.navigation")
    id("com.latenighthack.basekit.viewmodel")
}
kotlin {
    jvm()
    androidTarget()
    js(IR) { nodejs() }
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
}
android {
    namespace = "com.example.basekit.validation"
    compileSdk = 35
}
''' + check + '''
tasks.register("verifyPublishedPlugin") {
    doLast {
        verifyProcessor("kspCommonMainMetadata", "basekit-ksp")
        verifyProcessor("kspCommonMainMetadata", "basekit-viewmodel-ksp")
        listOf("Jvm", "Android", "Js", "IosArm64", "IosSimulatorArm64", "MacosArm64").forEach {
            verifyProcessor("ksp$it", "basekit-viewmodel-ksp")
        }
        listOf("IosArm64", "IosSimulatorArm64", "MacosArm64").forEach {
            verifyProcessor("ksp$it", "basekit-ksp")
        }
        check(tasks.findByName("collectBasekitAppleSwift") != null)
    }
}
''')
    terminal = directory / 'terminal'
    terminal.mkdir()
    (terminal / 'build.gradle.kts').write_text('''
plugins {
    kotlin("jvm")
    id("com.latenighthack.basekit.tui")
}
''' + check + '''
tasks.register("verifyPublishedPlugin") {
    doLast { verifyProcessor("ksp", "basekit-tui-ksp") }
}
''')
    run('-p', str(directory), 'verifyPublishedPlugin')


def verify_poms(repository, version):
    namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
    def pom(path):
        with urllib.request.urlopen(repository.rstrip('/') + '/' + path, timeout=30) as response:
            return ET.fromstring(response.read())
    def coordinates(element):
        return tuple(element.findtext('m:' + key, namespaces=namespace)
                     for key in ('groupId', 'artifactId', 'version'))
    expected = ('com.latenighthack.basekit', 'basekit-gradle-plugin', version)
    implementation = pom(f'com/latenighthack/basekit/basekit-gradle-plugin/{version}/basekit-gradle-plugin-{version}.pom')
    if coordinates(implementation) != expected:
        raise RuntimeError(f'Wrong plugin publication coordinates: {coordinates(implementation)}')
    for name in ('navigation', 'viewmodel', 'tui'):
        group = f'com.latenighthack.basekit.{name}'
        artifact = group + '.gradle.plugin'
        marker = pom(f'{group.replace(".", "/")}/{artifact}/{version}/{artifact}-{version}.pom')
        dependencies = marker.findall('m:dependencies/m:dependency', namespace)
        if coordinates(marker) != (group, artifact, version) or [coordinates(d) for d in dependencies] != [expected]:
            raise RuntimeError(f'Wrong {name} marker coordinates or implementation dependency')


def verify_incremental_version(directory, version):
    # Reproduce a version bump without cleaning the generated source. This failed when the
    # version-generating task declared outputs but no inputs.
    project = directory / 'incremental'
    shutil.copytree(ROOT / 'basekit-gradle-plugin', project / 'basekit-gradle-plugin',
                    ignore=shutil.ignore_patterns('build', '.gradle', '.kotlin'))
    (project / 'gradle').mkdir()
    shutil.copyfile(ROOT / 'gradle/libs.versions.toml', project / 'gradle/libs.versions.toml')
    for candidate in (version, version + '-validation'):
        (project / 'gradle.properties').write_text(
            f'GROUP=com.latenighthack.basekit\nVERSION_NAME={candidate}\n')
        run('-p', str(project / 'basekit-gradle-plugin'), 'generateBasekitVersions')
        source = project / 'basekit-gradle-plugin/build/generated/version/kotlin/com/latenighthack/basekit/gradle/plugin/BasekitVersions.kt'
        if f'BASEKIT_VERSION: String = "{candidate}"' not in source.read_text():
            raise RuntimeError('Incremental version bump left stale processor coordinates')


def wait_for_release(repository, version, seconds):
    paths = [f'com/latenighthack/basekit/{a}/{version}/{a}-{version}.jar' for a in
             ('basekit-gradle-plugin', 'basekit-ksp', 'basekit-viewmodel-ksp', 'basekit-tui-ksp')]
    for name in ('navigation', 'viewmodel', 'tui'):
        artifact = f'com.latenighthack.basekit.{name}.gradle.plugin'
        paths.append(f'com/latenighthack/basekit/{name}/{artifact}/{version}/{artifact}-{version}.pom')
    deadline = time.monotonic() + seconds
    pending = set(paths)
    while pending:
        for path in sorted(pending):
            try:
                request = urllib.request.Request(repository.rstrip('/') + '/' + path, method='HEAD')
                with urllib.request.urlopen(request, timeout=30):
                    pending.remove(path)
            except (urllib.error.URLError, TimeoutError) as error:
                print(f'Waiting for {path}: {error}', flush=True)
        if pending:
            if time.monotonic() >= deadline:
                raise RuntimeError('Release incomplete: ' + ', '.join(sorted(pending)))
            time.sleep(min(30, max(0, deadline - time.monotonic())))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', help='Published Maven repository URL; skips local publication')
    parser.add_argument('--wait-seconds', type=int, default=0)
    args = parser.parse_args()
    version = properties(ROOT / 'gradle.properties')['VERSION_NAME']
    plugin_props = properties(ROOT / 'basekit-gradle-plugin/gradle.properties')
    if 'VERSION_NAME' in plugin_props or 'GROUP' in plugin_props:
        raise RuntimeError('Plugin coordinates must come only from root gradle.properties')
    tag = os.environ.get('GITHUB_REF', '')
    if tag.startswith('refs/tags/') and tag != 'refs/tags/v' + version:
        raise RuntimeError(f'Tag {tag} does not match release version {version}')
    with tempfile.TemporaryDirectory(prefix='basekit-release-') as tmp:
        directory = Path(tmp)
        if args.repository:
            repository = args.repository
            wait_for_release(repository, version, args.wait_seconds)
        else:
            verify_incremental_version(directory, version)
            repository = (directory / 'repository').as_uri()
            run('-p', str(ROOT / 'basekit-gradle-plugin'),
                f'-PpluginValidationRepository={repository}', '-PRELEASE_SIGNING_ENABLED=false',
                'check', 'publishAllPublicationsToValidationRepository')
        verify_poms(repository, version)
        verify(repository, version, bool(args.repository), directory / 'consumer')
    print(f'Validated Basekit {version}: all plugin markers and JVM/Android/JS/Apple processor wiring.', flush=True)


if __name__ == '__main__':
    main()
