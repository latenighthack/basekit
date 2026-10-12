# Screen catalog (`catalog.yaml`)

The `com.latenighthack.basekit.catalog` plugin writes `catalog.yaml` during the build. The file lists
every screen in the module, the state each one shows, the actions and mutations it accepts, its lists
and child panes, where each action navigates, and the types those refer to. It is read from the
`@ViewModelSpec` and `@Destination` declarations, so it always matches the code.

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.latenighthack.basekit.catalog") version "0.3.2"
}
```

The catalog plugin also applies the `navigation` and `viewmodel` plugins. Run `assemble`, or
`generateBasekitCatalog` on its own; the file is written to
`build/generated/basekit-catalog/catalog.yaml`. It is not packaged into any artifact.

The processor runs on the common metadata pass only, so the catalog covers `commonMain`
declarations. Kotlin creates that pass only when `commonMain` is shared by two or more targets.

To go the other way, from a catalog to Kotlin, see [generating code from a catalog](catalog-codegen.md).
`assemble` also writes `catalog.sources.yaml` (where each entry is declared) and `catalog.html`, an
interactive [navigation graph page](catalog-graph.md).

## File layout

```yaml
schema: catalog/v1

screens: # ViewModels that are navigation destinations
components: # ViewModels that are not: list rows and child panes
types: # models and enums that state, params, args and responses refer to
```

Each screen is written under a comment header. Components are grouped under a header per screen:
components that more than one screen embeds come first, under `Shared components`; components no
screen embeds come last. Screens, components and types are sorted by name; the members of each
entry keep their declaration order. The same code always produces the same bytes.

## Schema

```yaml
schema: catalog/v1

screens:
  <Name>:
    spec: <qualified interface name>
    navigation:
      args: { <name>: <Type> }
      route: <"/path/{arg}" | null>
      route_args: [<name>]
      response: <Type | null>
    identity: <prop>
    state:
      <prop>: <Type>
    actions:
      <name>: { navigates_to: <Name> } # {} when the action does not navigate
    mutations:
      <name>: { param: { <name>: <Type> }, binds: <stateProp> } # binds only when one matches
    lists:
      <prop>:
        kind: flat | sectioned
        sections: [<Header>] # sectioned only
        element: <Component>
        rows: [<Component>]
    children:
      <prop>: <Component>

components:
  <Name>: # the same fields as a screen, without navigation

types:
  <Name>: { enum: [<CASE>] }
  <Name>: { fields: { <name>: <Type> } }
  <Name>: { fields: { ... }, subtypes: [<Name>] } # sealed types
  <Name>: { external: <qualified name> } # declared outside this module
```

Empty sections are left out.

### Names

A screen or component is named by its navigation name in UpperCamelCase: `IHomeViewModel` and
`HomeViewModel` are both `Home`, and `@Destination(navName = "user_profile")` is `UserProfile`.
The rule drops a leading interface `I` and one trailing `ViewModel`, `Screen`, `Destination` or
`Route`. When two declarations derive the same name, both are written under their qualified names
and the build logs a warning.

Types use their simple Kotlin names, such as `String?` or `List<RecentMessageModel>`. Types in the
`kotlin` packages are built in and are not listed under `types`.

### Fields

| Field                   | Source                                                                                   |
| ----------------------- | ---------------------------------------------------------------------------------------- |
| `spec`                  | Qualified name of the `@ViewModelSpec` (or `@Destination`) interface.                    |
| `navigation.args`       | The public properties of the destination's `Args` class.                                 |
| `navigation.route`      | `@Route.path` on the `Args` class, falling back to `@Destination.webPath`.               |
| `navigation.route_args` | The `Args` properties marked `@RouteArg`.                                                |
| `navigation.response`   | `R` of `RespondingDestination<Args, R>`.                                                 |
| `identity`              | The `@ViewModelIdentity` property.                                                       |
| `state`                 | The properties of the spec's `State`.                                                    |
| `actions`               | Zero-argument suspend functions.                                                         |
| `mutations`             | One-argument suspend functions. `binds` names the State property with the mutator's noun (`setQuery` binds `query`) and the same type; SwiftUI binds the two as a two-way `Binding`. |
| `lists`                 | `@ViewModelList` properties, and `DeltaList`, `SectionedDeltaList`, `Flow<Delta<…>>` and `Flow<SectionedDelta<…>>` properties. |
| `children`              | `@ChildViewModel` properties.                                                            |

A screen whose `@Destination` is not also a `@ViewModelSpec` has only `spec`, `navigation` and the
actions that carry `@NavigateTo`.

### Navigation

`navigates_to` is written on the action that declares the `@NavigateTo`. When a list row declares
it, the edge is on the row component's action, not on the screen that hosts the list. The navigation
graph is every `navigates_to` in the file, together with the `navigation` block of each target.

An action with more than one `@NavigateTo` lists its targets: `{ navigates_to: [Detail, Picker] }`.

### Lists

| Field      | Meaning                                                                                       |
| ---------- | --------------------------------------------------------------------------------------------- |
| `kind`     | `flat` for `Flow<Delta<Row>>` or `DeltaList<Row>`; `sectioned` for `SectionedDeltaList<Header, Row>` or `Flow<SectionedDelta<Header, Row>>`. |
| `sections` | The header's enum entries, or the subclasses of a sealed header. Sectioned lists only.         |
| `element`  | The row type the list is declared with, named by the same rule as components: a marker interface `IHomeListItemViewModel` is `HomeListItem`. |
| `rows`     | For `@ViewModelList`, its `possibleTypes`. Otherwise every `@ViewModelSpec` that is, or extends, the element type. |

### Types

`types` lists every type that state, mutator params, args and responses refer to, and the types their
fields refer to in turn:

- an enum lists its entries under `enum`;
- a class in this module lists its public properties under `fields`;
- a sealed type also lists its subclasses under `subtypes`. A subclass that declares no public
  properties (an `object`, or a ktbuf enum entry class) is a case and is only named there; a
  subclass with properties is listed as a type of its own;
- a class from a library is written as `external` with its qualified name; its fields are not read.

Two types with the same simple name are each written with as many enclosing names as tell them
apart, such as `Button.Style` and `Image.Style`, and the build logs a warning.

## Code that catalog/v1 does not allow

The catalog records what the code declares. Where that breaks a catalog/v1 rule, the entry is still
written and the build logs a warning:

- **A mutation that navigates.** catalog/v1 only allows `navigates_to` on zero-argument actions. A
  one-argument function with `@NavigateTo` is written as a mutation with `navigates_to`. Move the
  edge to the row's zero-argument action (see [child ViewModels](child-viewmodels.md)).
- **An edge outside any spec.** A `@NavigateTo` on a marker interface, or on an implementation class
  that implements no `@ViewModelSpec`, has no entry to sit on and is left out.

## YAML format

The file parses with YAML 1.1 parsers such as SnakeYAML:

- A map or list is written inline (`{ code: String }`) only when every entry is a plain identifier.
  Any entry with a nullable type, such as `String?`, is written in block style:

  ```yaml
  setAgentFilter:
    param:
      agentId: String?
    binds: selectedAgentId
  ```

- Values YAML 1.1 reads as booleans, nulls or numbers (`on`, `NO`, `null`) are quoted.
- Routes are always quoted.

## Example

The demo module's catalog is checked in at
[`demo-core/catalog.golden.yaml`](../../demo-core/catalog.golden.yaml); `./gradlew check` fails when
the generated file differs from it.
