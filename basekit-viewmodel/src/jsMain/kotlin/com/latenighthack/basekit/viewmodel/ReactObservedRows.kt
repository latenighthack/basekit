package com.latenighthack.basekit.viewmodel

/**
 * The rows of one generated React binding's lists, decorated to report to [observer]. Each row is
 * decorated once and kept while the row is reachable, so its generated hook binding stays the same.
 * Without an observer, rows are returned as they are.
 */
public class ReactObservedRows(public val observer: ViewModelObserver?) {
    private val decorated: dynamic = js("new WeakMap()")

    public fun <T : Any> get(raw: T, decorate: (T, ViewModelObserver) -> T): T {
        val observer = observer ?: return raw
        val existing = decorated.get(raw)
        if (existing != undefined) return existing.unsafeCast<T>()
        return decorate(raw, observer).also { decorated.set(raw, it) }
    }
}
