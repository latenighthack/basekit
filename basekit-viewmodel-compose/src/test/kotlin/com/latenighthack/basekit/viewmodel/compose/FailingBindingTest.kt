package com.latenighthack.basekit.viewmodel.compose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.latenighthack.basekit.demo.RealBindingProbeViewModel
import com.latenighthack.basekit.demo.bindBindingChildViewModel
import com.latenighthack.basekit.demo.bindBindingProbeViewModel
import com.latenighthack.basekit.demo.failingBindingChildViewModel
import com.latenighthack.basekit.viewmodel.ViewModelFailure
import com.latenighthack.basekit.viewmodel.ViewModelFailures
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** A throw from `state` or a list under the generated bindings reaches the handler, not the composition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ComposeTestApplication::class)
class FailingBindingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun throwingStateAndRowsGoToTheHandlerAndKeepWhatIsShown() {
        val raw = RealBindingProbeViewModel()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = bindBindingProbeViewModel(raw) { action -> scope.launch { action() } }
            Column {
                BasicText(model.state.note ?: "empty")
                model.Rows(bindingChild = { child -> BasicText(child.state.title) })
            }
        }
        compose.runOnIdle { raw.replaceRows() }
        compose.onNodeWithText("Row child").assertExists()
        compose.runOnIdle { raw.failState("gone") }
        compose.waitUntil { raw.handledFailures.size == 1 }
        compose.onNodeWithText("failed: gone").assertExists()
        compose.runOnIdle { raw.failRows("rows gone") }
        compose.waitUntil { raw.handledFailures.size == 2 }
        compose.onNodeWithText("Row child").assertExists()
        assertEquals(listOf("state: gone", "rows: rows gone"), raw.handledFailures)
    }

    @Test fun throwingStateWithoutAHandlerGoesToTheFallback() {
        val failures = mutableListOf<ViewModelFailure>()
        val previous = ViewModelFailures.unhandled
        ViewModelFailures.unhandled = { failures += it }
        try {
            val failing = failingBindingChildViewModel("deleted")
            compose.setContent {
                val scope = rememberCoroutineScope()
                BasicText(bindBindingChildViewModel(failing) { action -> scope.launch { action() } }.state.title)
            }
            compose.waitUntil { failures.size == 1 }
            compose.onNodeWithText("loaded").assertExists()
            assertEquals("com.latenighthack.basekit.demo.BindingChildViewModel", failures.single().viewModelName)
            assertEquals(ViewModelFailure.STATE, failures.single().source)
            assertEquals("deleted", failures.single().error.message)
        } finally {
            ViewModelFailures.unhandled = previous
        }
    }
}
