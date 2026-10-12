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
    id("com.latenighthack.basekit.catalog") version "0.3.2"
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

The task is not part of `build`. Without Gradle, run the jar
(`com.latenighthack.basekit:basekit-catalog-codegen`) or its application distribution:

```
basekit-catalog-codegen --catalog catalog.yaml --out src/commonMain/kotlin \
    [--navigation-package PKG] [--types-package PKG] [--component-package PKG] [--force]
```

| Option                 | Default                                                                                |
| ---------------------- | -------------------------------------------------------------------------------------- |
| `--navigation-package` | The `Basekit_NavigationPackage` KSP argument (Gradle task), else the screens' common package, which is also basekit's default. It must match where basekit generates the navigators. |
| `--types-package`      | The common package of all specs.                                                       |
| `--component-package`  | The common package of all specs.                                                       |

## Which files are rewritten

| File                                   | On every run                         |
| -------------------------------------- | ------------------------------------ |
| Spec files, `CatalogTypes.kt`, `ViewModelComponent.kt` | Rewritten. Edit the catalog, not these files. |
| Implementation files                   | Written only when missing; `--force` (or `force.set(true)`) rewrites them. |

A file whose content would not change is left untouched.

## What is generated

Each spec goes in the package of its `spec` qualified name.

### Spec files

One file per screen, named after the spec (`IHomeViewModel.kt`). It holds:

- the screen interface: `@Destination` (with `navName` only when the catalog name differs from the
  one basekit derives), `@ViewModelSpec`, `NavigationDestination<Args>` or
  `RespondingDestination<Args, R>`, and nested:
  - `Args`, with `@Route`, a typed constructor and `@RouteArg` on the route args;
  - `State`, a data class of the `state` fields (`class State` when there are none);
  - one sealed `<List>Header` interface per sectioned list, with a `data object` per section;
- `@ViewModelIdentity`, lists, `@ChildViewModel` properties, actions and mutations, with
  `@NavigateTo` for each `navigates_to`;
- every component only this screen embeds, at any depth;
- a marker interface for each list element that is not a component, such as
  `interface IHomeListItemViewModel`. Every row of the list extends it.

Flat lists are `@ViewModelList(rows…) val items: Flow<Delta<Element>>`. Sectioned lists are
`val sections: SectionedDeltaList<Spec.SectionsHeader, Element>`.

A component that several screens embed gets its own file. A component that nothing embeds is a
root: it gets its own file, with the components it embeds.

### Implementation files

Names follow the spec: `IHomeViewModel` is implemented by `HomeViewModel` in `HomeViewModel.kt`.
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
- Other actions and mutations do nothing.
- Initial State uses defaults: `""`, `0`, `false`, `null`, empty collections, the first enum
  entry or sealed case, and a constructor call for data classes. External types get
  `TODO("initial <Type>")`.
- A root component's implementation is a top-level `@ViewModelInject` class without a navigator. Its
  `navigates_to` edges are left as empty actions, because only screens own a navigator.

### `CatalogTypes.kt`

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
