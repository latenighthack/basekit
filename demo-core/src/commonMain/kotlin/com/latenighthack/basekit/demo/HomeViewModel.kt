package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.NavigationDestination
import com.latenighthack.basekit.navigation.NavigatorArgs
import com.latenighthack.basekit.navigation.annotations.Destination
import com.latenighthack.basekit.navigation.annotations.NavigateTo
import com.latenighthack.basekit.navigation.annotations.Route
import com.latenighthack.basekit.viewmodel.StatefulViewModel
import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.ViewModelInject
import com.latenighthack.basekit.viewmodel.annotations.ViewModelList
import com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec
import com.latenighthack.basekit.viewmodel.tui.annotations.TuiScreen
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.mutableDeltaListOf
import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

/**
 * The home screen: one interface that is at once the navigation destination (`@Destination`), the view
 * model (`@ViewModelSpec`), and its own TUI screen (`@TuiScreen`). It shows a list of feed items and
 * navigates to detail (per row, or the two banner actions) and to the picker.
 */
@Destination
@ViewModelSpec
@TuiScreen(HomeViewModel::class)
interface HomeViewModel :
    NavigationDestination<HomeViewModel.Args>,
    ViewModel<HomeViewModel.State> {

    @Route("/")
    class Args : NavigatorArgs()

    data class State(val title: String, val subtitle: String?)

    @ViewModelList(FeedItemViewModel::class)
    val items: Flow<Delta<FeedItemViewModel>>

    /** Append a new store-seeded row. */
    suspend fun onRefresh()

    // Two call sites to detail -> the generated DetailSource enum distinguishes them.
    @NavigateTo(DetailViewModel::class)
    suspend fun onOpenDetail()

    @NavigateTo(DetailViewModel::class)
    suspend fun onOpenDetailFromBanner()

    /** Open the picker and await the choice, then add it to the feed. */
    @NavigateTo(PickerViewModel::class)
    suspend fun onPick()
}

/** A row in [HomeViewModel]'s list. Selecting it navigates to that item's detail. */
@ViewModelSpec
interface FeedItemViewModel : ViewModel<FeedItemViewModel.State> {
    data class State(val title: String, val subtitle: String, val id: String)

    suspend fun onSelected()
}

class RealFeedItemViewModel(
    data: FeedItemData,
    private val navigator: HomeNavigator,
) : FeedItemViewModel, StatefulViewModel<FeedItemViewModel.State>(
    FeedItemViewModel.State(data.title, data.subtitle, data.id),
) {
    override suspend fun onSelected() = withState { state ->
        navigator.navigateToDetail(
            DetailViewModel.Args().apply { id = state.id },
            DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL,
        )
    }
}

/**
 * Built through the kotlin-inject graph: [store] resolves from [DemoModule] and the per-screen
 * [navigator] is supplied at the call site via `@Assisted`. It seeds its rows from the store and hands
 * the navigator to each one.
 */
@ViewModelInject
class RealHomeViewModel @Inject constructor(
    private val store: DemoStore,
    @Assisted private val navigator: HomeNavigator,
) : HomeViewModel, StatefulViewModel<HomeViewModel.State>(HomeViewModel.State(title = "Feed", subtitle = null)) {

    private val itemsList = mutableDeltaListOf<FeedItemViewModel>(
        store.feedItems().map { RealFeedItemViewModel(it, navigator) },
    )

    override val items: Flow<Delta<FeedItemViewModel>> get() = itemsList

    override suspend fun onRefresh() {
        itemsList.append(RealFeedItemViewModel(store.addSeededItem(), navigator))
    }

    override suspend fun onOpenDetail() {
        navigator.navigateToDetail(argsFor(store.feedItems().first()), DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL)
    }

    override suspend fun onOpenDetailFromBanner() {
        navigator.navigateToDetail(argsFor(store.feedItems().last()), DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL_FROM_BANNER)
    }

    override suspend fun onPick() {
        val result = navigator.navigateToPicker(PickerViewModel.Args()) ?: return
        val option = store.pickerOptions().firstOrNull { it.id == result.selectedId } ?: return
        itemsList.append(RealFeedItemViewModel(store.addOptionItem(option), navigator))
    }

    private fun argsFor(item: FeedItemData) = DetailViewModel.Args().apply { id = item.id }
}
