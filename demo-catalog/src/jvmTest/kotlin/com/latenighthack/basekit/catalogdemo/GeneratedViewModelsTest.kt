package com.latenighthack.basekit.catalogdemo

import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.loadedItems
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Drives the code basekit-catalog-codegen wrote from catalog.yaml: the kotlin-inject component builds
 * every screen, lists hold one instance of each row type, and navigating actions call the navigator
 * with the right target, args and source.
 */
class GeneratedViewModelsTest {

    private class RecordingNavigator : HomeNavigator, RoomNavigator, SettingsNavigator {
        val calls = mutableListOf<String>()

        override fun close(context: Any?) {
            calls += "close"
        }

        override fun navigateToRoom(args: IRoomViewModel.Args, source: RoomNavigationTarget.RoomSource, context: Any?) {
            calls += "Room(${args.roomId}) from $source"
        }

        override fun navigateToHome(args: IHomeViewModel.Args, context: Any?) {
            calls += "Home"
        }

        override fun navigateToSettings(args: ISettingsViewModel.Args, context: Any?) {
            calls += "Settings"
        }

        override suspend fun navigateToPicker(
            args: IPickerViewModel.Args,
            source: PickerNavigationTarget.PickerSource,
            context: Any?,
        ): PickResult? {
            calls += "Picker from $source"
            return null
        }
    }

    private val component = createViewModelComponent()

    @Test
    fun homeActionsAndRowsNavigateWithTheirSources() = runTest {
        val navigator = RecordingNavigator()
        val home = component.createHomeViewModel(navigator)

        home.onCreateRoom()
        home.onSettingsTapped()

        val sections = home.rooms.first().sections
        assertEquals(listOf(IHomeViewModel.RoomsHeader.Recent, IHomeViewModel.RoomsHeader.Suggested), sections.map { it.header })
        sections.forEach { section ->
            val rows = section.loadedItems()
            assertEquals(3, rows.size)
            assertIs<IEmptyRoomsItemViewModel>(rows[0])
            assertIs<IRoomItemViewModel>(rows[1])
            assertIs<ITitleItemViewModel>(rows[2])
        }
        val rows = sections.first().loadedItems()
        (rows[0] as IEmptyRoomsItemViewModel).onCreateRoomTapped()
        (rows[1] as IRoomItemViewModel).onItemTapped()
        (rows[2] as ITitleItemViewModel).onItemTapped()

        assertEquals(
            listOf(
                "Room() from HOME_ON_CREATE_ROOM",
                "Settings",
                "Room() from EMPTY_ROOMS_ITEM_ON_CREATE_ROOM_TAPPED",
                "Room() from ROOM_ITEM_ON_ITEM_TAPPED",
                "Picker from TITLE_ITEM_ON_ITEM_TAPPED",
            ),
            navigator.calls,
        )
        assertEquals("RoomItem", (rows[1] as IRoomItemViewModel).key)
        assertEquals(PhotoState.Idle, home.initialState.photoState)
    }

    @Test
    fun roomBindsItsQueryAndNavigatesFromAMutation() = runTest {
        val navigator = RecordingNavigator()
        val room = component.createRoomViewModel(IRoomViewModel.Args("room-1", null), navigator)

        room.setQuery("cards")
        assertEquals("cards", room.state.first().query)

        room.onInvite("friend-1")
        assertEquals(listOf("Picker from ROOM_ON_INVITE"), navigator.calls)
        assertEquals(RoomRole.HOST, room.initialState.role)
    }

    @Test
    fun pickerListsOneOfEachRowAndSettingsHasItsChild() = runTest {
        val picker = component.createPickerViewModel(RecordingNavigator()) { }
        val options: Delta<IPickerOptionViewModel> = picker.options.first()
        assertEquals(1, options.loadedItems().size)

        val navigator = RecordingNavigator()
        val settings = component.createSettingsViewModel(navigator)
        assertEquals(Text(value = "", style = Text.Style.BODY), settings.profile.initialState.name)
        assertEquals(Button.Style.PRIMARY, settings.initialState.signOutButton.style)
    }

    @Test
    fun redirectsCallTheNavigatorWithTheRedirectSource() = runTest {
        val navigator = RecordingNavigator()
        (component.createRoomViewModel(IRoomViewModel.Args("room-1", null), navigator) as RoomViewModel).redirectToHome()
        (component.createSettingsViewModel(navigator) as SettingsViewModel).redirectToRoom()
        assertEquals(listOf("Home", "Room() from SETTINGS_REDIRECT"), navigator.calls)
    }

    @Test
    fun aComponentNoScreenEmbedsIsBuiltOnItsOwn() = runTest {
        val panel = component.createDebugPanelViewModel()
        panel.setVerbose(true)
        assertEquals(true, panel.state.first().verbose)
    }
}
