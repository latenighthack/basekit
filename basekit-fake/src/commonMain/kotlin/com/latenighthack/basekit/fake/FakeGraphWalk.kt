package com.latenighthack.basekit.fake

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** What [walkFakeGraph] did: the screens it reached, in order, and each action it called. */
public data class FakeWalk(
    val screens: List<String>,
    val actions: List<String>,
    /** `Source -> Target` for each action that opened a screen. */
    val edges: List<String>,
)

/**
 * Calls every zero-argument action of the screen on top of [stack] and of the rows and children it
 * shows, once per spec and action, and does the same on each screen an action opens, then closes it
 * again. Actions that only record run first and actions that respond run last, since responding closes
 * the screen. Start the walk after rooting the stack, e.g. with the generated `FakeNavigator.start…`.
 */
public suspend fun walkFakeGraph(stack: FakeStack, maxDepth: Int = 32): FakeWalk = coroutineScope {
    val screens = mutableListOf<String>()
    val called = mutableListOf<String>()
    val edges = mutableListOf<String>()
    val seen = mutableSetOf<String>()

    suspend fun explore(depth: Int) {
        val entries = stack.entries.value
        val entry = entries.lastOrNull() ?: return
        if (entry.name !in screens) screens += entry.name
        val viewModel = entry.viewModel as? FakeViewModel ?: return
        val actions = viewModel.withEmbedded()
            .flatMap { fake -> fake.fakeActions.map { action -> fake to action } }
            .sortedBy { (_, action) -> action.effect.ordinal }
        for ((fake, action) in actions) {
            if (stack.entries.value.size != entries.size) break
            val key = fake::class.simpleName + "." + action.name
            if (!seen.add(key)) continue
            called += key
            val job = launch(start = CoroutineStart.UNDISPATCHED) { action.invoke() }
            val opened = stack.entries.value.drop(entries.size)
            if (opened.isNotEmpty()) {
                edges += "${entry.name} -> ${opened.first().name}"
                if (depth < maxDepth) explore(depth + 1)
                while (stack.entries.value.size > entries.size) stack.close()
            }
            job.join()
        }
    }

    explore(0)
    FakeWalk(screens, called, edges)
}

private fun FakeViewModel.withEmbedded(): List<FakeViewModel> {
    val all = mutableListOf<FakeViewModel>()
    fun add(fake: FakeViewModel) {
        if (all.any { it === fake }) return
        all += fake
        fake.fakeEmbedded.forEach(::add)
    }
    add(this)
    return all
}
