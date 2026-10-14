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

/**
 * Marks a `@ViewModelSpec` component that destinations host without listing it, such as app chrome
 * shown around every screen. The app creates one instance. The `@NavigateTo` edges of the component
 * and of the rows and children it embeds get their own `<Name>Navigator`, which has no `close()`
 * because the component is not a screen. The edges keep the component's call sites as their
 * `<Target>Source` entries.
 *
 * @param navName overrides the generated navigation name, as on [Destination].
 */
@Target(AnnotationTarget.CLASS)
public annotation class HostedComponent(val navName: String = "")

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

/**
 * Declares the destinations this `@Destination` may replace itself with without a user action, such as
 * an entry screen that sends the user to the right first step, or a screen that leaves when what it
 * shows is deleted. Each target joins the destination's generated navigator like a `@NavigateTo`
 * edge, with the source entry `<SCREEN>_REDIRECT`; which target to open is the ViewModel's decision.
 */
@Target(AnnotationTarget.CLASS)
public annotation class RedirectsTo(vararg val targets: KClass<*>)

/** Excludes an element from codegen. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
public annotation class CodegenIgnore
