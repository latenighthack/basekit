package com.latenighthack.basekit.viewmodel

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Base Activity that binds a [ViewModel]'s state stream to the view tree using the Activity's own
 * lifecycle. Generated `Abstract{Vm}Activity` subclasses supply the view and the typed state mapping.
 *
 * Being a [ComponentActivity] makes the Activity a `LifecycleOwner`, so state collection is scoped to
 * `lifecycleScope` and deltalist list adapters can bind against `this` — no manual teardown needed.
 */
public abstract class BaseActivity<VM : ViewModel<State>, State> : ComponentActivity() {

    protected lateinit var viewModel: VM
        private set

    private lateinit var rootView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = createViewModel()
        rootView = createView(this, viewModel)
        setContentView(rootView)

        onBindView(viewModel)
        // Render synchronously from the initial snapshot so the first frame is never empty.
        onStateChanged(viewModel, viewModel.initialState)

        lifecycleScope.launch {
            viewModel.state.collect { state -> onStateChanged(viewModel, state) }
        }
    }

    protected abstract fun createViewModel(): VM

    protected abstract fun createView(context: Context, viewModel: VM): View

    protected open fun onBindView(viewModel: VM) {}

    protected open fun onStateChanged(viewModel: VM, state: State) {}

    /** Binds a nested child ViewModel's state, scoped to this Activity's lifecycle. */
    protected fun <T : ViewModel<S>, S> bindChildViewModel(child: T, onState: (T, S) -> Unit) {
        onState(child, child.initialState)
        lifecycleScope.launch {
            child.state.collect { onState(child, it) }
        }
    }
}
