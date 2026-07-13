package tech.capullo.quantumcast.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import tech.capullo.quantumcast.viewmodel.PlayerState
import tech.capullo.quantumcast.viewmodel.RadioViewModel
import tech.capullo.quantumcast.viewmodel.UiState
import tech.capullo.source.radiobrowser.data.model.Station

private enum class SortBy { KBPS, VOTES, CLICKS }
private enum class SortDir { ASC, DESC }

// Search RESULTS screen - pushed from the home after a query is submitted there. Shows the results
// with an in-results filter field, sort menu, multi-select, and the "Auto" (custom rotation) button.
// The query input and Discovery button live on the home ("Select a station").
@Composable
fun SearchScreen(
    uiState: UiState<List<Station>>,
    playerState: PlayerState,
    favoriteUuids: Set<String>,
    onPlay: (Station) -> Unit,
    onToggleFavorite: (Station) -> Unit,
    onStartCustomRotation: (List<Station>) -> Unit = {},
    vm: RadioViewModel? = null,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current

    var filterText by remember { mutableStateOf("") }

    // Sort state - from VM for persistence
    var sortBy by remember { mutableStateOf(vm?.searchSortBy?.let { runCatching { SortBy.valueOf(it) }.getOrNull() }) }
    var sortDir by remember { mutableStateOf(if (vm?.searchSortDir == "ASC") SortDir.ASC else SortDir.DESC) }

    var inSelectMode by remember { mutableStateOf(false) }
    var selectedUuids by remember { mutableStateOf(setOf<String>()) }

    fun saveSortToVm() {
        vm?.searchSortBy = sortBy?.name
        vm?.searchSortDir = sortDir.name
    }

    fun cycleSort(clicked: SortBy) {
        when {
            sortBy != clicked -> {
                sortBy = clicked
                sortDir = SortDir.DESC
            }
            sortDir == SortDir.DESC -> sortDir = SortDir.ASC
            else -> sortBy = null
        }
        saveSortToVm()
    }

    fun enterSelectMode(uuid: String) {
        inSelectMode = true
        selectedUuids = setOf(uuid)
    }
    fun exitSelectMode() {
        inSelectMode = false
        selectedUuids = emptySet()
    }

    Column(modifier = modifier.fillMaxSize()) {
        when (uiState) {
            is UiState.Idle -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Search for stations from the home screen",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 48.dp),
                    )
                }
            }
            is UiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is UiState.Error -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(uiState.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is UiState.Success -> {
                if (uiState.data.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No stations found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    // In-results filter field
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = filterText,
                            onValueChange = { filterText = it },
                            placeholder = { Text("Filter results...") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            trailingIcon = {
                                if (filterText.isNotEmpty()) {
                                    IconButton(onClick = { filterText = "" }) {
                                        Icon(Icons.Default.Clear, "Clear")
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    val visibleStations = remember(uiState.data, sortBy, sortDir, filterText) {
                        computeVisibleStations(uiState.data, sortBy, sortDir, filterText)
                    }

                    var showSortMenu by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box {
                            val sortLabel = when (sortBy) {
                                SortBy.KBPS -> "kbps ${if (sortDir == SortDir.DESC) "↓" else "↑"}"
                                SortBy.VOTES -> "Votes ${if (sortDir == SortDir.DESC) "↓" else "↑"}"
                                SortBy.CLICKS -> "Clicks ${if (sortDir == SortDir.DESC) "↓" else "↑"}"
                                null -> null
                            }
                            OutlinedButton(
                                onClick = { showSortMenu = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(36.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            ) {
                                Icon(Icons.Default.Sort, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(sortLabel ?: "Sort by", style = MaterialTheme.typography.labelMedium)
                                Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp))
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                listOf(SortBy.KBPS to "kbps", SortBy.VOTES to "Votes", SortBy.CLICKS to "Clicks")
                                    .forEach { (sort, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                cycleSort(sort)
                                                showSortMenu = false
                                            },
                                            trailingIcon = if (sortBy == sort) {
                                                { Icon(if (sortDir == SortDir.DESC) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, null, modifier = Modifier.size(14.dp)) }
                                            } else {
                                                null
                                            },
                                        )
                                    }
                                if (sortBy != null) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Clear") },
                                        onClick = {
                                            sortBy = null
                                            saveSortToVm()
                                            showSortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))

                        // Select mode banner inline
                        if (inSelectMode) {
                            TextButton(onClick = {
                                selectedUuids = if (selectedUuids.size == visibleStations.size) {
                                    emptySet()
                                } else {
                                    visibleStations.map { it.uuid }.toSet()
                                }
                            }) {
                                Text(if (selectedUuids.size == visibleStations.size) "None" else "All")
                            }
                            IconButton(onClick = ::exitSelectMode, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, "Exit", modifier = Modifier.size(18.dp))
                            }
                        }

                        // Auto button - custom rotation of the visible (or selected) stations
                        val autoStations = if (inSelectMode) {
                            visibleStations.filter { it.uuid in selectedUuids }.ifEmpty { visibleStations }
                        } else {
                            visibleStations
                        }
                        FilledTonalButton(onClick = {
                            exitSelectMode()
                            onStartCustomRotation(autoStations)
                        }) {
                            Icon(Icons.Default.Radio, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (inSelectMode && selectedUuids.isNotEmpty()) "Auto (${selectedUuids.size})" else "Auto (${visibleStations.size})")
                        }
                    }

                    if (visibleStations.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No stations match filter", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(visibleStations, key = { it.uuid }) { station ->
                                StationCard(
                                    station = station,
                                    isPlaying = playerState.station?.uuid == station.uuid && playerState.isPlaying,
                                    isFavorite = station.uuid in favoriteUuids,
                                    onPlay = { onPlay(station) },
                                    onToggleFavorite = { onToggleFavorite(station) },
                                    inSelectMode = inSelectMode,
                                    isSelected = station.uuid in selectedUuids,
                                    onSelect = {
                                        selectedUuids = if (station.uuid in selectedUuids) {
                                            selectedUuids - station.uuid
                                        } else {
                                            selectedUuids + station.uuid
                                        }
                                    },
                                    onEnterSelectMode = { enterSelectMode(station.uuid) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun computeVisibleStations(
    data: List<Station>,
    sortBy: SortBy?,
    sortDir: SortDir,
    filterText: String,
): List<Station> {
    var list = data
    if (filterText.isNotBlank()) {
        list = list.filter { s ->
            s.name.contains(filterText, ignoreCase = true) ||
                s.country.contains(filterText, ignoreCase = true) ||
                s.tags.contains(filterText, ignoreCase = true)
        }
    }
    val asc = sortDir == SortDir.ASC
    return when (sortBy) {
        SortBy.KBPS -> if (asc) list.sortedBy { it.bitrate } else list.sortedByDescending { it.bitrate }
        SortBy.VOTES -> if (asc) list.sortedBy { it.votes } else list.sortedByDescending { it.votes }
        SortBy.CLICKS -> if (asc) list.sortedBy { it.clickCount } else list.sortedByDescending { it.clickCount }
        null -> list
    }
}
