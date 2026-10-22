package com.latenighthack.basekit.viewmodel.compose

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Column
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.latenighthack.basekit.demo.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ComposeTestApplication::class)
class GeneratedHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun generatedHostRetriesBindsAndRetainsAcrossRecreation() {
        var preparations = 0
        var disposals = 0
        val failures = mutableListOf<Throwable>()
        val raw = RealBindingProbeViewModel()
        val actions = mutableListOf<String>()
        val observed = raw.observingActions(com.latenighthack.basekit.viewmodel.ViewModelActionObserver { actions += it.actionName })
        val content: @Composable () -> Unit = {
            BindingProbeViewModelHost(
                ownerKey = "probe", storeOwner = compose.activity,
                prepare = {
                    preparations++
                    if (preparations == 1) error("offline")
                    PreparedViewModel(observed) { disposals++ }
                },
                onActionError = failures::add,
                loading = { BasicText("loading") },
                failure = { _, retry -> BasicText("retry", Modifier.clickable(onClick = retry)) },
            ) { model ->
                Column {
                    BasicText(model.state.note ?: "empty", Modifier.clickable { model.launch { setNote("edited") } })
                    BasicText("clear", Modifier.clickable { model.launch { setNote(null) } })
                    BasicText("fail", Modifier.clickable { model.launch { fail() } })
                    model.Rows(bindingChild = { child ->
                        BasicText(child.state.title, Modifier.clickable { child.launch { select() } })
                    })
                }
            }
        }
        compose.setContent(content)
        compose.onNodeWithText("retry").performClick()
        compose.onNodeWithText("empty").performClick()
        compose.onNodeWithText("edited").assertExists()
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { it.setContent(content = content) }
        compose.onNodeWithText("edited").assertExists()
        compose.onNodeWithText("clear").performClick()
        compose.onNodeWithText("empty").assertExists()
        compose.onNodeWithText("fail").performClick()
        compose.waitUntil { failures.size == 1 }
        assertEquals("probe failure", failures.single().message)
        assertEquals(listOf("fail"), actions)
        compose.runOnIdle { raw.replaceRows() }
        compose.onNodeWithText("Row child").performClick()
        compose.onNodeWithText("Selected child").assertExists()
        compose.runOnIdle { raw.replaceRows() }
        compose.onNodeWithText("Row child").assertExists()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil { raw.activeStateCollectors == 0 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil { raw.activeStateCollectors == 1 }
        assertEquals(2, preparations)
        assertEquals(0, disposals)
        compose.activityRule.scenario.close()
        assertEquals(1, disposals)
    }

    @Test fun generatedSectionsKeepLazilyMappedRowsWhenSectionsComeAndGo() {
        val raw = RealBindingProbeViewModel()
        var remembered = 0
        compose.setContent {
            BindingProbeViewModelHost(
                ownerKey = "sections", storeOwner = compose.activity,
                prepare = { PreparedViewModel(raw) {} },
                onActionError = {}, loading = {}, failure = { _, _ -> },
            ) { model ->
                model.Sections(header = { header, _ -> BasicText("header:$header") }, bindingChild = { child ->
                    val slot = remember { remembered++ }
                    BasicText("${child.state.title}@$slot", Modifier.clickable { child.launch { select() } })
                })
            }
        }
        compose.onNodeWithText("Row pinned@0").performClick()
        compose.onNodeWithText("Selected pinned@0").assertExists()
        compose.onNodeWithText("Row more@1").assertExists()

        // A section appears above, then disappears: the shown rows keep their ViewModel and state.
        compose.runOnIdle { raw.insertSectionRow(0, BindingProbeViewModel.Section.Group("new"), "fresh") }
        compose.onNodeWithText("Row fresh@2").assertExists()
        compose.onNodeWithText("Selected pinned@0").assertExists()
        compose.runOnIdle { raw.removeSectionRow(0) }
        compose.onNodeWithText("header:Group(name=new)").assertDoesNotExist()
        compose.onNodeWithText("Selected pinned@0").assertExists()
        compose.onNodeWithText("Row more@1").assertExists()
    }

    @Test fun theStackHostShowsEachSheetWithTheRouterMadeForIt() {
        val router = BasekitNavigationRouter { request ->
            if (request.edge == AppleNavigationEdge.HOME_ON_PICK) BasekitNavigationDecision(BasekitPresentation.Sheet) else BasekitNavigationDecision()
        }
        val rootEntry = router.root(BasekitRoute.Home(HomeViewModel.Args(), edge = null))
        val hosts = mutableMapOf<java.util.UUID, BasekitNavigationRouter>()
        compose.setContent {
            BasekitNavigationStackHost(
                router, rootEntry,
                root = { entry, host -> hosts[entry.id] = host; BasicText("home") },
                destination = { entry, host -> hosts[entry.id] = host; BasicText("picker") },
                // Shown in place, so the test sees every sheet without dialog windows.
                sheet = { _, _, content -> content() },
            )
        }
        compose.runOnIdle { router.showPicker("home", PickerViewModel.Args(), AppleNavigationEdge.HOME_ON_PICK, null) {} }
        compose.waitForIdle()
        val first = router.sheet!!
        val firstHost = router.host(first)
        compose.runOnIdle { firstHost.showPicker(first.ownerId, PickerViewModel.Args(), AppleNavigationEdge.HOME_ON_PICK, null) {} }
        compose.waitForIdle()
        val second = firstHost.sheet!!
        assertTrue(hosts[rootEntry.id] === router)
        assertTrue(hosts[first.id] === firstHost)
        assertTrue(hosts[second.id] === router.host(second))
        compose.onAllNodesWithText("picker").assertCountEquals(2)
        compose.runOnIdle { router.updateSheet(null) }
        compose.onAllNodesWithText("picker").assertCountEquals(0)
    }
}
