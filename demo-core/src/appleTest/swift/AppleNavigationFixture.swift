import DemoCore
import Foundation
import SwiftUI

// Compile-only consumer fixture. CI type-checks this with the generated Apple sources on iOS and macOS.

// An app view named like a host type, as apps write. The generated Swift is compiled into the app's
// module, where this declaration hides any framework type of the same name, so the generated code must
// not name a framework type `NavigationHost`. The router below is still passed to the generated
// navigators and `basekitViewModel` without casts.
struct NavigationHost: View {
    var body: some View { EmptyView() }
}
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
        let _ = HostHomeNavigator(ownerId: entry.ownerID, host: router)
        Text("Home \(String(describing: args))")
    case .detail(let args, _):
        let _ = HostDetailNavigator(ownerId: entry.ownerID, host: router)
        Text("Detail \(args.id)")
    case .picker(let args, _, _):
        let _ = HostPickerNavigator(ownerId: entry.ownerID, host: router)
        Text("Picker \(String(describing: args))")
    }
}

@available(iOS 18.0, macOS 15.0, *)
@MainActor
private func fixtureStack() -> some View {
    let router = BasekitNavigationRouter(policy: FixturePolicy())
    let root = router.root(.home(args: HomeViewModelArgs(), edge: nil))
    // Each entry comes with the router its navigator reports to: a sheet's own router, inside a sheet.
    return BasekitNavigationStackHost(router: router, rootEntry: root) { entry, host, _ in
        FixtureRoot(router: host, entry: entry)
    } destination: { entry, host, namespace in
        fixtureScreen(entry: entry, namespace: namespace, router: host)
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
    HostCloseNavigator(ownerId: entry.ownerID, host: child).close(context: nil)
    return child
}

// A presented entry reports to the router its presenter made for it. A host that presents by itself, as a
// UIKit platform presenter does, looks that router up from any router above the entry.
@available(iOS 18.0, macOS 15.0, *)
@MainActor
private func fixturePresentedModel(root: BasekitNavigationRouter, entry: BasekitNavigationEntry, factories: GeneratedViewModelFactories) -> BasekitDestinationModel {
    basekitViewModel(for: entry, host: root.host(for: entry), factories: factories)
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
