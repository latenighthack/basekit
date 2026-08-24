import DemoCore
import Foundation
import SwiftUI

// Compile-only consumer fixture. CI type-checks this with the generated Apple sources on iOS and macOS.
@available(iOS 18.0, macOS 15.0, *)
@MainActor
private struct FixturePolicy: BasekitNavigationPolicy {
    func decision(for request: BasekitNavigationRequest) -> BasekitNavigationDecision {
        switch request.edge {
        case .homeOnOpenDetail:
            return .init(presentation: .push, transition: .zoom(sourceID: "fixture-detail"))
        case .homeOnOpenDetailFromBanner:
            return .init(presentation: .platformCustom(id: "banner"), transition: .custom(id: "banner"))
        case .homeOnPick:
            return .init(presentation: .sheet)
        }
    }
}

@available(iOS 18.0, macOS 15.0, *)
@MainActor
private struct FixtureRoot: View {
    @ObservedObject var router: BasekitNavigationRouter
    let entry: BasekitNavigationEntry

    var body: some View {
        Text("Root")
            .basekitNavigationHandler(router: router, ownerID: entry.ownerID) { request, transaction in
                switch request.edge {
                case .homeOnOpenDetailFromBanner:
                    transaction.present(using: .init(presentation: .platformCustom(id: "inline")))
                    return true
                case .homeOnPick:
                    transaction.finish(with: PickResult(selectedId: 1))
                    return true
                case .homeOnOpenDetail:
                    return false
                }
            }
    }
}

@available(iOS 18.0, macOS 15.0, *)
@MainActor
@ViewBuilder
private func fixtureScreen(
    entry: BasekitNavigationEntry,
    namespace: Namespace.ID,
    router: BasekitNavigationRouter
) -> some View {
    switch entry.route {
    case .home(let args, _):
        let _ = AppleHomeNavigator(ownerId: entry.ownerID, host: router)
        Text("Home \(String(describing: args))")
    case .detail(let args, _):
        let _ = AppleDetailNavigator(ownerId: entry.ownerID, host: router)
        Text("Detail \(args.id)")
    case .picker(let args, _, _):
        let _ = ApplePickerNavigator(ownerId: entry.ownerID, host: router)
        Text("Picker \(String(describing: args))")
    }
}

@available(iOS 18.0, macOS 15.0, *)
@MainActor
private func fixtureStack() -> some View {
    let router = BasekitNavigationRouter(policy: FixturePolicy())
    let root = router.root(.home(args: HomeViewModelArgs(), edge: nil))
    return BasekitNavigationStackHost(router: router, rootEntry: root) { entry, _ in
        FixtureRoot(router: router, entry: entry)
    } destination: { entry, namespace in
        fixtureScreen(entry: entry, namespace: namespace, router: router)
    }
}

#if canImport(UIKit)
import UIKit

@available(iOS 18.0, *)
@MainActor
private final class FixtureAnimator: NSObject, UIViewControllerAnimatedTransitioning {
    func transitionDuration(using transitionContext: UIViewControllerContextTransitioning?) -> TimeInterval { 0.2 }
    func animateTransition(using transitionContext: UIViewControllerContextTransitioning) {
        transitionContext.completeTransition(!transitionContext.transitionWasCancelled)
    }
}

@available(iOS 18.0, *)
@MainActor
private func fixtureUIKitHost() -> BasekitNavigationControllerHost {
    let router = BasekitNavigationRouter(policy: FixturePolicy())
    let root = router.root(.home(args: HomeViewModelArgs(), edge: nil))
    let host = BasekitNavigationControllerHost(router: router, root: root) { entry in
        switch entry.route {
        case .detail:
            return UIViewController()
        case .home, .picker:
            return UIHostingController(rootView: fixtureScreen(entry: entry, namespace: Namespace().wrappedValue, router: router))
        }
    }
    host.animatorProvider = { _, _, _, entry in entry?.edge == .homeOnOpenDetailFromBanner ? FixtureAnimator() : nil }
    return host
}
#elseif canImport(AppKit)
import AppKit

@available(macOS 15.0, *)
@MainActor
private final class FixtureAppKitPresenter: BasekitAppKitPresenter {
    func present(entry: BasekitNavigationEntry, customID: String) {}
    func dismiss(entryID: UUID) {}
}

@available(macOS 15.0, *)
@MainActor
private func fixtureAppKitHooks(router: BasekitNavigationRouter) {
    router.use(appKitPresenter: FixtureAppKitPresenter())
}
#endif
