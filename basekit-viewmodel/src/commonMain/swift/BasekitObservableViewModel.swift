import Combine
import SwiftUI

// Swift support for the generated `Observable{ViewModel}` wrappers, compiled by SKIE into the
// framework like `KvoViewModel`.

/// A generated `Observable{ViewModel}`: an `ObservableObject` whose `observe()` keeps its published
/// state current until the calling task is cancelled.
@MainActor
public protocol BasekitObservableViewModel: ObservableObject {
    func observe() async
}

/// Owns a generated view model for this view's lifetime and observes it while the view is shown,
/// replacing the `@StateObject` + `init` + `.task { await model.observe() }` a screen otherwise
/// repeats:
///
/// ```swift
/// BasekitModelView(ObservableAgentViewModel(factories.agentFactory(args, navigator))) { model in
///     AgentScreen(model: model) // `@ObservedObject var model`
/// }
/// ```
///
/// [model] is evaluated once per view identity, as `@StateObject` does, so a layout change that keeps
/// the view's identity keeps its view model.
@available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *)
@MainActor
public struct BasekitModelView<Model: BasekitObservableViewModel, Content: View>: View {
    @StateObject private var model: Model
    private let content: (Model) -> Content

    public init(_ model: @autoclosure @escaping () -> Model, @ViewBuilder content: @escaping (Model) -> Content) {
        _model = StateObject(wrappedValue: model())
        self.content = content
    }

    public var body: some View {
        content(model).task { await model.observe() }
    }
}

#if canImport(UIKit) && !os(watchOS)
import UIKit

@MainActor private let basekitCellObservationKey = UnsafeMutableRawPointer.allocate(byteCount: 1, alignment: 1)

/// Cancels its observation when the cell lets go of it: when the cell is configured with another row,
/// or released.
private final class BasekitCellObservation: NSObject {
    private let task: Task<Void, Never>
    init(_ task: Task<Void, Never>) { self.task = task }
    deinit { task.cancel() }
}

extension UICollectionViewCell {
    /// Runs [observe] until this cell is configured with another row or released. The generated
    /// `Observable{ViewModel}` list binders call it with the row they hand the cell provider, so a row
    /// hosted with `@ObservedObject` (in a `UIHostingConfiguration`, say) redraws while the cell shows it.
    @MainActor public func basekitObserve(_ observe: @escaping @MainActor () async -> Void) {
        let observation = BasekitCellObservation(Task { await observe() })
        objc_setAssociatedObject(self, basekitCellObservationKey, observation, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
    }
}
#endif
