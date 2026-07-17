package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.NavigationDestination
import com.latenighthack.basekit.navigation.NavigatorArgs
import com.latenighthack.basekit.navigation.annotations.Destination
import com.latenighthack.basekit.navigation.annotations.Route
import com.latenighthack.basekit.navigation.annotations.RouteArg
import com.latenighthack.basekit.viewmodel.StatefulViewModel
import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.ViewModelInject
import com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec
import com.latenighthack.basekit.viewmodel.tui.annotations.TuiScreen
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

/**
 * The detail screen for a single feed item. Navigation carries the item [Args.id]; the ViewModel reads
 * that item's data (including a mutable count) from the shared [DemoStore], and its actions persist
 * changes back to the store so they survive re-navigation.
 */
@Destination
@ViewModelSpec
@TuiScreen(DetailViewModel::class)
interface DetailViewModel :
    NavigationDestination<DetailViewModel.Args>,
    ViewModel<DetailViewModel.State> {

    @Route("/detail/{id}")
    class Args : NavigatorArgs() {
        @RouteArg
        var id: String by storedProperty()
    }

    data class State(val id: String, val body: String, val count: Int, val note: String, val flagged: Boolean)

    suspend fun onIncrement()

    suspend fun onReset()

    /** Text-entry mutation: replace this item's note with typed text. */
    suspend fun onSetNote(note: String)

    /** Boolean mutation: flag or unflag this item. */
    suspend fun onSetFlagged(flagged: Boolean)
}

@ViewModelInject
class RealDetailViewModel @Inject constructor(
    private val store: DemoStore,
    @Assisted private val args: DetailViewModel.Args,
) : DetailViewModel, StatefulViewModel<DetailViewModel.State>(store.detail(args.id).toState()) {

    override suspend fun onIncrement() {
        store.incrementCount(args.id)
        update { store.detail(args.id).toState() }
    }

    override suspend fun onReset() {
        store.resetCount(args.id)
        update { store.detail(args.id).toState() }
    }

    override suspend fun onSetNote(note: String) {
        store.setNote(args.id, note)
        update { store.detail(args.id).toState() }
    }

    override suspend fun onSetFlagged(flagged: Boolean) {
        store.setFlagged(args.id, flagged)
        update { store.detail(args.id).toState() }
    }
}

private fun DetailData.toState() = DetailViewModel.State(id, body, count, note, flagged)
