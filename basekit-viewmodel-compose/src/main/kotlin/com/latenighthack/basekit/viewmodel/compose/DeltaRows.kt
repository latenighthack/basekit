package com.latenighthack.basekit.viewmodel.compose

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.android.compose.rememberItem
import kotlinx.coroutines.flow.Flow
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** The source retains its soft/lazy capabilities; neither keys nor rendering flatten the list. */
@Composable
public fun <T : Any> DeltaRows(
    source: Flow<Delta<T>>,
    identity: (T) -> Any,
    modifier: Modifier = Modifier,
    placeholder: @Composable (Int) -> Unit = {},
    row: @Composable (T) -> Unit,
) {
    val keys = remember { RowKeys() }
    val delta by source.collectAsStateWithLifecycle(initialValue = Delta<T>(emptyList(), Change.Reload))
    LazyColumn(modifier) {
        items(delta.items.size, key = { index ->
            when (val item = delta.items.softGet(index)) {
                is SoftValue.Present -> keys.key(identity(item.value))
                is SoftValue.NotLoaded, null -> "pending:$index"
            }
        }) { index ->
            when (val item = delta.items.softGet(index)) {
                is SoftValue.NotLoaded -> {
                    LaunchedEffect(delta.items, index) { item.request() }
                    placeholder(index)
                }
                null -> Unit
                is SoftValue.Present -> key(ReferenceIdentity(delta.items), ReferenceIdentity(item.value)) {
                    // Reconnect even when a replacement child has the same logical key.
                    val acquired = delta.items.rememberItem(index, identity(item.value))
                    row(acquired)
                }
            }
        }
    }
}

/** Replacements must reconnect even if a handwritten ViewModel implements logical equality. */
private class ReferenceIdentity(private val value: Any) {
    override fun equals(other: Any?): Boolean = other is ReferenceIdentity && value === other.value
    override fun hashCode(): Int = System.identityHashCode(value)
}

/** Saveable positional keys for legacy object identity, without retaining removed ViewModels. */
private class RowKeys {
    private val queue = ReferenceQueue<Any>()
    private val objects = mutableMapOf<WeakIdentity, Long>()
    private var next = 0L
    fun key(value: Any): String {
        if (value is String || value is Number || value is Boolean || value is Char) {
            return "loaded:${value.javaClass.name}:$value"
        }
        while (true) { val expired = queue.poll() ?: break; objects.remove(expired) }
        return "object:" + objects.getOrPut(WeakIdentity(value, queue)) { next++ }
    }
    private class WeakIdentity(value: Any, queue: ReferenceQueue<Any>) : WeakReference<Any>(value, queue) {
        private val hash = System.identityHashCode(value)
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = this === other ||
            (other is WeakIdentity && get() != null && get() === other.get())
    }
}
