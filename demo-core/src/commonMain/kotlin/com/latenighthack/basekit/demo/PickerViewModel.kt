package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.NavigationResponder
import com.latenighthack.basekit.navigation.NavigatorArgs
import com.latenighthack.basekit.navigation.RespondingDestination
import com.latenighthack.basekit.navigation.annotations.Destination
import com.latenighthack.basekit.viewmodel.StatefulViewModel
import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.ViewModelInject
import com.latenighthack.basekit.viewmodel.annotations.ViewModelList
import com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec
import com.latenighthack.basekit.viewmodel.tui.annotations.TuiScreen
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.mutableDeltaListOf
import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

/** The value the picker hands back to whoever opened it (null when dismissed). */
data class PickResult(val selectedId: Int)

/**
 * The picker: a [RespondingDestination] rendered as a list of options. Selecting a row responds with a
 * [PickResult], which resumes the suspended `navigateToPicker` caller; dismissing (Esc) resumes it with
 * null. The impl is built with an `@Assisted` [NavigationResponder] the TUI host supplies per push.
 */
@Destination
@ViewModelSpec
@TuiScreen(PickerViewModel::class)
interface PickerViewModel :
    RespondingDestination<PickerViewModel.Args, PickResult>,
    ViewModel<PickerViewModel.State> {

    class Args : NavigatorArgs()

    data class State(val title: String)

    // Polymorphic on purpose: option rows plus a placeholder row when there is nothing to pick. This
    // is the demo's coverage for `possibleTypes` binding — the Apple enum, the React `kind` handle, the
    // TUI per-type rows and the Android per-type row specs are all generated from this one declaration.
    @ViewModelList(PickerOptionViewModel::class, PickerEmptyViewModel::class)
    val options: Flow<Delta<PickerRowViewModel>>
}

/** Common supertype of the picker's rows. A marker, so it is deliberately NOT a `ViewModel<S>`. */
interface PickerRowViewModel

/** Shown in place of the options when the store has none. */
@ViewModelSpec
interface PickerEmptyViewModel : PickerRowViewModel, ViewModel<PickerEmptyViewModel.State> {
    data class State(val message: String)
}

/** One selectable option row. Selecting it responds with its id. */
@ViewModelSpec
interface PickerOptionViewModel : PickerRowViewModel, ViewModel<PickerOptionViewModel.State> {
    data class State(val label: String, val id: Int)

    suspend fun onSelected()
}

class RealPickerOptionViewModel(
    option: PickerOption,
    private val responder: NavigationResponder<PickResult>,
) : PickerOptionViewModel, StatefulViewModel<PickerOptionViewModel.State>(
    PickerOptionViewModel.State(option.label, option.id),
) {
    override suspend fun onSelected() = withState { state ->
        responder.respond(PickResult(state.id))
    }
}

class RealPickerEmptyViewModel : PickerEmptyViewModel, StatefulViewModel<PickerEmptyViewModel.State>(
    PickerEmptyViewModel.State(message = "Nothing to pick"),
)

@ViewModelInject
class RealPickerViewModel @Inject constructor(
    store: DemoStore,
    @Assisted responder: NavigationResponder<PickResult>,
) : PickerViewModel, StatefulViewModel<PickerViewModel.State>(PickerViewModel.State(title = "Pick one")) {

    private val optionsList = mutableDeltaListOf<PickerRowViewModel>(
        store.pickerOptions()
            .map { RealPickerOptionViewModel(it, responder) }
            .ifEmpty { listOf(RealPickerEmptyViewModel()) },
    )

    override val options: Flow<Delta<PickerRowViewModel>> get() = optionsList
}
