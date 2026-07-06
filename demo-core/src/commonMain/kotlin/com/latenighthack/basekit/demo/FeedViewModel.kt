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

    // Selecting a row navigates to DetailScreen; inferred since HomeScreen has a single outbound edge.
    @ViewModelList(FeedItemViewModel::class)
    val items: Flow<Delta<FeedItemViewModel>>

    suspend fun onRefresh()
}

class RealFeedItemViewModel(title: String, subtitle: String) :
    FeedItemViewModel,
    StatefulViewModel<FeedItemViewModel.State>(FeedItemViewModel.State(title, subtitle, id = title)) {

    override suspend fun onSelected() = withState { /* no-op selection in the demo */ }
}

class RealFeedViewModel :
    FeedViewModel,
    StatefulViewModel<FeedViewModel.State>(FeedViewModel.State(title = "Feed")) {

    private val itemsList = mutableDeltaListOf<FeedItemViewModel>(
        listOf(
            RealFeedItemViewModel("First", "one"),
            RealFeedItemViewModel("Second", "two"),
        )
    )

    override val items: Flow<Delta<FeedItemViewModel>> get() = itemsList

    private var appended = 0

    override suspend fun onRefresh() {
        appended += 1
        itemsList.append(RealFeedItemViewModel("Item $appended", "appended"))
    }
}
