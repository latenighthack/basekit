package com.latenighthack.basekit.viewmodel.compose

import android.os.Looper
import com.latenighthack.basekit.demo.AppleNavigationEdge
import com.latenighthack.basekit.demo.BasekitNavigationDecision
import com.latenighthack.basekit.demo.BasekitNavigationEntry
import com.latenighthack.basekit.demo.BasekitNavigationRouter
import com.latenighthack.basekit.demo.BasekitPresentation
import com.latenighthack.basekit.demo.BasekitRoute
import com.latenighthack.basekit.demo.DetailViewModel
import com.latenighthack.basekit.demo.PickResult
import com.latenighthack.basekit.demo.PickerViewModel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The generated Compose router behaves as the generated Swift router does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ComposeTestApplication::class)
class ComposeNavigationRouterTest {
    private fun detail() = DetailViewModel.Args().apply { id = "7" }

    @Test fun pushesAndClosesFromTheOwner() {
        val router = BasekitNavigationRouter()
        router.showDetail("home", detail(), AppleNavigationEdge.HOME_ON_OPEN_DETAIL, null)
        router.showDetail("home", detail(), AppleNavigationEdge.HOME_ON_OPEN_DETAIL, null)
        assertEquals(2, router.path.size)
        // An entry closes everything pushed over it.
        router.close(router.path[0].ownerId, null)
        assertTrue(router.path.isEmpty())
    }

    @Test fun closingAnOwnerItDoesNotPresentClosesItInTheParent() {
        val parent = BasekitNavigationRouter()
        parent.showDetail("home", detail(), AppleNavigationEdge.HOME_ON_OPEN_DETAIL, null)
        val child = BasekitNavigationRouter(parent)
        child.close(parent.path.single().ownerId, null)
        assertTrue(parent.path.isEmpty())
    }

    @Test fun aRespondingDestinationAnswersOnceAndCloses() {
        val router = BasekitNavigationRouter { BasekitNavigationDecision(BasekitPresentation.Sheet) }
        val answers = mutableListOf<PickResult?>()
        router.showPicker("home", PickerViewModel.Args(), AppleNavigationEdge.HOME_ON_PICK, null) { answers += it }
        val route = router.sheet!!.route as BasekitRoute.Picker
        route.responder!!.respond(PickResult(3))
        route.responder!!.respond(PickResult(4))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf<PickResult?>(PickResult(3)), answers)
        assertNull(router.sheet)
    }

    @Test fun dismissingASheetAnswersNull() {
        val router = BasekitNavigationRouter { BasekitNavigationDecision(BasekitPresentation.Sheet) }
        val answers = mutableListOf<PickResult?>()
        router.showPicker("home", PickerViewModel.Args(), AppleNavigationEdge.HOME_ON_PICK, null) { answers += it }
        router.updateSheet(null)
        assertEquals(listOf<PickResult?>(null), answers)
    }

    @Test fun aHandlerClaimsARequestForItsOwner() {
        val router = BasekitNavigationRouter()
        val token = router.register("home") { _, transaction ->
            transaction.present(BasekitNavigationDecision(BasekitPresentation.FullScreenCover))
            true
        }
        router.showDetail("home", detail(), AppleNavigationEdge.HOME_ON_OPEN_DETAIL, null)
        assertTrue(router.path.isEmpty())
        assertTrue(router.fullScreenCover?.route is BasekitRoute.Detail)
        router.unregister("home", token)
        router.showDetail("home", detail(), AppleNavigationEdge.HOME_ON_OPEN_DETAIL, null)
        assertEquals(1, router.path.size)
    }

    @Test fun platformCustomPresentationsGoToThePresenterAndDismisser() {
        val router = BasekitNavigationRouter { BasekitNavigationDecision(BasekitPresentation.PlatformCustom("toast")) }
        val presented = mutableListOf<BasekitNavigationEntry>()
        val dismissed = mutableListOf<java.util.UUID>()
        router.platformPresenter = { entry, id ->
            assertEquals("toast", id)
            presented += entry
        }
        router.platformDismisser = { dismissed += it }
        router.showDetail("home", detail(), AppleNavigationEdge.HOME_ON_OPEN_DETAIL, null)
        router.close(presented.single().ownerId, null)
        assertEquals(listOf(presented.single().id), dismissed)
    }

    @Test fun poppingThePathAnswersNull() {
        val router = BasekitNavigationRouter()
        val answers = mutableListOf<PickResult?>()
        router.showPicker("home", PickerViewModel.Args(), AppleNavigationEdge.HOME_ON_PICK, null) { answers += it }
        router.reconcilePath(emptyList())
        assertEquals(listOf<PickResult?>(null), answers)
    }

    @Test fun aDeepLinkWithoutAnEdgeReplacesThePath() {
        val router = BasekitNavigationRouter()
        router.showDetail("home", detail(), AppleNavigationEdge.HOME_ON_OPEN_DETAIL, null)
        assertTrue(router.open(PickerViewModel.Args()))
        val route = router.path.single().route as BasekitRoute.Picker
        // Nobody waits for a deep link's answer, so it has a responder that discards it.
        route.responder!!.respond(PickResult(1))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(router.path.isEmpty())
    }
}
