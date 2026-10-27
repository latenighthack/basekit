import { createBindingProbe, createBindingChild, BrowserProbeMessage as ProbeMessage } from 'basekit-demo-core';
import { BasekitNavigationStack, useBasekitNavigation, useDetailViewModel, useBindingProbeViewModel, type BasekitDestination, type BasekitNavigationStackProps, type BindingProbeViewModelBinding, type BindingProbeViewModelShelvesHeader, type PickerViewModelOptionsElement } from 'basekit-react';
import posthog from 'posthog-js';
import { createPostHogMetrics } from '@latenighthack/basekit-navigation-posthog';
import { BrowserBindingProbe, BrowserNavigationProbe, BasekitBrowserHistory, createBasekitNavigationRouter } from 'basekit-demo-core';

const metrics = createPostHogMetrics(posthog, { propertyProviders: [event => ({ kind: event.kind })] });
new BrowserBindingProbe(metrics);
// @ts-expect-error client must expose the real SDK capture contract
createPostHogMetrics({});
// @ts-expect-error domain objects cannot be automatically serialized
createPostHogMetrics(posthog, { properties: { date: new Date() } });

function contracts() {
  const state = useBindingProbeViewModel(createBindingProbe());
  const text: string = state.message.text;
  const optional: string | undefined = state.optionalMessage?.text;
  state.setNote(null); state.setNote(text); state.setMessage(new ProbeMessage('ok'));
  state.setTags(['one']); state.setFailure('try-again'); state.setFailure('none');
  const failure: 'none' | 'try-again' = state.failure;
  // @ts-expect-error React spells enum entries with the module option and @ReactEnumName
  state.setFailure('RETRY');
  const key: string | number | undefined = state.rows.keyAt(0);
  // @ts-expect-error wrong mutator argument
  state.setNote(42);
  // @ts-expect-error state property does not exist
  state.nonexistent;
  // @ts-expect-error unrelated object
  useBindingProbeViewModel({});
  // @ts-expect-error reference belongs to a different specification
  useBindingProbeViewModel(createBindingChild());
  return [optional, failure, key];
}
function exhaustive(child: PickerViewModelOptionsElement): void {
  switch (child.kind) {
    case 'pickerOption': child.use().onSelected(); return;
    case 'pickerEmpty': child.use().message; return;
    default: { const never: never = child; return never; }
  }
}
function incomplete(child: PickerViewModelOptionsElement): void {
  if (child.kind === 'pickerOption') return;
  // @ts-expect-error missing pickerEmpty
  const never: never = child;
}
const shelf: BindingProbeViewModelShelvesHeader = 'top';
// @ts-expect-error enum section headers use the React spelling too
const kotlinShelf: BindingProbeViewModelShelvesHeader = 'TOP';
type IsAny<T> = 0 extends (1 & T) ? true : false;
const noAny: IsAny<ReturnType<typeof useBindingProbeViewModel>> = false;
const noStateAny: IsAny<BindingProbeViewModelBinding['message']> = false;

// The generated web router: `kind` narrows a destination's reference to its own hook's.
function destinationContracts(destination: BasekitDestination) {
  switch (destination.kind) {
    case 'detail': useDetailViewModel(destination.ref).count; return;
    case 'home': case 'picker': return;
    default: { const never: never = destination; return never; }
  }
}
function wrongDestination(destination: BasekitDestination) {
  // @ts-expect-error a home reference is not a detail reference
  if (destination.kind === 'home') useDetailViewModel(destination.ref);
}
const navigationProbe = new BrowserNavigationProbe();
const stack = useBasekitNavigation(navigationProbe.web);
const top: string | undefined = stack.path[stack.path.length - 1]?.screen;
const unsubscribe: () => void = navigationProbe.web.subscribe(() => {});
const opened: boolean = navigationProbe.web.open('/detail/7');
new BasekitBrowserHistory(navigationProbe.web, navigationProbe.root).attach();
createBasekitNavigationRouter(request => request.screen === 'picker' ? 'sheet' : 'push');
const stackProps: BasekitNavigationStackProps = {
  router: navigationProbe.web, root: navigationProbe.root, destinations: navigationProbe.destinations,
  render: destination => destination.kind,
};
BasekitNavigationStack(stackProps);
// @ts-expect-error the stack needs its destinations
const missing: BasekitNavigationStackProps = { router: navigationProbe.web, root: navigationProbe.root, render: () => null };
