package com.latenighthack.basekit.viewmodel.codegen

/**
 * The `@objc` target-action thunks for a ViewModel's zero-arg actions.
 *
 * `@objcMembers` exports an `async throws` method to Objective-C as `…WithCompletionHandler:` — so
 * `onRefresh()` becomes `-(void)onRefreshWithCompletionHandler:(void (^)(NSError *))handler`. No
 * target-action call site can reach that shape: not `NSButton(title:target:action:)` on AppKit, not
 * `UIControl.addTarget(_:action:for:)` on UIKit, and not an Interface Builder connection on either.
 * Every consumer therefore had to hand-write the same one-line bridge.
 *
 * These thunks are the reachable shape, `-(void){name}Action:(id)sender`, so a control can target
 * the wrapper directly:
 *
 * ```swift
 * button.target = model
 * button.action = #selector(KvoHomeViewModel.onRefreshAction(_:))
 * ```
 *
 * This completes the imperative binding on both platforms: state already arrives through the
 * `@objc dynamic` properties (which UIKit KVO and AppKit's Cocoa Bindings both consume), and actions
 * were the only half that had no Objective-C-visible entry point.
 *
 * The work is handed to `KvoViewModel.runAction`, which retains the `Task` so `unbind()`/`deinit`
 * can cancel it and routes a thrown error to `onActionError` — a fire-and-forget call has no caller
 * to return an error to, so swallowing it here would be invisible rather than merely unhandled.
 *
 * Only zero-arg actions get a thunk. A mutator takes a typed value and a `sender` does not supply
 * one without guessing at the control, so those stay `async throws`-only.
 *
 * Platform-neutral: no `#if`, so this does not affect the byte-identical-across-Apple-passes
 * invariant that [appleListBinder] documents.
 */
internal fun targetActionThunks(vm: VmInfo): String = vm.actions.joinToString("\n\n") { action ->
    """
    |    /// Target-action / IBAction thunk for `${action.name}`, for call sites that need an
    |    /// Objective-C selector rather than the `async throws` method. Fire-and-forget; a thrown
    |    /// error goes to `onActionError`.
    |    @objc public func ${action.name}Action(_ sender: Any?) {
    |        runAction { [weak self] in try await self?.${action.name}() }
    |    }
    """.trimMargin()
}

/**
 * The generated thunk name for [actionName] — the single place the `Action` suffix is defined, so
 * the generator and the processor's collision check cannot disagree about it.
 */
internal fun targetActionThunkName(actionName: String): String = "${actionName}Action"
