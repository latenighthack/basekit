# Changelog

All notable changes to basekit are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/) (pre-1.0: minor versions may break API).

## [0.2.3] — 2026

### Added
- Polymorphic `@ViewModelList` binding on Android. A list declaring more than one `possibleTypes`
  entry now generates one named `ViewModelRowSpec` parameter per declared type instead of a single
  view factory, so heterogeneous lists render on Android the way they already do on Apple (closed
  Swift enum), React (`kind` handles) and the TUI (per-type rows). Named parameters make the set
  exhaustive: adding a row type to the annotation breaks every call site until it is handled. New
  runtime API in `basekit-viewmodel` androidMain: `ViewModelRowSpec`, `viewModelRow(...)`,
  `MultiTypeViewModelDeltaAdapter` and `RecyclerView.bindViewModelRows(...)`.
- `demo-core`'s picker list is now polymorphic (option rows + a placeholder row), so CI exercises
  `possibleTypes` generation on all four platform generators.
- Swift identifier safety in the Apple generators: a State property, action, mutator or parameter whose
  name collides with a Swift keyword or an inherited/already-declared wrapper member (`description`,
  `hash`, `objectWillChange`, `viewModel`, `observe`, ...) is declared with a trailing `_`
  (`swiftDeclName`), while references to Kotlin-exported members are backtick-escaped only when the name
  is a keyword (`swiftSourceRef`). Keywords cannot be backtick-escaped into a valid declaration, and
  inherited-member collisions cannot be escaped at all — both previously emitted uncompilable Swift.

### Fixed
- A polymorphic `@ViewModelList` made the Android target **fail to compile**, whether or not the
  generated helper was called: `AndroidBindingGenerator` always emitted a `bindViewModels` call,
  whose `T : ViewModel<S>` bound the list's marker element type cannot satisfy. Declaring a second
  `possibleTypes` entry therefore made the annotation unusable on Android.
- Each row of a polymorphic list now binds against its own child State type. Previously a list whose
  element type was a marker interface erased its `stateBinder` state parameter to `kotlin.Any`,
  because the element type has no `ViewModel<State>` supertype of its own.

## [Unreleased] — 0.2.0

### Added
- Generated Apple navigation bridge: scoped per-screen navigators, typed native routes and edge
  identities, local interception, policy-driven SwiftUI presentation, an iOS `UINavigationController`
  renderer, AppKit custom-presentation hooks, and deep-link args-to-route mapping.
- Shared `awaitNavigationResult` lifecycle so Kotlin owns responding-destination completion,
  dismissal, exactly-once behavior, and caller cancellation across native hosts.
- TUI render hints — `@TuiField`, `@TuiAction`, `@TuiToggle`, `@TuiList` — that steer how the tui
  processor draws each element: relabel/pin-key/hide actions, rename or hide state rows, transform a
  value's text, draw a number as a gauge (`BAR`) or a `Boolean` as a checkbox (`TOGGLE`), and merge a
  `Boolean` mutation with the state property it sets into a single interactive toggle. Routed end-to-end
  (annotation → `TuiProcessor` → generated `<Vm>Screen` → `TuiRender`); the TUI infers sensible defaults
  when no hint is present.
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
- Apple artifacts now require iOS 18 or macOS 15. Generated Swift is collected through the unified
  `collectBasekitAppleSwift` task; the previous viewmodel-named task remains compatible.
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
