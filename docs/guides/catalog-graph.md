# Navigation graph page

`basekit-catalog-codegen graph` turns a [`catalog.yaml`](catalog.md) into a single HTML file. The
file is self-contained: inline CSS and JavaScript, no network requests. It shows:

- **The graph:** a box for every screen, listing each navigation event the screen and the components
  it embeds declare (`RoomItem` · `onItemTapped() → Room`), with one edge per event to the screen it
  opens. The edges are styled by kind:
  - action events are solid;
  - mutation events (a one-argument function with `@NavigateTo`) are dashed;
  - events of a component that no screen embeds are dotted, on a dashed box, because no navigator
    reaches them.
- **Screen details:** clicking a screen, or one of its events, opens a panel with:
  - the screen's route, route args, args and response;
  - its full state, with catalog types expanded;
  - its actions, mutations (with what they bind), lists, children and identity.

  Each `→ Screen` chip opens that screen. Rows and children expand in place. Every entry links to
  where it is declared.

Pan by dragging or scrolling. Zoom with pinch, Ctrl/⌘ + scroll, or a mouse wheel. **Fit** shows the
whole graph. `/` focuses the search box; Enter centres and opens the first match. Opening the page
with `#screen=Home` shows that screen's details.

## From the code

With the catalog plugin applied, `assemble` writes `build/generated/basekit-catalog/catalog.html`
next to `catalog.yaml`. `./gradlew generateBasekitCatalogGraph` writes only the page.

```kotlin
basekitCatalogCodegen {
    graphCatalog.set(file("docs/catalog.yaml"))                   // default: the catalog from the code
    sourceLink.set("https://github.com/acme/app/blob/main/{path}#L{line}") // default: IntelliJ
}
```

The same settings as properties: `-Pbasekit.catalog.graphInput=docs/catalog.yaml` and
`-Pbasekit.catalog.sourceLink=…`.

## From a YAML file

```
basekit-catalog-codegen graph --catalog catalog.yaml --out catalog.html \
    [--sources catalog.sources.yaml] [--source-root DIR] [--source-link TEMPLATE] [--title TEXT]
```

`--sources` defaults to the `catalog.sources.yaml` beside the catalog. Without one, for example for a
hand-written catalog, the page has no source links.

## Source links

basekit-catalog-ksp writes `catalog.sources.yaml` next to `catalog.yaml`. For each catalog entry it
records the file and line of the declaration:

```yaml
root: "/Users/me/app"
sources:
  "Home": "core/src/commonMain/kotlin/com/example/IHomeViewModel.kt:27"
  "Home.state.title": "core/src/commonMain/kotlin/com/example/IHomeViewModel.kt:31"
  "Home.actions.onRefresh": "core/src/commonMain/kotlin/com/example/IHomeViewModel.kt:45"
  "types.PickResult": "core/src/commonMain/kotlin/com/example/PickResult.kt:3"
```

- **Keys** follow the catalog: `<Name>`, `<Name>.navigation.args.<arg>`, `<Name>.state.<field>`,
  `<Name>.identity`, `<Name>.actions.<name>`, `<Name>.mutations.<name>`, `<Name>.lists.<name>`,
  `<Name>.children.<name>`, `types.<Type>`.
- **Paths** are relative to `root`, the root project, which the plugin passes to KSP as
  `basekit.catalog.sourceRoot`.
- **The file is machine-specific.** It is build output; keep it out of version control.

A link template may use `{path}` (as recorded), `{absolutePath}` (`root` + path, or `--source-root`
+ path) and `{line}`:

| Opens in                  | Template                                                  |
| ------------------------- | --------------------------------------------------------- |
| IntelliJ / Android Studio | `idea://open?file={absolutePath}&line={line}` (default)   |
| VS Code                   | `vscode://file{absolutePath}:{line}`                      |
| GitHub                    | `https://github.com/<org>/<repo>/blob/<ref>/{path}#L{line}` |
