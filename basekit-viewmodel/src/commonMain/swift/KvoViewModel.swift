import Foundation

// Swift support for the generated `Kvo{ViewModel}` wrappers. SKIE compiles everything under
// `src/<sourceSet>/swift` into the produced framework, so this class ships inside
// BasekitViewModel.framework (and is re-emitted into any framework that exports it, e.g. DemoCore)
// on every Apple platform. A consuming Xcode/SwiftPM target compiles the generated `Kvo*.swift`
// alongside it and links the exported KMP frameworks.
//
// No UI framework is imported: the base is NSObject + Task, so it is identical under UIKit and
// AppKit. Only the generated subclasses' list binders differ by platform.

/// Base class for a generated `Kvo{ViewModel}`. Owns the Task that collects the Kotlin ViewModel's
/// `state` stream (a SKIE `AsyncSequence`, since this framework leaves Flow interop enabled) and
/// pushes each snapshot onto the `@objc dynamic` properties the subclass declares — making state
/// observable through ordinary KVO. The subscription is cancelled on `unbind()` / `deinit`.
open class KvoViewModel: NSObject {

    private var stateTask: Task<Void, Never>?

    /// In-flight actions started by `runAction`, keyed so each can remove itself on completion.
    /// Retained so `unbind()` / `deinit` can cancel work the user kicked off from a control.
    private var actionTasks: [UUID: Task<Void, Never>] = [:]

    /// Invoked when an action started through `runAction` throws. Defaults to nil (silent).
    ///
    /// This matters more than it would for the `async throws` methods: those hand the error to
    /// whoever awaited them, whereas a target-action thunk is fire-and-forget and has no caller to
    /// return to. Wire this to your telemetry so a failing button is not invisible.
    ///
    /// (The state subscription's own failures are a separate, still-silent path - see the `catch`
    /// in the generated `Kvo{Vm}` initialiser.)
    public var onActionError: ((Error) -> Void)?

    public override init() {
        super.init()
    }

    /// Runs a fire-and-forget action, retaining its `Task` so `unbind()` / `deinit` can cancel it
    /// and routing any thrown error to `onActionError`.
    ///
    /// The generated `{action}Action(_:)` target-action thunks call this. Registration happens on
    /// whichever thread delivered the action - AppKit and UIKit both dispatch target-action on the
    /// main thread - and the bookkeeping on completion hops back to the main actor to match.
    public func runAction(_ body: @escaping () async throws -> Void) {
        let id = UUID()
        actionTasks[id] = Task { [weak self] in
            do {
                try await body()
            } catch {
                await MainActor.run { self?.onActionError?(error) }
            }
            await MainActor.run { self?.actionTasks.removeValue(forKey: id) }
        }
    }

    /// Starts (or restarts) the state subscription. `body` should loop the ViewModel's `state`
    /// sequence and assign the mapped values to the subclass's dynamic properties on the main actor.
    public func startObserving(_ body: @escaping () async -> Void) {
        stateTask?.cancel()
        stateTask = Task { [weak self] in
            guard self != nil else { return }
            await body()
        }
    }

    /// Cancels the state subscription and any in-flight actions. Call when the owning view is torn
    /// down; also runs on deinit.
    public func unbind() {
        stateTask?.cancel()
        stateTask = nil
        for task in actionTasks.values {
            task.cancel()
        }
        actionTasks.removeAll()
    }

    deinit {
        stateTask?.cancel()
        for task in actionTasks.values {
            task.cancel()
        }
    }
}
