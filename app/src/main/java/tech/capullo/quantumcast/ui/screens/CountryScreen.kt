package tech.capullo.quantumcast.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.capullo.quantumcast.data.model.Country
import tech.capullo.quantumcast.data.model.Station
import tech.capullo.quantumcast.viewmodel.PlayerState
import tech.capullo.quantumcast.viewmodel.RadioViewModel
import tech.capullo.quantumcast.viewmodel.UiState

private fun countryCodeToFlag(code: String): String {
    if (code.length != 2) return ""
    val base = 0x1F1E6 - 0x41
    return String(Character.toChars(code[0].uppercaseChar().code + base)) +
        String(Character.toChars(code[1].uppercaseChar().code + base))
}

@Composable
fun CountryScreen(
    countryList: UiState<List<Country>>,
    countryStations: UiState<List<Station>>,
    selectedCountry: Country?,
    playerState: PlayerState,
    favoriteUuids: Set<String>,
    onLoadCountries: () -> Unit,
    onSelectCountry: (Country) -> Unit,
    onBack: () -> Unit,
    onPlay: (Station) -> Unit,
    onToggleFavorite: (Station) -> Unit,
    onStartCustomRotation: (List<Station>) -> Unit = {},
    vm: RadioViewModel? = null,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onLoadCountries() }
    BackHandler(enabled = selectedCountry != null) { onBack() }

    if (selectedCountry != null) {
        CountryStationsView(
            country = selectedCountry,
            uiState = countryStations,
            playerState = playerState,
            favoriteUuids = favoriteUuids,
            onBack = onBack,
            onPlay = onPlay,
            onToggleFavorite = onToggleFavorite,
            onStartCustomRotation = onStartCustomRotation,
            vm = vm,
            modifier = modifier,
        )
    } else {
        CountryListView(
            uiState = countryList,
            onSelect = onSelectCountry,
            vm = vm,
            modifier = modifier,
        )
    }
}

