import { createBindingProbe, createBindingChild, BrowserProbeMessage as ProbeMessage } from 'basekit-demo-core';
import { useBindingProbeViewModel, type BindingProbeViewModelBinding, type PickerViewModelOptionsElement } from 'basekit-react';

function contracts() {
  const state = useBindingProbeViewModel(createBindingProbe());
  const text: string = state.message.text;
  const optional: string | undefined = state.optionalMessage?.text;
  state.setNote(null); state.setNote(text); state.setMessage(new ProbeMessage('ok'));
  state.setTags(['one']); state.setFailure('RETRY');
  // @ts-expect-error wrong mutator argument
  state.setNote(42);
  // @ts-expect-error state property does not exist
  state.nonexistent;
  // @ts-expect-error unrelated object
  useBindingProbeViewModel({});
  // @ts-expect-error reference belongs to a different specification
  useBindingProbeViewModel(createBindingChild());
  return optional;
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
type IsAny<T> = 0 extends (1 & T) ? true : false;
const noAny: IsAny<ReturnType<typeof useBindingProbeViewModel>> = false;
const noStateAny: IsAny<BindingProbeViewModelBinding['message']> = false;
