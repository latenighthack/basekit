package com.latenighthack.basekit.navigation

/**
 * A destination that returns a value to its caller when it closes — the picker-style flow (pick a
 * photo/friend, then hand the choice back). The response type [R] is a property of the destination,
 * so the KSP processor reads it the same way it reads [Args] and makes the generated `navigateTo…`
 * method a `suspend` function returning `R?`.
 *
 * A `null` response means dismissed/closed without a value (mirrors the caller resuming from a back
 * gesture or `close()`), so callers must handle it.
 */
public interface RespondingDestination<Args : NavigatorArgs, R : Any> : NavigationDestination<Args>

/**
 * The channel a [RespondingDestination] uses to hand a value back to whoever navigated to it. The
 * destination's ViewModel is constructed with one and calls [respond] exactly once (a value, or null
 * to dismiss); doing so resumes the suspended `navigateTo…` caller.
 *
 * Prod app navigators back this with a `CompletableDeferred<R?>` resolved on respond-or-pop; the test
 * harness backs it the same way. A caller coroutine cancelled while suspended in `navigateTo…` must
 * drop the pending responder — see the test slice for the contract.
 */
public fun interface NavigationResponder<R : Any> {
    public fun respond(response: R?)
}
