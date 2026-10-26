# Generating code from a catalog

`basekit-catalog-codegen` reads a [`catalog.yaml`](catalog.md) and writes the Kotlin for every
screen and component in it: the `@ViewModelSpec` interfaces, a starting-point implementation of
each screen, the catalog's types, and a kotlin-inject component that builds the screens. The
generated tree compiles with basekit's processors, and the catalog they write back from it is the
catalog you started from.

## Running it

With the catalog plugin applied:

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.latenighthack.basekit.catalog") version "0.4.0"
}

basekitCatalogCodegen {
    catalog.set(file("catalog.yaml"))                 // default: catalog.yaml
    outputDirectory.set(file("src/commonMain/kotlin")) // default: src/commonMain/kotlin
}
```

```
./gradlew generateFromBasekitCatalog
./gradlew generateFromBasekitCatalog -Pbasekit.catalog.input=docs/catalog.yaml -Pbasekit.catalog.force=true
```

The task is not part of `build`. The same tool also writes the [navigation graph page](catalog-graph.md)
(`basekit-catalog-codegen graph`). Without Gradle, run the jar
(`com.latenighthack.basekit:basekit-catalog-codegen`) or its application distribution:

```
basekit-catalog-codegen --catalog catalog.yaml --out src/commonMain/kotlin \
    [--navigation-package PKG] [--types-package PKG] [--component-package PKG] [--force]
```

| Option                 | Default                                                                                |
| ---------------------- | -------------------------------------------------------------------------------------- |
| `--navigation-package` | The `Basekit_NavigationPackage` KSP argument (Gradle task), else the screens' common package, which is also basekit's default. It must match where basekit generates the navigators. |
| `--types-package`      | The common package of all specs. Only `CatalogTypes.kt` uses it: types one spec file uses go in that file's package. |
| `--component-package`  | The common package of all specs.                                                       |

## Which files are rewritten

| File                                   | On every run                         |
| -------------------------------------- | ------------------------------------ |
| `CatalogTypes.kt`, `ViewModelComponent.kt` | Rewritten. Edit the catalog, not these files. |
| Spec files                             | Each region is rewritten while it matches its hash; an edited region is kept. |

A spec file holds the spec and the implementation of a screen in two regions, `basekit:spec` and
`basekit:impl`. Each region has two parts, its imports and its declarations, each between IntelliJ
region comments:

```kotlin
// region basekit:spec imports
import com.latenighthack.basekit.viewmodel.ViewModel
// endregion basekit:spec imports
// region basekit:impl imports
import com.latenighthack.basekit.viewmodel.StatefulViewModel
// endregion basekit:impl imports

// region basekit:spec f6b51b8878235ec9
@ViewModelSpec
interface IPickerViewModel : …
// endregion basekit:spec

// region basekit:impl 75b6cbbb2f553b12
@ViewModelInject
class PickerViewModel …
// endregion basekit:impl
```

The hash on a region's opening line is taken from both parts as they were written. On a rerun:

- A region whose content still has its hash is rewritten from the catalog.
- An edited region is kept and reported (`kept … (impl region)`). `--force` (or `force.set(true)`)
  rewrites it.
- Code and imports outside the regions are never touched. Add helpers and imports there, or edit
  inside a region and keep it.
- Kotlin rejects a repeated import, so a region's imports leave out those already imported earlier
  in the file. When a rewritten region stops importing something, the import moves to a kept region,
  whose code may still use it.
- A file at a spec path without regions is kept unless `--force`, except for a spec file written by
  0.4.0, which is replaced. A 0.4.0 implementation file (`HomeViewModel.kt`) is moved into the
  `impl` region and deleted. It counts as edited unless it equals what the catalog generates now.

A file whose content would not change is left untouched.

## What is generated

Each spec goes in the package of its `spec` qualified name.

### Spec files

One file per screen, named after the spec (`IHomeViewModel.kt`). Its `spec` region holds:

- the screen interface: `@Destination` (with `navName` only when the catalog name differs from the
  one basekit derives), `@ViewModelSpec`, `NavigationDestination<Args>` or
  `RespondingDestination<Args, R>`, and nested:
  - `Args`, with `@Route` (and its `aliases`, from `route_aliases`), a typed constructor and
    `@RouteArg` on the route args;
  - `State`, a data class of the `state` fields (`class State` when there are none), with the
    [fake annotations](#fake-keys) of each field's `fake:` key;
  - one sealed `<List>Header` interface per sectioned list, with a `data object` per section;
- `@ViewModelIdentity`, lists, `@ChildViewModel` properties, actions and mutations, with
  `@NavigateTo` for each `navigates_to`;
- every component only this screen embeds, at any depth;
- a marker interface for each list element that is not a component, such as
  `interface IHomeListItemViewModel`. Every row of the list extends it;
- every catalog type that only this file uses, after the interfaces. See [types](#types).

Flat lists are `@ViewModelList(rows…) val items: Flow<Delta<Element>>`. Sectioned lists are
`val sections: SectionedDeltaList<Spec.SectionsHeader, Element>`.

### Fake keys

A list or a State field can carry a `fake:` key, written as the annotations basekit's
[fakes](fakes.md) read. They change nothing in a real build. Because they are generated, the spec region
stays generated: change the key in the catalog, not the annotation in the region.

```yaml
state:
  title:
    type: String
    fake: { text: TITLE }
  subtitle:
    type: String?
    fake: { null_frequency: 0.5, data_sets: { large: { null_frequency: 0.0 } } }
