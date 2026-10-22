import XCTest
import DemoCore
import PostHog
@testable import BasekitBindingConsumer

@MainActor
final class MetricsTests: XCTestCase {
    func testGeneratedBridgeUsesNativeSDKAndSharedCollector() async throws {
        var events: [PostHogEvent] = []
        var errors: [String] = []
        let config = PostHogConfig(projectToken: "phc_basekit_consumer", host: "http://127.0.0.1:1")
        config.captureScreenViews = false
        config.captureApplicationLifecycleEvents = false
        config.preloadFeatureFlags = false
        config.remoteConfig = false
        config.setBeforeSend { event in events.append(event); return nil }
        let client = PostHogSDK.with(config)
        client.optIn()
        defer { client.optIn(); client.close() }
        let options = PostHogMetricsOptions(properties: ["app": "swift-fixture"], propertyProviders: [],
            filter: nil, eventName: nil, screenName: nil, onError: { errors.append($0.message ?? "unknown") })
        let metrics = postHogMetrics(client: client, options: options)
        metrics.recordScreen(name: "HOME", properties: ["nested": ["number": 2, "flag": true, "array": [1, NSNull()]]])
        let raw = RealBindingProbeViewModel()
        let observed = ObservingBindingProbeViewModel(delegate: raw, actionObserver: metrics)
        let model = ObservableBindingProbeViewModel(observed)
        try await model.setNote("edited")
        do { try await model.fail(); XCTFail("Expected original action error") } catch {}
        XCTAssertEqual(errors, [])
        XCTAssertEqual(events.map(\.event), ["$screen", "action_invoked"])
        XCTAssertEqual(events[0].properties["$screen_name"] as? String, "HOME")
        XCTAssertEqual((events[0].properties["nested"] as? [String: Any])?["number"] as? Int, 2)
        XCTAssertEqual(events[1].properties["action"] as? String, "fail")
        XCTAssertEqual(events[1].properties["app"] as? String, "swift-fixture")
    }

    func testNavigationThroughARouterRecordsEachScreenOnce() async throws {
        var events: [PostHogEvent] = []
        let config = PostHogConfig(projectToken: "phc_basekit_consumer", host: "http://127.0.0.1:1")
        config.captureScreenViews = false
        config.captureApplicationLifecycleEvents = false
        config.preloadFeatureFlags = false
        config.remoteConfig = false
        config.setBeforeSend { event in events.append(event); return nil }
        let client = PostHogSDK.with(config)
        client.optIn()
        defer { client.optIn(); client.close() }
        let metrics = postHogMetrics(client: client)
        let router = BasekitNavigationRouter(navigationObserver: metrics, actionObserver: metrics)

        // The root is the host's: the router reports it the first time it shows.
        let root = router.root(.home(args: HomeViewModelArgs(), edge: nil))
        router.didShow(root)
        router.didShow(root)
        // The view model reports its action, and its navigator the screen it opens; the router adds nothing.
        guard case .home(let home) = basekitViewModel(for: root, host: router, factories: MetricsFactories()) else {
            return XCTFail("Expected the home view model")
        }
        try await home.onOpenDetail()
        try await eventually { router.path.count == 1 }
        router.didShow(try XCTUnwrap(router.path.last))
        // Back is the host's: the router reports the screen it closes.
        router.reconcilePath([])
        // A deep link is the router's.
        XCTAssertTrue(router.open(args: PickerViewModelArgs()))
        // A router made for presented content reports to the same collector.
        XCTAssertTrue(BasekitNavigationRouter(parent: router).navigationObserver === metrics)

        XCTAssertEqual(events.map(\.event), ["$screen", "action_invoked", "$screen", "navigation_closed", "$screen"])
        // The SDK carries the last screen onto later events, so read it from the screen events.
        XCTAssertEqual([0, 2, 4].map { events[$0].properties["$screen_name"] as? String }, ["HOME", "DETAIL", "PICKER"])
        XCTAssertEqual(events[1].properties["action"] as? String, "onOpenDetail")
        XCTAssertEqual(events[2].properties["source"] as? String, "HOME_ON_OPEN_DETAIL")
        XCTAssertEqual(events[3].properties["screen"] as? String, "DETAIL")
        XCTAssertTrue(events.allSatisfy { event in !event.event.contains("basekit") && !event.properties.keys.contains { $0.contains("basekit") } })
    }

    private func eventually(_ condition: () -> Bool) async throws {
        for _ in 0..<300 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Condition did not converge")
    }
}

/// Builds the demo's view models directly.
private final class MetricsFactories: NSObject, GeneratedViewModelFactories {
    private let store = InMemoryDemoStore()
    var detailFactory: (DetailViewModelArgs) -> RealDetailViewModel { { [store] args in RealDetailViewModel(store: store, args: args) } }
    var homeFactory: (any HomeNavigator) -> RealHomeViewModel { { [store] navigator in RealHomeViewModel(store: store, navigator: navigator) } }
    var pickerFactory: (any NavigationResponder) -> RealPickerViewModel { { [store] responder in RealPickerViewModel(store: store, responder: responder) } }
}
