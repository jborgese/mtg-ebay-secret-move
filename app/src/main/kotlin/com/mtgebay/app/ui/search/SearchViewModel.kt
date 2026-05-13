package com.mtgebay.app.ui.search

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mtgebay.app.data.CardRepository
import com.mtgebay.app.data.db.AppDatabase
import com.mtgebay.app.data.scryfall.ScryfallApi
import com.mtgebay.app.data.scryfall.ScryfallCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Manual search flow:
 *   1. User types a partial name → debounced autocomplete call returns up to ~20 names.
 *   2. User taps a name → fetch all printings (`unique=prints` from Scryfall search).
 *   3. User taps a printing → that's the chosen card; UI shows its details.
 *
 * Minimum-3-character query keeps autocomplete from firing on every keystroke
 * for very short prefixes that would return too many results.
 */
data class SearchUiState(
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val pickedName: String? = null,
    val printings: List<ScryfallCard> = emptyList(),
    val loadingPrintings: Boolean = false,
    val pickedCard: ScryfallCard? = null,
    val error: String? = null,
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = CardRepository(
        api = ScryfallApi.create(),
        dao = AppDatabase.get(application).scryfallCardDao(),
    )

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var autocompleteJob: Job? = null

    fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query, error = null) }
        autocompleteJob?.cancel()
        if (query.length < MIN_QUERY_LEN) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        autocompleteJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            try {
                val names = repo.autocomplete(query)
                _state.update { it.copy(suggestions = names) }
            } catch (e: CancellationException) {
                // Job replaced by a newer keystroke — not an error.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "autocomplete failed", e)
                _state.update { it.copy(error = "Autocomplete: ${e.message}") }
            }
        }
    }

    fun onNameSelected(name: String) {
        autocompleteJob?.cancel()
        _state.update {
            it.copy(
                query = name,
                suggestions = emptyList(),
                pickedName = name,
                printings = emptyList(),
                loadingPrintings = true,
                pickedCard = null,
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                val printings = repo.searchByExactName(name)
                _state.update { it.copy(printings = printings, loadingPrintings = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "search printings failed", e)
                _state.update {
                    it.copy(
                        loadingPrintings = false,
                        error = "Search: ${e.message}",
                    )
                }
            }
        }
    }

    fun onPrintingSelected(card: ScryfallCard) {
        _state.update { it.copy(pickedCard = card) }
    }

    fun clearSelection() {
        _state.update { it.copy(pickedCard = null) }
    }

    fun reset() {
        autocompleteJob?.cancel()
        _state.value = SearchUiState()
    }

    private companion object {
        const val TAG = "SearchViewModel"
        const val MIN_QUERY_LEN = 3
        const val DEBOUNCE_MS = 300L
    }
}
