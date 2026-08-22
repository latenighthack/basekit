package com.latenighthack.basekit.viewmodel

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Base Activity that binds a [ViewModel]'s state stream to the view tree using the Activity's own
 * lifecycle. Generated `Abstract{Vm}Activity` subclasses supply the view and the typed state mapping.
 *
 * Being a [ComponentActivity] makes the Activity a `LifecycleOwner`, so state collection is scoped to
 * `lifecycleScope` and deltalist list adapters can bind against `this` — no manual teardown needed.
 *
 * The framework ViewModel is retained across configuration changes: it is held in an androidx
 * [androidx.lifecycle.ViewModel] via [ViewModelProvider], so a rotation reuses the same instance
 * (and its state) instead of rebuilding it. State collection runs under
 * [repeatOnLifecycle]`(STARTED)`, so it stops while the Activity is stopped and restarts — replaying
 * the latest conflated state — when it returns to the foreground.
 */
public abstract class BaseActivity<VM : ViewModel<State>, State> : ComponentActivity() {

    protected lateinit var viewModel: VM
        private set

    private lateinit var rootView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = retainedViewModel()
        rootView = createView(this, viewModel)
        setContentView(rootView)

        onBindView(viewModel)
        // Render synchronously from the initial snapshot so the first frame is never empty.
        onStateChanged(viewModel, viewModel.initialState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> onStateChanged(viewModel, state) }
            }
        }
    }

    /**
     * Returns the retained framework ViewModel, constructing it via [createViewModel] only on first
     * creation. On a configuration change the same instance is returned, so its state survives.
     */
    @Suppress("UNCHECKED_CAST")
    private fun retainedViewModel(): VM {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                RetainedHolder(createViewModel()) as T
        }
        return ViewModelProvider(this, factory)[RetainedHolder::class.java].value as VM
    }

    protected abstract fun createViewModel(): VM

    protected abstract fun createView(context: Context, viewModel: VM): View

    protected open fun onBindView(viewModel: VM) {}

    protected open fun onStateChanged(viewModel: VM, state: State) {}

    /** Binds a nested child ViewModel's state, scoped to this Activity's STARTED lifecycle. */
    protected fun <T : ViewModel<S>, S> bindChildViewModel(child: T, onState: (T, S) -> Unit) {
        onState(child, child.initialState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                child.state.collect { onState(child, it) }
            }
        }
    }

    /** Config-change-retained holder for the framework ViewModel (which is not an androidx ViewModel). */
    private class RetainedHolder(val value: Any?) : androidx.lifecycle.ViewModel()
}