lists:
  credentials:
    kind: flat
    element: CredentialRow
    rows: [CredentialItem, NoCredentialsItem]
    fake: { min: 2, max: 3, placeholder: NoCredentialsItem, data_sets: { large: { size: 300 } } }
  banner:
    kind: flat
    element: Banner
    rows: [Banner]
    fake: { size: 0 }
```

| Key | Where | Annotation |
| --- | --- | --- |
| `size: 0` | list | `@FakeList(size = 0)` |
| `min: 1`, `max: 3` | list | `@FakeList(min = 1, max = 3)` |
| `rows: [A]` | list | `@FakeRows([A::class])`: which of the list's rows appear |
| `placeholder: B` | list | `@FakePlaceholder(B::class)`: the row shown only when the list has no other rows |
| `text: TITLE` | State field | `@FakeText(FakeTextKind.TITLE)` |
| `text: { kind: SENTENCE, min_length: 10, max_length: 40, values: ["a", "b"] }` | State field | `@FakeText(FakeTextKind.SENTENCE, minLength = 10, maxLength = 40, values = ["a", "b"])`; each key optional |
| `number: { min: 1.0, max: 5.0 }` | State field | `@FakeNumber(min = 1.0, max = 5.0)` |
| `null_frequency: 0.5` | State field | `@FakeNull(0.5)` (not `null:`, which YAML 1.1 reads as a null key) |
| `case: [A, B]` | State field | `@FakeCase(["A", "B"])` |
| `value: avatar` | State field | `@FakeValue("avatar")` |
| `data_sets: { large: { … } }` | both | The same keys (except `placeholder`) for one data set: each annotation with `dataSet = "large"` |

`rows` and `placeholder` name rows of the list. Row weights, `@FakeDefaults`, `@FakeRedirect` and
`@FakeResponds` have no key; write those in Kotlin. basekit-catalog-ksp writes the keys back from the
annotations, in the order above, so a catalog round-trips.

A component that several screens embed gets its own file. A component that nothing embeds is a
root: it gets its own file, with the components it embeds.

### Implementations

The `impl` region of each screen's spec file holds a starting-point implementation. Names follow the
spec: `IHomeViewModel` is implemented by `HomeViewModel`.
A spec without a leading `I` gets an `Impl` suffix: `HomeViewModel` → `HomeViewModelImpl`.

```kotlin
@ViewModelInject
class HomeViewModel @Inject constructor(
    @Assisted private val navigator: HomeNavigator,
) : IHomeViewModel, StatefulViewModel<IHomeViewModel.State>(IHomeViewModel.State(greeting = "", …)) {
    override val rooms: SectionedDeltaList<IHomeViewModel.RoomsHeader, IHomeListItemViewModel> = sectionedDeltaList(
        IHomeViewModel.RoomsHeader.Recent to listOf<IHomeListItemViewModel>(EmptyRoomsItemViewModel(), RoomItemViewModel()).toDeltaList(),
        …
    )

    override suspend fun onCreateRoom() {
        navigator.navigateToRoom(IRoomViewModel.Args(roomId = "", inviter = null), RoomNavigationTarget.RoomSource.HOME_ON_CREATE_ROOM)
    }

    private inner class RoomItemViewModel : IRoomItemViewModel, StatefulViewModel<…>(…) {
        override suspend fun onItemTapped() {
            withState { current ->
                navigator.navigateToRoom(IRoomViewModel.Args(roomId = current.roomId, inviter = null), …)
            }
        }
    }
}
```

- The assisted parameters, in order, are:
  - `args`, when the screen has args;
  - `navigator`: the screen's `<Name>Navigator` when it or anything it embeds navigates, otherwise
    `CloseNavigationTarget`. basekit only generates `CloseNavigationTarget` when the catalog has at
    least one edge; with none, the screen gets no navigator;
  - `responder`, for a responding screen.
- Every component the screen embeds is a `private inner class`, so its actions use the screen's
  navigator.
- Every list holds one instance of each row type, in every section, as a static delta list.
- Each child is one instance.
- An action with `navigates_to` calls `navigateTo<Target>`:
  - Its args take the value of a State property with the same name and type, otherwise a default.
  - The `<Target>Source` argument is passed when the target has more than one call site, as in
    basekit's generated navigators.
- A mutation with `binds` updates that State property.
- A screen with `redirects_to` gets `@RedirectsTo` on its spec and a `redirectTo<Target>()` function
  per target that makes the navigator call. Nothing calls it: when to redirect is the ViewModel's
  decision.
- Other actions and mutations do nothing.
- Initial State uses defaults: `""`, `0`, `false`, `null`, empty collections, the first enum
  entry or sealed case, and a constructor call for data classes. External types get
  `TODO("initial <Type>")`.
- A `hosted: true` component gets `@HostedComponent` on its spec. Its implementation is built like a
  screen's: it takes its own `<Name>Navigator` when it or anything it embeds navigates, and its
  embedded components are inner classes that use it. Its `create<Name>ViewModel` takes the navigator.
- Any other root component's implementation is a top-level `@ViewModelInject` class without a
  navigator. Its `navigates_to` edges are left as empty actions, because nothing gives it a navigator.

### Types

A catalog type is declared in the one spec file that uses it, and in `CatalogTypes.kt` (in
`--types-package`) when several spec files use it or none does. A type is used by a file when the
file's screen or components have it in a `state` field, a mutation `param`, a navigation arg or the
`response`, or when a type that file uses has it in a field. So a type that is only a field of a
shared type is shared too. `Button.Style` goes wherever `Button` goes, and a sealed type goes in the
same file as its class subtypes, because Kotlin requires them in one package.

`CatalogTypes.kt` is not written when every type is in a spec file. A run deletes a `CatalogTypes.kt`
an earlier run wrote (one that starts with the generated header) when it no longer writes one.

| Catalog type                         | Kotlin                                                              |
| ------------------------------------ | ------------------------------------------------------------------- |
| `{ enum: [A, B] }`                   | `enum class`                                                        |
| `{ fields: { … } }`                  | `data class` (`class` without fields)                               |
| `{ subtypes: [Case, Sub] }`          | `sealed interface`; a subtype that is not a catalog type is a nested `data object`, a subtype that is extends it |
| `{ subtypes: […], fields: { … } }`   | `sealed class` with the fields as constructor `val`s                |
| `Button.Style`                       | `Style` nested in `Button` (an `object` when `Button` is not a type) |
| `{ external: com.x.Instant }`        | imported where used; not generated                                  |

### `ViewModelComponent.kt`

```kotlin
@Component
abstract class ViewModelComponent : GeneratedViewModelFactories {
    fun createHomeViewModel(navigator: HomeNavigator): IHomeViewModel = homeFactory(navigator)
    …
}

