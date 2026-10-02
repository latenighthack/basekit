import XCTest
@testable import BasekitBindingConsumer

@MainActor
final class TextInputTests: XCTestCase {
    func testFlushDrainsEditsArrivingDuringDelayedDispatch() async throws {
        var firstGate: CheckedContinuation<Void, Never>?
        var secondGate: CheckedContinuation<Void, Never>?
        var dispatched: [String] = []
        let input = AcknowledgedTextInput(text: "", revision: 0) { text, revision in
            dispatched.append(text)
            await withCheckedContinuation { continuation in
                if revision == 1 { firstGate = continuation }
                else { secondGate = continuation }
            }
        }
        input.edit("a")
        while firstGate == nil { await Task.yield() }
        var started = false
        var finished = false
        let saving = Task { @MainActor in
            started = true
            try await input.flush()
            finished = true
        }
        while !started { await Task.yield() }
        input.edit("ab")
        firstGate?.resume()
        while secondGate == nil { await Task.yield() }
        for _ in 0..<10 { await Task.yield() }
        XCTAssertFalse(finished, "Save must wait for edits queued during the first dispatch")
        XCTAssertEqual(dispatched, ["a", "ab"])
        secondGate?.resume()
        try await saving.value
        XCTAssertTrue(finished)
    }

    func testRapidRepeatedEditsAcknowledgementAndSaveResetOrdering() async throws {
        var dispatched: [(String, Int32)] = []
        let input = AcknowledgedTextInput(text: "", revision: 0) { text, revision in
            dispatched.append((text, revision))
        }
        input.edit("a"); input.edit("ab"); input.edit("ab"); input.edit("abc")
        input.acknowledge(text: "a", revision: 1)
        XCTAssertEqual(input.text, "abc")
        try await input.flush() // Immediate Save drains all writes.
        XCTAssertEqual(dispatched.map(\.0), ["a", "ab", "ab", "abc"])
        XCTAssertEqual(dispatched.map(\.1), [1, 2, 3, 4])
        input.acknowledge(text: "abc", revision: 4)
        XCTAssertEqual(input.text, "abc")
        input.acknowledge(text: "stale", revision: 2)
        XCTAssertEqual(input.text, "abc")
        try await input.flush() // Reset preserves the last acknowledged revision.
        input.acknowledge(text: "", revision: 4)
        XCTAssertEqual(input.text, "")
        input.edit("new")
        try await input.flush()
        XCTAssertEqual(dispatched.last?.1, 5)
    }
}
