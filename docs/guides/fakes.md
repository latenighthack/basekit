# Fake view models

`basekit-fake-ksp` writes a fake implementation of every `@ViewModelSpec` in a module. A build with
fakes switched on uses them in place of the real implementations:

- `GeneratedViewModelFactories` and `GeneratedViewModelModule` return the fakes, with the same member
  names and parameters as in a real build. An app's kotlin-inject component compiles unchanged.
- The generated Compose and SwiftUI routers and the TUI component build the fakes. Their generated
  code does not change between real and fake builds.
- A fake's State, list rows and children hold generated data. Annotations, named data sets and
  provider functions set what the data looks like.
- A fake action with a `@NavigateTo` edge opens the fake of its target, with the target's Args filled
  in. Rows navigate through their host's navigator, as real rows do.

Fakes are for running and previewing an app without its data sources. They are not a replacement for
journey tests on real view models (see [testing](testing.md)).

## Switching fakes on

Apply the fakes plugin to each module that declares specs. It also applies the navigation and viewmodel
plugins:

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.latenighthack.basekit.fakes") // at the basekit version you use
}
```

Then build with the Gradle property:

```
./gradlew -Pbasekit.fakes=true :app:installDebug
```

or switch one module on in its build file:

```kotlin
basekitFakes {
    enabled.set(true)
    sourceDirectory.set(file("src/commonFakes/kotlin")) // the default
    listMin.set(0)                                      // optional, see below
    listMax.set(2)
}
```

`listMin` and `listMax` (also `-Pbasekit.fakes.listMin=…` and `-Pbasekit.fakes.listMax=…`) set how many
rows a list gets when no annotation sizes it. They are written into the generated fakes, so no app
code is needed. Unset, the fakes read `FakeConfig.listMin` and `listMax`, which are 1 and 1.

| | Real build | Fake build |
|---|---|---|
| `basekit-fake-annotations` on commonMain | yes | yes |
| `basekit-fake` (runtime) on commonMain | no | yes (`api`) |
| `basekit-fake-ksp` on `kspCommonMainMetadata` | no | yes |
| `src/commonFakes/kotlin` compiled | no | yes |
| `publish*` tasks | run | fail the build |

Like the catalog, fakes are written in the common metadata pass, which exists only when the module
has at least two targets. A spec declared in a platform source set gets no fake, and its real
implementation stays in place.

## What is generated

For each spec `X`, `FakeX` in the package of the real `@ViewModelInject` implementation, or else the
spec's package. Shared files go in the navigation package (`Basekit_NavigationPackage`, else the
destinations' common package):

| File | What it is |
|---|---|
| `FakeX.kt` | The fake of `X`. It implements `X` and `FakeViewModel`. |
| `FakeNavigator.kt` | A navigation stack with no UI. It implements every generated navigator interface. |
| `FakeArgs.kt` | `FakeArgs.home()` and so on: Args for each destination with every property set. |
| `FakeViewModels.kt` | `FakeViewModels.home()` and so on: the fake of each spec, with no navigation, for previews. |
| `FakeValues.kt` | Internal. The functions that build each State, Args and other type. |

### Constructors

Every fake has a primary constructor:

```kotlin
FakeHomeViewModel(
    fakeData: FakeData,
    fakeArgs: HomeViewModel.Args? = null,           // destinations with Args
    detailTarget: DetailNavigationTarget? = null,   // one per destination it, its rows or children open
    pickerTarget: PickerNavigationTarget? = null,
    fakeResponder: FakeResponder? = null,           // when it, its rows or children have @FakeResponds
)
```

When the spec has a real `@ViewModelInject` implementation, the fake also has an `@Inject` constructor
with the same `@Assisted` parameters, in the same order, and is itself `@ViewModelInject`. That
constructor passes the Args, the navigator (for every target that navigator includes) and the
responder on to the primary one. When the viewmodel processor finds two implementations of one spec,
the one marked `@GeneratedFake` wins.

A fake for a spec with no real `@ViewModelInject` implementation is not injected, so the factories are
the same in both builds. `FakeNavigator`, `FakeViewModels` and the fakes of their hosts still create it.

### Members

| Spec member | Fake |
|---|---|
| `initialState` | Built from the State's primary constructor. A destination's State takes each field from the Args property of the same name and type, when there are Args. |
| `state` | A `MutableStateFlow` that starts at `initialState`. |
| `@ViewModelList` | A `MutableDeltaList` of fake rows: one unless annotated (see [Lists](#lists)); sectioned lists get one list per section header. Rows are chosen among `possibleTypes`, except the `@FakePlaceholder` row. |
| `@ChildViewModel`, other spec-typed properties | The fake of that spec. |
| `@ViewModelIdentity` | A generated id, different for each row. |
| `Flow<T>` / `StateFlow<T>` properties | `flowOf(value)` / `MutableStateFlow(value)`. |
| Other properties | A generated value. |
| Functions | Each call is added to `BasekitFakes.log`, then see below. |

What a function does, in order:

1. A function with one parameter whose name, or whose `mutatorNoun` (`setNote` → `note`), names a
   State field of the same type sets that field: `copy(note = value)`. The State must be a data class.
2. A function with `@NavigateTo` edges opens one target per call, taking the targets in turn. It
   calls `navigateTo<X>` on the target it was handed, with the target's fake Args, and the call site's
   `<X>Source` when the target has more than one. Args properties that share a name and type with a
   State field get that field's current value, so a row's `onSelected` opens the detail of that row's
   `id`. When the fake was given no navigator for the target, the call is logged as unreachable.
3. A function marked `@FakeResponds` answers the responding destination it is shown in with a fake
   value of the response type. That closes the destination and resumes the caller.
4. A function returning a value returns a generated one.

### Redirects

The fake of a destination with `@RedirectsTo` edges redirects when its `state` is first collected, as
a real redirecting screen does. That is how an app that starts on a splash, launch or gate screen gets
past it. The fake redirects once per instance:

- to the first `@RedirectsTo` target in declaration order, unless `@FakeRedirect` names another;
- by calling `navigateTo<X>` on the navigator it was handed, with the target's fake Args (and
  same-named State fields copied in) and the `<X>Source` entry of the redirect (`SPLASH_REDIRECT`) when
  the target has more than one call site;
- never to a responding destination (the build warns).

The generated routers handle the call as they handle the real implementation's: their navigation
policy decides how the target is shown. `FakeNavigator` replaces the redirecting screen with the target
(see [Starting a fake app](#starting-a-fake-app)).

`@FakeRedirect(target)` on the destination picks the target, per data set like the other annotations.
Naming the destination itself keeps the fake on its own screen. Use that for a screen whose real
implementation redirects only in some cases, such as a screen that leaves when its item is missing:

```kotlin
@Destination
@ViewModelSpec
@RedirectsTo(HomeViewModel::class, JoinViewModel::class, WelcomeViewModel::class)
@FakeRedirect(WelcomeViewModel::class, dataSet = "signedOut")
interface SplashViewModel : NavigationDestination<SplashViewModel.Args>, ViewModel<SplashViewModel.State>

