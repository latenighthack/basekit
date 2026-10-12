# Changelog

All notable changes to basekit are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/) (pre-1.0: minor versions may break API).

## [0.4.0] — 2026-10-12

- Added the navigation graph page. `basekit-catalog-codegen graph` turns a catalog into one
  self-contained HTML file: every screen, the components in it that navigate, and one edge per event
  (actions, mutations, rows and children). Clicking a screen shows its route, args, full state with
  types expanded, actions, mutations, lists and children, each linked to its declaration. The catalog
  processor now also writes `catalog.sources.yaml` (file and line per catalog entry), and the catalog
  plugin's `assemble` writes `build/generated/basekit-catalog/catalog.html`. Links open IntelliJ /
  Android Studio by default; `sourceLink` takes any template. See [the guide](docs/guides/catalog-graph.md).
- `basekit-viewmodel-ksp` generates `GeneratedViewModelFactories` in commonMain: one kotlin-inject
  factory per `@ViewModelInject` implementation, named after its destination and taking its
  `@Assisted` parameters (`val homeFactory: (HomeNavigator) -> HomeViewModel`). An app component
  extends it instead of listing every factory: `@Component abstract class ViewModelComponent(@Component
  val core: Core) : GeneratedViewModelFactories`. The demo's test component now does.
- Added `basekit-catalog-codegen`, a JVM command-line tool that reads a `catalog.yaml` and writes a
  Kotlin tree: one spec file per screen (with its rows, child panes, section headers and list
  markers), a starting-point `@ViewModelInject` implementation per screen with rows and children as
  inner classes, static lists holding one instance of each row type, navigating actions that call the
  generated navigator, the catalog's types, and a kotlin-inject `ViewModelComponent` that extends
  `GeneratedViewModelFactories` and adds a `create…` function per screen. Screens that don't navigate
  take a `CloseNavigationTarget`. Spec, type and component files are rewritten on every run;
  implementation files are kept once they exist. The catalog plugin runs it as
  `generateFromBasekitCatalog`. See [the guide](docs/guides/catalog-codegen.md).
- Added `demo-catalog`, which generates code from a catalog, compiles it with the basekit processors
  and kotlin-inject, and checks the catalog written back is identical.
- Added the `com.latenighthack.basekit.catalog` plugin and the `basekit-catalog-ksp` processor.
  `assemble` writes `build/generated/basekit-catalog/catalog.yaml` (schema `catalog/v1`): every
  screen, component, list, child, navigation edge and referenced type, read from the
  `@ViewModelSpec` and `@Destination` declarations. Code that catalog/v1 does not allow, such as a
  mutator with `@NavigateTo`, is written as declared and logged as a warning. See
  [the catalog guide](docs/guides/catalog.md).
- `basekit-viewmodel-ksp` and `basekit-ksp` expose `ViewModelExtractor` and `NavigationExtractor`,
  which build the models their processors generate from. The generated code is unchanged.
- State and Args classes are always nested in their owning specification interface;
  examples, tests and guides use interface-qualified types such as `SearchViewModel.State`.
- Viewmodels are explicitly state driven: use pure `StatefulViewModel.update` calls or
  expose `Flow<State>` directly. Cold upstream work belongs to the binding collector;
  the interface does not require a hot StateFlow or a viewmodel-owned coroutine scope.
- Removed the state-update mutex. **Breaking:** `update` remains declared `suspend`,
  but its reducer is now a regular, non-suspending function and may be retried by StateFlow's
  atomic update. Move effects/suspension outside reducers and recompile subclasses.
  This supersedes the earlier mutex/exactly-once updater behavior described below.
- Added complete stateful and direct-Flow search/filter examples following Fullhouse's
  DeltaList pipelines, with scalar input state and typed item/empty children. Executable
  tests cover list mutations, child actions, source changes and binding cancellation/recollection. Aligned
  architecture, lifecycle, child and mutation guides: no application locks, mutexes or semaphores.
- Collection guidance rejects viewmodel list snapshots and copy/rediff caches; observable
  collections remain DeltaList streams throughout filtering, mapping and binding.

## [0.3.2] — 2026-10-11

- Map SwiftUI enum lists to their enhanced Swift element types.
- Navigation edges belong to the row that triggers them. `@NavigateTo` on a list row or child spec
  is collected from the destination's `@ChildViewModel`, `@ViewModelList`, `DeltaList` and
  `SectionedDeltaList` properties, including specs that extend a marker element type. The target
  joins the destination's navigator. **Breaking:** a row's call site is now named after the row spec
  (`FEED_ITEM_ON_SELECTED`) instead of the hosting destination (`HOME_ON_SELECTED`). This renames
  `<Target>Source` entries and `AppleNavigationEdge` / Swift `BasekitNavigationEdge` cases for edges
  declared on rows; exhaustive Swift switches over the edge must handle the new cases.
- A `@NavigateTo` on a spec that no destination embeds now produces a build warning.

## [0.3.1] — 2026-10-09

- Add optional navigation metrics and PostHog adapters for Kotlin, Swift and React consumers.
- Generate observing ViewModel wrappers and validate action observation across platform bindings.
- Align DeltaList on 0.3.3 and verify all published processor/plugin coordinates.

## [0.3.0] — 2026-10-02

- Typed generated React package, opaque typed references, exhaustive child unions, and explicit
  custom-value adapters. The raw hook entrypoint remains a migration surface.
- Optional generated Compose hosts with retained asynchronous preparation, retry, resource cleanup,
  typed actions and lifecycle-aware state/list bindings.
- Explicit shared `@ViewModelIdentity` keys across Apple, React and Compose.
- Concrete Swift custom state and enum types; preserve nullable and generic mutator types.
- Swift 6 executable-consumer fixes: main-actor KVO runtime, isolated responder completion capture,
  and caller-isolated SKIE async extensions (Swift 6.2+).
- Unsupported public suspend binding signatures now fail KSP; use `@CodegenIgnore` intentionally.
- Real KSP, TypeScript/browser, Android host and iOS consumer regression fixtures; acknowledged
  text-input example. Adopt DeltaList 0.3.2 and its container-owned `Form`/`List` collection path.

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
  Historical behavior, superseded by the pure reducer API in **Next** above.
- `BaseActivity` collects state under `repeatOnLifecycle(STARTED)` instead of a bare `lifecycleScope`.
- Described `ViewModel.state` as a hot, conflated `MutableStateFlow`. **Next** above corrects
  that description: only `StatefulViewModel` guarantees this; the interface also accepts cold flows.

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
