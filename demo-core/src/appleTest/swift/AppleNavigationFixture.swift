import DemoCore
import Foundation
import SwiftUI

// Compile-only consumer fixture. CI type-checks this with the generated Apple sources on iOS and macOS.
@available(iOS 18.0, macOS 15.0, *)
@MainActor
private struct FixturePolicy: BasekitNavigationPolicy {
    func decision(for request: BasekitNavigationRequest) -> BasekitNavigationDecision {
        switch request.edge {
        case .homeOnOpenDetail, .feedItemOnSelected:
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
                case .homeOnOpenDetail, .feedItemOnSelected:
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

// Host-side use of the generated view model factory, the child router, the closing navigator and
// action identifiers. Presentation stays the host's: this fixture only builds and observes models.
@available(iOS 18.0, macOS 15.0, *)
@MainActor
private struct FixtureDestination: View {
    let router: BasekitNavigationRouter
    let entry: BasekitNavigationEntry
    let factories: GeneratedViewModelFactories

    var body: some View {
        BasekitDestinationView(for: entry, host: router, factories: factories) { model in
            switch model {
            case .home(let home):
                Text("Home").accessibilityIdentifier(ObservableHomeViewModel.ActionIds.onRefresh)
                    .onTapGesture { Task { try? await home.onRefresh() } }
            case .detail(let detail):
                Text(String(describing: detail))
            case .picker:
                Text("Picker")
            }
        }
    }
}

@available(iOS 18.0, macOS 15.0, *)
@MainActor
private func fixtureModel(router: BasekitNavigationRouter, entry: BasekitNavigationEntry, factories: GeneratedViewModelFactories) -> any BasekitObservableViewModel {
    basekitViewModel(for: entry, host: router, factories: factories).model
}

@available(iOS 18.0, macOS 15.0, *)
@MainActor
private func fixtureChildRouter(parent: BasekitNavigationRouter, entry: BasekitNavigationEntry) -> BasekitNavigationRouter {
    let child = BasekitNavigationRouter(parent: parent)
    // The callback is main-actor isolated, so a host can forward `context` without assumeIsolated.
    child.onCloseRoot = { ownerID, context in parent.close(ownerId: ownerID, context: context) }
    AppleCloseNavigator(ownerId: entry.ownerID, host: child).close(context: nil)
    return child
}

#if canImport(UIKit)
@available(iOS 18.0, *)
@MainActor
private func fixtureSectionedBinding(_ model: KvoBindingProbeViewModel, collectionView: UICollectionView) {
    model.bindShelves(collectionView, cellProvider: { cv, indexPath, row in
        switch row {
        case .bindingChild(let child): _ = child.title
        case .bindingNote(let note): _ = note.text
        }
        return UICollectionViewCell()
    }, headerProvider: { _, _, header in
        switch header {
        case .TOP, .BOTTOM: return UICollectionReusableView()
        }
    })
    model.bindSections(collectionView, cellProvider: { _, _, child in
        _ = child.title
        return UICollectionViewCell()
    }, headerProvider: { _, _, header in
        switch header {
        case .Pinned: return UICollectionReusableView()
        case .Group(let group): _ = group.name; return UICollectionReusableView()
        }
    })
}

@available(iOS 18.0, *)
@MainActor
private func fixtureObservableBinding(_ model: ObservableBindingProbeViewModel, collectionView: UICollectionView) -> [AnyObject] {
    let shelves = model.bindShelves(collectionView, cellProvider: { _, _, row in
        switch row {
        case .bindingChild(let child): _ = child.title
        case .bindingNote(let note): _ = note.text
        }
        return UICollectionViewCell()
    }, headerProvider: { _, _, header in
        switch header {
        case .TOP, .BOTTOM: return UICollectionReusableView()
        }
    })
    let sections = model.bindSections(collectionView, cellProvider: { _, _, child in
        let _: ObservableBindingChildViewModel = child
        return UICollectionViewCell()
    }, headerProvider: { _, _, header in
        switch header {
        case ObservableBindingProbeViewModelSectionsHeader.Pinned: return UICollectionReusableView()
        case .Group(let group): _ = group.name; return UICollectionReusableView()
        }
    })
    let rows = model.bindRows(collectionView) { _, _, child in
        _ = child.title
        return UICollectionViewCell()
    }
    return [shelves, sections, rows]
}
#endif
