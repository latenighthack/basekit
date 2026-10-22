import UIKit
import XCTest
import DemoCore
@testable import BasekitBindingConsumer

@MainActor
final class BindingTests: XCTestCase {
    func testConcreteStateNullableMutatorsAndCancellation() async throws {
        let raw = RealBindingProbeViewModel()
        let model = ObservableBindingProbeViewModel(raw)
        let message: ProbeMessage = model.message
        let optional: ProbeMessage? = model.optionalMessage
        XCTAssertEqual(message.text, "hello")
        XCTAssertNil(optional)
        let observation = Task { await model.observe() }
        try await model.setMessage(ProbeMessage(text: "native"))
        try await model.setNote("edited")
        try await model.setFailure(.retry)
        try await model.setTags(["native"])
        try await eventually { model.note == "edited" && model.optionalMessage?.text == "native" && model.failure == .retry }
        try await model.setNote(nil)
        try await eventually { model.note == nil }
        do { try await model.fail(); XCTFail("Expected Kotlin action error") } catch {}
        observation.cancel()
        await observation.value
        try await eventually { raw.activeStateCollectors == 0 }
        try await model.setNote("after cancellation")
        for _ in 0..<10 { await Task.yield() }
        XCTAssertNil(model.note)
    }

    func testSuspendCancellationUsesSkieBridge() async throws {
        let raw = RealBindingProbeViewModel()
        let model = ObservableBindingProbeViewModel(raw)
        let action = Task { try await model.waitUntilCancelled() }
        try await eventually { raw.startedActions == 1 }
        action.cancel()
        do { try await action.value; XCTFail("Expected cancellation") } catch is CancellationError {}
        try await eventually { raw.cancelledActions == 1 }
    }

    func testEmptyListCollectionCancelsWithItsOwner() async throws {
        let raw = RealBindingProbeViewModel()
        let model = ObservableBindingProbeViewModel(raw)
        let list = DeltaList<BindingChildViewModel>()
        let collection = Task { await model.rows.collect(into: list) }
        try await eventually { raw.activeRowCollectors == 1 }
        collection.cancel()
        await collection.value
        try await eventually { raw.activeRowCollectors == 0 }
    }

    func testRespondingNavigationResolvesOnce() async throws {
        let router = BasekitNavigationRouter()
        let responder = RecordingResponder()
        router.showPicker(ownerId: "root", args: PickerViewModelArgs(), edge: .homeOnPick,
                          context: nil, responder: responder)
        let entry = try XCTUnwrap(router.path.last ?? router.sheet)
        router.finish(entryID: entry.id, with: PickResult(selectedId: 7))
        router.dismiss(entryID: entry.id)
        XCTAssertEqual(responder.results.count, 1)
        XCTAssertEqual((responder.results.first as? PickResult)?.selectedId, 7)
        XCTAssertTrue(router.path.isEmpty)
        XCTAssertNil(router.sheet)
    }

    // Stacked sheets: a sheet's view model reports to the router made for it, so what it opens is
    // presented over it. The policy, not the router, decides that a pick is a sheet.
    func testASheetsRouterStacksTheSheetItOpensAndClosesItFirst() throws {
        let router = BasekitNavigationRouter(policy: SheetsForPicks())
        let first = RecordingResponder(), second = RecordingResponder()
        router.showPicker(ownerId: "root", args: PickerViewModelArgs(), edge: .homeOnPick, context: nil, responder: first)
        let firstSheet = try XCTUnwrap(router.sheet)
        let firstHost = router.host(for: firstSheet)
        XCTAssertFalse(firstHost === router)
        XCTAssertTrue(router.host(for: firstSheet) === firstHost)
        firstHost.showPicker(ownerId: firstSheet.ownerID, args: PickerViewModelArgs(), edge: .homeOnPick, context: nil, responder: second)
        let secondSheet = try XCTUnwrap(firstHost.sheet)
        XCTAssertEqual(router.sheet, firstSheet)
        XCTAssertTrue(router.host(for: secondSheet) === firstHost.host(for: secondSheet))

        firstHost.finish(entryID: secondSheet.id, with: PickResult(selectedId: 2))
        XCTAssertEqual((second.results.first as? PickResult)?.selectedId, 2)
        XCTAssertNil(firstHost.sheet)
        XCTAssertEqual(router.sheet, firstSheet)
        XCTAssertTrue(first.results.isEmpty)
    }

