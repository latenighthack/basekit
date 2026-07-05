package com.latenighthack.basekit.navigation.annotations

import kotlin.reflect.KClass

/**
 * Marks an interface as a navigable destination. The processor derives the destination's arguments
 * type from its `NavigationDestination<Args>` supertype and adds it to the app navigation graph.
 *
 * @param webPath optional deep-link path; a `@Route` on the `Args` type takes precedence.
 */
@Target(AnnotationTarget.CLASS)
public annotation class Destination(val webPath: String = "")

/** Declares the deep-link path template for a destination's `Args` type, e.g. `/detail/{id}`. */
@Target(AnnotationTarget.CLASS)
public annotation class Route(val path: String = "")

/** Marks an `Args` property as a route (path) parameter bound from the matched URL. */
@Target(AnnotationTarget.PROPERTY)
public annotation class RouteArg

/** Declares a navigation edge from the annotated action to [target] (another `@Destination`). */
@Target(AnnotationTarget.FUNCTION)
@Repeatable
public annotation class NavigateTo(val target: KClass<*>)

/** Excludes an element from codegen. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
public annotation class CodegenIgnore
