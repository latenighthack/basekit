import React, { StrictMode, Suspense, startTransition, use, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserBindingProbe, BrowserProbeMessage as ProbeMessage, BrowserRenderProbe } from 'basekit-demo-core';
import { useBindingProbeViewModel, useRenderProbeViewModel, type BindingProbeViewModelRowsElement, type RenderProbeViewModelRef, type RenderProbeViewModelRowsElement, type RenderProbeViewModelBinding } from 'basekit-react';
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
    <button onClick={() => void model.setFailure('try-again')}>Enum</button>
    <button onClick={() => void model.fail().catch(e => setError(String(e)))}>Fail</button>
    <button onClick={() => probe.populate()}>Populate</button>
    <button onClick={() => probe.staticRows('static original')}>Static original</button>
    <button onClick={() => probe.staticRows('static replacement')}>Static replacement</button>
    <button onClick={() => probe.empty()}>Empty rows</button>
    <button onClick={() => probe.failState('gone')}>Fail state</button>
    <button onClick={() => probe.failRows('rows gone')}>Fail rows</button>
    <button onClick={() => void model.waitUntilCancelled().catch(() => {})}>Wait</button>
    <div id="rows">{model.rows.map(item => <Row key={item.key} item={item} />)}</div>
    <output id="error">{error}</output>
  </>;
}
const renderProbes = { hot: new BrowserRenderProbe(0), delayed: new BrowserRenderProbe(100), abandoned: new BrowserRenderProbe(0) };
const renders: Record<string, Array<{ greeting: string; count: number; rows: number }>> = { hot: [], delayed: [] };
const identity = { renders: 0, sameObject: [] as boolean[], sameIncrement: [] as boolean[], sameRows: [] as boolean[] };
Object.assign(window, { renderProbes, renders, identity });
function RenderProbe({ name, reference }: { name: 'hot' | 'delayed'; reference: RenderProbeViewModelRef }) {
  const model = useRenderProbeViewModel(reference);
  renders[name].push({ greeting: model.greeting, count: model.count, rows: model.rows.length });
  return <output id={`render-${name}`}>{model.greeting}:{model.count}:{model.rows.map((item, index) => <React.Fragment key={item.key}>{index > 0 && ','}<RowTitle item={item} /></React.Fragment>)}</output>;
}
function RowTitle({ item }: { item: RenderProbeViewModelRowsElement }) {
  return <>{item.use().title}</>;
}
function IdentityProbe() {
  const model = useRenderProbeViewModel(renderProbes.hot.reference);
  const [, setTick] = useState(0);
  const previous = useRef<RenderProbeViewModelBinding | null>(null);
  if (previous.current) {
    identity.renders++;
    identity.sameObject.push(previous.current === model);
    identity.sameIncrement.push(previous.current.increment === model.increment);
    identity.sameRows.push(previous.current.rows === model.rows);
  }
  previous.current = model;
  return <>
    <button onClick={() => setTick(t => t + 1)}>Rerender</button>
    <button onClick={() => void model.increment()}>Increment</button>
  </>;
}
const never = new Promise<never>(() => {});
function Abandoned() {
  useRenderProbeViewModel(renderProbes.abandoned.reference);
  return use(never);
}
function App() {
  const [mounted, setMounted] = useState(true);
  const [abandon, setAbandon] = useState(false);
  return <>
    <button onClick={() => setMounted(!mounted)}>Mount</button>{mounted && <Probe />}
    <RenderProbe name="hot" reference={renderProbes.hot.reference} />
    <RenderProbe name="delayed" reference={renderProbes.delayed.reference} />
    <IdentityProbe />
    <button onClick={() => startTransition(() => setAbandon(true))}>Abandon</button>
    <Suspense fallback={<output id="fallback">loading</output>}>
      <output id="committed">{abandon ? 'abandoned' : 'current'}</output>
      {abandon && <Abandoned />}
    </Suspense>
  </>;
}
createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>);
