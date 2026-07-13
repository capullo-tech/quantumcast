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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: RadioViewModel by viewModels()
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
                RadioApp(vm, discoveredServers, discoveryManager)
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
            "snaptest" -> vm.startSnapTest()
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
    discoveredServers: List<DiscoveredSnapserver>,
    discoveryManager: SnapserverDiscoveryManager,
) {
    val backStack = rememberNavBackStack(HomeRoute)

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
    val snapclientChannel by vm.snapclientChannel.collectAsState()
    val snapcastGroups by vm.snapcastGroups.collectAsState()
    val streamCanGoNext by vm.streamCanGoNext.collectAsState()
    val streamCanGoPrevious by vm.streamCanGoPrevious.collectAsState()
    val isStreamLocked by vm.isStreamLocked.collectAsState()
    val streamStats by vm.streamStats.collectAsState()
    val sleepTimerActive by vm.sleepTimerActive.collectAsState()
    val sleepTimerSecondsRemaining by vm.sleepTimerSecondsRemaining.collectAsState()

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
                        onConnectManually = { host, port, httpPort ->
                            vm.connectToSnapserver(host, port, httpPort)
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
                        onSetShareService = vm::setShareService,
                        onSetCustomServerName = vm::setCustomServerName,
                        onSetAutoEntangleOnLaunch = { vm.updateSetting { setAutoEntangleOnLaunch(it) } },
                        onSetWebDebugPanel = { vm.updateSetting { setWebDebugPanel(it) } },
                        onSetWebAutoplay = { vm.updateSetting { setWebAutoplay(it) } },
                        onSetMaxHistorySongs = { vm.updateSetting { setMaxHistorySongs(it) } },
                        onExportFavorites = vm::exportFavorites,
                        onImportFavorites = vm::importFavorites,
                        onBack = settingsBack,
                        modifier = Modifier.padding(padding),
                    )
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
