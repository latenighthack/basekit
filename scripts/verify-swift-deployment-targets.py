#!/usr/bin/env python3
"""Check the actual SKIE Swift invocations, including cold CI deployment-target resolution."""
import pathlib
import re

root = pathlib.Path(__file__).resolve().parents[1]
expected = {
    ("basekit-navigation", "macosArm64"): "arm64-apple-macos15.0",
    ("basekit-navigation", "iosArm64"): "arm64-apple-ios18.0",
    ("basekit-viewmodel", "macosArm64"): "arm64-apple-macos15.0",
    ("basekit-viewmodel", "iosArm64"): "arm64-apple-ios18.0",
    ("demo-core", "macosArm64"): "arm64-apple-macos15.0",
    ("demo-core", "iosSimulatorArm64"): "arm64-apple-ios18.0-simulator",
}
for (module, target), triple in expected.items():
    log = root / module / "build/skie/binaries/debugFramework/DEBUG" / target / "debug/logs/swiftc.log"
    actual = re.findall(r"(?:^|\s)-target\s+(\S+)", log.read_text())
    if actual != [triple]:
        raise SystemExit(f"{module}/{target}: expected {triple}, got {actual}")
    print(f"{module}/{target}: {triple}")
