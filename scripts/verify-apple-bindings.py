#!/usr/bin/env python3
"""Build and test real Swift 6 consumers. Requires xcodegen and an explicitly selected simulator."""
import argparse
import json
import platform
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--simulator', required=True, help='Owned simulator UUID')
parser.add_argument('--unit-only', action='store_true', help='Run binding/runtime checks without UI list qualification')
args = parser.parse_args()
target = 'iosSimulatorArm64' if platform.machine() == 'arm64' else 'iosX64'
subprocess.run([str(root / 'gradlew'), ':demo-core:linkDebugFramework' + target[0].upper() + target[1:]], cwd=root, check=True)
build = root / 'build/apple-consumer'
build.mkdir(parents=True, exist_ok=True)
framework = root / f'demo-core/build/bin/{target}/debugFramework'
generated = root / f'demo-core/build/generated/ksp/{target}/{target}Main/resources'
settings = {'SWIFT_VERSION': '6.0', 'IPHONEOS_DEPLOYMENT_TARGET': '18.0',
            'GENERATE_INFOPLIST_FILE': 'YES', 'CODE_SIGNING_ALLOWED': 'NO',
            'FRAMEWORK_SEARCH_PATHS': [str(framework)], 'TARGETED_DEVICE_FAMILY': '1,2'}
project = {'name': 'BasekitBindingConsumer', 'options': {'deploymentTarget': {'iOS': '18.0'}},
    'settings': {'base': settings}, 'targets': {
        'BasekitBindingConsumer': {'type': 'application', 'platform': 'iOS',
            'sources': [str(root / 'integration/apple/App'), str(root / 'examples/text-input'), str(generated)],
            'settings': {'base': {'PRODUCT_BUNDLE_IDENTIFIER': 'com.latenighthack.basekit.consumer'}},
            'dependencies': [{'framework': str(framework / 'DemoCore.framework'), 'embed': False}]},
        'BindingTests': {'type': 'bundle.unit-test', 'platform': 'iOS',
            'sources': [str(root / 'integration/apple/Tests')],
            'dependencies': [{'target': 'BasekitBindingConsumer'}]},
        'ListTests': {'type': 'bundle.ui-testing', 'platform': 'iOS',
            'sources': [str(root / 'integration/apple/UITests')],
            'dependencies': [{'target': 'BasekitBindingConsumer'}]},
    }, 'schemes': {'Bindings': {'build': {'targets': {'BasekitBindingConsumer': 'all'}},
        'test': {'targets': ['BindingTests', 'ListTests']}}}}
(build / 'project.json').write_text(json.dumps(project))
subprocess.run(['xcodegen', 'generate', '--spec', str(build / 'project.json')], cwd=build, check=True)
subprocess.run(['xcodebuild', '-project', str(build / 'BasekitBindingConsumer.xcodeproj'),
    '-scheme', 'Bindings', '-destination', f'platform=iOS Simulator,id={args.simulator}',
    '-derivedDataPath', str(build / 'DerivedData'), 'test'] + (['-only-testing:BindingTests'] if args.unit_only else []), cwd=root, check=True)
