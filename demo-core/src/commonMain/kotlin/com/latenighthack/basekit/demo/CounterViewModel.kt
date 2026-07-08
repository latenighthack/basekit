package com.latenighthack.basekit.demo

import com.latenighthack.basekit.viewmodel.StatefulViewModel
import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec
import com.latenighthack.basekit.viewmodel.annotations.ViewModelInject
import com.latenighthack.basekit.viewmodel.tui.annotations.TuiScreen
import me.tatarka.inject.annotations.Inject

/**
 * A plain (non-list) ViewModel: state with two fields and two zero-arg suspend actions. Exercises the
 * state-binding path of every generated platform wrapper.
 */
@ViewModelSpec
@TuiScreen(DetailScreen::class)
interface CounterViewModel : ViewModel<CounterViewModel.State> {
    data class State(val count: Int, val label: String)

    suspend fun onIncrement()

    suspend fun onReset()
}

/**
 * Concrete implementation built through the kotlin-inject graph: `@ViewModelInject` generates its
 * interface binding, and the `@Inject` constructor's [repo] is resolved from [DemoModule]. This screen
 * has no outbound navigation, so there is no assisted navigator.
 */
@ViewModelInject
class RealCounterViewModel @Inject constructor(
    repo: CounterRepository,
) :
    CounterViewModel,
    StatefulViewModel<CounterViewModel.State>(CounterViewModel.State(count = repo.initialCount(), label = "Taps")) {

    override suspend fun onIncrement(): Unit = update { copy(count = count + 1) }

    override suspend fun onReset(): Unit = update { copy(count = 0) }
}
