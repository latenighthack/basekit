# Changelog

All notable changes to basekit are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/) (pre-1.0: minor versions may break API).

## Next

- Generated navigation: sheets stack. The generated `BasekitNavigationRouter` (Swift and Compose) now
  makes a router of its own for every entry it presents other than by push: a sheet, a full-screen
  cover or a custom presentation. `router.host(for: entry)` (Compose: `router.host(entry)`) returns the
  router an entry's navigator reports to. For a presented entry that is the router made for it, so a
  sheet that opens another sheet presents it over itself instead of replacing itself. That router
  uses the presenter's policy, and the presenter's platform presenter and dismisser as they are when
  it uses them, so which presentation to use is still the app's choice. A router made with
  `BasekitNavigationRouter(parent:)` that has no platform presenter or dismisser of its own also uses
  its parent's. A presented entry that leaves takes everything presented over it, and the responders
  of those entries answer `null`; this includes a lower router presenting a new sheet in its one
  sheet slot. Once a presented entry has gone, its router presents nothing more: whatever the
  entry's view model still opens is dropped, and a responding destination answers `null`.
  `host(for:)` searches the routers below it, so a platform presenter that holds the root router can
  find the router for a custom entry presented deep in the stack.
- Breaking: `BasekitNavigationStackHost`'s `root` and `destination` closures also get the router the
  entry reports to: `{ entry, host, namespace in … }` on Apple and `{ entry, host -> … }` in Compose.
  Build the entry's view model with that `host`. The stack host shows each sheet and cover as a stack
  of its own, for the router made for it, so the sheets it opens stack. In Compose, a presented stack
  takes the size of its entries' content, so the default dialog keeps the size of what it shows.

- A throw from a view model's `state` or one of its `@ViewModelList` flows no longer crashes the
  app. On iOS it aborted the process inside SKIE's collector, before any Swift code ran; on Android
  and Compose it crashed the Activity or the composition. Every generated binding (Observable and Kvo
  wrappers, the Apple list bindings and binders, Compose `bind{Vm}` and its rows, the React hook and
  its lists, `BaseActivity` and the RecyclerView helpers) now collects `boundState` and `boundList`.
  These catch the throw in Kotlin and keep the last state and rows on screen. Cancellation still
  propagates. The throw goes to the implementation's new `ViewModelFailureHandler`: `onStateFailed`
  gets the error and the last state and returns the flow to continue with (an error state, nothing,
  or `state` to retry), and `onListFailed` gets the error and the list's name. Without a handler, it
  goes to `ViewModelFailures.unhandled`, which apps set at startup and which prints by default. The
  Apple pass emits `{Vm}BoundLists.kt` with one typed `bound{List}()` extension per list for the Swift
  wrappers. The generated `Observing{Vm}` forwards the handler. A `@ViewModelSpec` that extends
  `ViewModelFailureHandler` is a build error.
- Breaking: the deprecated `onError` property on generated `Observable{Vm}` wrappers is removed. It
  was never called; failures now go to the view model's handler or `ViewModelFailures.unhandled`.

- Apple navigation: `BasekitNavigationControllerHost` shows whatever path the router holds. Before, it
  only followed changes in the path's length, so a path that replaced its top entry kept showing the
  old screen. A path that adds one entry pushes it; one that drops entries from the top pops back to
  the screen that stays; any other change pushes or pops to its top screen and then sets the screens
  under it without animation, since UIKit's animated stack change misplaces screens after a transition
  that ended early. Screens that stay keep their controllers instead of being rebuilt, and a change
  during a transition waits for it, then shows the router's path as it is by then.
- Navigation args on JVM, Apple and JS: a nullable argument set to `null` reads back `null` instead of
  crashing, and reading an argument that was never set fails naming it.
- Sectioned lists bind on every platform. `@ViewModelList(rows...)` accepts a
  `SectionedDeltaList<Header, Row>` or `Flow<SectionedDelta<Header, Row>>` whose header is an enum or a
  sealed type. UIKit gets `bind<List>(_:cellProvider:headerProvider:)` on the Kvo wrapper over
  `SectionedDeltaCollectionDataSource`, with typed rows and a header enum spelled as in Kotlin;
  Compose gets a header slot over the new `DeltaSections`; React gets `SectionedViewModelList` with
  string-union (enum) or `kind`-union (sealed) headers. SwiftUI and the RecyclerView helpers stay flat.
  The catalog code generator declares a sectioned list's rows with `@ViewModelList`.
- Requires deltalist 0.4.2.
- Compose `DeltaRows` and `DeltaSections` never read a lazily mapped row (`lazyMap`, `lazyMapItems`)
  to compute its key: those rows are keyed by position IDs that follow inserts, removes and moves
  (`SectionedStableIds`) and are regenerated on a reload. Lists that hold their rows keep identity or
  object keys. A row renders the cached row from deltalist's `rememberItem`, keeps its `remember`
  state while that object stays the same, and resets it when an `Update` rebuilds the row. Before, every key read built
  a new row, so rows without `@ViewModelIdentity` lost their state on every change.
- Apple navigation: `AppleCloseNavigator(ownerId:host:)` is generated beside the other Apple
  navigators; `onCloseRoot` and `BasekitNavigationControllerHost`'s animator and interaction providers
  are `@MainActor`; `BasekitNavigationRouter(parent:)` forwards closing its root to the router that
  presented it.
- `basekitViewModel(for:host:factories:)` builds a destination's `Observable{Spec}` from a navigation
  entry as a `BasekitDestinationModel`, with the navigator under `entry.ownerID`. It does not throw:
  a destination without a `@ViewModelInject` implementation (or whose implementation takes an
  `@Assisted` parameter an entry cannot supply) fails the build, naming the destination and where it
  is declared, and an entry built by hand for a responding destination without a responder stops at
  a `preconditionFailure`. `BasekitDestinationModelError` is removed.