@Destination
@ViewModelSpec
@RedirectsTo(HomeViewModel::class)
@FakeRedirect(RoomViewModel::class)   // stays on the room
interface RoomViewModel : NavigationDestination<RoomViewModel.Args>, ViewModel<RoomViewModel.State>
```

## Configuring the data

Annotations from `com.latenighthack.basekit.fake.annotations`. The shape annotations go on properties:
State constructor `val`s, `@ViewModelList` properties, Args properties and other spec properties.

| Annotation | Sets |
|---|---|
| `@FakeText(kind, minLength, maxLength, values)` | A `String`: one of `FakeTextKind` (`TITLE`, `SENTENCE`, `PERSON_NAME`, `EMAIL`, `URL`, `IMAGE_URL`, `ID`, `DATE`, `PRICE`, …), a length limit, or a fixed set of values. |
| `@FakeNumber(min, max)` | An `Int`, `Long`, `Short`, `Byte`, `Double` or `Float`. |
| `@FakeList(size, min, max)` | Rows in a `@ViewModelList` (per section), or entries in a `List`, `Set` or `Map` field. |
| `@FakeRows(types, weights)` | Which of a list's `possibleTypes` appear, and how often. |
| `@FakePlaceholder(type)` | On a list: the row type its implementation inserts with `ifEmpty` when it has no other rows. |
| `@FakeNull(frequency)` | How often a nullable value is null, from 0.0 to 1.0. |
| `@FakeCase(cases, weights)` | Which enum entries or sealed subclasses appear, by name. On a sectioned list, which section headers, in order. |
| `@FakeValue(provider)` | Take the value from a named `@FakeProvider`. |
| `@FakeDefaults(listMin, listMax, nullFrequency)` | On a spec: list sizes and null frequency for its lists and State fields that have no annotation of their own. |
| `@FakeRedirect(target)` | On a destination with `@RedirectsTo`: which target its fake redirects to, or the destination itself to stay. |
| `@FakeResponds` | On a function: it answers the responding destination. |
| `@FakeProvider(name, dataSet)` | On a function: it makes values of its return type. |

```kotlin
data class State(
    @FakeText(FakeTextKind.TITLE) val title: String,
    @FakeNumber(min = 1.0, max = 5.0) val rating: Int,
    @FakeNull(frequency = 0.5) val subtitle: String?,
)

