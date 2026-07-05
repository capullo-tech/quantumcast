package tech.capullo.quantumcast.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.capullo.quantumcast.data.model.Station
import tech.capullo.quantumcast.viewmodel.PlayerState
import tech.capullo.quantumcast.viewmodel.RadioViewModel
import tech.capullo.quantumcast.viewmodel.UiState

private enum class SortBy { KBPS, VOTES, CLICKS }
private enum class SortDir { ASC, DESC }
private enum class SearchPhase { SEARCH, FILTER }

@Composable
fun SearchScreen(
    uiState: UiState<List<Station>>,
    playerState: PlayerState,
    favoriteUuids: Set<String>,
    onSearch: (String) -> Unit,
    onResetSearch: () -> Unit = {},
    onShuffleRotation: () -> Unit = {},
    isShuffleLoading: Boolean = false,
    shuffleConnected: Boolean = false,
    onStartCustomRotation: (List<Station>) -> Unit = {},
    onPlay: (Station) -> Unit,
    onToggleFavorite: (Station) -> Unit,
    vm: RadioViewModel? = null,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current

    // Single-field state - persisted in VM if available
    var phase by remember { mutableStateOf(if (uiState is UiState.Idle) SearchPhase.SEARCH else SearchPhase.FILTER) }
    var fieldText by remember { mutableStateOf("") }

    // Sort state - from VM for persistence
    var sortBy by remember { mutableStateOf(vm?.searchSortBy?.let { runCatching { SortBy.valueOf(it) }.getOrNull() }) }
    var sortDir by remember { mutableStateOf(if (vm?.searchSortDir == "ASC") SortDir.ASC else SortDir.DESC) }

    var inSelectMode by remember { mutableStateOf(false) }
    var selectedUuids by remember { mutableStateOf(setOf<String>()) }

    // Sync phase with uiState from outside (e.g. initial load)
    LaunchedEffect(uiState) {
        if (uiState is UiState.Idle && phase == SearchPhase.FILTER) {
            phase = SearchPhase.SEARCH
            fieldText = ""
        }
    }

    fun doSearch(q: String) {
        if (q.isBlank()) return
        onSearch(q)
        phase = SearchPhase.FILTER
        fieldText = ""
        focusManager.clearFocus()
    }

    fun resetToSearch() {
        fieldText = ""
        phase = SearchPhase.SEARCH
        onResetSearch()
    }

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
        // Single search/filter field
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = fieldText,
                onValueChange = { fieldText = it },
                placeholder = {
                    Text(if (phase == SearchPhase.SEARCH) "Search stations..." else "Filter results...")
                },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (fieldText.isNotEmpty() || phase == SearchPhase.FILTER) {
                        IconButton(onClick = {
                            if (phase == SearchPhase.FILTER) {
                                resetToSearch()
                            } else {
                                fieldText = ""
                            }
                        }) {
                            Icon(
                                if (phase == SearchPhase.FILTER) Icons.Default.Close else Icons.Default.Clear,
                                if (phase == SearchPhase.FILTER) "New search" else "Clear",
                            )
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = if (phase == SearchPhase.SEARCH) ImeAction.Search else ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onSearch = { if (phase == SearchPhase.SEARCH) doSearch(fieldText) else focusManager.clearFocus() },
                    onDone = { focusManager.clearFocus() },
                ),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.weight(1f),
            )
        }

        when (uiState) {
            is UiState.Idle -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        modifier = Modifier.padding(horizontal = 48.dp),
                    ) {
                        // Animation only appears once discovery starts - no idle icon
                        if (isShuffleLoading) {
                            QuantumEntangleIcon(
                                connected = shuffleConnected,
                                modifier = Modifier.size(96.dp),
                            )
                        }
                        FilledTonalButton(
                            onClick = { if (!isShuffleLoading) onShuffleRotation() },
                            enabled = !isShuffleLoading,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Text("Discovery", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            "or search for stations above",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                    val visibleStations = remember(uiState.data, sortBy, sortDir, fieldText) {
                        computeVisibleStations(uiState.data, sortBy, sortDir, fieldText)
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

                        // Discovery - same quantum icon as the discovery animation;
                        // orbits while loading, all dots lit when idle/connected
                        FilledTonalIconButton(
                            onClick = { if (!isShuffleLoading) onShuffleRotation() },
                            enabled = !isShuffleLoading,
                            modifier = Modifier.size(36.dp),
                        ) {
                            QuantumEntangleIcon(
                                connected = if (isShuffleLoading) shuffleConnected else true,
                                color = LocalContentColor.current,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        // Auto button
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

// Quantum entangle icon - same geometry and choreography as the web player's
// connection status icon (center node + 5 pentagon dots, lines always drawn).
// Connecting: one bright dot travels the ring in hard 200ms steps (1s cycle).
// Connected: all dots fade fully lit - held briefly before now-playing opens.
@Composable
fun QuantumEntangleIcon(
    connected: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    var lit by remember { mutableIntStateOf(0) }
    LaunchedEffect(connected) {
        while (!connected) {
            delay(200)
            lit = (lit + 1) % 5
        }
    }
    val dotAlphas = List(5) { i ->
        animateFloatAsState(
            targetValue = if (connected || i == lit) 1f else 0.15f,
            animationSpec = tween(if (connected) 350 else 60),
            label = "qdot$i",
        ).value
    }
    val centerAlpha by animateFloatAsState(
        targetValue = if (connected) 1f else 0.5f,
        animationSpec = tween(350),
        label = "qcenter",
    )
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val c = Offset(12f * u, 12f * u)
        val pts = listOf(12f to 4.5f, 19.13f to 9.68f, 16.41f to 18.07f, 7.59f to 18.07f, 4.87f to 9.68f)
            .map { (x, y) -> Offset(x * u, y * u) }
        for (p in pts) drawLine(color.copy(alpha = 0.4f), c, p, strokeWidth = 1.2f * u)
        drawCircle(color.copy(alpha = centerAlpha), 2.3f * u, c)
        pts.forEachIndexed { i, p -> drawCircle(color.copy(alpha = dotAlphas[i]), 1.9f * u, p) }
    }
}
