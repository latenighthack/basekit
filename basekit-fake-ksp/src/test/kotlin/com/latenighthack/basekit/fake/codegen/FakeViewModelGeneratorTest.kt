package com.latenighthack.basekit.fake.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeViewModelGeneratorTest {
    private val detail = FakeTarget("Detail", "com.app.DetailNavigationTarget")
    private val picker = FakeTarget("Picker", "com.app.PickerNavigationTarget")

    private val home = FakeSpec(
        specQualifiedName = "com.app.HomeViewModel",
        specSimpleName = "HomeViewModel",
        packageName = "com.app",
        className = "FakeHomeViewModel",
        argsType = "com.app.HomeViewModel.Args",
        stateType = "com.app.HomeViewModel.State",
        initialState = "com.app.FakeValues.homeViewModelState(fakeData.child(\"state\"), fakeArgs)",
        targets = listOf(detail, picker),
        inject = FakeInject(listOf("navigator" to "com.app.HomeNavigator"), listOf("detailTarget = navigator", "pickerTarget = navigator")),
        functions = listOf(
            FakeFunction("onRefresh", suspend = true),
            FakeFunction(
                "onOpenDetail",
                suspend = true,
                navigations = listOf(
                    FakeNavigation(detail, "DetailViewModel", "args(fakeData)", source = "com.app.DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL"),
                ),
            ),
            FakeFunction("onPick", suspend = true, navigations = listOf(FakeNavigation(picker, "PickerViewModel", null, responding = true))),
        ),
        lists = listOf(
            FakeList(
                property = "items",
                type = "kotlinx.coroutines.flow.Flow<com.latenighthack.deltalist.Delta<com.app.FeedItemViewModel>>",
                elementType = "com.app.FeedItemViewModel",
                size = "{data}.size(\"items\", min = 3, max = 8)",
                rows = listOf(FakeEmbedded("com.app.FakeFeedItemViewModel", listOf(detail))),
            ),
        ),
    )

    @Test
    fun aFakeReplacingAnInjectedImplementationCopiesItsConstructor() {
        val source = FakeViewModelGenerator.render(home)
        assertTrue("@com.latenighthack.basekit.fake.GeneratedFake(com.app.HomeViewModel::class)" in source)
        assertTrue("@com.latenighthack.basekit.viewmodel.annotations.ViewModelInject\npublic class FakeHomeViewModel(" in source)
        assertTrue("    private val detailTarget: com.app.DetailNavigationTarget? = null," in source)
        assertTrue("        @me.tatarka.inject.annotations.Assisted navigator: com.app.HomeNavigator," in source)
        assertTrue(") : this(com.latenighthack.basekit.fake.BasekitFakes.data(\"HomeViewModel\"), detailTarget = navigator, pickerTarget = navigator)" in source)
    }

    @Test
    fun aFakeWithNoImplementationIsNotInjected() {
        val source = FakeViewModelGenerator.render(home.copy(inject = null, internal = true))
        assertFalse("ViewModelInject" in source)
        assertFalse("@me.tatarka.inject.annotations.Inject" in source)
        assertTrue("internal class FakeHomeViewModel(" in source)
    }

    @Test
    fun navigatingActionsCallTheirTargetWithItsSource() {
        val source = FakeViewModelGenerator.render(home)
        assertTrue("navigator.navigateToDetail(args(fakeData), com.app.DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL)" in source)
        assertTrue("fakeData.unreachable(\"onOpenDetail\", \"DetailViewModel\")" in source)
        // A responding target's answer is logged.
        assertTrue("fakeData.record(\"onPick\", navigator.navigateToPicker())" in source)
        assertTrue("FakeAction(\"onPick\", com.latenighthack.basekit.fake.FakeAction.Effect.NAVIGATE) { onPick() }" in source)
        assertTrue("FakeAction(\"onRefresh\", com.latenighthack.basekit.fake.FakeAction.Effect.RECORD) { onRefresh() }" in source)
    }

    @Test
    fun anActionWithSeveralTargetsTakesThemInTurn() {
        val source = FakeViewModelGenerator.render(
            home.copy(
                functions = listOf(
                    FakeFunction(
                        "onNext",
                        suspend = true,
                        navigations = listOf(FakeNavigation(detail, "DetailViewModel", null), FakeNavigation(picker, "PickerViewModel", null, responding = true)),
                    ),
                ),
            ),
        )
        assertTrue("private var onNextNavigations = 0" in source)
        assertTrue("when (onNextNavigations++ % 2) {" in source)
        assertTrue("            0 -> {" in source)
        assertTrue("            else -> {" in source)
    }

    @Test
    fun argsTakeSameNamedStateFields() {
        val source = FakeViewModelGenerator.render(
            home.copy(functions = listOf(FakeFunction("onSelected", suspend = true, navigations = listOf(FakeNavigation(detail, "DetailViewModel", "args(fakeData)", argsCopies = listOf("id")))))),
        )
        assertTrue("navigator.navigateToDetail(args(fakeData).apply { id = fakeState.value.id })" in source)
    }

    @Test
    fun boundMutatorsCopyTheirValueIntoState() {
        val source = FakeViewModelGenerator.render(
            home.copy(
                functions = listOf(
                    FakeFunction("setNote", suspend = true, parameters = listOf("value" to "kotlin.String?"), binds = "note"),
                    FakeFunction("onFilter", suspend = true, parameters = listOf("in" to "kotlin.String")),
                ),
            ),
        )
        assertTrue("override suspend fun setNote(value: kotlin.String?) {" in source)
        assertTrue("fakeData.record(\"setNote\", value)" in source)
        assertTrue("fakeState.value = fakeState.value.copy(note = value)" in source)
        // Unbound: only recorded. Keywords are backticked.
        assertTrue("override suspend fun onFilter(`in`: kotlin.String) {" in source)
        assertFalse("copy(`in`" in source)
        // Functions with parameters are not actions.
        assertTrue("override val fakeActions: List<com.latenighthack.basekit.fake.FakeAction> get() = emptyList()" in source)
    }

    @Test
    fun respondingActionsAnswerTheResponder() {
        val source = FakeViewModelGenerator.render(home.copy(responds = true, functions = listOf(FakeFunction("onSelected", suspend = true, responds = true))))
        assertTrue("    private val fakeResponder: com.latenighthack.basekit.fake.FakeResponder? = null," in source)
        assertTrue("fakeResponder?.respond(fakeData.child(\"onSelected\"))" in source)
        assertTrue("Effect.RESPOND" in source)
    }

    @Test
    fun valuesFunctionsReturnTheirValue() {
        val source = FakeViewModelGenerator.render(home.copy(functions = listOf(FakeFunction("label", suspend = false, returnType = "kotlin.String", returnValue = "fakeData.text(\"label\")"))))
        assertTrue("override fun label(): kotlin.String {" in source)
        assertTrue("        return fakeData.text(\"label\")" in source)
    }

    @Test
    fun flatListsHandTheirRowsTheTargetsTheyNeed() {
        val source = FakeViewModelGenerator.render(home)
        assertTrue("fakeData.list(\"items\", fakeData.size(\"items\", min = 3, max = 8)) { row -> com.app.FakeFeedItemViewModel(row, detailTarget = detailTarget) }" in source)
        assertTrue("com.latenighthack.deltalist.mutableDeltaListOf<com.app.FeedItemViewModel>(itemsRows)" in source)
        assertTrue("override val fakeEmbedded: List<com.latenighthack.basekit.fake.FakeViewModel> get() = itemsRows.filterIsInstance" in source)
    }

    @Test
    fun polymorphicListsChooseARowTypeByWeight() {
        val list = FakeList(
            property = "options",
            type = "kotlinx.coroutines.flow.Flow<com.latenighthack.deltalist.Delta<com.app.Row>>",
            elementType = "com.app.Row",
            size = "{data}.size(\"options\")",
            rows = listOf(FakeEmbedded("com.app.FakeOption", responds = true), FakeEmbedded("com.app.FakeEmpty")),
            weights = "intArrayOf(1, 0)",
        )
        val source = FakeViewModelGenerator.render(home.copy(lists = listOf(list), responds = true))
        assertTrue("when (row.choose(\"type\", intArrayOf(1, 0))) { 0 -> com.app.FakeOption(row, fakeResponder = fakeResponder); else -> com.app.FakeEmpty(row) }" in source)
    }

    @Test
    fun sectionedListsMakeRowsPerSection() {
        val list = FakeList(
            property = "shelves",
            type = "com.latenighthack.deltalist.SectionedDeltaList<com.app.Shelf, com.app.Row>",
            elementType = "com.app.Row",
            size = "{data}.size(\"shelves\")",
            rows = listOf(FakeEmbedded("com.app.FakeRow")),
            sections = listOf("com.app.Shelf.TOP", "com.app.Shelf.BOTTOM"),
            headerType = "com.app.Shelf",
        )
        val source = FakeViewModelGenerator.render(home.copy(lists = listOf(list)))
        assertTrue("listOf<com.app.Shelf>(com.app.Shelf.TOP, com.app.Shelf.BOTTOM).mapIndexed { index, header ->" in source)
        assertTrue("header to section.list(\"rows\", section.size(\"shelves\")) { row -> com.app.FakeRow(row) }" in source)
        assertTrue("com.latenighthack.deltalist.operators.sectionedDeltaList(" in source)
        assertTrue("private val shelvesRows: List<com.app.Row> get() = shelvesSections.flatMap { it.second }" in source)
    }

    @Test
    fun childrenAndOtherPropertiesAreImplemented() {
        val source = FakeViewModelGenerator.render(
            home.copy(
                children = listOf(FakeChild("avatar", "com.app.AvatarViewModel", FakeEmbedded("com.app.FakeAvatarViewModel"))),
                properties = listOf(FakeProperty("identity", "kotlin.String", false, "fakeData.text(\"identity\")"), FakeProperty("draft", "kotlin.String", true, "\"\"")),
            ),
        )
        assertTrue("override val avatar: com.app.AvatarViewModel = com.app.FakeAvatarViewModel(fakeData.child(\"avatar\"))" in source)
        assertTrue("override val identity: kotlin.String = fakeData.text(\"identity\")" in source)
        assertTrue("override var draft: kotlin.String = \"\"" in source)
        assertTrue("listOfNotNull(avatar as? com.latenighthack.basekit.fake.FakeViewModel)" in source)
    }

    @Test
    fun aRedirectingFakeRedirectsWhileItsStateIsCollected() {
        val home = FakeTarget("Home", "com.app.HomeNavigationTarget")
        val splash = this.home.copy(
            functions = emptyList(),
            lists = emptyList(),
            targets = listOf(detail, home),
            redirects = listOf(
                FakeNavigation(home, "HomeViewModel", "args(fakeData)"),
                FakeNavigation(detail, "DetailViewModel", null, source = "com.app.DetailNavigationTarget.DetailSource.SPLASH_REDIRECT"),
            ),
        )
        val first = FakeViewModelGenerator.render(splash)
        assertTrue("        get() = kotlinx.coroutines.flow.flow {\n            fakeRedirect()\n            fakeState.collect(this)" in first)
        assertTrue("    override fun fakeRedirect() {\n        if (fakeRedirected) return\n        fakeRedirected = true\n        val navigator = homeTarget" in first)
        assertTrue("com.latenighthack.basekit.fake.fakeRedirecting(navigator) { navigator.navigateToHome(args(fakeData)) }" in first)
        assertFalse("navigateToDetail" in first)
        assertFalse("FakeAction(\"redirect\"" in first)

        val chosen = FakeViewModelGenerator.render(splash.copy(redirectChoice = "when (fakeData.dataSet.firstOf(\"empty\")) { \"empty\" -> -1; else -> 1 }"))
        assertTrue("        when (when (fakeData.dataSet.firstOf(\"empty\")) { \"empty\" -> -1; else -> 1 }) {" in chosen)
        assertTrue("navigator.navigateToDetail(com.app.DetailNavigationTarget.DetailSource.SPLASH_REDIRECT)" in chosen)
        assertTrue("            else -> fakeData.record(\"redirect\", \"stays\")" in chosen)

        // A fake without redirects reads its state directly and keeps the default fakeRedirect.
        val plain = FakeViewModelGenerator.render(this.home)
        assertTrue("get() = fakeState\n" in plain)
        assertFalse("fakeRedirect" in plain)
    }

    @Test
    fun variantsSelectByDataSet() {
        assertEquals("a", variants("data", "a", emptyList()))
        assertEquals(
            "when (data.dataSet.firstOf(\"large\", \"empty\")) { \"large\" -> b; \"empty\" -> c; else -> a }",
            variants("data", "a", listOf("large" to "b", "empty" to "c")),
        )
        assertEquals("\"a\\\"b\\\$c\"", quote("a\"b\$c"))
    }
}
