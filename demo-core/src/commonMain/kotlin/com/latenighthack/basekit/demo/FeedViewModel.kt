package com.latenighthack.basekit.demo

import com.latenighthack.basekit.viewmodel.StatefulViewModel
import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec
import com.latenighthack.basekit.viewmodel.annotations.ViewModelInject
import com.latenighthack.basekit.viewmodel.annotations.ViewModelList
import com.latenighthack.basekit.viewmodel.tui.annotations.TuiScreen
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.mutableDeltaListOf
import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

/** A child ViewModel used as a row in [FeedViewModel]'s list. */
@ViewModelSpec
interface FeedItemViewModel : ViewModel<FeedItemViewModel.State> {
    data class State(val title: String, val subtitle: String, val id: String)

    suspend fun onSelected()
}

/**
 * A list-bearing ViewModel: [items] is a deltalist stream of child ViewModels. Exercises the list
 * binding path (RecyclerView / UICollectionView / React list) of the generated wrappers.
 */
@ViewModelSpec
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

/**
 * Built through the kotlin-inject graph: `@ViewModelInject` generates its interface binding, [repo] is
 * resolved from [DemoModule], and the per-screen [navigator] is supplied at the call site via
 * `@Assisted` (the component passes the screen's generated navigator). It hands the navigator to each
 * row it creates.
 */
@ViewModelInject
class RealFeedViewModel @Inject constructor(
    private val repo: CounterRepository,
    @Assisted private val navigator: HomeNavigator,
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
        itemsList.append(RealFeedItemViewModel("Item $appended", "seed ${repo.initialCount()}", navigator))
    }
}
