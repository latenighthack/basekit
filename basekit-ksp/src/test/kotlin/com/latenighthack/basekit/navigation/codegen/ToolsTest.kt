package com.latenighthack.basekit.navigation.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers the name-derivation helpers the navigation generators depend on. */
class ToolsTest {

    @Test
    fun toDestinationNavName_strips_known_suffixes() {
        assertEquals("home", "HomeScreen".toDestinationNavName())
        assertEquals("home", "HomeViewModel".toDestinationNavName())
        assertEquals("detail", "DetailDestination".toDestinationNavName())
        assertEquals("user_profile", "UserProfileScreen".toDestinationNavName())
    }

    @Test
    fun toDestinationNavName_drops_a_leading_interface_I() {
        assertEquals("home", "IHomeScreen".toDestinationNavName())
        // A leading I not followed by an uppercase letter is kept (it is part of the word).
        assertEquals("image", "Image".toDestinationNavName())
    }

    // Documents a real edge: the leading-I strip fires on any capitalized second letter, so "IOSScreen"
    // becomes "os", not "ios". Named here so a future change to the rule is a conscious one.
    @Test
    fun toDestinationNavName_leading_I_edge_case() {
        assertEquals("os", "IOSScreen".toDestinationNavName())
    }

    @Test
    fun camelWords_splits_on_boundaries() {
        assertEquals(listOf("on", "Open", "Detail"), "onOpenDetail".camelWords())
    }

    @Test
    fun toUpperCamelCase_handles_snake_and_camel() {
        assertEquals("UserProfile", "user_profile".toUpperCamelCase())
        assertEquals("Home", "home".toUpperCamelCase())
    }

    @Test
    fun toUpperSnakeCase_from_camel() {
        assertEquals("ON_OPEN_DETAIL", "onOpenDetail".toUpperSnakeCase())
    }
}