@ViewModelList(OptionViewModel::class, NoOptionsViewModel::class)
@FakePlaceholder(NoOptionsViewModel::class)
@FakeList(min = 3, max = 8)
val options: Flow<Delta<RowViewModel>>
```

### Lists

A list no annotation sizes gets one row. Many lists in an app hold one row or none: a section or banner
that is shown or hidden, built as `someFlow.lazyMap { row }`. With one row each, nested lists stay one
row deep instead of multiplying. Size a list that shows many rows with `@FakeList`, or a whole spec's
lists with `@FakeDefaults`; change the default with `basekitFakes { listMin; listMax }`.

A list whose implementation shows a placeholder when it has no rows (`.ifEmpty { noCredentialsRow }`,
see [delta lists](delta-lists.md)) names that row type with `@FakePlaceholder`. The fake does the same:
it never chooses the placeholder among the other row types, and a list that comes out empty (always, in
the `empty` data set; or with `@FakeList(size = 0)`) shows one placeholder. In a sectioned list each
section gets its own. A list that names no placeholder chooses among all its `possibleTypes`, and is
simply empty in `empty`.

With no annotation, a `String` gets a kind from its property name, and a number a range: `email` is an
email address, `avatarUrl` an image URL, `userId` an id, `title` a title, `description` a sentence,
`rating` 1-5, `age` 18-90. The rules are in `FakeNames.Default`. Add rules in front of them through
`FakeConfig(names = FakeNames.Default + FakeNameRule(FakeTextKind.COMPANY) { it.last() == "brand" })`.
Anything else gets a few words, or a number from 0 to 100.

Values of other types:

| Type | Fake |
|---|---|
| Enum | An entry, all equally likely unless `@FakeCase` says otherwise. |
| Sealed type | An object case, or a subclass built from its constructor. |
| Class with a public primary constructor | Built from its constructor. Parameters with a default keep it unless annotated. |
| `List`, `Set`, `Map`, `Pair` | Built from their element types. |
| Anything else (interfaces, generic classes, types from libraries without a public constructor) | A `@FakeProvider` is needed. A non-null field fails the build at that field. A nullable field is always null, with a warning. |

### Providers

A provider is a top-level function in `src/commonFakes/kotlin`, which is compiled only in fake builds:

```kotlin
@FakeProvider
fun FakeData.instant(): Instant = Instant.fromEpochSeconds(long("seconds", 1_700_000_000, 1_800_000_000))

