package com.mtgebay.app.ui.search

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mtgebay.app.data.CardRepository
import com.mtgebay.app.data.db.AppDatabase
import com.mtgebay.app.data.scryfall.ScryfallApi
import com.mtgebay.app.data.scryfall.ScryfallCard
import com.mtgebay.app.pricing.CardCondition
import com.mtgebay.app.pricing.PriceQuery
import com.mtgebay.app.pricing.PriceResult
import com.mtgebay.app.pricing.PriceSourceChain
import com.mtgebay.app.pricing.ScryfallPriceSource
import com.mtgebay.app.pricing.TcgTrackingApi
import com.mtgebay.app.pricing.TcgTrackingPriceSource
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
 *   1. User types a partial name → debounced autocomplete returns ~20 names.
 *   2. User taps a name → fetch all printings (`unique=prints`).
 *   3. User taps a printing → that's the chosen card; we trigger a price lookup
 *      using TCGTracking primary, Scryfall fallback.
 *   4. User can adjust condition (NM/LP/MP/HP/DMG) and foil — each change
 *      re-queries the chain.
 */
data class SearchUiState(
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val pickedName: String? = null,
    val printings: List<ScryfallCard> = emptyList(),
    val loadingPrintings: Boolean = false,
    val pickedCard: ScryfallCard? = null,
    val pickedCondition: CardCondition = CardCondition.NM,
    val pickedFoil: Boolean = false,
    val pricing: PriceResult? = null,
    val pricingLoading: Boolean = false,
    val pricingError: String? = null,
    val error: String? = null,
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val cardRepo = CardRepository(
        api = ScryfallApi.create(),
        dao = AppDatabase.get(application).scryfallCardDao(),
    )

    private val priceChain = PriceSourceChain(
        listOf(
            TcgTrackingPriceSource(TcgTrackingApi.create()),
            ScryfallPriceSource(cardRepo),
        ),
    )

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var autocompleteJob: Job? = null
    private var priceJob: Job? = null

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
                val names = cardRepo.autocomplete(query)
                _state.update { it.copy(suggestions = names) }
            } catch (e: CancellationException) {
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
                pricing = null,
                pricingLoading = false,
                pricingError = null,
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                val printings = cardRepo.searchByExactName(name)
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
        // If the printing exists only in foil, default the toggle to true; otherwise non-foil.
        val defaultFoil = card.finishes.size == 1 && card.finishes.first() == "foil"
        _state.update {
            it.copy(
                pickedCard = card,
                pickedCondition = CardCondition.NM,
                pickedFoil = defaultFoil,
                pricing = null,
                pricingError = null,
            )
        }
        refreshPrice()
    }

    fun onConditionChanged(condition: CardCondition) {
        _state.update { it.copy(pickedCondition = condition) }
        refreshPrice()
    }

    fun onFoilChanged(foil: Boolean) {
        _state.update { it.copy(pickedFoil = foil) }
        refreshPrice()
    }

    fun clearSelection() {
        priceJob?.cancel()
        _state.update {
            it.copy(
                pickedCard = null,
                pricing = null,
                pricingLoading = false,
                pricingError = null,
            )
        }
    }

    fun reset() {
        autocompleteJob?.cancel()
        priceJob?.cancel()
        _state.value = SearchUiState()
    }

    private fun refreshPrice() {
        val card = _state.value.pickedCard ?: return
        val condition = _state.value.pickedCondition
        val foil = _state.value.pickedFoil

        priceJob?.cancel()
        _state.update { it.copy(pricing = null, pricingLoading = true, pricingError = null) }
        priceJob = viewModelScope.launch {
            try {
                val result = priceChain.query(
                    PriceQuery(
                        scryfallId = card.id,
                        tcgplayerId = card.tcgplayerId,
                        setCode = card.set,
                        collectorNumber = card.collectorNumber,
                        isFoil = foil,
                        condition = condition,
                    ),
                )
                _state.update { it.copy(pricing = result, pricingLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "price lookup failed", e)
                _state.update {
                    it.copy(pricingLoading = false, pricingError = e.message ?: "Pricing failed")
                }
            }
        }
    }

    private companion object {
        const val TAG = "SearchViewModel"
        const val MIN_QUERY_LEN = 3
        const val DEBOUNCE_MS = 300L
    }
}
