import DemoCore
import SwiftUI

@main
struct ConsumerApp: App {
    var body: some Scene { WindowGroup { ProbeScreen() } }
}

@MainActor
private final class ProbeSession: ObservableObject {
    let raw: RealBindingProbeViewModel
    let model: ObservableBindingProbeViewModel
    init() {
        raw = RealBindingProbeViewModel()
        model = ObservableBindingProbeViewModel(raw)
    }
}

@MainActor
struct ProbeScreen: View {
    @StateObject private var session = ProbeSession()
    @StateObject private var rows = DeltaList<BindingChildViewModel>()
    @State private var useList = false
    @State private var visible = true
    private var model: ObservableBindingProbeViewModel { session.model }
    var body: some View {
        VStack {
            Button("Use List") { useList = true }
            Button(visible ? "Hide list" : "Show list") { visible.toggle() }
            Button("Populate") { session.raw.replaceRows() }
            Button("Empty") { session.raw.clearRows() }
            if visible {
                if useList {
                    List { rowContent }
                        .task(id: ObjectIdentifier(model.rows)) { await model.rows.collect(into: rows) }
                } else {
                    Form { rowContent }
                        .task(id: ObjectIdentifier(model.rows)) { await model.rows.collect(into: rows) }
                }
            } else { Spacer() }
        }.task { await model.observe() }
    }
    private var rowContent: some View {
        DeltaForEach(model.rows, observing: rows) { ProbeRow(model: $0) }
    }
}

@MainActor
private struct ProbeRow: View {
    @ObservedObject var model: ObservableBindingChildViewModel
    var body: some View {
        Button(model.title) { Task { try await model.select() } }
    }
}
