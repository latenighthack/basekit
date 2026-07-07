package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.RouteTable

// Everything below references KSP-generated types (HomeNavigator, DetailNavigationTarget,
// GeneratedRoutes) that live in this same package. It compiling is the end-to-end proof that the
// navigation processor emitted correct, usable Kotlin.

/** A concrete navigator implementing the generated graph interface for HomeScreen. */
class DemoNavigator : HomeNavigator {
    override fun close(context: Any?) {
        // no-op demo implementation
    }

    override fun navigateToDetail(
        args: DetailScreen.Args,
        source: DetailNavigationTarget.DetailSource,
        context: Any?,
    ) {
        // no-op demo implementation
    }

    // Responding destination: a real navigator would push the picker and suspend on its result; the demo
    // just dismisses immediately (null). The generated signature is `suspend`, returning PickResult?.
    override suspend fun navigateToPicker(args: PickerScreen.Args, context: Any?): PickResult? = null
}

/** Exercises the generated navigator method + source enum. */
fun openDetail(navigator: HomeNavigator) {
    navigator.navigateToDetail(
        DetailScreen.Args().apply { id = "42" },
        DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL,
    )
}

/** Exercises the generated route table registration + runtime URL matching. */
val demoRoutes: RouteTable = RouteTable().apply { GeneratedRoutes.register(this) }
