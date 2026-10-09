# basekit development

@ViewModelSpec declares interfaces; implementations are handwritten. Navigation, DI, Kotlin/Swift/React bindings are generated. Validate KSP and consumer bindings together.

Read the build scripts and relevant tests before editing. Source examples:
- `basekit-navigation/src/commonMain/kotlin/com/latenighthack/basekit/navigation/NavigatorArgs.kt`
- `basekit-navigation/src/commonMain/kotlin/com/latenighthack/basekit/navigation/NavigationDestination.kt`

## Dependency development

Released builds do not consult global Maven Local. For paired Fullhouse work, use its `./fh deps resolve` and `./fh deps publish --library NAME`; configure explicit repository paths in Fullhouse's ignored `.fh/workspace.json`. The CLI passes `-PfhWorkspace` and an isolated `-Dmaven.repo.local`. Do not republish different bytes under the same version.

Use the repository Gradle wrapper and inspect target-specific test tasks. Run changed-library tests plus Fullhouse consumer verification for affected JVM, Android, JS and Apple variants. Builds and generators require separate worktrees for concurrent edits. Never edit generated bindings to fix their source generator.

Publication to external repositories and app/service releases are separate from local validation.
