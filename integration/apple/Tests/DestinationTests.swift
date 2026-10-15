import XCTest
import SwiftUI
import UIKit
import DemoCore
@testable import BasekitBindingConsumer

@MainActor
final class DestinationTests: XCTestCase {
    func testDeepLinkedRespondingDestinationRespondsAndCloses() async throws {
        let router = BasekitNavigationRouter()
        XCTAssertTrue(router.open(args: PickerViewModelArgs()))
        let entry = try XCTUnwrap(router.path.last)
        guard case .picker(_, _, let attached) = entry.route else { return XCTFail("Expected the picker route") }
        let responder = try XCTUnwrap(attached, "open attaches a responder to a responding route")

        // The destination builds without a responder of its own, and its row responds through the entry's.
        guard case .picker = basekitViewModel(for: entry, host: router, factories: CountingFactories()) else {
            return XCTFail("Expected the picker view model")
        }
        try await RealPickerOptionViewModel(option: PickerOption(id: 1, label: "Apples"), responder: responder).onSelected()
        try await eventually { router.path.isEmpty }

        // A second response is ignored: the result was already discarded and the entry closed.
        responder.respond(response: PickResult(selectedId: 2))
        XCTAssertTrue(router.path.isEmpty)
    }

    func testDestinationViewBuildsItsModelOnceAcrossRerenders() async throws {
        let router = BasekitNavigationRouter()
        let entry = router.root(.home(args: HomeViewModelArgs(), edge: nil))
        let factories = CountingFactories()
        let trigger = RenderTrigger()
        let probe = ContentProbe()
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
        window.rootViewController = UIHostingController(
            rootView: DestinationHost(trigger: trigger, router: router, entry: entry, factories: factories, probe: probe)
        )
        window.makeKeyAndVisible()
        defer { window.isHidden = true }

        try await eventually { probe.renders >= 1 }
        for count in 1...3 {
            trigger.count = count
            try await eventually { probe.renderedCount == count }
        }
        XCTAssertEqual(factories.homeBuilds, 1)
        XCTAssertEqual(Set(probe.models).count, 1, "Every render received the same view model")
    }

    func testObservableSectionedBinderDeliversRowsThatPublishStateChanges() async throws {
        let model = ObservableBindingProbeViewModel(RealBindingProbeViewModel())
        let collectionView = UICollectionView(
            frame: CGRect(x: 0, y: 0, width: 320, height: 480),
            collectionViewLayout: UICollectionViewCompositionalLayout.list(using: .init(appearance: .plain))
        )
        collectionView.register(UICollectionViewCell.self, forCellWithReuseIdentifier: "row")
        let window = UIWindow(frame: collectionView.frame)
        window.addSubview(collectionView)
        window.makeKeyAndVisible()
        defer { window.isHidden = true }

        var rows: [ObservableBindingChildViewModel] = []
        let dataSource = model.bindSections(collectionView) { cv, indexPath, row in
            rows.append(row)
            return cv.dequeueReusableCell(withReuseIdentifier: "row", for: indexPath)
        }
        try await eventually {
            collectionView.layoutIfNeeded()
            return rows.count == 2
        }
        let pinned = try XCTUnwrap(rows.first { $0.basekitIdentity == "pinned" })
        XCTAssertEqual(pinned.title, "Row pinned")

        try await pinned.select()
        try await eventually { pinned.title == "Selected pinned" }
        withExtendedLifetime(dataSource) {}
    }

    func testObservableFlatBinderDeliversRowsThatPublishStateChanges() async throws {
        let raw = RealBindingProbeViewModel()
        let model = ObservableBindingProbeViewModel(raw)
        let collectionView = UICollectionView(
            frame: CGRect(x: 0, y: 0, width: 320, height: 480),
            collectionViewLayout: UICollectionViewCompositionalLayout.list(using: .init(appearance: .plain))
        )
        collectionView.register(UICollectionViewCell.self, forCellWithReuseIdentifier: "row")
        let window = UIWindow(frame: collectionView.frame)
        window.addSubview(collectionView)
        window.makeKeyAndVisible()
        defer { window.isHidden = true }

        var rows: [ObservableBindingChildViewModel] = []
        let dataSource = model.bindRows(collectionView) { cv, indexPath, row in
            rows.append(row)
            return cv.dequeueReusableCell(withReuseIdentifier: "row", for: indexPath)
        }
        raw.replaceRows()
        try await eventually {
            collectionView.layoutIfNeeded()
            return !rows.isEmpty
        }
        let row = try XCTUnwrap(rows.last)
        try await row.select()
        try await eventually { row.title == "Selected child" }
        withExtendedLifetime(dataSource) {}
    }

    private func eventually(_ condition: () -> Bool) async throws {
        for _ in 0..<300 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Condition did not converge")
    }
}

/// Builds the demo's view models directly, counting home builds.
private final class CountingFactories: NSObject, GeneratedViewModelFactories {
    private let store = InMemoryDemoStore()
    private(set) var homeBuilds = 0

    var detailFactory: (DetailViewModelArgs) -> RealDetailViewModel {
        { [store] args in RealDetailViewModel(store: store, args: args) }
    }
    var homeFactory: (any HomeNavigator) -> RealHomeViewModel {
        { [unowned self] navigator in
            homeBuilds += 1
            return RealHomeViewModel(store: store, navigator: navigator)
        }
    }
    var pickerFactory: (any NavigationResponder) -> RealPickerViewModel {
        { [store] responder in RealPickerViewModel(store: store, responder: responder) }
    }
}

@MainActor
private final class RenderTrigger: ObservableObject {
    @Published var count = 0
}

@MainActor
private final class ContentProbe {
    var renders = 0
    var renderedCount = -1
    var models: [ObjectIdentifier] = []
}

/// Re-renders its destination view each time [trigger] changes, keeping the view's identity.
private struct DestinationHost: View {
    @ObservedObject var trigger: RenderTrigger
    let router: BasekitNavigationRouter
    let entry: BasekitNavigationEntry
    let factories: GeneratedViewModelFactories
    let probe: ContentProbe

    var body: some View {
        let count = trigger.count
        BasekitDestinationView(for: entry, host: router, factories: factories) { model in
            let _ = record(model, count: count)
            Text("Render \(count)")
        }
    }

    private func record(_ model: BasekitDestinationModel, count: Int) {
        probe.renders += 1
        probe.renderedCount = count
        probe.models.append(ObjectIdentifier(model.model))
    }
}
