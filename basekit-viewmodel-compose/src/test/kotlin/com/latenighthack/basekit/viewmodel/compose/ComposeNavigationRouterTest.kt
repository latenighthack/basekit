package com.latenighthack.basekit.viewmodel.compose

import android.os.Looper
import com.latenighthack.basekit.demo.NavigationEdge
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
        router.showDetail("home", detail(), NavigationEdge.HOME_ON_OPEN_DETAIL, null)
        router.showDetail("home", detail(), NavigationEdge.HOME_ON_OPEN_DETAIL, null)
        assertEquals(2, router.path.size)
        // An entry closes everything pushed over it.
        router.close(router.path[0].ownerId, null)
        assertTrue(router.path.isEmpty())
    }

    @Test fun closingAnOwnerItDoesNotPresentClosesItInTheParent() {
        val parent = BasekitNavigationRouter()
        parent.showDetail("home", detail(), NavigationEdge.HOME_ON_OPEN_DETAIL, null)
        val child = BasekitNavigationRouter(parent)
        child.close(parent.path.single().ownerId, null)
        assertTrue(parent.path.isEmpty())
    }

    @Test fun aRespondingDestinationAnswersOnceAndCloses() {
        val router = BasekitNavigationRouter { BasekitNavigationDecision(BasekitPresentation.Sheet) }
        val answers = mutableListOf<PickResult?>()
        router.showPicker("home", PickerViewModel.Args(), NavigationEdge.HOME_ON_PICK, null) { answers += it }
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
        router.showPicker("home", PickerViewModel.Args(), NavigationEdge.HOME_ON_PICK, null) { answers += it }
        router.updateSheet(null)
        assertEquals(listOf<PickResult?>(null), answers)
    }

    @Test fun aHandlerClaimsARequestForItsOwner() {
        val router = BasekitNavigationRouter()
        val token = router.register("home") { _, transaction ->
            transaction.present(BasekitNavigationDecision(BasekitPresentation.FullScreenCover))
            true
        }
        router.showDetail("home", detail(), NavigationEdge.HOME_ON_OPEN_DETAIL, null)
        assertTrue(router.path.isEmpty())
        assertTrue(router.fullScreenCover?.route is BasekitRoute.Detail)
        router.unregister("home", token)
        router.showDetail("home", detail(), NavigationEdge.HOME_ON_OPEN_DETAIL, null)
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
        router.showDetail("home", detail(), NavigationEdge.HOME_ON_OPEN_DETAIL, null)
        router.close(presented.single().ownerId, null)
        assertEquals(listOf(presented.single().id), dismissed)
    }

    @Test fun poppingThePathAnswersNull() {
        val router = BasekitNavigationRouter()
        val answers = mutableListOf<PickResult?>()
        router.showPicker("home", PickerViewModel.Args(), NavigationEdge.HOME_ON_PICK, null) { answers += it }
        router.reconcilePath(emptyList())
        assertEquals(listOf<PickResult?>(null), answers)
    }

    @Test fun aDeepLinkWithoutAnEdgeReplacesThePath() {
        val router = BasekitNavigationRouter()
        router.showDetail("home", detail(), NavigationEdge.HOME_ON_OPEN_DETAIL, null)
        assertTrue(router.open(PickerViewModel.Args()))
        val route = router.path.single().route as BasekitRoute.Picker
        // Nobody waits for a deep link's answer, so it has a responder that discards it.
        route.responder!!.respond(PickResult(1))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(router.path.isEmpty())
    }

    // Stacked sheets: a sheet's view model reports to the router made for it, so what it opens is
    // presented over it. The policy, not the router, decides that a pick is a sheet.
    private fun sheetsForPicks() = BasekitNavigationRouter { request ->
        if (request.edge == NavigationEdge.HOME_ON_PICK) BasekitNavigationDecision(BasekitPresentation.Sheet) else BasekitNavigationDecision()
    }

    private fun BasekitNavigationRouter.pick(owner: String, answers: MutableList<PickResult?>) =
        showPicker(owner, PickerViewModel.Args(), NavigationEdge.HOME_ON_PICK, null) { answers += it }

    @Test fun aSheetsRouterStacksTheSheetItOpens() {
        val router = sheetsForPicks()
        val first = mutableListOf<PickResult?>(); val second = mutableListOf<PickResult?>()
        router.pick("home", first)
        val firstSheet = router.sheet!!
        val firstHost = router.host(firstSheet)
        firstHost.pick(firstSheet.ownerId, second)
        assertEquals(firstSheet, router.sheet)
        assertTrue(firstHost.sheet?.route is BasekitRoute.Picker)
        assertTrue(first.isEmpty())
        assertTrue(second.isEmpty())
    }

    @Test fun theTopSheetAnsweringClosesOnlyIt() {
        val router = sheetsForPicks()
        val first = mutableListOf<PickResult?>(); val second = mutableListOf<PickResult?>()
        router.pick("home", first)
        val firstHost = router.host(router.sheet!!)
        firstHost.pick(router.sheet!!.ownerId, second)
        (firstHost.sheet!!.route as BasekitRoute.Picker).responder!!.respond(PickResult(2))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf<PickResult?>(PickResult(2)), second)
        assertNull(firstHost.sheet)
        assertTrue(router.sheet?.route is BasekitRoute.Picker)
        assertTrue(first.isEmpty())
    }

    @Test fun aSheetClosingItselfClosesTheSheetsOverIt() {
        val router = sheetsForPicks()
        val first = mutableListOf<PickResult?>(); val second = mutableListOf<PickResult?>()
        router.pick("home", first)
        val firstSheet = router.sheet!!
        val firstHost = router.host(firstSheet)
        firstHost.pick(firstSheet.ownerId, second)
        // The first sheet's navigator closes through its own router, which passes it to the presenter.
        firstHost.close(firstSheet.ownerId, null)
        assertNull(router.sheet)
        assertNull(firstHost.sheet)
        assertEquals(listOf<PickResult?>(null), first)
        assertEquals(listOf<PickResult?>(null), second)
    }

    @Test fun aSheetFromALowerRouterReplacesEverythingAboveIt() {
        val router = sheetsForPicks()
        val first = mutableListOf<PickResult?>(); val second = mutableListOf<PickResult?>(); val third = mutableListOf<PickResult?>()
        router.pick("home", first)
        router.host(router.sheet!!).pick(router.sheet!!.ownerId, second)
        router.pick("home", third)
        assertEquals(listOf<PickResult?>(null), first)
        assertEquals(listOf<PickResult?>(null), second)
        assertTrue(third.isEmpty())
        assertNull(router.host(router.sheet!!).sheet)
    }

    @Test fun hostIsTheRouterAnEntrysNavigatorReportsTo() {
        val router = sheetsForPicks()
        router.showDetail("home", detail(), NavigationEdge.HOME_ON_OPEN_DETAIL, null)
        assertTrue(router.host(router.path.single()) === router)
        router.pick("home", mutableListOf())
        val sheet = router.sheet!!
        val sheetHost = router.host(sheet)
        assertTrue(sheetHost !== router)
        assertTrue(router.host(sheet) === sheetHost)
        // The sheet's router uses the app's policy: a detail opened from the sheet is pushed in it.
        sheetHost.showDetail(sheet.ownerId, detail(), NavigationEdge.HOME_ON_OPEN_DETAIL, null)
        val pushed = sheetHost.path.single()
        assertTrue(router.host(pushed) === sheetHost)
        sheetHost.pick(sheet.ownerId, mutableListOf())
        val nested = sheetHost.sheet!!
        assertTrue(router.host(nested) === sheetHost.host(nested))
        assertTrue(router.host(nested) !== sheetHost)
    }

    @Test fun aSheetsRouterPresentsCustomEntriesThroughThePresenterItWasGiven() {
        val router = BasekitNavigationRouter { request ->
            when (request.edge) {
                NavigationEdge.HOME_ON_PICK -> BasekitNavigationDecision(BasekitPresentation.Sheet)
                else -> BasekitNavigationDecision(BasekitPresentation.PlatformCustom("toast"))
            }
        }
        val presented = mutableListOf<BasekitNavigationEntry>()
        val dismissed = mutableListOf<java.util.UUID>()
        router.platformPresenter = { entry, _ -> presented += entry }
        router.platformDismisser = { dismissed += it }
        router.pick("home", mutableListOf())
        val sheet = router.sheet!!
        router.host(sheet).showDetail(sheet.ownerId, detail(), NavigationEdge.HOME_ON_OPEN_DETAIL, null)
        val toast = presented.single()
        assertTrue(router.host(toast) !== router.host(sheet))
        router.updateSheet(null)
        assertEquals(listOf(toast.id), dismissed)
    }

    @Test fun aSheetsRouterUsesThePresenterSetAfterItWasMade() {
        val router = BasekitNavigationRouter { request ->
            when (request.edge) {
                NavigationEdge.HOME_ON_PICK -> BasekitNavigationDecision(BasekitPresentation.Sheet)
                else -> BasekitNavigationDecision(BasekitPresentation.PlatformCustom("toast"))
            }
        }
        router.pick("home", mutableListOf())
        val sheet = router.sheet!!
        val presented = mutableListOf<BasekitNavigationEntry>()
        router.platformPresenter = { entry, _ -> presented += entry }
        router.host(sheet).showDetail(sheet.ownerId, detail(), NavigationEdge.HOME_ON_OPEN_DETAIL, null)
        assertEquals(1, presented.size)
    }

    @Test fun aDismissedSheetsLateNavigationAnswersNullAndShowsNothing() {
        val router = sheetsForPicks()
        router.pick("home", mutableListOf())
        val sheet = router.sheet!!
        val sheetHost = router.host(sheet)
        router.updateSheet(null)
        val late = mutableListOf<PickResult?>()
        sheetHost.pick(sheet.ownerId, late)
        sheetHost.showDetail(sheet.ownerId, detail(), NavigationEdge.HOME_ON_OPEN_DETAIL, null)
        assertEquals(listOf<PickResult?>(null), late)
        assertNull(sheetHost.sheet)
        assertTrue(sheetHost.path.isEmpty())
    }
}
