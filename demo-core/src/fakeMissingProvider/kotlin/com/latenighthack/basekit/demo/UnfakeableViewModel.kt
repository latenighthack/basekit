package com.latenighthack.basekit.demo

import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec

/** An interface the fakes cannot build a value of without a @FakeProvider. */
interface Clock {
    fun now(): Long
}

/**
 * A spec whose State holds a [Clock]. Compiled only with `-Pbasekit.demo.fakeMissingProvider`, where
 * scripts/verify-binding-diagnostics.py expects basekit-fake-ksp to reject it in a fake build.
 */
@ViewModelSpec
interface UnfakeableViewModel : ViewModel<UnfakeableViewModel.State> {
    data class State(val clock: Clock)
}
