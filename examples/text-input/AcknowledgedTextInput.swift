import Combine
import Foundation

/// Application-owned adapter for asynchronous shared state. Revisions acknowledge edits, including
/// repeated equal strings. The shared implementation must echo revisions monotonically and retain
/// the revision across Reset. Call flush() before Save or Reset, and route thrown errors to the UI.
@MainActor
public final class AcknowledgedTextInput: ObservableObject {
    @Published public private(set) var text: String
    private var nextRevision: Int32
    private var acknowledgedRevision: Int32
    private var pending: [(revision: Int32, text: String)] = []
    private var tasks: [Task<Void, Error>] = []
    private var tail: Task<Void, Error>?
    private let send: @MainActor (String, Int32) async throws -> Void

    public init(text: String, revision: Int32, send: @escaping @MainActor (String, Int32) async throws -> Void) {
        self.text = text
        self.nextRevision = revision
        self.acknowledgedRevision = revision
        self.send = send
    }

    /// Return the task so the host can display dispatch failures. Later edits wait for earlier ones.
    @discardableResult
    public func edit(_ value: String) -> Task<Void, Error> {
        precondition(nextRevision < Int32.max, "Create a new editing session before revision overflow")
        nextRevision += 1
        let revision = nextRevision
        pending.append((revision, value))
        text = value
        let previous = tail
        let send = send
        let task = Task { @MainActor in
            try await previous?.value
            try Task.checkCancellation()
            try await send(value, revision)
        }
        tasks.append(task)
        tail = task
        return task
    }

    public func acknowledge(text sharedText: String, revision: Int32) {
        guard revision >= acknowledgedRevision else { return }
        acknowledgedRevision = revision
        nextRevision = max(nextRevision, revision)
        pending.removeAll { $0.revision <= revision }
        text = pending.last?.text ?? sharedText
    }

    public func flush() async throws {
        while let current = tail {
            try await current.value
            // Editing can continue while dispatch suspends. Drain those newer edits as well.
            if tail == current {
                tasks.removeAll()
                return
            }
        }
    }
    /// Cancel a disposed screen's queued work. Persist drafts in the shared model if needed.
    public func cancel() { tasks.forEach { $0.cancel() }; tasks.removeAll() }
}