    func testClosingASheetClosesTheSheetsOverIt() throws {
        let router = BasekitNavigationRouter(policy: SheetsForPicks())
        let first = RecordingResponder(), second = RecordingResponder()
        router.showPicker(ownerId: "root", args: PickerViewModelArgs(), edge: .homeOnPick, context: nil, responder: first)
        let firstSheet = try XCTUnwrap(router.sheet)
        let firstHost = router.host(for: firstSheet)
        firstHost.showPicker(ownerId: firstSheet.ownerID, args: PickerViewModelArgs(), edge: .homeOnPick, context: nil, responder: second)
        // The first sheet's navigator closes through its own router, which passes it to the presenter.
        firstHost.close(ownerId: firstSheet.ownerID, context: nil)
        XCTAssertNil(router.sheet)
        XCTAssertNil(firstHost.sheet)
        XCTAssertEqual(first.results.count, 1)
        XCTAssertNil(first.results.first ?? nil)
        XCTAssertEqual(second.results.count, 1)
        XCTAssertNil(second.results.first ?? nil)
    }

    func testASheetFromALowerRouterReplacesEverythingAboveIt() throws {
        let router = BasekitNavigationRouter(policy: SheetsForPicks())
        let first = RecordingResponder(), second = RecordingResponder(), third = RecordingResponder()
        router.showPicker(ownerId: "root", args: PickerViewModelArgs(), edge: .homeOnPick, context: nil, responder: first)
        let firstSheet = try XCTUnwrap(router.sheet)
        router.host(for: firstSheet).showPicker(ownerId: firstSheet.ownerID, args: PickerViewModelArgs(), edge: .homeOnPick, context: nil, responder: second)
        router.showPicker(ownerId: "root", args: PickerViewModelArgs(), edge: .homeOnPick, context: nil, responder: third)
        XCTAssertEqual(first.results.count, 1)
        XCTAssertEqual(second.results.count, 1)
        XCTAssertTrue(third.results.isEmpty)
        XCTAssertNil(router.host(for: try XCTUnwrap(router.sheet)).sheet)
    }

    func testADismissedSheetsLateNavigationAnswersNilAndShowsNothing() throws {
        let router = BasekitNavigationRouter(policy: SheetsForPicks())
        router.showPicker(ownerId: "root", args: PickerViewModelArgs(), edge: .homeOnPick, context: nil, responder: RecordingResponder())
        let sheet = try XCTUnwrap(router.sheet)
        let sheetHost = router.host(for: sheet)
        router.sheetBinding.wrappedValue = nil
        let late = RecordingResponder()
        sheetHost.showPicker(ownerId: sheet.ownerID, args: PickerViewModelArgs(), edge: .homeOnPick, context: nil, responder: late)
        XCTAssertEqual(late.results.count, 1)
        XCTAssertNil(late.results.first ?? nil)
        XCTAssertNil(sheetHost.sheet)
    }

    func testKvoUsesConcreteExportedTypes() async throws {
        let model = KvoBindingProbeViewModel(RealBindingProbeViewModel())
        let message: ProbeMessage = model.message
        let optional: ProbeMessage? = model.optionalMessage
        let failure: __ProbeFailure = model.failure
        XCTAssertEqual(message.text, "hello")
        XCTAssertNil(optional)
        XCTAssertEqual(failure.toSwiftEnum(), .none)
        try await model.setNote(nil)
        model.unbind()
    }

