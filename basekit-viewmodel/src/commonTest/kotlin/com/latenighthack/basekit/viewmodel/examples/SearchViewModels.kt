package com.latenighthack.basekit.viewmodel.examples

import com.latenighthack.basekit.viewmodel.StatefulViewModel
import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.ViewModelList
import com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.operators.filterItems
import com.latenighthack.deltalist.operators.ifEmpty
import com.latenighthack.deltalist.operators.lazyMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

// The example's domain ID; a product uses its existing canonical ID type.
data class CatalogItemId(val value: Int)

data class CatalogItem(val id: CatalogItemId, val title: String, val favorite: Boolean)

// The use case exposes the repository's live DeltaList. The VM never copies it into state.
fun interface ObserveCatalog {
    fun observe(): DeltaList<CatalogItem>
}

fun interface OpenCatalogItem {
    suspend fun open(id: CatalogItemId)
}

interface SearchListItem

@ViewModelSpec
interface CatalogItemViewModel : SearchListItem, ViewModel<CatalogItemViewModel.State> {
    data class State(val title: String, val favorite: Boolean)
    val id: CatalogItemId
    suspend fun open()
}

@ViewModelSpec
interface SearchEmptyViewModel : SearchListItem, ViewModel<SearchEmptyViewModel.State> {
    data class State(val text: String = "", val favoritesOnly: Boolean = false)
    suspend fun clearFilters()
}

@ViewModelSpec
interface SearchViewModel : ViewModel<SearchViewModel.State> {
    // State belongs to the spec interface and contains only scalar input.
    data class State(val text: String = "", val favoritesOnly: Boolean = false)

    suspend fun updateText(text: String)
    suspend fun updateFavoritesOnly(enabled: Boolean)
    suspend fun clearFilters()

    @ViewModelList(CatalogItemViewModel::class, SearchEmptyViewModel::class)
    val items: Flow<Delta<SearchListItem>>
}

// Rows have no local draft. A changed domain item is remapped by lazyMap.
private class CatalogRow(
    item: CatalogItem,
    private val openItem: OpenCatalogItem,
) : CatalogItemViewModel {
    override val id = item.id
    override val initialState = CatalogItemViewModel.State(item.title, item.favorite)
    override val state: Flow<CatalogItemViewModel.State> = emptyFlow()
    override suspend fun open() = openItem.open(id)
}

// One real child reused whenever this screen's filtered list is empty.
// Its state is a direct Flow; the row binding owns that collection.
private class EmptySearchRow(private val owner: SearchViewModel) : SearchEmptyViewModel {
    override val initialState = SearchEmptyViewModel.State(
        owner.initialState.text, owner.initialState.favoritesOnly,
    )
    override val state: Flow<SearchEmptyViewModel.State> = owner.state
        .map { SearchEmptyViewModel.State(it.text, it.favoritesOnly) }
        .distinctUntilChanged()
    override suspend fun clearFilters() = owner.clearFilters()
}

private data class SearchCriteria(val query: String, val favoritesOnly: Boolean)

// Fullhouse's shape: state -> flatMapLatest(observation/filter) -> lazyMap -> ifEmpty.
// filterItems preserves DeltaList coordinates and soft/lazy access.
@OptIn(ExperimentalCoroutinesApi::class)
private fun SearchViewModel.searchItems(
    catalog: ObserveCatalog,
    openItem: OpenCatalogItem,
): Flow<Delta<SearchListItem>> {
    val emptyItem = EmptySearchRow(this)
    return state
        .map { SearchCriteria(it.text.trim(), it.favoritesOnly) }
        .distinctUntilChanged()
        .flatMapLatest { criteria ->
            catalog.observe().filterItems { item ->
                (!criteria.favoritesOnly || item.favorite) &&
                    (criteria.query.isEmpty() || item.title.contains(criteria.query, ignoreCase = true))
            }
        }
        .lazyMap<CatalogItem, SearchListItem> { CatalogRow(it, openItem) }
        .ifEmpty { emptyItem }
}

class StatefulSearchViewModel(
    catalog: ObserveCatalog,
    openItem: OpenCatalogItem,
) : StatefulViewModel<SearchViewModel.State>(SearchViewModel.State()), SearchViewModel {
    override suspend fun updateText(text: String) = update { copy(text = text) }
    override suspend fun updateFavoritesOnly(enabled: Boolean) = update { copy(favoritesOnly = enabled) }
    override suspend fun clearFilters() = update { SearchViewModel.State() }

    override val items: Flow<Delta<SearchListItem>> = searchItems(catalog, openItem)
}

class FlowSearchViewModel(
    catalog: ObserveCatalog,
    openItem: OpenCatalogItem,
) : SearchViewModel {
    override val initialState = SearchViewModel.State()
    private val input = MutableStateFlow(initialState)
    override val state: Flow<SearchViewModel.State> = input.asStateFlow()

    override suspend fun updateText(text: String) = input.update { it.copy(text = text) }
    override suspend fun updateFavoritesOnly(enabled: Boolean) = input.update { it.copy(favoritesOnly = enabled) }
    override suspend fun clearFilters() = input.update { SearchViewModel.State() }

    override val items: Flow<Delta<SearchListItem>> = searchItems(catalog, openItem)
}
