# Optional PostHog navigation and action metrics

Add `com.latenighthack.basekit:basekit-navigation-posthog:<basekit-version>` to your KMP client.
It includes `basekit-navigation-metrics` and supplies the real PostHog Android and JavaScript SDK
dependencies in their platform variants. The SDK-free collector is also available on its own as
`basekit-navigation-metrics`, including JVM and Apple targets.

The app initializes PostHog and retains ownership of identity, consent, batching, and shutdown.
The shared reporter sees only `capture(event, properties)` and `screen(name, properties)`.
There is no global installation or per-action application instrumentation.

## Install at factory boundaries

Use the same collector for the navigator and the ViewModel returned by each factory:

```kotlin
fun home(navigator: HomeNavigator): HomeViewModel =
    RealHomeViewModel(store, ObservingHomeNavigator(navigator, metrics))
        .observingActions(metrics)
```

The ViewModel processor generates `Observing{Spec}` and a typed `observingActions` extension for
every specification. Calling the extension again with the same observer returns the same wrapper.
State streams, lists, child objects and explicit identity properties delegate to the implementation.
Install decorators at child/row factories too: decorating a parent does not replace its child graph.
Keep factories returning public specification interfaces, rather than implementation classes.

Actions are public zero-argument suspend methods, including inherited methods. They are captured
once before invocation, whether called from Kotlin or through Android, React, KVO or SwiftUI
bindings. One-argument mutators, `@CodegenIgnore` methods, internal self-calls and calls to an
undecorated implementation are not observed. Original exceptions and cancellation still propagate.

## Android

Add the optional coordinate to `commonMain` for shared clients, or to `androidMain` in a platform
integration module. Its Android variant exposes `com.posthog:posthog-android:3.72.1` transitively.

```kotlin
import com.latenighthack.basekit.navigation.posthog.postHogMetrics
import com.posthog.PostHog

// PostHog has already been configured by Application.
val metrics = postHogMetrics(PostHog)
```

The factory also accepts an independent configured `PostHogInterface` instance. It calls native
`capture` and `screen`; it does not initialize or reconfigure the SDK.

## JavaScript / React

For direct JavaScript consumers, package `build/generated/basekit-posthog-js` after running
`./gradlew :basekit-navigation-posthog:collectBasekitPostHogJavaScript`. This is an ESM npm package
named `@latenighthack/basekit-navigation-posthog`, with typed options and a dependency on
`posthog-js@1.438.1`. Kotlin/JS consumers receive the SDK dependency through Gradle automatically.
Versions 1.438.2 and 1.438.3 reference unpublished dependency declarations and fail strict TypeScript
checks; the integration pins the preceding compatible release.

```typescript
import posthog from 'posthog-js';
import { createPostHogMetrics } from '@latenighthack/basekit-navigation-posthog';

const metrics = createPostHogMetrics(posthog, {
  properties: { app: 'example' },
  propertyProviders: [event => ({ observation_kind: event.kind })],
});
const client = createClient(metrics); // your app's exported Kotlin/JS factory
metrics.recordScreen('HOME', { entry: 'cold' });
```

The corresponding app factory constructs the shared collector in its own compiled Kotlin module:

```kotlin
import com.latenighthack.basekit.navigation.posthog.JavaScriptPostHogMetrics
import com.latenighthack.basekit.navigation.posthog.asCollector

@JsExport
fun createClient(configuration: JavaScriptPostHogMetrics): ClientReference {
    val metrics = configuration.asCollector()
    return buildClient(metrics).reference()
}
```

The exported handle carries the configured SDK and plain options across npm package boundaries.
`asCollector()` keeps navigation's sealed Kotlin types in the app's runtime, even when an npm
distribution contains a separate copy of the library. Do not pass raw Kotlin observers between
independently compiled npm distributions.

## Swift / iOS

Add this repository as a Swift Package and select the `BasekitNavigationPostHog` product. It
includes `posthog-ios` 3.91.0. The package supports the existing iOS 18 / macOS 15 minimums.

In the module generating navigation, opt in to the bridge and export the shared collector in your
app's framework:

```kotlin
ksp {
    arg("basekit.navigation.posthog", "true")
    arg("basekit.navigation.swiftFrameworkImports", "MyClient")
}
// In your existing binaries.framework block:
export("com.latenighthack.basekit:basekit-navigation-metrics:<basekit-version>")
```

