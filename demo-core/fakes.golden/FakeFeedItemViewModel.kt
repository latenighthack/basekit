package com.latenighthack.basekit.demo

@com.latenighthack.basekit.fake.GeneratedFake(com.latenighthack.basekit.demo.FeedItemViewModel::class)
public class FakeFeedItemViewModel(
    override val fakeData: com.latenighthack.basekit.fake.FakeData,
    private val detailTarget: com.latenighthack.basekit.demo.DetailNavigationTarget? = null,
) : com.latenighthack.basekit.demo.FeedItemViewModel, com.latenighthack.basekit.fake.FakeViewModel {

    override val initialState: com.latenighthack.basekit.demo.FeedItemViewModel.State = com.latenighthack.basekit.demo.FakeValues.feedItemViewModelState(fakeData.child("state"))

    private val fakeState: kotlinx.coroutines.flow.MutableStateFlow<com.latenighthack.basekit.demo.FeedItemViewModel.State> =
        kotlinx.coroutines.flow.MutableStateFlow(initialState)

    override val state: kotlinx.coroutines.flow.Flow<com.latenighthack.basekit.demo.FeedItemViewModel.State> get() = fakeState

    override suspend fun onSelected() {
        fakeData.record("onSelected")
        val navigator = detailTarget
        if (navigator == null) {
            fakeData.unreachable("onSelected", "DetailViewModel")
        } else {
            navigator.navigateToDetail(com.latenighthack.basekit.demo.FakeValues.detailViewModelArgs(fakeData.child("onSelected")).apply { id = fakeState.value.id }, com.latenighthack.basekit.demo.DetailNavigationTarget.DetailSource.FEED_ITEM_ON_SELECTED)
        }
    }

    override val fakeActions: List<com.latenighthack.basekit.fake.FakeAction>
        get() = listOf(
            com.latenighthack.basekit.fake.FakeAction("onSelected", com.latenighthack.basekit.fake.FakeAction.Effect.NAVIGATE) { onSelected() },
        )

    override val fakeEmbedded: List<com.latenighthack.basekit.fake.FakeViewModel> get() = emptyList()
}
