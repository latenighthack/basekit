package com.latenighthack.basekit.navigation

/**
 * Base contract implemented by every `@Destination`. The [Args] type parameter ties a destination
 * to its navigation arguments; the KSP processor reads it to build the navigation graph.
 */
public interface NavigationDestination<Args : NavigatorArgs>