The dependency must also be an `api` dependency of the framework module. Continue exporting the
navigation and ViewModel runtime as required by your existing bindings. Include the generated
`BasekitPostHogBridge.swift` with the other collected navigation Swift sources. The bridge conforms
to the protocol in your app's framework; the Swift Package remains independent of that module name.

```swift
import MyClient
import PostHog
import BasekitNavigationPostHog

// PostHogSDK.shared has already been configured by the app.
let metrics = postHogMetrics(client: PostHogSDK.shared)
let navigator = ObservingHomeNavigator(delegate: realNavigator, observer: metrics)
let model = ObservingHomeViewModel(delegate: realModel, actionObserver: metrics)
```

Pass observed instances into the existing native wrappers or your shared client factory. No
generated binding file needs editing. The reporter forwards synchronous SDK methods without a
main-actor hop, so shared Kotlin actions can report from their calling dispatcher.

## Events and extensions

| Observation | Event | Timing |
| --- | --- | --- |
| Zero-argument action | `basekit action invoked` | Before invoking the implementation |
| Navigation request | `$screen` | Before invoking the concrete navigator |
| Close request | `basekit navigation closed` | Before invoking the concrete navigator |
| Responding navigation | `basekit navigation responded` | After successful return, including null dismissal |
| `recordScreen` | `$screen` | When the host calls it |

All platforms use `$screen` with `$screen_name`. JavaScript deliberately uses this convention
instead of `$pageview`. Automatic navigation metrics record intent; a failed presentation can
still have a request event. Initial screens and host-driven back gestures need a host
`recordScreen(name, properties)` call. Avoid calling it again for an already observed request.
Disable overlapping SDK screen autocapture for Basekit-managed screens in your SDK configuration.

Built-in properties are `basekit_viewmodel`, `basekit_action`, `basekit_screen`,
`basekit_navigation_operation` and, when generated source enums exist, `basekit_source`.
Screen names default to generated enum names. ViewModel names are qualified specification names
and action names are source method names; no runtime class reflection is used. Custom non-enum
screen identifiers default to `unknown`; provide a screen-name mapper for their explicit names.

```kotlin
val options = PostHogMetricsOptions(
    properties = mapOf("app" to "example", "environment" to "test"),
    propertyProviders = listOf({ observation ->
        when (observation) {
            is MetricsObservation.Action -> mapOf("feature" to featureFor(observation.event.viewModel))
            is MetricsObservation.Navigation -> extraNavigationProperties(observation.event)
            is MetricsObservation.Screen -> emptyMap()
        }
    }),
    filter = { observation -> shouldReport(observation) },
    eventName = { observation, defaultName -> customEventName(observation) ?: defaultName },
    screenName = { screen -> friendlyScreenName(screen) },
    onError = { error -> reportLocalMetricsError(error) },
)
val metrics = PostHogMetricsCollector(reporter, options)
```

Providers receive original action implementations or navigation observations, including arguments,
context and results. None of those values, nor state or domain identity, are serialized automatically.
Supply only explicit JSON properties: strings, booleans, finite numbers, lists and maps with string
keys. Nested null values are supported; omit absent top-level properties. Domain objects, cycles,
non-finite numbers and excessively deep structures are rejected without stringifying them.

Static properties merge first, then manual screen properties and property providers in order.
The `basekit_` namespace and `$screen_name` are reserved for authoritative metadata. Use the
screen-name mapper to change a screen name; custom event naming does not rename `$screen` events.
Callbacks run synchronously on the calling thread and must be fast and safe for concurrent calls.
An extension or reporting failure discards that report and calls the guarded error handler;
it never prevents an action or navigation from executing. SDK opt-out remains effective.

## Validation and distribution

Collector tests run on JVM and Apple; generated decorators are compiled with real KSP consumers.
Android and Swift adapter tests capture actual SDK events through local before-send hooks. Browser
acceptance tests cover the typed npm boundary, action exclusions and unified navigation events.

The Maven modules use Basekit's unified version and release workflow. The JavaScript package is
collected as a release artifact; npm publication and app distribution are separate actions.
For paired Fullhouse checks, use its isolated `fh deps` workflow and immutable development versions.
