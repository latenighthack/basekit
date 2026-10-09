import XCTest
import PostHog
@testable import BasekitNavigationPostHog

final class PostHogReportingAdapterTests: XCTestCase {
    func testNativeSDKReceivesScreenAndCustomProperties() {
        var events: [PostHogEvent] = []
        let config = PostHogConfig(projectToken: "phc_basekit_test", host: "http://127.0.0.1:1")
        config.captureScreenViews = false
        config.captureApplicationLifecycleEvents = false
        config.preloadFeatureFlags = false
        config.remoteConfig = false
        config.setBeforeSend { event in events.append(event); return nil }
        let client = PostHogSDK.with(config)
        client.optIn()
        defer { client.optIn(); client.close() }
        let adapter = PostHogReportingAdapter(client: client)
        adapter.screen(name: "DETAIL", properties: ["domain": "fixture", "nested": ["number": 2, "flag": true]])
        adapter.capture(event: "basekit action invoked", properties: ["basekit_action": "save"])
        XCTAssertEqual(events.map(\.event), ["$screen", "basekit action invoked"])
        XCTAssertEqual(events[0].properties["$screen_name"] as? String, "DETAIL")
        XCTAssertEqual(events[0].properties["domain"] as? String, "fixture")
        XCTAssertEqual((events[0].properties["nested"] as? [String: Any])?["number"] as? Int, 2)
        XCTAssertEqual(events[1].properties["basekit_action"] as? String, "save")
        client.optOut()
        adapter.capture(event: "opted out", properties: [:])
        XCTAssertEqual(events.count, 2)
    }
}
