package tech.capullo.quantumcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.Serializable
import tech.capullo.audio.snapcast.DiscoveredSnapserver
import tech.capullo.audio.snapcast.SnapserverDiscoveryManager
import tech.capullo.audio.tunnel.TunnelManager
import tech.capullo.audio.ui.PublicLinkState
import tech.capullo.quantumcast.player.PlaybackService
import tech.capullo.quantumcast.ui.screens.*
import tech.capullo.quantumcast.ui.theme.RadioTheme
import tech.capullo.quantumcast.viewmodel.RadioViewModel
import tech.capullo.source.radiobrowser.data.model.Station

// Navigation3 destinations. @Serializable NavKeys (kotlinx-serialization is already applied)
// so rememberNavBackStack can save/restore them across process death. Home is the backstack root;
// Favorites/Countries/Settings are pushed children with real back-nav (no bottom tab bar).
@Serializable private data object HomeRoute : NavKey

@Serializable private data object SearchRoute : NavKey

@Serializable private data object FavoritesRoute : NavKey

@Serializable private data object CountriesRoute : NavKey

@Serializable private data object SettingsRoute : NavKey

@Serializable private data class RecordingsRoute(val tab: Int = 0) : NavKey

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: RadioViewModel by viewModels()
    private val recVm: tech.capullo.quantumcast.viewmodel.RecordingViewModel by viewModels()
    private lateinit var discoveryManager: SnapserverDiscoveryManager

    private val skipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                PlaybackService.ACTION_SKIP_NEXT -> vm.skipStation()
                PlaybackService.ACTION_SKIP_PREV -> vm.skipPrevStation()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.connectPlayer()
        discoveryManager = SnapserverDiscoveryManager(this)

        val filter = IntentFilter().apply {
            addAction(PlaybackService.ACTION_SKIP_NEXT)
            addAction(PlaybackService.ACTION_SKIP_PREV)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(skipReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(skipReceiver, filter)
        }

        setContent {
            val settings by vm.settings.collectAsState()
            RadioTheme(themeMode = settings.themeMode) {
                val discoveredServers by discoveryManager.discoveredServers.collectAsState()
                val recordingsActive by recVm.active.collectAsState()
                // One blink clock for every "recording" indicator, running only while something records.
                tech.capullo.quantumcast.ui.screens.ProvideRecBlink(active = recordingsActive.isNotEmpty()) {
                    RadioApp(vm, recVm, discoveredServers, discoveryManager)
                }
            }
        }
    }

    // ADB test: adb shell am start -n tech.capullo.quantumcast/.MainActivity --es dbg shuffle
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Debug-only test hooks. MainActivity is the exported launcher, so gate these to
        // debuggable builds - a release build must expose no playback-control surface to other apps.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        when (intent.getStringExtra("dbg")) {
            "shuffle" -> vm.startShuffleRotation()
            "skip" -> vm.skipStation()
            "stop" -> vm.stopRotation()
            // Optional --es url / --es name pin the program material, which materially changes how
            // sharply the calibration correlates. am start ... --es dbg snaptest --es url <stream>
            "snaptest" -> vm.startSnapTest(intent.getStringExtra("url"), intent.getStringExtra("name"))
            "calibrate" -> vm.startSyncCalibration() // rig testing: am start ... --es dbg calibrate
            // Non-mutating single measurement; logs peak z + level (dBFS) for a volume sweep.
            // am start ... --es dbg micz
            "micz" -> vm.measureOnce()
            // GO/NO-GO sensitivity sweep for the volume balance. Probes one client 180ms apart and
            // dumps every arrival's lag/z/level; writes no volumes, so the commanded gain stays the
            // ground truth. Optional --es target <client name> picks which client is probed.
            // am start ... --es dbg levelsweep
            "levelsweep" -> vm.measureLevelSweep(intent.getStringExtra("target"))
            // Revert the volumes the last balance wrote. am start ... --es dbg undobalance
            "undobalance" -> vm.undoBalancedVolumes()
            // Dump one capture's decimated ref+mic PCM (float32-le) + metadata to
            // /sdcard/Android/data/<pkg>/files/, so estimator questions can be answered offline
            // instead of costing a rig session. --ez probe true separates the arrivals first.
            // am start ... --es dbg pcmdump [--ez probe true] [--es target <client name>]
            // --ei settle <ms> overrides the post-probe settle, so SETTLE_MS can be measured
            // from dumps instead of from 7-minute calibrations. --ei capture <ms> shortens the
            // recording, which is what makes a too-short settle visible: at 12 s the contamination
            // is ~10 % of the window and averages away.
            "pcmdump" -> vm.dumpCapturePcm(
                intent.getStringExtra("target"),
                intent.getBooleanExtra("probe", true),
                intent.getIntExtra("settle", 7_000).toLong(),
                intent.getIntExtra("capture", 12_000),
            )
            // Toggle the public-link tunnel without the UI, so the rig can test it.
            // am start ... --es dbg tunnel --ez on true
            "tunnel" -> vm.updateSetting { setTunnelEnabled(intent.getBooleanExtra("on", true)) }
            // Fix the snapserver base port (--ei port N; omit or -1 to auto-pick, 0 to disable).
            // Applies on the next broadcast start. am start ... --es dbg fixport --ei port 34000
            "fixport" -> vm.setSnapserverFixedPort(intent.getIntExtra("port", -1))
            // Connect this device as a snapclient to host:base (control on base+2). Rig testing:
            // am start ... --es dbg connect --es host 192.168.x.y --ei port 34000
            "connect" -> {
                val host = intent.getStringExtra("host")
                val base = intent.getIntExtra("port", 0)
                if (host != null && base > 0) vm.connectToSnapserver(host, base, base + 2)
            }
            "disconnect" -> vm.disconnectSnapclient()
        }
    }

    override fun onDestroy() {
        discoveryManager.stopDiscovery()
        unregisterReceiver(skipReceiver)
        vm.disconnectPlayer()
        super.onDestroy()
    }
}

