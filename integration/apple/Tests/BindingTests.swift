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

    private func eventually(_ condition: () -> Bool) async throws {
        for _ in 0..<200 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("State observation did not converge")
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