@FakeProvider(name = "avatar")
fun avatar(fake: FakeData): Avatar = Avatar(fake.text("url", FakeTextKind.IMAGE_URL))
```

An unnamed provider is used for every value of its return type, including a whole State or a row's
State. A named one is used only where `@FakeValue("avatar")` asks for it. Two providers for the same
type (or name) and data set fail the build.

### Data sets and seeds

The fakes read `BasekitFakes.config` when they are created:

```kotlin
BasekitFakes.config = FakeConfig(seed = 42, dataSet = FakeDataSet.EMPTY)
```

| Data set | Lists | Text | Nullable values |
|---|---|---|---|
| `TYPICAL` (default) | as annotated, else 1 row | as annotated | null 20% of the time, or as annotated |
| `EMPTY` | empty (the placeholder row, if the list names one) | as annotated | always null |
| `LARGE` | ten times as many (10 rows when unannotated) | twice the words | never null |

A custom data set is based on another: `FakeDataSet("crowded", base = FakeDataSet.LARGE, listScale = 2.0)`.

Annotations without `dataSet` apply to every data set and are scaled by it. Annotations with
`dataSet = "large"` apply to `large` and every data set based on it, as written. With
`@FakeList(min = 3, max = 8) @FakeList(size = 300, dataSet = "large")`, a list has 3-8 rows, none in
`empty`, 300 in `large` and 300 in `crowded`.

Every value comes from a random generator seeded with the config's seed, the fake's path
(`HomeViewModel.items[2].state`) and the field name. The same seed gives the same data on every
platform, and adding a field does not change the values of the others.

`BasekitFakes.using(config) { … }` uses another config for one block.

## Starting a fake app

In a fake build, root the generated router at a destination as usual, with fake Args:

```kotlin
router.root(BasekitRoute.Home(FakeArgs.home(), edge = null))
```

Without a UI, use `FakeNavigator`:

```kotlin
val navigator = FakeNavigator()
val home = navigator.startHome()     // FakeArgs.home() by default
home.onOpenDetail()
val detail = navigator.top as DetailViewModel
```

Starting or opening a destination that redirects lands on its target: the redirect replaces the
redirecting screen on the stack instead of adding a screen. `FakeNavigator().startSplash()` leaves one
entry, the fake Home, so a walk started there walks Home.

`walkFakeGraph(navigator.stack)` calls every zero-argument action of the screen on top, and of the
rows and children it shows, once per spec and action. For each screen an action opens, it does the
same, then closes that screen. A screen an action opens that redirects is counted as its target. It returns the screens reached, the actions called and the edges taken:

```kotlin
val walk = walkFakeGraph(FakeNavigator().apply { startHome() }.stack)
assertEquals(listOf("HomeViewModel", "DetailViewModel", "PickerViewModel"), walk.screens)
```

`BasekitFakes.log.events` holds the most recent 500 calls on fakes: the fake's path, the member and the
value it was given or got back.

## Limits

- React gets fakes where its view models come from `GeneratedViewModelFactories`: the generated web
  router's `basekitWebDestinations(factories)`, a kotlin-inject component, or `FakeViewModels`. React
  code that constructs a real implementation itself still gets the real one.
- Code that names a real implementation type (`val home: RealHomeViewModel = component.homeFactory(…)`)
  does not compile in a fake build, because the factory returns the fake.
- A real implementation that takes no navigator, but whose spec has edges, gets a fake that logs those
  navigations as unreachable. The build warns about it.
- A responding destination with no `@FakeResponds` action can only be closed, which answers null. The
  build warns about it.
- Generic spec functions, and a spec's rows or children declared in a module without fakes, fail the
  build.
- A handwritten class named `Fake<Spec>` in the same package fails the build. Rename it.