@Composable
private fun RadioApp(
    vm: RadioViewModel,
    recVm: tech.capullo.quantumcast.viewmodel.RecordingViewModel,
    discoveredServers: List<DiscoveredSnapserver>,
    discoveryManager: SnapserverDiscoveryManager,
) {
    val backStack = rememberNavBackStack(HomeRoute)

    val activityContext = androidx.compose.ui.platform.LocalContext.current
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.startSyncCalibration() }

    val searchResults by vm.searchResults.collectAsState()
    val playerState by vm.playerState.collectAsState()
    val rotationState by vm.rotationState.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val favoriteUuids by vm.favoriteUuids.collectAsState()
    val settings by vm.settings.collectAsState()
    val countryList by vm.countryList.collectAsState()
    val countryStations by vm.countryStations.collectAsState()
    val selectedCountry by vm.selectedCountry.collectAsState()
    val groups by vm.groups.collectAsState()
    val expandedGroupIds by vm.expandedGroupIds.collectAsState()
    val trackHistory by vm.trackHistory.collectAsState()
    val rotationQueue by vm.rotationQueue.collectAsState()
    val isShazamRunning by vm.isShazamRunning.collectAsState()
    val isShuffleLoading by vm.isShuffleLoading.collectAsState()
    val shuffleConnected by vm.shuffleConnected.collectAsState()
    val showTrackDetail by vm.showDetailScreen.collectAsState()
    val snapclientHost by vm.snapclientHost.collectAsState()
    val broadcastHttpPort by vm.broadcastHttpPort.collectAsState()
    val tunnelState by vm.tunnelState.collectAsState()
    val snapclientChannel by vm.snapclientChannel.collectAsState()
    val snapcastGroups by vm.snapcastGroups.collectAsState()
    val streamCanGoNext by vm.streamCanGoNext.collectAsState()
    val streamCanGoPrevious by vm.streamCanGoPrevious.collectAsState()
    val isStreamLocked by vm.isStreamLocked.collectAsState()
    val streamStats by vm.streamStats.collectAsState()
    val sleepTimerActive by vm.sleepTimerActive.collectAsState()
    val sleepTimerSecondsRemaining by vm.sleepTimerSecondsRemaining.collectAsState()
    val activeRecordings by recVm.active.collectAsState()

    // Recording targets the station this phone streams itself; in client mode there is none.
    // Not while a recording plays (its synthetic station has no stream URL anyway).
    val recStation = playerState.station?.takeIf { snapclientHost.isEmpty() && it.streamUrl.isNotEmpty() }?.let {
        tech.capullo.quantumcast.recording.RecStation(it.uuid, it.name, it.streamUrl, it.favicon, it.country, it.countryCode)
    }
    val isRecordingCurrent = recStation != null && activeRecordings.any { it.station.uuid == recStation.uuid }
    // The recording notification (with its Stop button) needs POST_NOTIFICATIONS on 13+; the
    // playback notification never did (media-style is exempt), so the app never asked before.
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    val toggleRecord: () -> Unit = {
        if (recStation != null) {
            if (!isRecordingCurrent && Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(activityContext, android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            // Feedback is the play button itself (it starts/stops alternating with a red dot).
            recVm.toggleRecord(recStation)
        }
    }
    val openRecordings: () -> Unit = {
        if (backStack.lastOrNull() !is RecordingsRoute) backStack.add(RecordingsRoute())
    }

    // Non-null while a recording (not a station) plays: switches both players to recording controls.
    val playingRecording by vm.playingRecording.collectAsState()
    val recordingPrefs by vm.recordingPrefs.collectAsState()
    val recordingUi = if (playingRecording == null) null else tech.capullo.quantumcast.ui.screens.RecordingPlaybackUi(
        progress = vm.mediaProgress,
        skipSeconds = recordingPrefs.skipSeconds,
        speed = recordingPrefs.speed,
        onSeek = vm::seekRecording,
        onSkipBack = vm::skipBackward,
        onSkipForward = vm::skipForward,
        onPrevious = vm::previousRecording,
        onNext = vm::nextRecording,
        onSetSpeed = vm::setRecordingSpeed,
    )

    fun startCustomRotation(stations: List<Station>) = vm.startCustomRotation(stations)

    if (showTrackDetail) {
        // Fade in - smooth hand-off from the entangle "connected" hold
        val detailAlpha = remember { Animatable(0f) }
        LaunchedEffect(Unit) { detailAlpha.animateTo(1f, tween(450)) }
        TrackDetailScreen(
            playerState = playerState,
            trackHistory = trackHistory,
            isShazamRunning = isShazamRunning,
            rotationState = rotationState,
            rotationQueue = rotationQueue,
            favoriteUuids = favoriteUuids,
            onBack = { vm.hideDetail() },
            onOpenSettings = {
                vm.hideDetail()
                if (backStack.lastOrNull() != SettingsRoute) backStack.add(SettingsRoute)
            },
            onTogglePlayPause = vm::togglePlayPause,
            isRecording = isRecordingCurrent,
            onToggleRecord = toggleRecord,
            anyRecording = activeRecordings.isNotEmpty(),
            onOpenRecordings = {
                vm.hideDetail()
                openRecordings()
            },
            recording = recordingUi,
            onToggleSpeed = vm::toggleRecordingSpeed,
            onIdentifyNow = vm::identifyNow,
            onCancelIdentify = vm::cancelIdentify,
            onSkip = vm::skipStation,
            onSkipPrev = vm::skipPrevStation,
            onToggleTimerPause = vm::toggleTimerPause,
            onToggleRotationOrder = vm::toggleRotationOrder,
            onCycleRepeat = vm::cycleRepeatMode,
            onStopRotation = vm::stopRotation,
            onToggleFavorite = vm::toggleFavorite,
            onRemoveFromQueue = vm::removeFromRotationQueue,
            onJumpToQueueStation = vm::jumpToQueueStation,
            onClearHistory = vm::clearTrackHistory,
            onDeleteHistoryAt = vm::deleteTrackHistoryAt,
            sleepTimerActive = sleepTimerActive,
            sleepTimerSecondsRemaining = sleepTimerSecondsRemaining,
            onToggleSleepTimer = vm::toggleSleepTimer,
            streamStats = streamStats,
            snapcastGroups = snapcastGroups,
            broadcastHttpPort = broadcastHttpPort,
            publicLink = tunnelState.toPublicLinkState(),
            snapclientChannel = snapclientChannel,
            onAdjustClientVolume = vm::adjustClientVolume,
            onAdjustClientLatency = vm::adjustClientLatency,
            onSetSnapclientChannel = vm::setSnapclientChannel,
            onChangeClientChannel = vm::changeClientChannel,
            isSnapclientMode = snapclientHost.isNotEmpty(),
            snapclientHost = snapclientHost,
            onDisconnect = vm::disconnectSnapclient,
            streamCanGoNext = streamCanGoNext,
            streamCanGoPrevious = streamCanGoPrevious,
            isStreamLocked = isStreamLocked,
            onToggleStreamLock = vm::toggleStreamLock,
            onResetSelf = vm::resetSelf,
            onResetAll = vm::resetAll,
            onCalibrateSync = {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    activityContext,
                    android.Manifest.permission.RECORD_AUDIO,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) vm.startSyncCalibration() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
            },
            calibrationRunning = vm.calibrationState is tech.capullo.audio.calibration.SyncCalibrator.State.Running,
            calibrationStatus = when (val cs = vm.calibrationState) {
                is tech.capullo.audio.calibration.SyncCalibrator.State.Idle -> ""
                is tech.capullo.audio.calibration.SyncCalibrator.State.Running -> cs.message
                is tech.capullo.audio.calibration.SyncCalibrator.State.Done -> "Calibrated: ${cs.summary}"
                is tech.capullo.audio.calibration.SyncCalibrator.State.Failed -> "Calibration failed: ${cs.reason}"
            },
            shareService = settings.shareService,
            onSetShareService = vm::setShareService,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = detailAlpha.value },
        )
        return
    }

    Scaffold(
        // Each destination draws its own TopAppBar, so this outer Scaffold (which owns the
        // persistent NowPlayingBar) must NOT also apply the top status-bar inset to content -
        // otherwise the inset is counted twice and leaves a dead band above every title. Keep only
        // the bottom inset so the NowPlayingBar still clears the gesture bar.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Bottom),
        bottomBar = {
            NowPlayingBar(
                state = playerState,
                // currentTrack (not history) so the bar reverts to station art
                // when recognition stops matching, same as the detail screen
                latestTrack = playerState.currentTrack,
                rotationState = rotationState,
                isSnapclientMode = snapclientHost.isNotEmpty(),
                streamCanGoNext = streamCanGoNext,
                streamCanGoPrevious = streamCanGoPrevious,
                isStreamLocked = isStreamLocked,
                onTogglePlayPause = vm::togglePlayPause,
                onSkipPrev = vm::skipPrevStation,
                onSkip = vm::skipStation,
                onOpenDetail = { vm.showDetail() },
                isRecording = isRecordingCurrent,
                onToggleRecord = toggleRecord,
                recording = recordingUi,
                onToggleSpeed = vm::toggleRecordingSpeed,
            )
        },
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            // Home is the root; Favorites/Countries/Settings are pushed children, so back pops.
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<HomeRoute> {
                    HomeScreen(
                        discoveredServers = discoveredServers,
                        connectedHost = snapclientHost,
                        lastManualHost = settings.lastManualHost,
                        onStartDiscovery = { discoveryManager.startDiscovery() },
                        onConnectToServer = { server ->
                            vm.connectToSnapserver(server.hostAddress, server.port, server.httpPort)
                        },
                        onConnectManually = { host, typedPort ->
                            vm.connectManually(host, typedPort)
                        },
                        onClearLastManualHost = { vm.updateSetting { setLastManualHost("") } },
                        onSearch = { query ->
                            vm.searchStations(query)
                            if (backStack.lastOrNull() != SearchRoute) backStack.add(SearchRoute)
                        },
                        onShuffleRotation = { vm.startShuffleRotation() },
                        isShuffleLoading = isShuffleLoading,
                        shuffleConnected = shuffleConnected,
                        onOpenSettings = {
                            if (backStack.lastOrNull() != SettingsRoute) backStack.add(SettingsRoute)
                        },
                        onOpenFavorites = {
                            if (backStack.lastOrNull() != FavoritesRoute) backStack.add(FavoritesRoute)
                        },
                        onOpenCountries = {
                            if (backStack.lastOrNull() != CountriesRoute) backStack.add(CountriesRoute)
                        },
                        onOpenRecordings = openRecordings,
                        recordingActive = activeRecordings.isNotEmpty(),
                        modifier = Modifier.padding(padding),
                    )
                }
                entry<SearchRoute> {
                    BackHandler { backStack.removeLastOrNull() }
                    Column(modifier = Modifier.padding(padding)) {
                        ChildTopBar("Search") { backStack.removeLastOrNull() }
                        SearchScreen(
                            uiState = searchResults,
                            playerState = playerState,
                            favoriteUuids = favoriteUuids,
                            onPlay = { station ->
                                vm.cancelRotation()
                                vm.play(station)
                            },
                            onToggleFavorite = vm::toggleFavorite,
                            onStartCustomRotation = ::startCustomRotation,
                            vm = vm,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                entry<FavoritesRoute> {
                    BackHandler { backStack.removeLastOrNull() }
                    Column(modifier = Modifier.padding(padding)) {
                        ChildTopBar("Favorites") { backStack.removeLastOrNull() }
                        FavoritesScreen(
                            favorites = favorites,
                            groups = groups,
                            expandedGroupIds = expandedGroupIds,
                            playerState = playerState,
                            onPlay = { fav ->
                                vm.cancelRotation()
                                vm.playFromFavorite(fav)
                            },
                            onRemove = vm::toggleFavorite,
                            onStartRotation = vm::startFavRotation,
                            onStartCustomRotation = ::startCustomRotation,
                            onToggleGroupExpanded = vm::toggleGroupExpanded,
                            onCreateGroup = vm::createGroup,
                            onRenameGroup = vm::renameGroup,
                            onDeleteGroup = vm::deleteGroup,
                            onAssignToGroup = vm::assignToGroup,
                            onUnassignFromGroup = vm::unassignFromGroup,
                            onReorderFavorite = vm::reorderFavoriteInGroup,
                            onReorderGroup = vm::reorderGroup,
                            vm = vm,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                entry<CountriesRoute> {
                    // Country-list level pops home; the stations sub-view keeps its own back
                    // (CountryScreen's BackHandler(enabled = selectedCountry != null) → clearCountrySelection).
                    BackHandler(enabled = selectedCountry == null) { backStack.removeLastOrNull() }
                    Column(modifier = Modifier.padding(padding)) {
                        if (selectedCountry == null) {
                            ChildTopBar("Countries") { backStack.removeLastOrNull() }
                        }
                        CountryScreen(
                            countryList = countryList,
                            countryStations = countryStations,
                            selectedCountry = selectedCountry,
                            playerState = playerState,
                            favoriteUuids = favoriteUuids,
                            onLoadCountries = vm::loadCountries,
                            onSelectCountry = vm::selectCountry,
                            onBack = vm::clearCountrySelection,
                            onPlay = { station ->
                                vm.cancelRotation()
                                vm.play(station)
                            },
                            onToggleFavorite = vm::toggleFavorite,
                            onStartCustomRotation = ::startCustomRotation,
                            vm = vm,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                entry<SettingsRoute> {
                    BackHandler { backStack.removeLastOrNull() }
                    val settingsBack: () -> Unit = {
                        backStack.removeLastOrNull()
                        Unit
                    }
                    SettingsScreen(
                        settings = settings,
                        onSetApiServer = { vm.updateSetting { setApiServer(it) } },
                        onSetSearchLimit = { vm.updateSetting { setSearchLimit(it) } },
                        onSetRandomBatchSize = { vm.updateSetting { setRandomBatchSize(it) } },
                        onSetRotationMinutes = {
                            vm.updateSetting { setRotationMinutes(it) }
                            vm.setLiveMinutes(it)
                        },
                        onSetVlcNetworkCachingMs = { vm.updateSetting { setVlcNetworkCachingMs(it) } },
                        onSetShazamIntervalSeconds = { vm.updateSetting { setShazamIntervalSeconds(it) } },
                        onSetSleepTimerMinutes = { vm.updateSetting { setSleepTimerMinutes(it) } },
                        onSetThemeMode = { vm.updateSetting { setThemeMode(it) } },
                        onSetBalance = { vm.updateSetting { setBalance(it) } },
                        onSetNormalizationEnabled = { vm.updateSetting { setNormalizationEnabled(it) } },
                        onSetShareService = vm::setShareService,
                        onSetCustomServerName = vm::setCustomServerName,
                        onSetSnapserverFixedPort = vm::setSnapserverFixedPort,
                        onSetAutoEntangleOnLaunch = { vm.updateSetting { setAutoEntangleOnLaunch(it) } },
                        onSetWebDebugPanel = { vm.updateSetting { setWebDebugPanel(it) } },
                        onSetWebAutoplay = { vm.updateSetting { setWebAutoplay(it) } },
                        onSetTunnelEnabled = { vm.updateSetting { setTunnelEnabled(it) } },
                        onSetMaxHistorySongs = { vm.updateSetting { setMaxHistorySongs(it) } },
                        onExportFavorites = vm::exportFavorites,
                        onImportFavorites = vm::importFavorites,
                        onBack = settingsBack,
                        onOpenRecordingStorage = { backStack.add(RecordingsRoute(tab = 2)) },
                        recordingPrefs = recordingPrefs,
                        onSetSkipSeconds = { s -> vm.updateRecordingPrefs { setSkipSeconds(s) } },
                        onSetSmartRewind = { on -> vm.updateRecordingPrefs { setSmartRewind(on) } },
                        onSetSmartRewindLimit = { s -> vm.updateRecordingPrefs { setSmartRewindLimit(s) } },
                        onSetPitchCorrection = vm::setPitchCorrection,
                        modifier = Modifier.padding(padding),
                    )
                }
                entry<RecordingsRoute> { route ->
                    BackHandler { backStack.removeLastOrNull() }
                    Column(modifier = Modifier.padding(padding)) {
                        ChildTopBar("Recordings") { backStack.removeLastOrNull() }
                        RecordingsScreen(
                            vm = recVm,
                            currentStation = recStation,
                            initialTab = route.tab,
                            playingName = playingRecording?.name,
                            onPlayRecording = vm::playRecording,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
        )
    }
}

// Slim top bar for pushed child screens (Favorites/Countries) that lack their own - just a
// title and a back arrow that pops the backstack. Settings brings its own bar, so it's not wrapped.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChildTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    )
}

// Maps the tunnel's state machine onto the library's public-link model for the sheet's QR dialog.
// Same mapping Telecloud uses - the library deliberately does not depend on TunnelManager, so each
// app bridges the two itself.
private fun TunnelManager.TunnelState.toPublicLinkState() = when (this) {
    is TunnelManager.TunnelState.Active -> PublicLinkState.Active(publicUrl)
    TunnelManager.TunnelState.Starting -> PublicLinkState.Starting
    is TunnelManager.TunnelState.Error -> PublicLinkState.Error(message)
    TunnelManager.TunnelState.Off -> PublicLinkState.Off
}