    func testObservedActionsThroughSwiftWrappersPreserveCancellationAndExcludeMutators() async throws {
        let raw = RealBindingProbeViewModel()
        let observer = RecordingActionObserver()
        let observed = ObservingBindingProbeViewModel(delegate: raw, actionObserver: observer)
        let model = ObservableBindingProbeViewModel(observed)
        try await model.setNote("edited")
        do { try await model.fail(); XCTFail("Expected Kotlin error") } catch {}
        let kvo = KvoBindingProbeViewModel(observed)
        do { try await kvo.fail(); XCTFail("Expected Kotlin error") } catch {}
        let action = Task { try await model.waitUntilCancelled() }
        try await eventually { raw.startedActions == 1 }
        action.cancel()
        do { try await action.value; XCTFail("Expected cancellation") } catch is CancellationError {}
        XCTAssertEqual(observer.names, ["fail", "fail", "waitUntilCancelled"])
        XCTAssertEqual(raw.cancelledActions, 1)
        kvo.unbind()
    }

    /// `sections` is `groupBy` → `lazyMapItems`: cells get the cached row, so a section appearing
    /// above and then disappearing builds only the new row, and shown rows keep their keys.
    func testSectionedBindingKeepsLazilyMappedRowsWhenSectionsComeAndGo() async throws {
        let raw = RealBindingProbeViewModel()
        let model = KvoBindingProbeViewModel(raw)
        let layout = UICollectionViewFlowLayout()
        layout.itemSize = CGSize(width: 300, height: 40)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 640))
        let collectionView = UICollectionView(frame: window.bounds, collectionViewLayout: layout)
        collectionView.register(UICollectionViewCell.self, forCellWithReuseIdentifier: "row")
        window.addSubview(collectionView)
        window.isHidden = false
        let dataSource = model.bindSections(collectionView, cellProvider: { cv, indexPath, child in
            let cell = cv.dequeueReusableCell(withReuseIdentifier: "row", for: indexPath)
            cell.accessibilityIdentifier = child.basekitIdentity
            return cell
        })
        func shown() -> Set<String> {
            collectionView.layoutIfNeeded()
            return Set(collectionView.visibleCells.compactMap(\.accessibilityIdentifier))
        }

        try await eventually { dataSource.sections.count == 2 }
        XCTAssertEqual(shown(), ["pinned", "more"])
        let pinned = try XCTUnwrap(dataSource.itemKey(at: IndexPath(item: 0, section: 0)))

        raw.insertSectionRow(index: 0, section: BindingProbeViewModelSectionGroup(name: "new"), id: "fresh")
        try await eventually { dataSource.sections.count == 3 }
        XCTAssertEqual(shown(), ["fresh", "pinned", "more"])
        XCTAssertEqual(dataSource.itemKey(at: IndexPath(item: 0, section: 1)), pinned)

        raw.removeSectionRow(index: 0)
        try await eventually { dataSource.sections.count == 2 }
        XCTAssertEqual(shown(), ["pinned", "more"])
        XCTAssertEqual(dataSource.itemKey(at: IndexPath(item: 0, section: 0)), pinned)
        XCTAssertEqual(raw.builtSectionRows, 3)
        dataSource.unbind()
    }

    /// Without `boundState` this aborts the process inside SKIE's collector.
    func testThrowingStateGoesToTheHandlerAndObserveReturns() async throws {
        let raw = RealBindingProbeViewModel()
        let model = ObservableBindingProbeViewModel(raw)
        let observation = Task { await model.observe() }
        try await eventually { raw.activeStateCollectors == 1 }
        try await model.setNote("edited")
        try await eventually { model.note == "edited" }
        raw.failState(message: "gone")
        // The handler's flow emits once and completes, so `observe()` returns by itself.
        await observation.value
        XCTAssertEqual(model.note, "failed: gone")
        XCTAssertEqual(model.message.text, "hello")
        XCTAssertEqual(raw.handledFailures, ["state: gone"])
        XCTAssertEqual(raw.activeStateCollectors, 0)
    }

    func testKvoThrowingStateGoesToTheHandler() async throws {
        let raw = RealBindingProbeViewModel()
        let model = KvoBindingProbeViewModel(raw)
        try await eventually { raw.activeStateCollectors == 1 }
        raw.failState(message: "gone")
        try await eventually { model.note == "failed: gone" }
        XCTAssertEqual(raw.handledFailures, ["state: gone"])
        model.unbind()
    }

    func testThrowingStateWithoutAHandlerGoesToTheGlobalFallback() async throws {
        let log = FailureLog()
        let previous = ViewModelFailures.shared.unhandled
        ViewModelFailures.shared.unhandled = { failure in log.messages.append("\(failure.viewModelName).\(failure.source): \(failure.error.message ?? "")") }
        defer { ViewModelFailures.shared.unhandled = previous }
        let model = ObservableBindingChildViewModel(failingBindingChildViewModel(message: "deleted"))
        await model.observe()
        XCTAssertEqual(model.title, "loaded")
        XCTAssertEqual(log.messages, ["com.latenighthack.basekit.demo.BindingChildViewModel.state: deleted"])
    }

    /// The path `DeltaListView` and `DeltaForEach` collect through.
    func testThrowingListKeepsItsRowsInADeltaList() async throws {
        let raw = RealBindingProbeViewModel()
        let model = ObservableBindingProbeViewModel(raw)
        let list = DeltaList<BindingChildViewModel>()
        let collection = Task { await model.rows.collect(into: list) }
        try await eventually { raw.activeRowCollectors == 1 }
        raw.replaceRows()
        try await eventually { list.totalSize == 1 }
        raw.failRows(message: "rows gone")
        await collection.value
        XCTAssertEqual(list.totalSize, 1)
        XCTAssertEqual(raw.handledFailures, ["rows: rows gone"])
    }

    func testThrowingListKeepsItsRowsInTheUIKitBinder() async throws {
        let raw = RealBindingProbeViewModel()
        let model = ObservableBindingProbeViewModel(raw)
        let layout = UICollectionViewFlowLayout()
        layout.itemSize = CGSize(width: 300, height: 40)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 640))
        let collectionView = UICollectionView(frame: window.bounds, collectionViewLayout: layout)
        collectionView.register(UICollectionViewCell.self, forCellWithReuseIdentifier: "row")
        window.addSubview(collectionView)
        window.isHidden = false
        let dataSource = model.bindRows(collectionView) { cv, indexPath, _ in
            cv.dequeueReusableCell(withReuseIdentifier: "row", for: indexPath)
        }
        try await eventually { raw.activeRowCollectors == 1 }
        raw.replaceRows()
        try await eventually { collectionView.numberOfItems(inSection: 0) == 1 }
        raw.failRows(message: "rows gone")
        try await eventually { raw.activeRowCollectors == 0 }
        XCTAssertEqual(raw.handledFailures, ["rows: rows gone"])
        XCTAssertEqual(collectionView.numberOfItems(inSection: 0), 1)
        dataSource.unbind()
    }

    private func eventually(_ condition: () -> Bool) async throws {
        for _ in 0..<200 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("State observation did not converge")
    }
}

@available(iOS 18.0, macOS 15.0, *)
private struct SheetsForPicks: BasekitNavigationPolicy {
    func decision(for request: BasekitNavigationRequest) -> BasekitNavigationDecision {
        request.edge == .homeOnPick ? .init(presentation: .sheet) : .init()
    }
}

private final class RecordingResponder: NSObject, NavigationResponder {
    var results: [Any?] = []
    func respond(response: Any?) { results.append(response) }
}

private final class RecordingActionObserver: NSObject, ViewModelActionObserver {
    var names: [String] = []
    func onAction(event: ViewModelActionEvent) { names.append(event.actionName) }
}

private final class FailureLog {
    var messages: [String] = []
}
