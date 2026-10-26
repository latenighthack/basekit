package com.latenighthack.basekit.fake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** One call on a fake: the fake's [path], the action or mutator called and the value it was given or got back. */
public data class FakeEvent(val path: String, val member: String, val value: Any? = null)

/** The most recent [capacity] calls on fakes, oldest first. */
public class FakeLog(private val capacity: Int = 500) {
    private val mutableEvents = MutableStateFlow<List<FakeEvent>>(emptyList())

    public val events: StateFlow<List<FakeEvent>> get() = mutableEvents

    public fun add(event: FakeEvent) {
        mutableEvents.update { (it + event).takeLast(capacity) }
    }

    public fun clear() {
        mutableEvents.value = emptyList()
    }
}
