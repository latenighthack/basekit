package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class NavigatorInterfaceGeneratorTest {
    // Rows that each declare `onItemTapped` are separate call sites named after the row spec, not one
    // shared HOME_ON_ITEM_TAPPED entry, and their targets join the hosting destination's navigator.
    @Test
    fun rowEdgesAreNamedAfterTheRowSpecAndJoinTheHostNavigator() {
        val output = RecordingCodeGenerator()
        val destinations = listOf(
            destination(
                "HomeViewModel",
                "sample.HomeViewModel",
                "home",
                listOf(
                    NavEdge("home", "onJoinRoomTapped", "sample.RoomViewModel"),
                    NavEdge("home_room_item", "onItemTapped", "sample.RoomViewModel"),
                    NavEdge("recommended_title_item", "onItemTapped", "sample.TitleViewModel"),
                ),
            ),
            destination("RoomViewModel", "sample.RoomViewModel", "room"),
            destination("TitleViewModel", "sample.TitleViewModel", "title"),
        )

        NavigatorInterfaceGenerator(output, NoopLogger, Dependencies(false), "sample").generate(destinations)

        val source = output.source()
        assertContains(source, "enum class RoomSource {\n        HOME_ON_JOIN_ROOM_TAPPED,\n        HOME_ROOM_ITEM_ON_ITEM_TAPPED\n    }")
        // A single call site to Title: no source parameter.
        assertContains(source, "fun navigateToTitle(context: Any? = null)")
        assertFalse("HOME_ON_ITEM_TAPPED" in source)
        assertContains(source, "interface HomeNavigator : CloseNavigationTarget, RoomNavigationTarget, TitleNavigationTarget")
    }

    private fun destination(
        simpleName: String,
        qualifiedName: String,
        navName: String,
        edges: List<NavEdge> = emptyList(),
    ) = DestinationInfo(
        simpleName = simpleName,
        qualifiedName = qualifiedName,
        navName = navName,
        argsQualifiedName = null,
        argsSwiftName = null,
        responseQualifiedName = null,
        responseSwiftName = null,
        routePath = null,
        routeArgs = emptyList(),
        edges = edges,
    )
}

private object NoopLogger : KSPLogger {
    override fun logging(message: String, symbol: KSNode?) = Unit
    override fun info(message: String, symbol: KSNode?) = Unit
    override fun warn(message: String, symbol: KSNode?) = Unit
    override fun error(message: String, symbol: KSNode?) = Unit
    override fun exception(e: Throwable) = Unit
}
