package com.latenighthack.basekit.viewmodel.compose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.latenighthack.basekit.demo.RealBindingProbeViewModel
import com.latenighthack.basekit.demo.bindBindingProbeViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** A generated mutator binding reads state and writes through the mutator, as the Swift ones do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ComposeTestApplication::class)
class MutatorBindingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun writesCallTheMutatorAndShowAtOnce() {
        val model = RealBindingProbeViewModel()
        var write: (String?) -> Unit = {}
        compose.setContent {
            val scope = rememberCoroutineScope()
            val binding = bindBindingProbeViewModel(model) { action -> scope.launch { action() } }
            write = binding.noteBinding.onValueChange
            BasicText(binding.noteBinding.value ?: "empty")
        }
        compose.onNodeWithText("empty").assertExists()
        compose.runOnIdle { write("typed") }
        compose.onNodeWithText("typed").assertExists()
        compose.runOnIdle { assertEquals("typed", runBlocking { model.state.first().note }) }
    }
}
