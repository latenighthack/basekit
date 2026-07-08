package com.latenighthack.basekit.viewmodel.tui

import com.latenighthack.basekit.navigation.NavigationResponder
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.event.EventResult
import dev.tamboui.tui.event.KeyEvent
import kotlinx.coroutines.CoroutineScope

/**
 * One TUI screen — the unit the generated code produces per `@ViewModelSpec`. The host renders the top
 * screen of its back stack each frame and routes key events to it.
 */
public interface TuiScreen {
    /** Panel title shown for this screen. */
    public val title: String

    /** Builds the TamboUI element tree for the current snapshot. Called every frame. */
    public fun render(): Element

    /** Handles a key when this screen is on top. Return [EventResult.HANDLED] to consume it. */
    public fun onKey(event: KeyEvent): EventResult = EventResult.UNHANDLED
}

/**
 * The navigation surface handed to every screen: a back stack plus the coroutine scope actions run
 * on. Generated `…Navigator` implementations delegate here — `navigateTo…` -> [push], `close` -> [pop].
 */
public interface TuiNavigation {
    /** Scope tied to the running app; actions and state collection launch here. */
    public val scope: CoroutineScope

    /** Pushes a screen onto the back stack (becomes the visible screen next frame). */
    public fun push(screen: TuiScreen)

    /** Pops the top screen; quits the app if it was the last one. */
    public fun pop()

    /**
     * Pushes a responding screen and suspends until it responds. [build] receives a
     * [NavigationResponder] to hand to the pushed screen's ViewModel; calling `respond(value)` pops the
     * screen and resumes here with `value`. Popping the screen another way (Esc/back) resumes with null.
     */
    public suspend fun <R : Any> pushForResult(build: (NavigationResponder<R>) -> TuiScreen): R?
}
