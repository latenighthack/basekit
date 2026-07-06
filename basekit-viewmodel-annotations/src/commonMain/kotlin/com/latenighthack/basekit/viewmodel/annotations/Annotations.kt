package com.latenighthack.basekit.viewmodel.annotations

import kotlin.reflect.KClass

/**
 * Marks an interface as a ViewModel. The processor derives the state type from its
 * `ViewModel<State>` supertype and generates a native binding wrapper per platform
 * (Android base activity, iOS `Kvo{ViewModel}` Swift wrapper, React `use{ViewModel}` hook).
 *
 * @param webPath optional path used by the web (React) binding for routing.
 */
@Target(AnnotationTarget.CLASS)
public annotation class ViewModel(val webPath: String = "")

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
