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
        XCTAssertEqual(events.map(\.event), ["$screen", "basekit action invoked"])
        XCTAssertEqual(events[0].properties["$screen_name"] as? String, "HOME")
        XCTAssertEqual((events[0].properties["nested"] as? [String: Any])?["number"] as? Int, 2)
        XCTAssertEqual(events[1].properties["basekit_action"] as? String, "fail")
        XCTAssertEqual(events[1].properties["app"] as? String, "swift-fixture")
    }
}
