package com.latenighthack.basekit.demo

import com.latenighthack.basekit.viewmodel.StatefulViewModel
import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.ViewModel as ViewModelAnnotation
import com.latenighthack.basekit.viewmodel.annotations.ViewModelList
import com.latenighthack.basekit.viewmodel.tui.annotations.TuiScreen
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.mutableDeltaListOf
import kotlinx.coroutines.flow.Flow

/** A child ViewModel used as a row in [FeedViewModel]'s list. */
@ViewModelAnnotation
interface FeedItemViewModel : ViewModel<FeedItemViewModel.State> {
    data class State(val title: String, val subtitle: String, val id: String)

    suspend fun onSelected()
}

/**
 * A list-bearing ViewModel: [items] is a deltalist stream of child ViewModels. Exercises the list
 * binding path (RecyclerView / UICollectionView / React list) of the generated wrappers.
 */
@ViewModelAnnotation
@TuiScreen(HomeScreen::class)
interface FeedViewModel : ViewModel<FeedViewModel.State> {
    data class State(val title: String)

    @ViewModelList(FeedItemViewModel::class)
    val items: Flow<Delta<FeedItemViewModel>>

    suspend fun onRefresh()
}

// Each row is injected with the navigator for everywhere HomeScreen can go. Selecting it makes an
// explicit navigateTo call — the TUI's generated navigator turns that into a screen push.
class RealFeedItemViewModel(
    title: String,
    subtitle: String,
    private val navigator: HomeNavigator,
) :
    FeedItemViewModel,
    StatefulViewModel<FeedItemViewModel.State>(FeedItemViewModel.State(title, subtitle, id = title)) {

    override suspend fun onSelected() = withState { state ->
        navigator.navigateToDetail(
            DetailScreen.Args().apply { id = state.id },
            DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL,
        )
    }
}

// The screen ViewModel receives the navigator and hands it to each row it creates.
class RealFeedViewModel(
    private val navigator: HomeNavigator,
) :
    FeedViewModel,
    StatefulViewModel<FeedViewModel.State>(FeedViewModel.State(title = "Feed")) {

    private val itemsList = mutableDeltaListOf<FeedItemViewModel>(
        listOf(
            RealFeedItemViewModel("First", "one", navigator),
            RealFeedItemViewModel("Second", "two", navigator),
        )
    )

    override val items: Flow<Delta<FeedItemViewModel>> get() = itemsList

    private var appended = 0

    override suspend fun onRefresh() {
        appended += 1
        itemsList.append(RealFeedItemViewModel("Item $appended", "appended", navigator))
    }
}
