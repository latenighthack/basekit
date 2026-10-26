package com.latenighthack.basekit.fake

import com.latenighthack.basekit.navigation.NavigationResponder
import com.latenighthack.basekit.navigation.awaitNavigationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** One screen on a [FakeStack]: the spec's simple [name] and its [viewModel]. */
public class FakeEntry internal constructor(
    public val name: String,
    public val viewModel: Any,
    internal val dismiss: (() -> Unit)?,
) {
    override fun toString(): String = name
}

/**
 * A navigation stack with no UI, which the generated `FakeNavigator` pushes fakes onto. A responding
 * screen is removed when it responds; closing it answers null.
 */
public class FakeStack {
    private val mutableEntries = MutableStateFlow<List<FakeEntry>>(emptyList())
    private var replaceNext = false

    public val entries: StateFlow<List<FakeEntry>> get() = mutableEntries

    /** The view model on top, or null when the stack is empty. */
    public val top: Any? get() = mutableEntries.value.lastOrNull()?.viewModel

    /** Replaces the stack with one screen. */
    public fun root(name: String, viewModel: Any) {
        mutableEntries.value = listOf(FakeEntry(name, viewModel, null))
    }

    /** Adds a screen on top, or replaces the top screen inside [replacing]. */
    public fun push(name: String, viewModel: Any) {
        val entry = FakeEntry(name, viewModel, null)
        if (replaceNext && mutableEntries.value.isNotEmpty()) {
            replaceNext = false
            mutableEntries.update { it.dropLast(1) + entry }
        } else {
            mutableEntries.update { it + entry }
        }
    }

    /** Runs [block], in which the first [push] replaces the top screen instead of adding one: a redirect. */
    public fun replacing(block: () -> Unit) {
        val previous = replaceNext
        replaceNext = true
        try {
            block()
        } finally {
            replaceNext = previous
        }
    }

    /** Pushes the view model [create] makes from a responder, and suspends until it responds or is closed. */
    public suspend fun <R : Any> pushForResult(name: String, create: (NavigationResponder<R>) -> Any): R? =
        awaitNavigationResult { responder ->
            lateinit var entry: FakeEntry
            val removing = NavigationResponder<R> { response ->
                mutableEntries.update { entries -> entries - entry }
                responder.respond(response)
            }
            entry = FakeEntry(name, create(removing), dismiss = { responder.respond(null) })
            mutableEntries.update { it + entry }
        }

    /** Removes the top screen; a responding screen answers null. */
    public fun close() {
        val entry = mutableEntries.value.lastOrNull() ?: return
        mutableEntries.update { entries -> entries - entry }
        entry.dismiss?.invoke()
    }
}
