import type { PostHog } from 'posthog-js';
import type { JavaScriptPostHogMetrics as RuntimeMetrics } from './basekit-basekit-navigation-posthog.mjs';

export type JSONValue = string | number | boolean | null | JSONValue[] | { [key: string]: JSONValue };
export type Properties = Record<string, JSONValue>;
export type Observation =
  | { kind: 'action'; viewModelName: string; actionName: string; viewModel: unknown; raw: unknown }
  | { kind: 'navigation'; operation: 'navigate' | 'close' | 'respond'; screenName: string; raw: unknown }
  | { kind: 'screen'; screenName: string; raw: unknown };
export interface MetricsOptions {
  properties?: Properties;
  propertyProviders?: Array<(observation: Observation) => Properties>;
  filter?: (observation: Observation) => boolean;
  eventName?: (observation: Observation, defaultName: string) => string;
  screenName?: (screen: string) => string;
  onError?: (error: unknown) => void;
}
export interface JavaScriptPostHogMetrics extends RuntimeMetrics {
  readonly sdkClient: PostHog;
  readonly configuration: MetricsOptions | null | undefined;
  recordScreen(name: string, properties?: Properties): void;
}
export declare function createPostHogMetrics(client: PostHog, options?: MetricsOptions): JavaScriptPostHogMetrics;
