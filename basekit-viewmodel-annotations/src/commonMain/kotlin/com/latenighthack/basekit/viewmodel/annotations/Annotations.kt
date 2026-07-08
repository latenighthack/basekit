package com.latenighthack.basekit.viewmodel.annotations

import kotlin.reflect.KClass

/**
 * Marks an interface as a ViewModel. The processor derives the state type from its
 * `ViewModel<State>` supertype and generates a native binding wrapper per platform
 * (Android base activity, iOS `Kvo{ViewModel}` Swift wrapper, React `use{ViewModel}` hook).
 *
 * Named `ViewModelSpec` (not `ViewModel`) so it does not collide with the `ViewModel<State>`
 * runtime interface that annotated types also implement — both can be imported without an alias.
 *
 * @param webPath optional path used by the web (React) binding for routing.
 */
@Target(AnnotationTarget.CLASS)
public annotation class ViewModelSpec(val webPath: String = "")

/**
 * Marks a `Flow<Delta<ChildVm>>` property as a list of child ViewModels. The generated bindings
 * wire the deltalist stream into the platform list system (RecyclerView / UICollectionView /
 * React list) and manage each child ViewModel's state subscription.
 *
 * @param possibleTypes the concrete child ViewModel types the list may contain (for polymorphic rows).
 */
@Target(AnnotationTarget.PROPERTY)
public annotation class ViewModelList(vararg val possibleTypes: KClass<*>)

/** Marks a property that holds a single nested child ViewModel bound alongside its parent. */
@Target(AnnotationTarget.PROPERTY)
public annotation class ChildViewModel

/** Excludes an element from ViewModel binding codegen. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class CodegenIgnore

/**
 * Marks a concrete ViewModel implementation for kotlin-inject wiring. The processor emits a
 * `@Provides` binding from the implementation to its `@ViewModelSpec` interface into the generated
 * `GeneratedViewModelModule`, which the platform component includes — so the implementer never writes
 * that binding by hand.
 *
 * The implementation must expose an `@Inject` constructor. Its non-navigator parameters are resolved
 * from the kotlin-inject graph; annotate the per-screen navigator parameter with kotlin-inject's
 * `@Assisted` so it is supplied at the call site rather than from the graph.
 */
@Target(AnnotationTarget.CLASS)
public annotation class ViewModelInject

/**
 * Marks an interface of kotlin-inject `@Provides` functions that the generated platform component
 * should include, making app-supplied dependencies (repositories, clients) available to the graph
 * that builds `@ViewModelInject` ViewModels.
 */
@Target(AnnotationTarget.CLASS)
public annotation class ViewModelModule
