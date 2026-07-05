package tech.capullo.quantumcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.Serializable
import tech.capullo.quantumcast.data.model.Station
import tech.capullo.quantumcast.player.PlaybackService
import tech.capullo.quantumcast.snapcast.DiscoveredSnapserver
import tech.capullo.quantumcast.snapcast.SnapserverDiscoveryManager
import tech.capullo.quantumcast.ui.screens.*
import tech.capullo.quantumcast.ui.theme.RadioTheme
import tech.capullo.quantumcast.viewmodel.RadioViewModel

// Navigation3 tab destinations. @Serializable NavKeys (kotlinx-serialization is already applied)
// so rememberNavBackStack can save/restore them across process death.
@Serializable private data object SearchRoute : NavKey

@Serializable private data object FavoritesRoute : NavKey

@Serializable private data object SnapcastRoute : NavKey

@Serializable private data object CountriesRoute : NavKey

@Serializable private data object SettingsRoute : NavKey

private data class TabItem(val route: NavKey, val label: String, val icon: ImageVector)

private val tabItems = listOf(
    TabItem(SearchRoute, "Search", Icons.Default.Search),
    TabItem(FavoritesRoute, "Favorites", Icons.Default.Favorite),
    TabItem(SnapcastRoute, "Qcast", Icons.Default.SurroundSound),
    TabItem(CountriesRoute, "Countries", Icons.Default.Public),
    TabItem(SettingsRoute, "Settings", Icons.Default.Settings),
)

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

    override fun onResume() {
        super.onResume()
        // Returning to the app reclaims local audio after a focus loss
        vm.onAppForeground()
    }

    // ADB test: adb shell am start -n tech.capullo.quantumcast/.MainActivity --es dbg shuffle
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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
    val backStack = rememberNavBackStack(SearchRoute)

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
    val snapclientChannel by vm.snapclientChannel.collectAsState()
    val snapclientState by vm.snapclientState.collectAsState()
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
                backStack.clear()
                backStack.add(SettingsRoute)
            },
            onTogglePlayPause = vm::togglePlayPause,
            onIdentifyNow = vm::identifyNow,
            onCancelIdentify = vm::cancelIdentify,
            onSkip = vm::skipStation,
            onSkipPrev = vm::skipPrevStation,
            onToggleTimerPause = vm::toggleTimerPause,
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
            snapclientChannel = snapclientChannel,
            onAdjustClientVolume = vm::adjustClientVolume,
            onAdjustClientLatency = vm::adjustClientLatency,
            onSetSnapclientChannel = vm::setSnapclientChannel,
            onChangeClientChannel = vm::changeClientChannel,
            isSnapclientMode = snapclientHost.isNotEmpty(),
            snapclientHost = snapclientHost,
            streamCanGoNext = streamCanGoNext,
            streamCanGoPrevious = streamCanGoPrevious,
            isStreamLocked = isStreamLocked,
            onToggleStreamLock = vm::toggleStreamLock,
            shareService = settings.shareService,
            onSetShareService = vm::setShareService,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = detailAlpha.value },
        )
        return
    }

    Scaffold(
        bottomBar = {
            Column {
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
                NavigationBar {
                    tabItems.forEach { tab ->
                        NavigationBarItem(
                            selected = backStack.lastOrNull() == tab.route,
                            onClick = {
                                if (backStack.lastOrNull() != tab.route) {
                                    backStack.clear()
                                    backStack.add(tab.route)
                                }
                            },
                            icon = { Icon(tab.icon, tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            // Tabs are flat single-element roots (no inter-tab history), so onBack is
            // inert here; the guard just prevents ever emptying the stack.
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<SearchRoute> {
                    SearchScreen(
                        uiState = searchResults,
                        playerState = playerState,
                        favoriteUuids = favoriteUuids,
                        onSearch = vm::searchStations,
                        onResetSearch = vm::resetSearch,
                        onShuffleRotation = { vm.startShuffleRotation() },
                        isShuffleLoading = isShuffleLoading,
                        shuffleConnected = shuffleConnected,
                        onStartCustomRotation = ::startCustomRotation,
                        onPlay = { station ->
                            vm.cancelRotation()
                            vm.play(station)
                        },
                        onToggleFavorite = vm::toggleFavorite,
                        vm = vm,
                        modifier = Modifier.padding(padding),
                    )
                }
                entry<FavoritesRoute> {
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
                        modifier = Modifier.padding(padding),
                    )
                }
                entry<SnapcastRoute> {
                    SnapcastScreen(
                        discoveredServers = discoveredServers,
                        connectedHost = snapclientHost,
                        snapclientState = snapclientState,
                        lastManualHost = settings.lastManualHost,
                        onStartDiscovery = { discoveryManager.startDiscovery() },
                        onConnectToServer = { server ->
                            vm.connectToSnapserver(server.hostAddress, server.port)
                        },
                        onConnectManually = { host, port ->
                            vm.connectToSnapserver(host, port)
                        },
                        onClearLastManualHost = { vm.updateSetting { setLastManualHost("") } },
                        onDisconnect = vm::disconnectSnapclient,
                        modifier = Modifier.padding(padding),
                    )
                }
                entry<CountriesRoute> {
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
                        modifier = Modifier.padding(padding),
                    )
                }
                entry<SettingsRoute> {
                    val settingsBack: () -> Unit = {
                        if (playerState.station != null) {
                            vm.showDetail()
                        } else {
                            backStack.clear()
                            backStack.add(SearchRoute)
                        }
                    }
                    BackHandler(onBack = settingsBack)
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
