// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "BasekitNavigationPostHog",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "BasekitNavigationPostHog", targets: ["BasekitNavigationPostHog"])],
    dependencies: [.package(url: "https://github.com/PostHog/posthog-ios.git", exact: "3.91.0")],
    targets: [
        .target(name: "BasekitNavigationPostHog", dependencies: [.product(name: "PostHog", package: "posthog-ios")], path: "basekit-navigation-posthog/src/appleMain/swift"),
        .testTarget(name: "BasekitNavigationPostHogTests", dependencies: ["BasekitNavigationPostHog"], path: "basekit-navigation-posthog/src/appleTest/swift"),
    ]
)
