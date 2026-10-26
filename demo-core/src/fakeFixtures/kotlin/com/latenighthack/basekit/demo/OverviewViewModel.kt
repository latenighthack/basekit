package com.latenighthack.basekit.demo

import com.latenighthack.basekit.fake.annotations.FakePlaceholder
import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.ViewModelIdentity
import com.latenighthack.basekit.viewmodel.annotations.ViewModelList
import com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec
import com.latenighthack.deltalist.Delta
import kotlinx.coroutines.flow.Flow

// Compiled only in fake builds, as the fixture for list sizes and placeholder rows: a screen of sections
// with no fake annotations on its sizes, each holding credentials or, when it has none, a placeholder.

@ViewModelSpec
interface OverviewViewModel : ViewModel<OverviewViewModel.State> {
    data class State(val title: String)

    @ViewModelList(OverviewSectionViewModel::class)
    val sections: Flow<Delta<OverviewSectionViewModel>>
}

@ViewModelSpec
interface OverviewSectionViewModel : ViewModel<OverviewSectionViewModel.State> {
    data class State(val heading: String)

    // The implementation would build this as credentials.lazyMap { … }.ifEmpty { noCredentialsRow }.
    @ViewModelList(CredentialItemViewModel::class, NoCredentialsItemViewModel::class)
    @FakePlaceholder(NoCredentialsItemViewModel::class)
    val items: Flow<Delta<OverviewRowViewModel>>
}

/** Common supertype of a section's rows. */
interface OverviewRowViewModel

@ViewModelSpec
interface CredentialItemViewModel : ViewModel<CredentialItemViewModel.State>, OverviewRowViewModel {
    data class State(val name: String)

    @ViewModelIdentity
    val id: String
}

@ViewModelSpec
interface NoCredentialsItemViewModel : ViewModel<NoCredentialsItemViewModel.State>, OverviewRowViewModel {
    data class State(val message: String)
}
