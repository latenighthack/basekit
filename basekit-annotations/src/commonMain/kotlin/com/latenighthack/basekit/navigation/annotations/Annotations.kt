package com.latenighthack.basekit.navigation.annotations

import kotlin.reflect.KClass

/**
 * Marks an interface as a navigable destination. The processor derives the destination's arguments
 * type from its `NavigationDestination<Args>` supertype and adds it to the app navigation graph.
 *
 * @param webPath optional deep-link path; a `@Route` on the `Args` type takes precedence.
 * @param navName overrides the generated navigation name (the basis for the `<X>NavigationTarget` /
 *   `<X>Navigator` type names). Defaults to the simple name with a leading `I` and a trailing
 *   `Screen`/`Destination`/`Route`/`ViewModel` suffix stripped. Set this to disambiguate two
 *   destinations that would otherwise derive the same name (e.g. `home.HomeScreen` and
 *   `admin.HomeScreen`), which is otherwise a build error.
 */
@Target(AnnotationTarget.CLASS)
public annotation class Destination(val webPath: String = "", val navName: String = "")

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
