package com.latenighthack.basekit.demo

@com.latenighthack.basekit.fake.GeneratedFake(com.latenighthack.basekit.demo.PickerViewModel::class)
@com.latenighthack.basekit.viewmodel.annotations.ViewModelInject
public class FakePickerViewModel(
    override val fakeData: com.latenighthack.basekit.fake.FakeData,
    fakeArgs: com.latenighthack.basekit.demo.PickerViewModel.Args? = null,
    private val fakeResponder: com.latenighthack.basekit.fake.FakeResponder? = null,
) : com.latenighthack.basekit.demo.PickerViewModel, com.latenighthack.basekit.fake.FakeViewModel {

    @me.tatarka.inject.annotations.Inject
    public constructor(
        @me.tatarka.inject.annotations.Assisted responder: com.latenighthack.basekit.navigation.NavigationResponder<com.latenighthack.basekit.demo.PickResult>,
    ) : this(com.latenighthack.basekit.fake.BasekitFakes.data("PickerViewModel"), fakeResponder = com.latenighthack.basekit.fake.FakeResponder { data -> responder.respond(com.latenighthack.basekit.demo.FakeValues.pickResult(data.child("response"))) })

    override val initialState: com.latenighthack.basekit.demo.PickerViewModel.State = com.latenighthack.basekit.demo.FakeValues.pickerViewModelState(fakeData.child("state"), fakeArgs)

    private val fakeState: kotlinx.coroutines.flow.MutableStateFlow<com.latenighthack.basekit.demo.PickerViewModel.State> =
        kotlinx.coroutines.flow.MutableStateFlow(initialState)

    override val state: kotlinx.coroutines.flow.Flow<com.latenighthack.basekit.demo.PickerViewModel.State> get() = fakeState

    private val optionsRows: List<com.latenighthack.basekit.demo.PickerRowViewModel> =
        fakeData.list("options", fakeData.size("options")) { row -> when (row.choose("type", intArrayOf(1, 0))) { 0 -> com.latenighthack.basekit.demo.FakePickerOptionViewModel(row, fakeResponder = fakeResponder); else -> com.latenighthack.basekit.demo.FakePickerEmptyViewModel(row) } }
            .ifEmpty { listOf<com.latenighthack.basekit.demo.PickerRowViewModel>(com.latenighthack.basekit.demo.FakePickerEmptyViewModel(fakeData.child("optionsPlaceholder"))) }

    override val options: kotlinx.coroutines.flow.Flow<com.latenighthack.deltalist.Delta<com.latenighthack.basekit.demo.PickerRowViewModel>> =
        com.latenighthack.deltalist.mutableDeltaListOf<com.latenighthack.basekit.demo.PickerRowViewModel>(optionsRows)

    override val fakeActions: List<com.latenighthack.basekit.fake.FakeAction> get() = emptyList()

    override val fakeEmbedded: List<com.latenighthack.basekit.fake.FakeViewModel> get() = optionsRows.filterIsInstance<com.latenighthack.basekit.fake.FakeViewModel>()
}
