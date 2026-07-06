package com.latenighthack.basekit.demo

import com.latenighthack.basekit.viewmodel.StatefulViewModel
import com.latenighthack.basekit.viewmodel.ViewModel

/**
 * A plain (non-list) ViewModel: state with two fields and two zero-arg suspend actions. Exercises the
 * state-binding path of every generated platform wrapper.
 */
@com.latenighthack.basekit.viewmodel.annotations.ViewModel
interface CounterViewModel : ViewModel<CounterViewModel.State> {
    data class State(val count: Int, val label: String)

    suspend fun onIncrement()

    suspend fun onReset()
}

/** Concrete implementation exercised by the generated bindings. */
class RealCounterViewModel :
    CounterViewModel,
    StatefulViewModel<CounterViewModel.State>(CounterViewModel.State(count = 0, label = "Taps")) {

    override suspend fun onIncrement(): Unit = update { copy(count = count + 1) }

    override suspend fun onReset(): Unit = update { copy(count = 0) }
}
