package com.latenighthack.basekit.demo

@com.latenighthack.basekit.fake.GeneratedFake(com.latenighthack.basekit.demo.HomeViewModel::class)
@com.latenighthack.basekit.viewmodel.annotations.ViewModelInject
public class FakeHomeViewModel(
    override val fakeData: com.latenighthack.basekit.fake.FakeData,
    fakeArgs: com.latenighthack.basekit.demo.HomeViewModel.Args? = null,
    private val detailTarget: com.latenighthack.basekit.demo.DetailNavigationTarget? = null,
    private val pickerTarget: com.latenighthack.basekit.demo.PickerNavigationTarget? = null,
) : com.latenighthack.basekit.demo.HomeViewModel, com.latenighthack.basekit.fake.FakeViewModel {

    @me.tatarka.inject.annotations.Inject
    public constructor(
        @me.tatarka.inject.annotations.Assisted navigator: com.latenighthack.basekit.demo.HomeNavigator,
    ) : this(com.latenighthack.basekit.fake.BasekitFakes.data("HomeViewModel"), detailTarget = navigator, pickerTarget = navigator)

    override val initialState: com.latenighthack.basekit.demo.HomeViewModel.State = com.latenighthack.basekit.demo.FakeValues.homeViewModelState(fakeData.child("state"), fakeArgs)

    private val fakeState: kotlinx.coroutines.flow.MutableStateFlow<com.latenighthack.basekit.demo.HomeViewModel.State> =
        kotlinx.coroutines.flow.MutableStateFlow(initialState)

    override val state: kotlinx.coroutines.flow.Flow<com.latenighthack.basekit.demo.HomeViewModel.State> get() = fakeState

    private val itemsRows: List<com.latenighthack.basekit.demo.FeedItemViewModel> =
        fakeData.list("items", when (fakeData.dataSet.firstOf("large")) { "large" -> fakeData.size("items", min = 300, max = 300, exact = true); else -> fakeData.size("items", min = 3, max = 8) }) { row -> com.latenighthack.basekit.demo.FakeFeedItemViewModel(row, detailTarget = detailTarget) }

    override val items: kotlinx.coroutines.flow.Flow<com.latenighthack.deltalist.Delta<com.latenighthack.basekit.demo.FeedItemViewModel>> =
        com.latenighthack.deltalist.mutableDeltaListOf<com.latenighthack.basekit.demo.FeedItemViewModel>(itemsRows)

    override suspend fun onRefresh() {
        fakeData.record("onRefresh")
    }

    override suspend fun onOpenDetail() {
        fakeData.record("onOpenDetail")
        val navigator = detailTarget
        if (navigator == null) {
            fakeData.unreachable("onOpenDetail", "DetailViewModel")
        } else {
            navigator.navigateToDetail(com.latenighthack.basekit.demo.FakeValues.detailViewModelArgs(fakeData.child("onOpenDetail")), com.latenighthack.basekit.demo.DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL)
        }
    }

    override suspend fun onOpenDetailFromBanner() {
        fakeData.record("onOpenDetailFromBanner")
        val navigator = detailTarget
        if (navigator == null) {
            fakeData.unreachable("onOpenDetailFromBanner", "DetailViewModel")
        } else {
            navigator.navigateToDetail(com.latenighthack.basekit.demo.FakeValues.detailViewModelArgs(fakeData.child("onOpenDetailFromBanner")), com.latenighthack.basekit.demo.DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL_FROM_BANNER)
        }
    }

    override suspend fun onPick() {
        fakeData.record("onPick")
        val navigator = pickerTarget
        if (navigator == null) {
            fakeData.unreachable("onPick", "PickerViewModel")
        } else {
            fakeData.record("onPick", navigator.navigateToPicker(com.latenighthack.basekit.demo.FakeValues.pickerViewModelArgs(fakeData.child("onPick"))))
        }
    }

    override val fakeActions: List<com.latenighthack.basekit.fake.FakeAction>
        get() = listOf(
            com.latenighthack.basekit.fake.FakeAction("onRefresh", com.latenighthack.basekit.fake.FakeAction.Effect.RECORD) { onRefresh() },
            com.latenighthack.basekit.fake.FakeAction("onOpenDetail", com.latenighthack.basekit.fake.FakeAction.Effect.NAVIGATE) { onOpenDetail() },
            com.latenighthack.basekit.fake.FakeAction("onOpenDetailFromBanner", com.latenighthack.basekit.fake.FakeAction.Effect.NAVIGATE) { onOpenDetailFromBanner() },
            com.latenighthack.basekit.fake.FakeAction("onPick", com.latenighthack.basekit.fake.FakeAction.Effect.NAVIGATE) { onPick() },
        )

    override val fakeEmbedded: List<com.latenighthack.basekit.fake.FakeViewModel> get() = itemsRows.filterIsInstance<com.latenighthack.basekit.fake.FakeViewModel>()
}
