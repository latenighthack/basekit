# Changelog

All notable changes to basekit are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/) (pre-1.0: minor versions may break API).

## [Unreleased] — 0.2.0

### Added
- macOS targets (`macosArm64`, `macosX64`) across the KMP modules; the Apple ViewModel codegen is
  now one universal Swift file per ViewModel (UIKit vs AppKit selected with `#if canImport`).
- `build.yml` CI: builds every target, links the Apple frameworks, and type-checks the generated
  Swift against them on `push`/`pull_request` — a broken target now fails a PR instead of a release.
- `LICENSE` (Apache-2.0) and this changelog; committed the `kotlin-js-store` npm lockfile.
- Unit tests for `Route`/`RouteTable`, `NavigatorArgs`, `StatefulViewModel`, the navigation
  response/close harness, and the codegen naming helpers.
- `@Destination(navName = "…")` to disambiguate destinations that would otherwise derive the same
  generated navigation name.
- Android `BaseActivity` retains its ViewModel across configuration changes.

### Changed
- `StatefulViewModel.update` now serializes via a `Mutex`, so a suspending updater body runs exactly
  once (the previous `getAndUpdate` CAS loop could re-run it under contention).
- `BaseActivity` collects state under `repeatOnLifecycle(STARTED)` instead of a bare `lifecycleScope`.
- Corrected the `ViewModel.state` KDoc: it is a hot, conflated `MutableStateFlow`, not cold.

### Fixed
- The generated `TestClientNavigator.close()` no longer hangs a suspended responding-destination
  caller when a later navigation happened before the close: `NavigationRecorder` now tracks a stack
  of unresolved responders and dismisses the top one.
- The navigation processor now reports a clear error (instead of a bare `FileAlreadyExistsException`)
  when two destinations collide on a navigation name, and errors on a non-String `@RouteArg` at its
  source location instead of emitting uncompilable code. The TUI processor reports colliding
  `<Name>Screen` classes.

## [0.1.5] — 2026
- Wired the KSP metadata output into `sourcesJar` for Gradle 9 strict validation; deltalist 0.1.3.

## [0.1.4] — 2026
- Published `basekit-tui-annotations` and `basekit-tui-ksp` to Maven Central.

## [0.1.3] — 2026
- Maintenance release.

## [0.1.2] — 2026
- Bumped Kotlin 2.3.10 / KSP 2.3.10 / SKIE 0.10.14, Gradle 9.5.1, AGP 8.13.2.

## [0.1.1] — 2026
- Kotlin 2.2.21 / KSP 2.0.5 / SKIE 0.10.13; deltalist 0.1.2 pin; CI-safe composite include; TUI and
  ViewModel codegen updates; the release workflow.
