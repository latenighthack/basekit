package com.latenighthack.basekit.viewmodel.compose

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Column
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.Composable
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeneratedHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun generatedHostRetriesBindsAndRetainsAcrossRecreation() {
        var preparations = 0
        var disposals = 0
        val failures = mutableListOf<Throwable>()
        val raw = RealBindingProbeViewModel()
        val content: @Composable () -> Unit = {
            BindingProbeViewModelHost(
                ownerKey = "probe", storeOwner = compose.activity,
                prepare = {
                    preparations++
                    if (preparations == 1) error("offline")
                    PreparedViewModel(raw) { disposals++ }
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
}
