import XCTest
import UIKit
import DemoCore
@testable import BasekitBindingConsumer

@MainActor
final class NavigationControllerHostTests: XCTestCase {
    func testReplacingTheTopEntryShowsItsScreenAndKeepsTheOthers() async throws {
        let fixture = HostFixture()
        defer { fixture.close() }
        try await eventually(fixture) { fixture.hasRootAppeared }
        let rootController = fixture.navigationController.viewControllers.first
        let first = fixture.entry()
        fixture.router.reconcilePath([first])
        try await eventually(fixture) { fixture.isSettled && fixture.shownEntryIDs == [fixture.root.id, first.id] }

        let replacement = fixture.entry()
        fixture.router.reconcilePath([replacement])
        try await eventually(fixture) { fixture.isSettled && fixture.shownEntryIDs == [fixture.root.id, replacement.id] }
        XCTAssertTrue(fixture.navigationController.viewControllers.first === rootController, "The root keeps its screen")
        XCTAssertEqual(fixture.builtEntryIDs, [fixture.root.id, first.id, replacement.id], "Each screen is built once")
    }

    func testAChangeDuringATransitionWaitsForItAndShowsThePathAsItIsThen() async throws {
        let fixture = HostFixture()
        defer { fixture.close() }
        try await eventually(fixture) { fixture.hasRootAppeared }
        let push = HeldAnimator()
        fixture.host.animatorProvider = { _, _, _, _ in push.handOutOnce() }
        let pushed = fixture.entry()
        fixture.router.reconcilePath([pushed])
        try await eventually(fixture) { push.isRunning }

        let replacement = fixture.entry()
        fixture.router.reconcilePath([replacement])
        XCTAssertEqual(fixture.builtEntryIDs, [fixture.root.id, pushed.id], "Nothing changes while the push runs")

        push.finish()
        try await eventually(fixture) { fixture.isSettled && fixture.shownEntryIDs == [fixture.root.id, replacement.id] }
        XCTAssertEqual(fixture.builtEntryIDs, [fixture.root.id, pushed.id, replacement.id])
    }

    func testDroppingEntriesFromTheTopPopsBackToTheScreenThatStays() async throws {
        let fixture = HostFixture()
        defer { fixture.close() }
        try await eventually(fixture) { fixture.hasRootAppeared }
        let first = fixture.entry()
        let second = fixture.entry()
        fixture.router.reconcilePath([first])
        try await eventually(fixture) { fixture.isSettled && fixture.shownEntryIDs.count == 2 }
        let firstController = fixture.navigationController.viewControllers.last
        fixture.router.reconcilePath([first, second])
        try await eventually(fixture) { fixture.isSettled && fixture.shownEntryIDs.count == 3 }

        fixture.router.reconcilePath([first])
        try await eventually(fixture) { fixture.isSettled && fixture.shownEntryIDs == [fixture.root.id, first.id] }
        XCTAssertTrue(fixture.navigationController.viewControllers.last === firstController, "The screen that stays keeps its state")
    }

    private func eventually(
        _ fixture: HostFixture,
        line: UInt = #line,
        _ condition: () -> Bool
    ) async throws {
        for _ in 0..<300 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Condition did not converge: \(fixture.report)", line: line)
    }
}

/// A UIKit host in a visible window, so its transitions run, with screens that know their entry.
@MainActor
private final class HostFixture {
    let router = BasekitNavigationRouter()
    let root: BasekitNavigationEntry
    let host: BasekitNavigationControllerHost
    private let builds = BuildLog()
    // On the app's scene, so the window is on screen and its transitions run to completion.
    private let window: UIWindow

    init() {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow()
        window.frame = CGRect(x: 0, y: 0, width: 320, height: 480)
        root = router.root(.home(args: HomeViewModelArgs(), edge: nil))
        host = BasekitNavigationControllerHost(router: router, root: root) { [builds] entry in
            builds.entryIDs.append(entry.id)
            return EntryController(entryID: entry.id)
        }
        window.rootViewController = host.navigationController
        window.makeKeyAndVisible()
    }

    var navigationController: UINavigationController { host.navigationController }

    /// The entries whose screens were built, in order.
    var builtEntryIDs: [UUID] { builds.entryIDs }

    var isSettled: Bool { navigationController.transitionCoordinator == nil }

    /// The root is on screen, as it is before anything navigates in an app; until then UIKit doesn't animate.
    var hasRootAppeared: Bool { (navigationController.viewControllers.first as? EntryController)?.hasAppeared == true }

    /// What the host shows and the router holds, for a failure.
    var report: String {
        let names = [root.id: "root"]
        let shown = shownEntryIDs.map { names[$0] ?? String($0.uuidString.prefix(4)) }
        let path = router.path.map { String($0.id.uuidString.prefix(4)) }
        return "shown \(shown), path \(path), transition \(navigationController.transitionCoordinator != nil), window \(navigationController.view.window != nil)"
    }

    var shownEntryIDs: [UUID] {
        navigationController.viewControllers.compactMap { ($0 as? EntryController)?.entryID }
    }

    func entry() -> BasekitNavigationEntry {
        BasekitNavigationEntry(route: .detail(args: DetailViewModelArgs(), edge: nil))
    }

    func close() {
        window.isHidden = true
    }
}

/// A transition that runs until the test finishes it.
@MainActor
private final class HeldAnimator: NSObject, UIViewControllerAnimatedTransitioning {
    private var context: UIViewControllerContextTransitioning?
    private var isHandedOut = false

    var isRunning: Bool { context != nil }

    /// This animator for the first transition, and the default for every later one.
    func handOutOnce() -> UIViewControllerAnimatedTransitioning? {
        guard !isHandedOut else { return nil }
        isHandedOut = true
        return self
    }

    func transitionDuration(using transitionContext: UIViewControllerContextTransitioning?) -> TimeInterval { 0.3 }

    func animateTransition(using transitionContext: UIViewControllerContextTransitioning) {
        if let arriving = transitionContext.view(forKey: .to) {
            transitionContext.containerView.addSubview(arriving)
        }
        context = transitionContext
    }

    func finish() {
        context?.completeTransition(true)
        context = nil
    }
}

@MainActor
private final class BuildLog {
    var entryIDs: [UUID] = []
}

private final class EntryController: UIViewController {
    let entryID: UUID
    private(set) var hasAppeared = false

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        hasAppeared = true
    }

    init(entryID: UUID) {
        self.entryID = entryID
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("Not used")
    }
}
