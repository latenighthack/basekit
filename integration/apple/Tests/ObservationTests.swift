import XCTest
import DemoCore
@testable import BasekitBindingConsumer

/// A `ViewModelObserver` through the generated Swift wrappers: the same events, in the same order, as
/// the Kotlin bindings report on the JVM and in Compose.
@MainActor
final class ObservationTests: XCTestCase {
    func testObservableWrapperReportsStateMutatorsAndCancellation() async throws {
        let raw = RealBindingProbeViewModel()
        let recorder = RecordingViewModelObserver()
        let model = ObservableBindingProbeViewModel(raw, observer: recorder)
        let observation = Task { await model.observe() }
        try await eventually { recorder.lines.contains("FirstEmission ATTEMPT state") }
        XCTAssertEqual(raw.activeStateCollectors, 1)
        try await model.setNote("edited")
        try await eventually { model.note == "edited" }
        observation.cancel()
        await observation.value
        try await eventually { recorder.lines.last == "Detached STATE_BINDING state" }

        // The binding collects on its own task, so the mutator's events interleave with it in any order.
        XCTAssertEqual(recorder.lines.filter { !$0.contains("MUTATOR") }, [
            "Attached STATE_BINDING state",
            "StateChanged STATE_BINDING state",
            "Started ATTEMPT state",
            "FirstEmission ATTEMPT state",
            "StateChanged ATTEMPT state",
            "StateChanged ATTEMPT state",
            "Cancelled ATTEMPT state",
            "Detached STATE_BINDING state",
        ])
        XCTAssertEqual(recorder.lines.filter { $0.contains("MUTATOR") }, ["Started MUTATOR setNote", "Completed MUTATOR setNote"])
        XCTAssertEqual(recorder.changed.last, ["note"])
        XCTAssertEqual(raw.activeStateCollectors, 0)
    }

    func testActionsReportFailuresAndCancellation() async throws {
        let raw = RealBindingProbeViewModel()
        let recorder = RecordingViewModelObserver()
        let model = ObservableBindingProbeViewModel(raw, observer: recorder)
        do { try await model.fail(); XCTFail("Expected the Kotlin error") } catch {}
        let action = Task { try await model.waitUntilCancelled() }
        try await eventually { raw.startedActions == 1 }
        action.cancel()
        do { try await action.value; XCTFail("Expected cancellation") } catch is CancellationError {}

        XCTAssertEqual(recorder.lines, [
            "Started ACTION fail", "Failed ACTION fail",
            "Started ACTION waitUntilCancelled", "Cancelled ACTION waitUntilCancelled",
        ])
    }

    func testListsReportDeltasAndRowsReportToTheSameObserver() async throws {
        let raw = RealBindingProbeViewModel()
        let recorder = RecordingViewModelObserver()
        let model = ObservableBindingProbeViewModel(raw, observer: recorder)
        let list = DeltaList<BindingChildViewModel>()
        let collection = Task { await model.rows.collect(into: list) }
        try await eventually { recorder.lines.contains("ListDelta ATTEMPT rows") }
        XCTAssertEqual(raw.activeRowCollectors, 1)

        let row = model.rows.makeElement(for: RealBindingChildViewModel(identity: "r"))
        let rowObservation = Task { await row.observe() }
        try await eventually { recorder.owners.contains("com.latenighthack.basekit.demo.BindingChildViewModel") }
        rowObservation.cancel()
        await rowObservation.value
        collection.cancel()
        await collection.value
        try await eventually { raw.activeRowCollectors == 0 }

        XCTAssertEqual(Array(recorder.lines.prefix(4)), [
            "Attached LIST_BINDING rows",
            "Started ATTEMPT rows",
            "FirstEmission ATTEMPT rows",
            "ListDelta ATTEMPT rows",
        ])
        XCTAssertTrue(recorder.lines.contains("Detached LIST_BINDING rows"))
    }

    func testRouterDestinationsAndChildRoutersUseTheRoutersObserver() async throws {
        let recorder = RecordingViewModelObserver()
        let router = BasekitNavigationRouter()
        router.viewModelObserver = recorder
        let root = router.root(.home(args: HomeViewModelArgs(), edge: nil))
        guard case .home(let home) = basekitViewModel(for: root, host: router, factories: ObservationFactories()) else {
            return XCTFail("Expected the home view model")
        }
        try await home.onRefresh()

        XCTAssertEqual(recorder.lines, ["Started ACTION onRefresh", "Completed ACTION onRefresh"])
        XCTAssertTrue(BasekitNavigationRouter(parent: router).viewModelObserver === recorder)
    }

    func testRoutersReportPresentationApartFromRequests() async throws {
        let recording = RecordingNavigation()
        let router = BasekitNavigationRouter(navigationObserver: recording)
        let root = router.root(.home(args: HomeViewModelArgs(), edge: nil))
        router.didShow(root)
        XCTAssertTrue(router.open(args: { let args = DetailViewModelArgs(); args.id = "7"; return args }()))
        router.reconcilePath([])

        XCTAssertEqual(recording.lines, [
            "presented ROOT", "requested",
            "requested", "presented PUSH",
            "close", "dismissed PUSH",
        ])
    }

    private func eventually(_ condition: () -> Bool) async throws {
        for _ in 0..<300 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Condition did not converge")
    }
}

/// Records each event as `Kind KIND name`, read from the Kotlin `toString` so no SKIE spelling is assumed.
private final class RecordingViewModelObserver: NSObject, ViewModelObserver {
    var lines: [String] = []
    var owners: Set<String> = []
    var changed: [[String]] = []

    func onEvent(event: ViewModelEvent) {
        let kind = "\(event)".prefix { $0 != "(" }
        let operation = "\(event.operation)"
        let operationKind = operation.components(separatedBy: "kind=").last?.prefix { $0 != ")" && $0 != "," } ?? ""
        lines.append("\(kind) \(operationKind) \(event.operation.name)")
        owners.insert(event.operation.owner)
        if let state = event as? ViewModelEvent.StateChanged { changed.append(state.changedProperties) }
    }
}

private final class RecordingNavigation: NSObject, NavigationObserver, NavigationPresentationObserver {
    var lines: [String] = []
    func onNavigation(event: NavigationEvent) {
        let kind = "\(event)".prefix { $0 != "(" }
        lines.append(["NavigatedTo": "requested", "Closed": "close", "Responded": "responded"][String(kind)] ?? String(kind))
    }
    func onPresentation(event: NavigationPresentationEvent) {
        let description = "\(event)"
        let presentation = description.components(separatedBy: "presentation=").last?.prefix { $0 != ")" && $0 != "," } ?? ""
        lines.append((description.hasPrefix("Presented") ? "presented " : "dismissed ") + presentation)
    }
}

private final class ObservationFactories: NSObject, GeneratedViewModelFactories {
    private let store = InMemoryDemoStore()
    var detailFactory: (DetailViewModelArgs) -> RealDetailViewModel { { [store] args in RealDetailViewModel(store: store, args: args) } }
    var homeFactory: (any HomeNavigator) -> RealHomeViewModel { { [store] navigator in RealHomeViewModel(store: store, navigator: navigator) } }
    var pickerFactory: (any NavigationResponder) -> RealPickerViewModel { { [store] responder in RealPickerViewModel(store: store, responder: responder) } }
}
