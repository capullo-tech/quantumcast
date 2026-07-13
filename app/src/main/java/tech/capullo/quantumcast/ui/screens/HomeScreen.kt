package tech.capullo.quantumcast.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.capullo.audio.snapcast.DiscoveredSnapserver
import tech.capullo.audio.ui.LocalRadiosSection

// The home / "Select a station" screen (mirrors Telecloud's GroupSelectorScreen): a TopAppBar with
// a Settings gear, then a scanning "local radios" radar section, a search field, a Discovery
// (quantum-shuffle) button, and Favorites / Countries rows. Typing a query and submitting runs the
// search and opens the results screen; Search results, Favorites and Countries are pushed screens.
//
// No inner Scaffold on purpose: this screen lives inside MainActivity's outer Scaffold (which owns
// the persistent NowPlayingBar). That outer Scaffold zeroes its top window inset, so this TopAppBar
// applies the status-bar inset exactly once - a nested Scaffold would double it.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    // Radar / local-broadcast discovery (absorbed from the old Qcast tab)
    discoveredServers: List<DiscoveredSnapserver> = emptyList(),
    connectedHost: String = "",
    lastManualHost: String = "",
    onStartDiscovery: () -> Unit = {},
    onConnectToServer: (DiscoveredSnapserver) -> Unit = {},
    onConnectManually: (host: String, port: Int, httpPort: Int) -> Unit = { _, _, _ -> },
    onClearLastManualHost: () -> Unit = {},
    // Search: submitting a query runs the search and opens the results screen
    onSearch: (String) -> Unit = {},
    // Discovery (quantum-shuffle random rotation)
    onShuffleRotation: () -> Unit = {},
    isShuffleLoading: Boolean = false,
    shuffleConnected: Boolean = false,
    // Navigation to pushed child screens
    onOpenSettings: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenCountries: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var searchText by remember { mutableStateOf("") }

    // Scan for local snapcast servers while the home is on-screen
    LaunchedEffect(Unit) { onStartDiscovery() }

    fun submitSearch() {
        val q = searchText.trim()
        if (q.isEmpty()) return
        focusManager.clearFocus()
        onSearch(q)
        searchText = ""
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Select a station") },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            },
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            // 1. Radar / local broadcasts (discover + join)
            item {
                // Shared radar/scanning section (tech.capullo.audio.ui). Ports are dynamic, so the
                // 1604/1680 fallbacks only apply to a bare manual host with no ":port" typed.
                LocalRadiosSection(
                    servers = discoveredServers.filter { it.hostAddress != connectedHost },
                    onJoinServer = onConnectToServer,
                    onJoinManual = onConnectManually,
                    fallbackStreamPort = 1604,
                    fallbackHttpPort = 1680,
                    initialManualHost = lastManualHost,
                    onClearManualHost = onClearLastManualHost,
                )
            }

            // 2. Search field - type a query, submit to open the results screen
            item {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text("Search stations…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (searchText.isNotEmpty()) {
                            IconButton(onClick = { searchText = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // 3. Discovery - quantum-shuffle a random rotation (shows the entangle animation while loading)
            item {
                FilledTonalButton(
                    onClick = { if (!isShuffleLoading) onShuffleRotation() },
                    enabled = !isShuffleLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .height(56.dp),
                ) {
                    if (isShuffleLoading) {
                        QuantumEntangleIcon(
                            connected = shuffleConnected,
                            color = LocalContentColor.current,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Discovery", style = MaterialTheme.typography.titleMedium)
                }
            }

            // 4 + 5. Favorites / Countries → push their own screens
            item {
                NavRow("Favorites", Icons.Default.Favorite, onOpenFavorites)
                NavRow("Countries", Icons.Default.Public, onOpenCountries)
            }
        }
    }
}

@Composable
private fun NavRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(label, style = MaterialTheme.typography.titleMedium) },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// Quantum entangle icon - same geometry and choreography as the web player's connection status icon
// (center node + 5 pentagon dots, lines always drawn). Shown on the Discovery button while a shuffle
// is loading: one bright dot travels the ring in hard 200ms steps, then all dots fade fully lit on
// connect.
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