@Composable
private fun CountryListView(
    uiState: UiState<List<Country>>,
    onSelect: (Country) -> Unit,
    vm: RadioViewModel? = null,
    modifier: Modifier = Modifier,
) {
    var filterQuery by remember { mutableStateOf("") }
    // Sort state lives in VM so it survives navigation; fall back to local if no VM
    var sortByName by remember { mutableStateOf(vm?.countryListSortByName ?: false) }
    var sortAscending by remember { mutableStateOf(vm?.countryListSortAscending ?: false) }

    fun setSort(byName: Boolean, asc: Boolean) {
        sortByName = byName
        sortAscending = asc
        vm?.countryListSortByName = byName
        vm?.countryListSortAscending = asc
    }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filterQuery,
            onValueChange = { filterQuery = it },
            placeholder = { Text("Filter countries...") },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        var showSortMenu by remember { mutableStateOf(false) }
        Box(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
            val sortLabel = "${if (sortByName) "Name" else "Count"} ${if (sortAscending) "↑" else "↓"}"
            OutlinedButton(
                onClick = { showSortMenu = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Icon(Icons.Default.Sort, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(sortLabel, style = MaterialTheme.typography.labelMedium)
                Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                DropdownMenuItem(
                    text = { Text("By Count") },
                    onClick = {
                        if (sortByName) {
                            setSort(false, false)
                        } else {
                            setSort(false, !sortAscending)
                        }
                        showSortMenu = false
                    },
                    trailingIcon = if (!sortByName) {
                        { Icon(if (sortAscending) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(14.dp)) }
                    } else {
                        null
                    },
                )
                DropdownMenuItem(
                    text = { Text("By Name") },
                    onClick = {
                        if (!sortByName) {
                            setSort(true, true)
                        } else {
                            setSort(true, !sortAscending)
                        }
                        showSortMenu = false
                    },
                    trailingIcon = if (sortByName) {
                        { Icon(if (sortAscending) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(14.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
        when (uiState) {
            is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(uiState.message, color = MaterialTheme.colorScheme.error)
            }
            is UiState.Success -> {
                val filtered = uiState.data
                    .filter { filterQuery.isBlank() || it.name.contains(filterQuery, ignoreCase = true) }
                    .let { list ->
                        if (sortByName) {
                            if (sortAscending) list.sortedBy { it.name } else list.sortedByDescending { it.name }
                        } else {
                            if (sortAscending) list.sortedBy { it.stationCount } else list.sortedByDescending { it.stationCount }
                        }
                    }
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(filtered, key = { it.code.ifEmpty { it.name } }) { country ->
                        CountryRow(country = country, onClick = { onSelect(country) })
                    }
                }
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun CountryRow(country: Country, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = countryCodeToFlag(country.code).ifEmpty { "🌐" },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(country.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (country.code.isNotBlank()) {
                Text(country.code, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "${country.stationCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    HorizontalDivider(modifier = Modifier.padding(start = 48.dp), thickness = 0.5.dp)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun CountryStationsView(
    country: Country,
    uiState: UiState<List<Station>>,
    playerState: PlayerState,
    favoriteUuids: Set<String>,
    onBack: () -> Unit,
    onPlay: (Station) -> Unit,
    onToggleFavorite: (Station) -> Unit,
    onStartCustomRotation: (List<Station>) -> Unit,
    vm: RadioViewModel? = null,
    modifier: Modifier = Modifier,
) {
    var sortField by remember { mutableStateOf(vm?.countryStationsSortField) }
    var sortAsc by remember { mutableStateOf(vm?.countryStationsSortAsc ?: false) }
    var inSelectMode by remember { mutableStateOf(false) }
    var selectedUuids by remember { mutableStateOf(setOf<String>()) }

    fun enterSelectMode(uuid: String) {
        inSelectMode = true
        selectedUuids = setOf(uuid)
    }
    fun exitSelectMode() {
        inSelectMode = false
        selectedUuids = emptySet()
    }

    fun cycleSort(field: Int) {
        when {
            sortField != field -> {
                sortField = field
                sortAsc = false
            }
            !sortAsc -> sortAsc = true
            else -> sortField = null
        }
        vm?.countryStationsSortField = sortField
        vm?.countryStationsSortAsc = sortAsc
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(country.name) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
        )
        when (uiState) {
            is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(uiState.message, color = MaterialTheme.colorScheme.error)
            }
            is UiState.Success -> {
                if (uiState.data.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No stations found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    val sorted = remember(uiState.data, sortField, sortAsc) {
                        when (sortField) {
                            1 -> if (sortAsc) uiState.data.sortedBy { it.bitrate } else uiState.data.sortedByDescending { it.bitrate }
                            2 -> if (sortAsc) uiState.data.sortedBy { it.name } else uiState.data.sortedByDescending { it.name }
                            else -> uiState.data
                        }
                    }

                    // Sort + Auto row
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
                            val sortLabel = when (sortField) {
                                1 -> "kbps ${if (sortAsc) "↑" else "↓"}"
                                2 -> "Name ${if (sortAsc) "↑" else "↓"}"
                                else -> null
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
                                listOf(1 to "kbps", 2 to "Name").forEach { (field, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            cycleSort(field)
                                            showSortMenu = false
                                        },
                                        trailingIcon = if (sortField == field) {
                                            { Icon(if (sortAsc) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(14.dp)) }
                                        } else {
                                            null
                                        },
                                    )
                                }
                                if (sortField != null) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Clear") },
                                        onClick = {
                                            sortField = null
                                            showSortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        if (inSelectMode) {
                            TextButton(onClick = {
                                selectedUuids = if (selectedUuids.size == sorted.size) {
                                    emptySet()
                                } else {
                                    sorted.map { it.uuid }.toSet()
                                }
                            }) { Text(if (selectedUuids.size == sorted.size) "None" else "All") }
                            IconButton(onClick = ::exitSelectMode, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, "Exit", modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        val autoStations = sorted.filter { it.uuid in selectedUuids }.ifEmpty { sorted }
                        FilledTonalButton(onClick = {
                            exitSelectMode()
                            onStartCustomRotation(autoStations)
                        }) {
                            Icon(Icons.Default.Radio, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (selectedUuids.isEmpty()) "Auto (${sorted.size})" else "Auto (${selectedUuids.size})")
                        }
                    }

                    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sorted, key = { it.uuid }) { station ->
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
            else -> {}
        }
    }
}
