import Foundation
import PostHog

/// The app initializes and owns this SDK. No identity, consent, queue or lifecycle configuration is changed.
public final class PostHogReportingAdapter {
    private let client: PostHogSDK

    public init(client: PostHogSDK) { self.client = client }

    public func capture(event: String, properties: [String: Any]) {
        client.capture(event, properties: properties)
    }

    public func screen(name: String, properties: [String: Any]) {
        client.screen(name, properties: properties)
    }
}
