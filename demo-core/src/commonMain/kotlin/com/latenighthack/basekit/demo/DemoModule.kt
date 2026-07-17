package com.latenighthack.basekit.demo

import com.latenighthack.basekit.viewmodel.annotations.ViewModelModule
import me.tatarka.inject.annotations.Provides

/** A feed row's data. */
data class FeedItemData(val id: String, val title: String, val subtitle: String)

/** A detail screen's data, keyed by feed item id. [count], [note] and [flagged] are mutated by the detail actions. */
data class DetailData(val id: String, val body: String, val count: Int, val note: String, val flagged: Boolean)

/** One choice the picker offers. */
data class PickerOption(val id: Int, val label: String)

/**
 * The single in-memory backing store every ViewModel reads from, so navigating between screens shows
 * consistent data: the feed list, each item's detail (with a mutable count), and the picker options.
 */
interface DemoStore {
    fun feedItems(): List<FeedItemData>
    fun addSeededItem(): FeedItemData
    fun addOptionItem(option: PickerOption): FeedItemData
    fun detail(id: String): DetailData
    fun incrementCount(id: String): Int
    fun resetCount(id: String)
    fun setNote(id: String, note: String)
    fun setFlagged(id: String, flagged: Boolean)
    fun pickerOptions(): List<PickerOption>
}

class InMemoryDemoStore : DemoStore {
    private val items = mutableListOf(
        FeedItemData("first", "First", "one"),
        FeedItemData("second", "Second", "two"),
    )
    private val counts = mutableMapOf<String, Int>()
    private val notes = mutableMapOf<String, String>()
    private val flags = mutableMapOf<String, Boolean>()
    private var seeded = 0
    private val options = listOf(
        PickerOption(1, "Apples"),
        PickerOption(2, "Bananas"),
        PickerOption(3, "Cherries"),
    )

    override fun feedItems(): List<FeedItemData> = items.toList()

    override fun addSeededItem(): FeedItemData {
        seeded += 1
        return FeedItemData("seed-$seeded", "Item $seeded", "seeded").also { items.add(it) }
    }

    override fun addOptionItem(option: PickerOption): FeedItemData =
        FeedItemData("option-${option.id}", option.label, "picked").also { items.add(it) }

    override fun detail(id: String): DetailData {
        val title = items.firstOrNull { it.id == id }?.title ?: id
        return DetailData(id, "Body for $title", counts[id] ?: 0, notes[id] ?: "", flags[id] ?: false)
    }

    override fun incrementCount(id: String): Int = ((counts[id] ?: 0) + 1).also { counts[id] = it }

    override fun resetCount(id: String) {
        counts[id] = 0
    }

    override fun setNote(id: String, note: String) {
        notes[id] = note
    }

    override fun setFlagged(id: String, flagged: Boolean) {
        flags[id] = flagged
    }

    override fun pickerOptions(): List<PickerOption> = options
}

// A single shared store instance so every injected ViewModel sees the same data across navigations.
private val sharedDemoStore: DemoStore = InMemoryDemoStore()

/**
 * App-supplied kotlin-inject bindings. `@ViewModelModule` makes the generated `GeneratedTuiComponent`
 * include this interface, so `@ViewModelInject` ViewModels can depend on [DemoStore] without anyone
 * hand-writing the component.
 */
@ViewModelModule
interface DemoModule {
    @Provides
    fun demoStore(): DemoStore = sharedDemoStore
}
