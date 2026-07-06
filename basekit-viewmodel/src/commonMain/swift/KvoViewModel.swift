import Foundation

#if canImport(UIKit)
import UIKit
#endif

// Swift support for the generated `Kvo{ViewModel}` wrappers. Delivered as source (like deltalist's
// src/commonMain/swift): a consuming Xcode/SwiftPM target compiles these alongside the generated
// `Kvo*.swift` and links the exported KMP frameworks (BasekitViewModel, which re-exports
// DeltaListCore). Not compiled by Gradle.

/// Base class for a generated `Kvo{ViewModel}`. Owns the Task that collects the Kotlin ViewModel's
/// `state` stream (a SKIE `AsyncSequence`, since this framework leaves Flow interop enabled) and
/// pushes each snapshot onto the `@objc dynamic` properties the subclass declares — making state
/// observable through ordinary KVO. The subscription is cancelled on `unbind()` / `deinit`.
open class KvoViewModel: NSObject {

    private var stateTask: Task<Void, Never>?

    public override init() {
        super.init()
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

    /// Cancels the state subscription. Call when the owning view is torn down; also runs on deinit.
    public func unbind() {
        stateTask?.cancel()
        stateTask = nil
    }

    deinit {
        stateTask?.cancel()
    }
}
