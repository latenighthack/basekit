package com.latenighthack.basekit.navigation

/**
 * Base contract implemented by every `@Destination`. The [Args] type parameter ties a destination
 * to its navigation arguments; the KSP processor reads it to build the navigation graph.
 * Declare the concrete `Args` class inside its owning destination interface and reference it as
 * `ExampleViewModel.Args`. The specification's `State` class is nested in the same interface.
 */
public interface NavigationDestination<Args : NavigatorArgs>