@KmpComponentCreate
expect fun createViewModelComponent(): ViewModelComponent
```

The factories come from `GeneratedViewModelFactories`, which basekit's ViewModel processor generates
from the `@ViewModelInject` implementations (see [platform providers](platform-providers.md)). Each
takes the implementation's assisted parameters. A root component's factory takes none. The tool
looks for the interface in the common package of the generated implementations, which is where the
processor puts it.

This component has no parent. An app whose ViewModels need its own bindings writes its component
instead, for example `@Component abstract class ViewModelComponent(@Component val core: Core) :
GeneratedViewModelFactories`.

## Build requirements

- **Targets:** basekit's processors, including the navigator generator, run on the common metadata
  pass. Kotlin only creates that pass when `commonMain` is shared by two or more targets, so a
  single-target module gets no navigators or catalog.
- **kotlin-inject:**
  - The component needs `me.tatarka.inject:kotlin-inject-runtime-kmp`.
  - The kotlin-inject KSP compiler must run on every target (`kspJvm`, `kspAndroid`, `kspIosArm64`,
    …) to generate the `createViewModelComponent` actuals.
  - Use kotlin-inject 0.8.0 or later. Under KSP2, 0.7.1 fails in `KmpComponentCreateGenerator`.
- **deltalist-core:** the implementations use `toDeltaList` and `sectionedDeltaList`.
- **Nullable args:** they compile, but on JVM, Apple and JS, reading one that holds `null` throws:
  `InMemoryStoredProperty` returns `storedValue!!`. The generated code only sets args.

[`demo-catalog`](../../demo-catalog) runs the whole loop:
1. Generates code from its `catalog.yaml`.
2. Compiles it with the processors and kotlin-inject.
3. Tests the component and the navigation calls.
4. Checks that the catalog written back is identical to the input.
