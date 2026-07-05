package tech.capullo.quantumcast.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tech.capullo.quantumcast.data.settings.AppSettings
import tech.capullo.quantumcast.data.settings.RadioServer
import tech.capullo.quantumcast.data.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSetApiServer: (String) -> Unit,
    onSetSearchLimit: (Int) -> Unit,
    onSetRandomBatchSize: (Int) -> Unit,
    onSetRotationMinutes: (Int) -> Unit,
    onSetVlcNetworkCachingMs: (Int) -> Unit = {},
    onSetShazamIntervalSeconds: (Int) -> Unit,
    onSetSleepTimerMinutes: (Int) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit = {},
    onSetShareService: (tech.capullo.quantumcast.data.settings.ShareService) -> Unit = {},
    onSetCustomServerName: (String) -> Unit = {},
    onSetAutoEntangleOnLaunch: (Boolean) -> Unit = {},
    onSetWebDebugPanel: (Boolean) -> Unit = {},
    onSetWebAutoplay: (Boolean) -> Unit = {},
    onSetMaxHistorySongs: (Int) -> Unit = {},
    onExportFavorites: (java.io.OutputStream) -> Unit = {},
    onImportFavorites: (java.io.InputStream) -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showImportConfirm by remember { mutableStateOf<Uri?>(null) }
    var swipeDx by remember { mutableFloatStateOf(0f) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try { context.contentResolver.openOutputStream(uri)?.use { onExportFavorites(it) } } catch (_: Exception) {}
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        showImportConfirm = uri
    }

    showImportConfirm?.let { uri ->
        AlertDialog(
            onDismissRequest = { showImportConfirm = null },
            title = { Text("Restore Favorites") },
            text = { Text("This will replace all current favorites and groups. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes != null) onImportFavorites(java.io.ByteArrayInputStream(bytes))
                    } catch (_: Exception) {}
                    showImportConfirm = null
                }) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = null }) { Text("Cancel") }
            }
        )
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> swipeDx += delta },
                onDragStarted = { swipeDx = 0f },
                onDragStopped = { if (swipeDx > 120f) onBack() else swipeDx = 0f }
            )
    ) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            ),
            windowInsets = WindowInsets(0)
        )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item { SectionHeader("Device") }

        item {
            TextInputRow(
                label = "Server display name",
                description = "Shown to other devices via NSD and in snapclient view. Leave blank to use device name.",
                value = settings.customServerName,
                placeholder = android.os.Build.MODEL,
                onValueChange = onSetCustomServerName,
                keyboardType = KeyboardType.Text,
            )
        }

        item {
            ToggleRow(
                label = "Web player autostart",
                description = "Web clients start listening automatically on page load instead of waiting for the headphones button. Browsers may still require one tap the first time. Applies on the web page's next reload.",
                checked = settings.webAutoplay,
                onToggle = onSetWebAutoplay,
            )
        }

        item {
            ToggleRow(
                label = "Web player debug panel",
                description = "Show the audio debug bar at the bottom of the web player. Applies on the web page's next reload.",
                checked = settings.webDebugPanel,
                onToggle = onSetWebDebugPanel,
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item { SectionHeader("Appearance") }

        item {
            ThemeModeRow(current = settings.themeMode, onSelect = onSetThemeMode)
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item { SectionHeader("Share Service") }
        item {
            ShareServiceRow(current = settings.shareService, onSelect = onSetShareService)
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item { SectionHeader("Radio Browser API") }

        item {
            ServerPickerRow(
                current = settings.apiServer,
                onSelect = onSetApiServer
            )
        }

        item {
            StepperRow(
                label = "Search results limit",
                description = "Max stations returned per search",
                value = settings.searchLimit,
                range = 10..200,
                step = 10,
                onValueChange = onSetSearchLimit
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item { SectionHeader("Rotation") }

        item {
            ToggleRow(
                label = "Entangle on launch",
                description = "Start Entangled Discovery automatically when the app opens (unless already playing)",
                checked = settings.autoEntangleOnLaunch,
                onToggle = onSetAutoEntangleOnLaunch,
            )
        }

        item {
            StepperRow(
                label = "Minutes per station",
                description = "How long to play each station before switching",
                value = settings.rotationMinutes,
                range = 1..60,
                step = 1,
                onValueChange = onSetRotationMinutes
            )
        }

        item {
            StepperRow(
                label = "Stations per batch",
                description = "Stations loaded per random/shuffle cycle",
                value = settings.randomBatchSize,
                range = 5..50,
                step = 5,
                onValueChange = onSetRandomBatchSize
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item { SectionHeader("Sleep Timer") }

        item {
            StepperRow(
                label = "Sleep timer duration",
                description = "Minutes before music pauses (tap moon icon in Now Playing)",
                value = settings.sleepTimerMinutes,
                range = 5..120,
                step = 5,
                onValueChange = onSetSleepTimerMinutes
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item { SectionHeader("Reckon") }

        item {
            StepperRow(
                label = "Identification interval",
                description = "Seconds between auto-identifications (default 120)",
                value = settings.shazamIntervalSeconds,
                range = 30..300,
                step = 10,
                onValueChange = onSetShazamIntervalSeconds
            )
        }

        item {
            StepperRow(
                label = "Max history songs",
                description = if (settings.maxHistorySongs == 0) "Unlimited" else "${settings.maxHistorySongs} songs remembered",
                value = settings.maxHistorySongs,
                range = 0..500,
                step = 10,
                onValueChange = onSetMaxHistorySongs
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item { SectionHeader("Qcast") }

        item {
            IntInputRow(
                label = "Stream buffer",
                description = "Network caching (ms). Lower = faster start, less stutter tolerance. Range: 100–10000",
                value = settings.vlcNetworkCachingMs,
                onValueChange = onSetVlcNetworkCachingMs,
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item { SectionHeader("Favorites Backup") }

        item {
            val timestamp = remember { java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault()).format(java.util.Date()) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { exportLauncher.launch("qc_favorites_$timestamp.json") }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.SaveAlt, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Export Favorites", style = MaterialTheme.typography.bodyMedium)
                    Text("Save favorites and groups to a JSON file", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { importLauncher.launch(arrayOf("application/json", "*/*")) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.UploadFile, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Import Favorites", style = MaterialTheme.typography.bodyMedium)
                    Text("Restore favorites from a JSON file", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
    } // Column
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun ShareServiceRow(
    current: tech.capullo.quantumcast.data.settings.ShareService,
    onSelect: (tech.capullo.quantumcast.data.settings.ShareService) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Default share service", style = MaterialTheme.typography.bodyMedium)
        Text("Tap to share · long-press the share icon in Now Playing to cycle", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(
                tech.capullo.quantumcast.data.settings.ShareService.YOUTUBE to "YouTube",
                tech.capullo.quantumcast.data.settings.ShareService.SPOTIFY to "Spotify",
                tech.capullo.quantumcast.data.settings.ShareService.APPLE_MUSIC to "Apple",
            ).forEachIndexed { index, (svc, label) ->
                SegmentedButton(
                    selected = current == svc,
                    onClick = { onSelect(svc) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}

@Composable
private fun ThemeModeRow(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            Text("App color scheme", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SingleChoiceSegmentedButtonRow {
            listOf(ThemeMode.SYSTEM to "Auto", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
                .forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = current == mode,
                        onClick = { onSelect(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
        }
    }
}

@Composable
private fun ServerPickerRow(current: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val knownServer = RadioServer.entries.find { it.url == current }
    val displayLabel = knownServer?.label ?: "Custom ($current)"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("API Server", style = MaterialTheme.typography.bodyMedium)
            Text(displayLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RadioServer.entries.filter { it != RadioServer.CUSTOM }.forEach { server ->
                DropdownMenuItem(
                    text = { Text(server.label) },
                    onClick = { onSelect(server.url); expanded = false },
                    leadingIcon = {
                        if (server.url == current) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                )
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    description: String,
    value: Int,
    range: IntRange,
    step: Int,
    onValueChange: (Int) -> Unit
) {
    var showInputDialog by remember { mutableStateOf(false) }

    if (showInputDialog) {
        StepperInputDialog(
            label = label,
            current = value,
            range = range,
            onDismiss = { showInputDialog = false },
            onConfirm = { onValueChange(it); showInputDialog = false }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedIconButton(
                onClick = { if (value - step >= range.first) onValueChange(value - step) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.Remove, "Decrease", modifier = Modifier.size(16.dp))
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .widthIn(min = 36.dp)
                    .clickable { showInputDialog = true },
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedIconButton(
                onClick = { if (value + step <= range.last) onValueChange(value + step) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.Add, "Increase", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun StepperInputDialog(
    label: String,
    current: Int,
    range: IntRange,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(current.toString()) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("${range.first}–${range.last}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    text.toIntOrNull()?.coerceIn(range)?.let { onConfirm(it) } ?: onDismiss()
                }),
                modifier = Modifier.focusRequester(focusRequester)
            )
        },
        confirmButton = {
            TextButton(onClick = {
                text.toIntOrNull()?.coerceIn(range)?.let { onConfirm(it) } ?: onDismiss()
            }) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ToggleRow(label: String, description: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun IntInputRow(label: String, description: String, value: Int, onValueChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 2.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { text.toIntOrNull()?.let(onValueChange) }),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    val parsed = text.toIntOrNull()
                    if (parsed != null && parsed != value) {
                        IconButton(onClick = { onValueChange(parsed) }) {
                            Icon(Icons.Default.Check, "Save", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun TextInputRow(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    description: String = "",
    keyboardType: KeyboardType = KeyboardType.Uri,
) {
    var text by remember(value) { mutableStateOf(value) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 2.dp))
            if (description.isNotEmpty()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (text != value) {
                        IconButton(onClick = { onValueChange(text) }) {
                            Icon(Icons.Default.Check, "Save", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        }
    }
}