- `BasekitDestinationView(for:host:factories:content:)` builds a destination's model once per view
  identity, observes it while shown, and hands it to `content`. It makes no presentation decisions.
- `BasekitNavigationRouter.open` (and `open(args:)`, `open(match:)`) gives a responding destination
  opened without a responder, as from a deep link, a responder that discards its result; the
  destination responds and closes as when a navigator opens it.
- `Observable{Vm}` gets the UIKit list binders `Kvo{Vm}` has: `bind<List>(_:cellProvider:)` over
  `DeltaCollectionDataSource` for flat lists and `bind<List>(_:cellProvider:headerProvider:)` over
  `SectionedDeltaCollectionDataSource` for sectioned ones, with `Observable{Row}` rows, element enums
  and header enums prefixed `Observable`. Each row is observed while its cell shows it
  (`UICollectionViewCell.basekitObserve`), so a SwiftUI row in a `UIHostingConfiguration` redraws.
- `BasekitModelView` owns a generated SwiftUI view model and observes it while shown; Observable
  wrappers conform to `BasekitObservableViewModel`.
- Generated bindings expose `ActionIds`, one identifier per action and mutation
  (`sidebar.onAccountTapped`), identical in Swift, Compose and React.
- Added `@Route(aliases = [...])` and catalog `route_aliases` for a screen with more than one URL.
  `GeneratedRoutes` registers each alias with the destination's args factory; an alias must capture
  the same `{params}` as the path. The catalog tools read and write `route_aliases` (they warned it
  was unknown), the code generator emits the annotation argument, and the graph page lists aliases
  in the screen details.
- Added `@HostedComponent` (catalog: `hosted: true`) for a component every screen hosts without
  listing it, such as app chrome around every screen. The app creates one instance. The `@NavigateTo`
  edges of the component and everything it embeds get the component's own `<Name>Navigator`,
  without `close()`, plus its `Observing`, `Apple` and test navigators; they no longer warn as
  unreachable. Each edge keeps its call site as its `<Target>Source` entry. The catalog code generator
  annotates the spec and gives the implementation and its `create<Name>ViewModel` the navigator; the
  graph page draws the component like a screen.
- Swift wrappers read a State, child, list or identity property by its Swift name in the SKIE-built
  framework. SKIE exports a property named `description` or `hash` with a trailing `_`, because it
  would collide with `description()`/`hash()` (from `toString`/`hashCode`). The wrappers declared
  `description_` but read `description`, which compiled, resolved to `NSObject.description`, and held
  the State's `toString()`. A `@property:ObjCName` swiftName is honored.
- Swift wrappers collect `state` with `for await`: SKIE bridges it to a non-throwing sequence, so the
  `do`/`catch` was unreachable and Swift warned on every wrapper. `Observable{Vm}.onError` is
  deprecated, since nothing calls it. A State without fields no longer declares `initial`, the
  `state` cast or the `self` rebind, which Swift warned were unused; `state` is still collected.
- The React hook backticks a State property, action, list or child named after a Kotlin keyword
  (`` result.`in` = state.`in` ``); it emitted `result.in`, which did not compile.
- `collectBasekitAppleSwift` and `collectBasekitViewModelSwift` sync their output directories instead
  of copying into them, so Swift generated for a removed spec no longer stays behind.
- Compose bindings for a list whose only row type is its element type call the row directly, instead
  of a `when` with an always-true `is` check and a redundant `else`.

- The catalog code generator writes a screen's spec and implementation into one file
  (`IHomeViewModel.kt`), as two regions, `basekit:spec` and `basekit:impl`. Each region records a
  hash of its imports and declarations, and a rerun rewrites only regions that still match it: an
  edited region is kept (`--force` rewrites it), and code outside the regions is never touched. A
  0.4.0 spec file is replaced, and the separate implementation file 0.4.0 wrote is moved into the
  `impl` region and deleted.
- The catalog code generator declares a type that only one screen's spec file uses in that file,
  after the specs, instead of in `CatalogTypes.kt`. Use through other types counts: a type that is
  only a field of a shared type stays in `CatalogTypes.kt`. Dotted names (`Button.Style`) and sealed
  families stay with their top-level type. `CatalogTypes.kt` now holds only types several files use
  and is not written when there are none; a stale generated one is deleted.
- Added `@RedirectsTo(targets)` for a `@Destination` that replaces itself with another screen without
  a user action, such as an entry screen sending the user to the right first step. Each target joins
  the destination's generated navigator like a `@NavigateTo` edge, with source entry
  `<SCREEN>_REDIRECT`. The catalog writes it as `redirects_to`; the code generator emits the
  annotation and a `redirectTo<Target>()` function per target; the navigation graph page draws each
  redirect as its own edge and lists them in the screen details.
- The catalog tools warn about keys they don't know instead of ignoring them silently.
- The navigation graph page places screens by tap distance from the entry screen instead of by
  longest path. On Picnic's catalog this cuts 36 mostly single-screen columns to 17, and the canvas
  goes from about 10,700 × 1,200 to about 3,400 × 3,200. In graphs of 12 or more boxes, edges into
  a screen opened by 6 or more events become short `→ Name` stubs; the full lines show on hover or
  with **All edges**.
- Clicking a screen in the navigation graph page redraws the graph around it: the screens that open it
  on the left (only their relevant rows), the screen in the middle, the screens it opens on the right.
  **Reset view** returns to the whole graph and **Details** opens the screen's details; each focus is
  a `#focus=Name` URL, so the back button and links work.

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
