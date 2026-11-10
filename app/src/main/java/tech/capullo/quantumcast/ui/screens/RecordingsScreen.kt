package tech.capullo.quantumcast.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import tech.capullo.quantumcast.recording.ActiveRecording
import tech.capullo.quantumcast.recording.RecState
import tech.capullo.quantumcast.recording.RecStation
import tech.capullo.quantumcast.recording.RecordingFile
import tech.capullo.quantumcast.recording.RecordingPrefs
import tech.capullo.quantumcast.recording.RecordingScheduler
import tech.capullo.quantumcast.recording.RecordingService
import tech.capullo.quantumcast.recording.ResumePoint
import tech.capullo.quantumcast.recording.Schedule
import tech.capullo.quantumcast.viewmodel.RecordingViewModel
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Recordings: files (play / share / save to… / delete) with recordings in progress on top,
 * weekly schedules, and storage (folder, size cap, reliability checklist).
 */
@Composable
fun RecordingsScreen(
    vm: RecordingViewModel,
    currentStation: RecStation?,
    initialTab: Int = 0,
    /** Name of the recording playing in QuantumCast, if any (highlighted). */
    playingName: String? = null,
    onPlayRecording: (RecordingFile, List<RecordingFile>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val active by vm.active.collectAsState()
    val files by vm.files.collectAsState()
    val prefs by vm.prefs.collectAsState()
    // Scheduled recordings run unattended; their notification is the only Stop button and the
    // only place a failure shows, so ask for the permission when schedules are opened.
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(tab) {
        if (tab == 1 && Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val favorites by vm.favorites.collectAsState()

    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("Recordings", "Schedules", "Storage").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        val stations = buildList {
            currentStation?.let { add(it) }
            favorites.forEach { f -> if (none { it.uuid == f.uuid }) add(RecStation(f.uuid, f.name, f.url, f.favicon)) }
        }
        val runTest: (RecStation) -> Unit = { st ->
            vm.runScheduleTest(st) { (context as? android.app.Activity)?.finishAndRemoveTask() }
        }
        var oemWarning by remember { mutableStateOf(false) }
        when (tab) {
            0 -> FilesTab(vm, active, files, prefs.resume, playingName, onPlayRecording)
            1 -> SchedulesTab(
                schedules = prefs.schedules,
                stations = stations,
                onSave = { s ->
                    val isNew = prefs.schedules.none { it.id == s.id }
                    vm.saveSchedule(s)
                    // Once per install, at the moment it matters: before the first schedule is missed.
                    if (isNew && !prefs.oemWarned && oemAutoStartHint() != null) oemWarning = true
                },
                onDelete = vm::deleteSchedule,
            )
            else -> StorageTab(vm, prefs, files, stations, runTest)
        }
        val hint = oemAutoStartHint()
        if (oemWarning && hint != null) {
            val testStation = stations.firstOrNull()
            AlertDialog(
                onDismissRequest = { oemWarning = false; vm.markOemWarned() },
                title = { Text("One more step for schedules") },
                text = {
                    Text(
                        "${Build.MANUFACTURER} phones block closed apps from waking up, so this schedule won't start unless you allow it.\n\n" +
                            "$hint\n\nThen use Test to confirm it works.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        oemWarning = false
                        vm.markOemWarned()
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }) { Text("Open App info") }
                },
                dismissButton = {
                    Row {
                        if (testStation != null) {
                            TextButton(onClick = { oemWarning = false; vm.markOemWarned(); runTest(testStation) }) { Text("Test") }
                        }
                        TextButton(onClick = { oemWarning = false; vm.markOemWarned() }) { Text("Later") }
                    }
                },
            )
        }
    }
}

// --- Recordings tab ---

@Composable
private fun FilesTab(
    vm: RecordingViewModel,
    active: List<ActiveRecording>,
    files: List<RecordingFile>,
    resume: Map<String, ResumePoint>,
    playingName: String?,
    onPlayRecording: (RecordingFile, List<RecordingFile>) -> Unit,
) {
    val context = LocalContext.current
    var saving by remember { mutableStateOf<RecordingFile?>(null) }
    var confirmDelete by remember { mutableStateOf<RecordingFile?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = saving
        saving = null
        if (uri != null && f != null) {
            vm.saveTo(f, uri) { ok -> Toast.makeText(context, if (ok) "Saved" else "Save failed", Toast.LENGTH_SHORT).show() }
        }
    }

    if (active.isEmpty() && files.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "No recordings yet.\nLong-press the play button to record the station you're on, or add a schedule.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val playable = files.filterNot { it.inProgress }
    LazyColumn(Modifier.fillMaxSize()) {
        items(active, key = { "a-" + it.id }) { r -> ActiveRow(r) { vm.stop(r.id) } }
        items(playable, key = { "f-" + it.name }) { f ->
            FileRow(
                f,
                resume = resume[f.name],
                isPlaying = f.name == playingName,
                // The playlist is this list as shown.
                onPlay = { onPlayRecording(f, playable) },
                onShare = { shareFile(context, f) },
                onSave = { saving = f; saveLauncher.launch(f.name) },
                onDelete = { confirmDelete = f },
            )
        }
    }
    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete recording?") },
            text = { Text(f.name) },
            confirmButton = { TextButton(onClick = { vm.delete(f); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ActiveRow(r: ActiveRecording, onStop: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(r.id) { while (true) { kotlinx.coroutines.delay(1_000); now = System.currentTimeMillis() } }
    val status = when (r.state) {
        RecState.CONNECTING -> "Connecting…"
        RecState.RECONNECTING -> "Reconnecting…"
        RecState.RECORDING -> RecordingService.formatElapsed((now - r.startedAt) / 1000) + " · " + RecordingService.formatBytes(r.bytes)
    }
    val ends = r.endsAt?.let { " · ends " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }.orEmpty()
    ListItem(
        leadingContent = { BlinkingRecDot(12.dp) },
        headlineContent = { Text(r.station.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                status + ends + (r.streamTitle.takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: ""),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = { IconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Stop recording", tint = RecRed) } },
    )
}

@Composable
private fun FileRow(
    f: RecordingFile,
    resume: ResumePoint?,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val durMs = (f.durationSeconds ?: 0) * 1000
    // "34:10 / 1:30:00" once started, else just the length.
    val timing = when {
        resume != null && resume.posMs > 0 && durMs > 0 -> "${formatClock(resume.posMs)} / ${formatClock(durMs)}"
        durMs > 0 -> formatClock(durMs)
        else -> ""
    }
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(f.startedAt))
    ListItem(
        modifier = Modifier.clickable(onClick = onPlay),
        colors = if (isPlaying) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        leadingContent = {
            coil.compose.AsyncImage(
                model = f.meta.favicon.takeIf { it.isNotEmpty() },
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        },
        headlineContent = { Text(f.stationName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(listOf(date, timing, RecordingService.formatBytes(f.size)).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1)
                if (resume != null && resume.posMs > 0 && durMs > 0) {
                    LinearProgressIndicator(
                        progress = { (resume.posMs.toFloat() / durMs).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp),
                        drawStopIndicator = {},
                        gapSize = 0.dp,
                    )
                }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text("Save to…") }, onClick = { menu = false; onSave() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        },
    )
}

private fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
    "mp3" -> "audio/mpeg"
    "aac" -> "audio/aac"
    "ogg" -> "audio/ogg"
    "flac" -> "audio/flac"
    else -> "audio/*"
}

// Rows play inside QuantumCast (same engine, Snapcast too); Share still hands the file to other apps.

private fun shareFile(context: Context, f: RecordingFile) {
    val i = Intent(Intent.ACTION_SEND).setType(mimeOf(f.name)).putExtra(Intent.EXTRA_STREAM, f.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(i, f.name))
}

// --- Schedules tab ---

private val DAYS = DayOfWeek.entries

@Composable
private fun SchedulesTab(
    schedules: List<Schedule>,
    stations: List<RecStation>,
    onSave: (Schedule) -> Unit,
    onDelete: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<Schedule?>(null) }
    var creating by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        if (schedules.isEmpty()) {
            Text(
                "No schedules. Tap + to record a station on chosen days, e.g. Mon–Fri 18:30 for 90 min.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(schedules, key = { it.id }) { s ->
                ListItem(
                    modifier = Modifier.clickable { editing = s },
                    headlineContent = { Text(s.station.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text("${daysLabel(s.days)} · ${"%02d:%02d".format(s.startMinute / 60, s.startMinute % 60)} · ${s.durationMinutes} min")
                    },
                    trailingContent = { Switch(checked = s.enabled, onCheckedChange = { onSave(s.copy(enabled = it)) }) },
                )
            }
        }
        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Add schedule") }
    }
    if (creating || editing != null) {
        ScheduleDialog(
            initial = editing,
            stations = stations,
            onDismiss = { creating = false; editing = null },
            onSave = { onSave(it); creating = false; editing = null },
            onDelete = editing?.let { e -> { onDelete(e.id); editing = null } },
        )
    }
}

private fun daysLabel(mask: Int): String = when (mask) {
    0b0011111 -> "Mon–Fri"
    0b1100000 -> "Weekends"
    0b1111111 -> "Every day"
    else -> DAYS.filter { mask and (1 shl (it.value - 1)) != 0 }
        .joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleDialog(
    initial: Schedule?,
    stations: List<RecStation>,
    onDismiss: () -> Unit,
    onSave: (Schedule) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val choices = remember(stations, initial) {
        if (initial != null && stations.none { it.uuid == initial.station.uuid }) listOf(initial.station) + stations else stations
    }
    var station by remember { mutableStateOf(initial?.station ?: choices.firstOrNull()) }
    var days by remember { mutableIntStateOf(initial?.days ?: 0b0011111) }
    val time = rememberTimePickerState(
        initialHour = (initial?.startMinute ?: (18 * 60 + 30)) / 60,
        initialMinute = (initial?.startMinute ?: (18 * 60 + 30)) % 60,
        is24Hour = true,
    )
    var duration by remember { mutableStateOf((initial?.durationMinutes ?: 60).toString()) }
    var pickStation by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    val mins = duration.toIntOrNull()?.takeIf { it in 1..24 * 60 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New schedule" else "Edit schedule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (choices.isEmpty()) {
                    Text("Add the station to Favorites first, or start playing it.", color = MaterialTheme.colorScheme.error)
                } else {
                    Box {
                        OutlinedButton(onClick = { pickStation = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(station?.name ?: "Choose station", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = pickStation, onDismissRequest = { pickStation = false }) {
                            choices.forEach { s ->
                                DropdownMenuItem(text = { Text(s.name) }, onClick = { station = s; pickStation = false })
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAYS.forEach { d ->
                        val bit = 1 shl (d.value - 1)
                        FilterChip(
                            selected = days and bit != 0,
                            onClick = { days = days xor bit },
                            label = { Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault())) },
                        )
                    }
                }
                OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Starts at %02d:%02d".format(time.hour, time.minute))
                }
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it.filter(Char::isDigit).take(4) },
                    label = { Text("Duration (minutes)") },
                    singleLine = true,
                    isError = mins == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Delete schedule")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = station != null && days != 0 && mins != null,
                onClick = {
                    onSave(
                        Schedule(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            station = station!!,
                            days = days,
                            startMinute = time.hour * 60 + time.minute,
                            durationMinutes = mins!!,
                            enabled = initial?.enabled ?: true,
                            createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickTime) {
        AlertDialog(
            onDismissRequest = { pickTime = false },
            // Typed hours/minutes: exact times like 18:30 are quicker to enter than on the dial.
            text = { TimeInput(state = time) },
            confirmButton = { TextButton(onClick = { pickTime = false }) { Text("OK") } },
        )
    }
}

// --- Storage tab ---

private val CAP_CHOICES = listOf(250, 500, 1024, 2048, 5120, 10240, 0)

private fun capLabel(mb: Int) = when {
    mb == 0 -> "No limit"
    mb >= 1024 -> "${mb / 1024} GB"
    else -> "$mb MB"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageTab(
    vm: RecordingViewModel,
    prefs: RecordingPrefs,
    files: List<RecordingFile>,
    stations: List<RecStation>,
    onTest: (RecStation) -> Unit,
) {
    val context = LocalContext.current
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setFolder(uri)
    }
    val used = files.sumOf { it.size }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Folder", style = MaterialTheme.typography.titleMedium)
        Text(
            if (prefs.treeUri == null) "App folder (internal). Removed if the app is uninstalled; use Save to… to keep a copy."
            else Uri.decode(prefs.folderUri.substringAfterLast("/")),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { folderLauncher.launch(null) }) { Text("Change folder") }
            if (prefs.treeUri != null) TextButton(onClick = { vm.setFolder(null) }) { Text("Use app folder") }
        }
        Text(
            "Only new recordings go to a changed folder; existing ones stay where they are.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        Text("Size limit", style = MaterialTheme.typography.titleMedium)
        Text(
            "Using ${RecordingService.formatBytes(used)}" +
                if (prefs.capMb > 0) " of ${capLabel(prefs.capMb)} (≈ ${prefs.capMb * 8L * 1024 * 1024 / 128_000 / 3600} h at 128 kbps). Oldest recordings are deleted first." else "",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CAP_CHOICES.forEach { mb ->
                FilterChip(selected = prefs.capMb == mb, onClick = { vm.setCapMb(mb) }, label = { Text(capLabel(mb)) })
            }
        }

        HorizontalDivider()
        ReliabilityChecklist(context, stations.firstOrNull(), onTest)
    }
}

@Composable
private fun ReliabilityChecklist(context: Context, testStation: RecStation?, onTest: (RecStation) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    val notificationsOk = remember(refresh) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val batteryOk = remember(refresh) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    val alarmsOk = remember(refresh) { RecordingScheduler.canScheduleExact(context) }
    val oemHint = oemAutoStartHint()

    Text("Scheduled recording checklist", style = MaterialTheme.typography.titleMedium)
    Text(
        "Schedules wake the app at the right time even when it's closed, as long as these are set.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    CheckRow("Notifications allowed", notificationsOk) {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    CheckRow("Battery optimization off", batteryOk) {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        )
    }
    CheckRow("Exact alarms allowed", alarmsOk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }
    }
    if (oemHint != null) {
        CheckRow(
            "Allow auto start (${Build.MANUFACTURER})",
            ok = null,
            hint = "This phone blocks closed apps from waking up, so schedules won't start. $oemHint",
        ) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }
    ScheduleTestRow(testStation, onTest)
}

/**
 * The vendor auto-start switch can't be read, so the only proof is a real wake-up: arm an alarm a
 * minute out, close the app the hard way, and let the result notification say what happened.
 */
@Composable
private fun ScheduleTestRow(station: RecStation?, onTest: (RecStation) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Test scheduled recording") },
        supportingContent = {
            Text(
                if (station == null) "Play a station or add a favorite first."
                else "Closes QuantumCast, then checks it wakes up by itself in 1 minute and records ${station.name} for 20 s.",
            )
        },
        trailingContent = { TextButton(enabled = station != null, onClick = { confirm = true }) { Text("Test") } },
    )
    if (confirm && station != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Test scheduled recording?") },
            text = {
                Text(
                    "QuantumCast will close now (playback stops). Lock the phone if you like. In about a minute you'll get " +
                        "\"Schedule test passed\". If nothing arrives within 2 minutes, open QuantumCast: it will tell you the test failed " +
                        "and auto start needs allowing.",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = false; onTest(station) }) { Text("Close & test") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Where this phone's vendor auto-start switch lives, or null on phones without one.
 * Device-verified (ColorOS 15 on OPPO, Motorola's razr ROM): without it the OS blocks the alarm's
 * cold start ("prevent start … alarmManger" / "MotoBatteryCareService: restricted process start"),
 * and ColorOS also deletes our alarms when the process dies. Battery optimization off is NOT enough.
 * No API reads or sets it, and both vendors' own screens refuse to open from other apps.
 */
fun oemAutoStartHint(): String? {
    val oem = Build.MANUFACTURER.lowercase()
    return when {
        listOf("oppo", "realme", "oneplus").any { it in oem } ->
            "Turn on both: Settings → Apps → Auto launch → QuantumCast, and App info → Battery usage → Allow background activity."
        "motorola" in oem ->
            "Turn on: Settings → Apps → App auto start → QuantumCast (search \"auto start\" in Settings)."
        listOf("xiaomi", "redmi", "poco").any { it in oem } ->
            "Turn on: App info → Autostart, and set Battery saver to \"No restrictions\"."
        listOf("vivo", "iqoo", "huawei", "honor").any { it in oem } ->
            "Allow QuantumCast to auto-start / run in background in this phone's app launch settings."
        else -> null
    }
}

/** ok = null: the system can't tell us; the user has to check it. */
@Composable
private fun CheckRow(label: String, ok: Boolean?, hint: String? = null, onFix: () -> Unit) {
    ListItem(
        leadingContent = {
            when (ok) {
                true -> Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                else -> Icon(Icons.Default.Warning, null, tint = if (ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        headlineContent = { Text(label, fontWeight = if (ok == false) FontWeight.SemiBold else FontWeight.Normal) },
        supportingContent = hint?.let { { Text(it) } },
        trailingContent = if (ok != true) { { TextButton(onClick = { runCatching(onFix) }) { Text(if (ok == null) "Open" else "Fix") } } } else null,
    )
}
