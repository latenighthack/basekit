package com.latenighthack.basekit.viewmodel.tui

import com.latenighthack.basekit.navigation.NavigationResponder
import dev.tamboui.style.Color
import dev.tamboui.style.Overflow
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.app.ToolkitRunner
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.event.EventResult
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import dev.tamboui.tui.event.ResizeEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the TamboUI runner over a back stack of [TuiScreen]s. Each frame it renders the top screen
 * inside a titled panel above a fixed-height [TuiLog] window, and routes keys: `Esc` pops (or quits at
 * the root), `q`/`Ctrl+C` quit when a screen leaves them unhandled, everything else goes to the top
 * screen.
 */
public class TuiHost(private val rootFactory: (TuiNavigation) -> TuiScreen) : TuiNavigation {

    private companion object {
        // Rows reserved for the bottom log window, including its top and bottom border.
        const val LOG_PANEL_HEIGHT = 8

        // TamboUI only repaints after it handles an input event (or a resize) — it has no repaint
        // clock of its own. Navigation pushes and Holder flow emissions happen off the render thread,
        // so without a clock they stay invisible until the next keypress (the classic "the last
        // navigation never shows" symptom). Drive a modest repaint clock so they surface on their own.
        const val REPAINT_INTERVAL_MS = 50L
    }

    private val job = SupervisorJob()

    // Last uncaught error from a launched action/collector, rendered as an overlay until a key clears it.
    @Volatile
    private var lastError: Throwable? = null

    // Screens dispatch actions and collect state fire-and-forget on this scope. Without a handler an
    // uncaught throwable is silently dropped and its stderr trace is painted over by the terminal, so a
    // failing ViewModel call looks like a no-op. Capture it here and surface it instead of losing it.
    private val errorHandler = CoroutineExceptionHandler { _, throwable -> lastError = throwable }
    override val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + job + errorHandler)

    private val stack = ArrayDeque<TuiScreen>()

    // Screens pushed via pushForResult carry a deferred that resolves on respond-or-dismiss.
    private val pending = HashMap<TuiScreen, CompletableDeferred<Any?>>()
    private var runner: ToolkitRunner? = null

    override fun push(screen: TuiScreen) {
        stack.addLast(screen)
        // push runs off the render thread (from a navigator inside a launched action), so ask for a
        // repaint or the new top screen won't appear until the next keypress.
        requestRender()
    }

    override fun pop() {
        if (stack.size > 1) {
            val removed = stack.removeLast()
            pending.remove(removed)?.complete(null)
            requestRender()
        } else {
            runner?.quit()
        }
    }

    /**
     * Forces a full repaint from off the render thread. TamboUI's loop only renders after it handles
     * an input event or a resize; a dispatched [ResizeEvent] is the one non-input event it turns into
     * a render, and its dimensions are ignored (the renderer re-reads the live terminal frame), so it
     * is a safe "repaint now" nudge.
     */
    private fun requestRender() {
        val active = runner ?: return
        if (!active.isRunning) return
        active.tuiRunner().dispatch(ResizeEvent.of(0, 0))
    }

    override suspend fun <R : Any> pushForResult(build: (NavigationResponder<R>) -> TuiScreen): R? {
        val deferred = CompletableDeferred<Any?>()
        val responder = NavigationResponder<R> { response ->
            if (deferred.complete(response)) pop()
        }
        val screen = build(responder)
        pending[screen] = deferred
        push(screen)
        @Suppress("UNCHECKED_CAST")
        return deferred.await() as R?
    }

    /** Builds the app, opens the terminal, and blocks until the user quits. */
    public fun run() {
        stack.addLast(rootFactory(this))
        try {
            // Capture process-wide System.out/System.err into the bottom log window for the session, so
            // stray prints are visible for debugging instead of corrupting the rendered screen.
            TuiLog.install()
            ToolkitRunner.create().use { r ->
                runner = r
                // Repaint clock: navigation nudges via requestRender(), but Holder flow emissions
                // (state/list updates like Home's recommended titles landing) have no such hook, so a
                // steady clock repaints them into view. Cancelled with `scope` in the finally below.
                scope.launch {
                    while (isActive) {
                        delay(REPAINT_INTERVAL_MS)
                        requestRender()
                    }
                }
                r.run { renderRoot() }
            }
        } finally {
            runner = null
            TuiLog.uninstall()
            scope.cancel()
        }
    }

    private fun renderRoot(): Element {
        val top = stack.last()
        val error = lastError
        val hint = when {
            error != null -> "[any key] Dismiss error"
            stack.size > 1 -> "[Esc] Back   [q] Quit"
            else -> "[q] Quit"
        }
        val body = if (error != null) Toolkit.column(top.render(), errorPanel(error)) else top.render()
        val main = Toolkit.panel(
            top.title,
            body,
            Toolkit.text(hint).dim(),
        ).rounded().id("tui-root").focusable().onKeyEvent { event -> routeKey(top, event) }.fill()
        // The screen fills the area above a fixed-height log window pinned to the bottom. Only `main` is
        // focusable, so the log window is visible but never captures keys or otherwise interferes.
        return Toolkit.column(main, logPanel())
    }

    /** The bottom log window: a fixed-height, non-focusable panel tailing [TuiLog]'s recent output. */
    private fun logPanel(): Element {
        val visibleLines = LOG_PANEL_HEIGHT - 2 // minus the panel's top and bottom border rows
        val recent = TuiLog.tail(visibleLines)
        val text = if (recent.isEmpty()) "(no log output yet)" else recent.joinToString("\n")
        return Toolkit.panel(
            "Logs",
            // Ellipsize each line so long output stays one row and the newest line keeps its spot at the
            // bottom instead of wrapping the tail out of the fixed-height window.
            Toolkit.text(text).overflow(Overflow.ELLIPSIS).fg(Color.GRAY),
        ).rounded().borderColor(Color.DARK_GRAY).length(LOG_PANEL_HEIGHT)
    }

    /** The caught throwable (message, top frames, cause chain) as a red-bordered panel. */
    private fun errorPanel(error: Throwable): Element {
        val text = buildString {
            append(error::class.simpleName ?: "Error")
            error.message?.let { append(": ").append(it) }
            error.stackTrace.take(8).forEach { frame -> append("\n  at ").append(frame.toString()) }
            var cause = error.cause
            while (cause != null && cause !== error) {
                append("\nCaused by: ").append(cause::class.simpleName ?: "")
                cause.message?.let { append(": ").append(it) }
                cause = cause.cause
            }
        }
        return Toolkit.panel("Error", Toolkit.text(text).fg(Color.RED)).rounded().borderColor(Color.RED)
    }

    private fun routeKey(top: TuiScreen, event: KeyEvent): EventResult {
        // An error overlay swallows the next key to dismiss itself, so the same press can't also act.
        if (lastError != null) {
            lastError = null
            return EventResult.HANDLED
        }
        // The screen gets first refusal on every key so an active input prompt can consume Esc to cancel
        // itself; Esc only falls through to back/quit when the screen leaves it unhandled.
        if (top.onKey(event) == EventResult.HANDLED) return EventResult.HANDLED
        if (event.isKey(KeyCode.ESCAPE)) {
            pop()
            return EventResult.HANDLED
        }
        return EventResult.UNHANDLED
    }
}
