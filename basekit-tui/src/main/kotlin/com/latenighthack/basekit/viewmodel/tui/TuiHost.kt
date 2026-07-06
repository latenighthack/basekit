package com.latenighthack.basekit.viewmodel.tui

import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.app.ToolkitRunner
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.event.EventResult
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Drives the TamboUI runner over a back stack of [TuiScreen]s. Each frame it renders the top screen
 * inside a titled panel and routes keys: `Esc` pops (or quits at the root), `q`/`Ctrl+C` quit when a
 * screen leaves them unhandled, everything else goes to the top screen.
 */
public class TuiHost(private val rootFactory: (TuiNavigation) -> TuiScreen) : TuiNavigation {

    private val job = SupervisorJob()
    override val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + job)

    private val stack = ArrayDeque<TuiScreen>()
    private var runner: ToolkitRunner? = null

    override fun push(screen: TuiScreen) {
        stack.addLast(screen)
    }

    override fun pop() {
        if (stack.size > 1) {
            stack.removeLast()
        } else {
            runner?.quit()
        }
    }

    /** Builds the app, opens the terminal, and blocks until the user quits. */
    public fun run() {
        stack.addLast(rootFactory(this))
        try {
            ToolkitRunner.create().use { r ->
                runner = r
                r.run { renderRoot() }
            }
        } finally {
            runner = null
            scope.cancel()
        }
    }

    private fun renderRoot(): Element {
        val top = stack.last()
        val hint = if (stack.size > 1) "[Esc] Back   [q] Quit" else "[q] Quit"
        return Toolkit.panel(
            top.title,
            top.render(),
            Toolkit.text(hint).dim(),
        ).rounded().id("tui-root").focusable().onKeyEvent { event -> routeKey(top, event) }
    }

    private fun routeKey(top: TuiScreen, event: KeyEvent): EventResult {
        if (event.isKey(KeyCode.ESCAPE)) {
            pop()
            return EventResult.HANDLED
        }
        return top.onKey(event)
    }
}
