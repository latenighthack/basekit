import React, { StrictMode, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserBindingProbe, BrowserProbeMessage as ProbeMessage } from 'basekit-demo-core';
import { useBindingProbeViewModel, type BindingProbeViewModelRowsElement } from 'basekit-react';
import posthog from 'posthog-js';
import { createPostHogMetrics } from '@latenighthack/basekit-navigation-posthog';
const metricsEvents: Array<{ event: string; properties: Record<string, unknown> }> = [];
const metricsErrors: string[] = [];
posthog.init('phc_basekit_test', {
  api_host: window.location.origin, autocapture: false, capture_pageview: false,
  capture_pageleave: false, disable_session_recording: true, persistence: 'memory',
  opt_out_useragent_filter: true, // Headless Chromium is intentionally part of this SDK acceptance fixture.
  advanced_disable_feature_flags: true,
  before_send: event => { if (event) metricsEvents.push({ event: event.event, properties: event.properties }); return null; },
});
const metrics = createPostHogMetrics(posthog, {
  properties: { app: 'browser-fixture', nested: { number: 2, flag: true } },
  onError: error => metricsErrors.push(String(error)),
});
const probe = new BrowserBindingProbe(metrics);
const reference = probe.reference;
Object.assign(window, { bindingProbe: probe, metricsEvents, metricsErrors, basekitMetrics: metrics });
function Row({ item }: { item: BindingProbeViewModelRowsElement }) {
  const child = item.use();
  return <button data-testid="row" data-key={item.key} onClick={() => void child.select()}>{child.title}</button>;
}
function Probe() {
  const model = useBindingProbeViewModel(reference);
  const [error, setError] = useState('');
  return <>
    <output id="note">{model.note ?? 'null'}</output>
    <output id="message">{model.optionalMessage?.text ?? 'null'}</output>
    <output id="failure">{model.failure}</output>
    <button onClick={() => void model.setNote('changed')}>Edit</button>
    <button onClick={() => void model.setNote(null)}>Clear</button>
    <button onClick={() => void model.setMessage(new ProbeMessage('custom'))}>Custom</button>
    <button onClick={() => void model.setFailure('RETRY')}>Enum</button>
    <button onClick={() => void model.fail().catch(e => setError(String(e)))}>Fail</button>
    <button onClick={() => probe.populate()}>Populate</button>
    <button onClick={() => probe.staticRows('static original')}>Static original</button>
    <button onClick={() => probe.staticRows('static replacement')}>Static replacement</button>
    <button onClick={() => probe.empty()}>Empty rows</button>
    <button onClick={() => void model.waitUntilCancelled().catch(() => {})}>Wait</button>
    <div id="rows">{model.rows.map(item => <Row key={item.key} item={item} />)}</div>
    <output id="error">{error}</output>
  </>;
}
function App() {
  const [mounted, setMounted] = useState(true);
  return <><button onClick={() => setMounted(!mounted)}>Mount</button>{mounted && <Probe />}</>;
}
createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>);
