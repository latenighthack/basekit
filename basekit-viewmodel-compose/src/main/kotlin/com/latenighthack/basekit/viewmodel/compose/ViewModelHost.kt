package com.latenighthack.basekit.viewmodel.compose

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ownership is explicit: the callback can dispose a whole prepared client, not only its root. */
public class PreparedViewModel<VM : Any>(public val viewModel: VM, private val dispose: () -> Unit) {
    private var closed = false
    /** Called on the owner's main thread. */
    public fun close() { if (!closed) { closed = true; dispose() } }
}

public sealed interface Preparation<out VM> {
    public data object Loading : Preparation<Nothing>
    public data class Failed(val error: Throwable) : Preparation<Nothing>
    public data class Ready<VM>(val viewModel: VM) : Preparation<VM>
}

/** Main-thread owner; persists across configuration changes, never across process death. */
public class PreparedViewModelOwner<VM : Any>(private val prepare: suspend () -> PreparedViewModel<VM>) : ViewModel() {
    private val mutableState = MutableStateFlow<Preparation<VM>>(Preparation.Loading)
    public val state = mutableState.asStateFlow()
    private var prepared: PreparedViewModel<VM>? = null
    private var attempt: Job? = null
    private var cleared = false
    init { start() }

    public fun retry() { if (mutableState.value is Preparation.Failed) start() }

    private fun start() {
        if (cleared || attempt?.isActive == true) return
        mutableState.value = Preparation.Loading
        attempt = viewModelScope.launch {
            try {
                val result = prepare()
                if (cleared || !isActive) { result.close(); return@launch }
                prepared = result
                mutableState.value = Preparation.Ready(result.viewModel)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { if (!cleared) mutableState.value = Preparation.Failed(error) }
        }
    }

    public fun launch(onError: (Throwable) -> Unit, action: suspend () -> Unit): Job = viewModelScope.launch {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { onError(error) }
    }

    override fun onCleared() {
        cleared = true
        attempt?.cancel()
        prepared?.close()
        prepared = null
    }
}

/** A factory and key identify an owner lifetime. Changing a lambda does not recreate the client. */
@Composable
public fun <VM : Any> PreparedViewModelHost(
    ownerKey: String,
    storeOwner: ViewModelStoreOwner,
    prepare: suspend () -> PreparedViewModel<VM>,
    loading: @Composable () -> Unit,
    failure: @Composable (Throwable, retry: () -> Unit) -> Unit,
    content: @Composable (VM, PreparedViewModelOwner<VM>) -> Unit,
) {
    val factory = remember(ownerKey, storeOwner) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PreparedViewModelOwner(prepare) as T
        }
    }
    val owner: PreparedViewModelOwner<VM> = viewModel(viewModelStoreOwner = storeOwner, key = ownerKey, factory = factory)
    val state by owner.state.collectAsStateWithLifecycle()
    when (val current = state) {
        Preparation.Loading -> loading()
        is Preparation.Failed -> failure(current.error, owner::retry)
        is Preparation.Ready -> content(current.viewModel, owner)
    }
}
