package com.latenighthack.basekit.fake

import com.latenighthack.basekit.navigation.NavigationResponder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FakeStackTest {
    @Test
    fun aResponseRemovesTheScreenAndResumesTheCaller() = runTest {
        val stack = FakeStack()
        stack.root("Home", "home")
        lateinit var responder: NavigationResponder<Int>
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            stack.pushForResult<Int>("Picker") { responder = it; "picker" }
        }
        assertEquals(listOf("Home", "Picker"), stack.entries.value.map { it.name })
        responder.respond(4)
        assertEquals(4, result.await())
        assertEquals(listOf("Home"), stack.entries.value.map { it.name })
    }

    @Test
    fun closingARespondingScreenAnswersNull() = runTest {
        val stack = FakeStack()
        stack.root("Home", "home")
        val result = async(start = CoroutineStart.UNDISPATCHED) { stack.pushForResult<Int>("Picker") { "picker" } }
        stack.close()
        assertNull(result.await())
        assertEquals("home", stack.top)
    }

    @Test
    fun aRedirectReplacesTheTopScreen() {
        val stack = FakeStack()
        stack.root("Home", "home")
        stack.push("Splash", "splash")
        stack.replacing { stack.push("Feed", "feed") }
        assertEquals(listOf("Home", "Feed"), stack.entries.value.map { it.name })
        // Only the first push inside is a replacement.
        stack.replacing {
            stack.push("Detail", "detail")
            stack.push("More", "more")
        }
        assertEquals(listOf("Home", "Detail", "More"), stack.entries.value.map { it.name })
        // A navigator that is not a FakeRedirector navigates as usual.
        var called = false
        fakeRedirecting(Any()) { called = true }
        assertEquals(true, called)
    }

    @Test
    fun theWalkOpensEveryScreenAndCallsEveryActionOnce() = runTest {
        val stack = FakeStack()
        val data = FakeData(FakeConfig(), "Walk")
        val leaf = TestFake(data, emptyList())
        lateinit var responder: NavigationResponder<String>
        val picker = TestFake(data, listOf(FakeAction("onSelected", FakeAction.Effect.RESPOND) { responder.respond("x") }))
        var picked: String? = null
        val home = TestFake(
            data,
            listOf(
                FakeAction("onPick", FakeAction.Effect.NAVIGATE) {
                    picked = stack.pushForResult<String>("Picker") { responder = it; picker }
                },
                FakeAction("onOpen", FakeAction.Effect.NAVIGATE) { stack.push("Leaf", leaf) },
                FakeAction("onRefresh", FakeAction.Effect.RECORD) {},
            ),
        )
        stack.root("Home", home)
        val walk = walkFakeGraph(stack)
        assertEquals(listOf("Home", "Picker", "Leaf"), walk.screens)
        assertEquals(listOf("Home -> Picker", "Home -> Leaf"), walk.edges)
        assertEquals("x", picked)
        assertEquals(listOf("Home"), stack.entries.value.map { it.name })
    }

    private class TestFake(override val fakeData: FakeData, actions: List<FakeAction>) : FakeViewModel {
        override val fakeActions: List<FakeAction> = actions
        override val fakeEmbedded: List<FakeViewModel> = emptyList()
    }
}
