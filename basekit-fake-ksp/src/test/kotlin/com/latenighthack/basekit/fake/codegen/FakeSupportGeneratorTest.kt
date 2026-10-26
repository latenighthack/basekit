package com.latenighthack.basekit.fake.codegen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeSupportGeneratorTest {
    private val detail = FakeTarget("Detail", "com.app.DetailNavigationTarget")
    private val home = FakeDestination(
        navCap = "Home",
        specType = "com.app.HomeViewModel",
        specSimpleName = "HomeViewModel",
        fakeClass = "com.app.FakeHomeViewModel",
        argsType = "com.app.HomeViewModel.Args",
        argsBuilder = "com.app.FakeValues.homeViewModelArgs",
        targets = listOf(detail),
    )
    private val detailDestination = FakeDestination(
        navCap = "Detail",
        specType = "com.app.DetailViewModel",
        specSimpleName = "DetailViewModel",
        fakeClass = "com.app.FakeDetailViewModel",
        argsType = "com.app.DetailViewModel.Args",
        argsBuilder = "com.app.FakeValues.detailViewModelArgs",
        navigatedTo = true,
        hasSource = true,
    )
    private val picker = FakeDestination(
        navCap = "Picker",
        specType = "com.app.PickerViewModel",
        specSimpleName = "PickerViewModel",
        fakeClass = "com.app.FakePickerViewModel",
        responds = true,
        responseType = "com.app.PickResult",
        responseValue = "com.app.FakeValues.pickResult(data.child(\"response\"))",
        navigatedTo = true,
    )

    @Test
    fun theNavigatorImplementsEveryNavigatorAndOpensFakes() {
        val source = FakeSupportGenerator.renderNavigator("com.app", listOf(home, detailDestination, picker), listOf("HomeNavigator"), hasClose = true)
        assertTrue(") : HomeNavigator, DetailNavigationTarget, PickerNavigationTarget, CloseNavigationTarget, com.latenighthack.basekit.fake.FakeRedirector {" in source)
        // Opening a screen lets it redirect, and a redirect replaces the top of the stack.
        assertTrue("        stack.root(\"HomeViewModel\", viewModel)\n        viewModel.fakeRedirect()" in source)
        assertTrue("        stack.push(\"DetailViewModel\", viewModel)\n        viewModel.fakeRedirect()" in source)
        assertTrue("    override fun redirecting(block: () -> Unit) {\n        stack.replacing(block)" in source)
        assertTrue("public fun startHome(args: com.app.HomeViewModel.Args = FakeArgs.home()): com.app.HomeViewModel {" in source)
        assertTrue("com.app.FakeHomeViewModel(com.latenighthack.basekit.fake.BasekitFakes.data(\"HomeViewModel\"), fakeArgs = args, detailTarget = this)" in source)
        assertTrue("override fun navigateToDetail(args: com.app.DetailViewModel.Args, source: DetailNavigationTarget.DetailSource, context: Any?) {" in source)
        assertTrue("public fun startPicker(): com.app.PickerViewModel {" in source)
        assertTrue("override suspend fun navigateToPicker(context: Any?): com.app.PickResult? =" in source)
        assertTrue("stack.pushForResult<com.app.PickResult>(\"PickerViewModel\") { responder ->" in source)
        assertTrue("fakeResponder = com.latenighthack.basekit.fake.FakeResponder { data -> responder.respond(com.app.FakeValues.pickResult(data.child(\"response\"))) }" in source)
        assertTrue("override fun close(context: Any?) {" in source)
    }

    @Test
    fun aGraphWithoutEdgesHasNoNavigatorTypes() {
        val source = FakeSupportGenerator.renderNavigator("com.app", listOf(home.copy(targets = emptyList())), emptyList(), hasClose = false)
        assertTrue("public class FakeNavigator(\n    public val stack: com.latenighthack.basekit.fake.FakeStack = com.latenighthack.basekit.fake.FakeStack(),\n) : com.latenighthack.basekit.fake.FakeRedirector {" in source)
        assertFalse("close(" in source)
    }

    @Test
    fun argsAreFilledPerDestination() {
        val source = FakeSupportGenerator.renderArgs("com.app", listOf(home, picker))
        assertTrue("public fun home(" in source)
        assertTrue("fakeData: com.latenighthack.basekit.fake.FakeData = com.latenighthack.basekit.fake.BasekitFakes.data(\"HomeViewModel.args\")," in source)
        assertTrue("): com.app.HomeViewModel.Args = com.app.FakeValues.homeViewModelArgs(fakeData)" in source)
        assertFalse("fun picker(" in source)
    }

    @Test
    fun valuesImportProvidersAndIndentFunctions() {
        val source = FakeSupportGenerator.renderValues("com.app", listOf("com.app.instant as provider0"), listOf("fun a(data: FakeData): A = A(\n    x = 1,\n)"))
        assertTrue("import com.app.instant as provider0" in source)
        assertTrue("internal object FakeValues {\n    fun a(data: FakeData): A = A(\n        x = 1,\n    )\n}" in source)
    }
}
