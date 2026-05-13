package com.mtgebay.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.mtgebay.app.data.scryfall.ScryfallCard

/**
 * Manual card search screen. Three sub-states the UI renders linearly:
 *  - **Browse**: text field with autocomplete suggestions below it.
 *  - **Printings list**: after picking a name, all printings of that card.
 *  - **Picked**: after picking a printing, full card detail.
 */
@Composable
fun ManualSearchScreen(
    viewModel: SearchViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        SearchField(
            query = state.query,
            onQueryChanged = viewModel::onQueryChanged,
            onClear = viewModel::reset,
        )
        Spacer(Modifier.height(12.dp))

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        when {
            state.pickedCard != null ->
                PickedCardPanel(card = state.pickedCard!!, onBack = viewModel::clearSelection)

            state.loadingPrintings ->
                LoadingRow("Loading printings…")

            state.printings.isNotEmpty() ->
                PrintingsList(
                    pickedName = state.pickedName,
                    printings = state.printings,
                    onPick = viewModel::onPrintingSelected,
                )

            state.suggestions.isNotEmpty() ->
                SuggestionsList(state.suggestions, viewModel::onNameSelected)

            state.query.isBlank() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Type at least 3 characters of a card name.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

            else ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No suggestions yet…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear")
                }
            }
        },
        singleLine = true,
        label = { Text("Card name") },
        placeholder = { Text("e.g. Doubling Season") },
    )
}

@Composable
private fun SuggestionsList(suggestions: List<String>, onPick: (String) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(suggestions) { i, name ->
            Text(
                text = name,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(name) }
                    .padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (i < suggestions.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun PrintingsList(
    pickedName: String?,
    printings: List<ScryfallCard>,
    onPick: (ScryfallCard) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (pickedName != null) {
            Text(
                "Printings of \"$pickedName\" (${printings.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(printings, key = { it.id }) { card ->
                PrintingRow(card, onClick = { onPick(card) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PrintingRow(card: ScryfallCard, onClick: () -> Unit) {
    val thumbUrl = card.imageUris?.small ?: card.cardFaces?.firstOrNull()?.imageUris?.small
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbUrl != null) {
            AsyncImage(
                model = thumbUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(width = 60.dp, height = 84.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(width = 60.dp, height = 84.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(card.setName, style = MaterialTheme.typography.titleSmall)
            Text(
                "${card.set.uppercase()} #${card.collectorNumber}" +
                    (card.rarity?.let { " · ${it.replaceFirstChar(Char::uppercase)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            card.releasedAt?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PickedCardPanel(card: ScryfallCard, onBack: () -> Unit) {
    val imageUrl = card.imageUris?.normal ?: card.cardFaces?.firstOrNull()?.imageUris?.normal
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(card.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "${card.setName} · ${card.set.uppercase()} #${card.collectorNumber}" +
                    (card.rarity?.let { " · ${it.replaceFirstChar(Char::uppercase)}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "scryfall_id: ${card.id}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            card.tcgplayerId?.let {
                Text("tcgplayer_id: $it", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onBack) { Text("Back to printings") }
        }
    }
}

@Composable
private fun LoadingRow(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}
