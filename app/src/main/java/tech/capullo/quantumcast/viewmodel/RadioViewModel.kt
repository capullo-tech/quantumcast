package tech.capullo.quantumcast.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import tech.capullo.quantumcast.data.db.AppDatabase
import tech.capullo.quantumcast.data.db.FavoriteEntity
import tech.capullo.quantumcast.data.db.FavoriteGroupEntity
import tech.capullo.quantumcast.data.model.Country
import tech.capullo.quantumcast.data.model.Station
import tech.capullo.quantumcast.data.model.TrackLookup
import tech.capullo.quantumcast.data.repository.RadioRepository
import tech.capullo.quantumcast.data.settings.AppSettings
import tech.capullo.quantumcast.data.settings.SettingsRepository
import tech.capullo.quantumcast.player.PlaybackService
import tech.capullo.quantumcast.shazam.AudioCapturer
import tech.capullo.quantumcast.shazam.ShazamRecognizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.io.OutputStream

data class PlayerState(
    val station: Station? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val bufferingPercent: Float = 0f,
    val icyTitle: String = "",
    val currentTrack: tech.capullo.quantumcast.data.model.TrackLookup? = null,
)

sealed class UiState<out T> {
    object Idle : UiState<Nothing>()
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

enum class RotationMode { RANDOM, FAVORITES, CUSTOM }

data class RotationState(
    val isActive: Boolean = false,
    val mode: RotationMode = RotationMode.RANDOM,
    val secondsRemaining: Int = 0,
    val totalSeconds: Int = 0,
    val stationIndex: Int = 0,
    val totalStations: Int = 0,
    val timerPaused: Boolean = false
) {
    val progress: Float get() = if (totalSeconds > 0) 1f - secondsRemaining.toFloat() / totalSeconds else 0f
}

class RadioViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = RadioRepository(AppDatabase.getInstance(app))
    val settingsRepo = SettingsRepository(app)

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .onEach { repo.setServerUrl(it.apiServer) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _searchResults = MutableStateFlow<UiState<List<Station>>>(UiState.Idle)
    val searchResults: StateFlow<UiState<List<Station>>> = _searchResults

    private val _countryList = MutableStateFlow<UiState<List<Country>>>(UiState.Idle)
    val countryList: StateFlow<UiState<List<Country>>> = _countryList

    private val _countryStations = MutableStateFlow<UiState<List<Station>>>(UiState.Idle)
    val countryStations: StateFlow<UiState<List<Station>>> = _countryStations

    private val _selectedCountry = MutableStateFlow<Country?>(null)
    val selectedCountry: StateFlow<Country?> = _selectedCountry

    private val _trackHistory = MutableStateFlow<List<TrackLookup>>(emptyList())
    val trackHistory: StateFlow<List<TrackLookup>> = _trackHistory

    private val _isShazamRunning = MutableStateFlow(false)
    val isShazamRunning: StateFlow<Boolean> = _isShazamRunning

    data class StreamStats(val codec: String, val bitrate: Int, val sampleRate: Int, val channels: Int)
    private val _streamStats = MutableStateFlow<StreamStats?>(null)
    val streamStats: StateFlow<StreamStats?> = _streamStats

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState

    private val _rotationState = MutableStateFlow(RotationState())
    val rotationState: StateFlow<RotationState> = _rotationState

    private val _rotationQueue = MutableStateFlow<List<Station>>(emptyList())
    val rotationQueue: StateFlow<List<Station>> = _rotationQueue

    private val _liveMinutes = MutableStateFlow(3)
    val liveMinutes: StateFlow<Int> = _liveMinutes

    val favorites: StateFlow<List<FavoriteEntity>> = repo.getFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteUuids: StateFlow<Set<String>> = repo.getFavoriteUuids()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val groups: StateFlow<List<FavoriteGroupEntity>> = repo.getGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _expandedGroupIds = MutableStateFlow<Set<String>>(emptySet())
    val expandedGroupIds: StateFlow<Set<String>> = _expandedGroupIds

    private val _showDetailScreen = MutableStateFlow(false)
    val showDetailScreen: StateFlow<Boolean> = _showDetailScreen
    fun showDetail() { _showDetailScreen.value = true }
    fun hideDetail() { _showDetailScreen.value = false }

    init {
        // Entangle on launch: runs once per VM lifetime (= app open, not config change).
        // settingsRepo.settings.first() waits for the real DataStore emission - the
        // stateIn default would read autoEntangleOnLaunch=false before persistence loads.
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            if (!s.autoEntangleOnLaunch) return@launch
            // Wait for the PlaybackService bind (connectPlayer runs in Activity.onCreate)
            withTimeoutOrNull(5_000) { while (playbackService == null) delay(50) }
            val svc = playbackService ?: return@launch
            // Don't hijack a session that survived app close (foreground service):
            // broadcaster with a station, or connected snapclient.
            val st = svc.state.value
            if (st.isPlaying || st.stationName.isNotEmpty() || st.snapclientHost.isNotEmpty()) return@launch
            if (_rotationState.value.isActive || _playerState.value.station != null) return@launch
            Log.d(TAG, "Auto-entangle on launch")
            startShuffleRotation()
        }
    }

    private val _snapclientHost = MutableStateFlow("")
    val snapclientHost: StateFlow<String> = _snapclientHost

    private val _snapclientChannel = MutableStateFlow("stereo")
    val snapclientChannel: StateFlow<String> = _snapclientChannel

    private val _snapclientState = MutableStateFlow(
        tech.capullo.quantumcast.snapcast.SnapclientProcess.ConnectionState.STARTING
    )
    val snapclientState: StateFlow<tech.capullo.quantumcast.snapcast.SnapclientProcess.ConnectionState> = _snapclientState

    private val _snapcastGroups = MutableStateFlow<List<tech.capullo.quantumcast.snapcast.Group>>(emptyList())
    val snapcastGroups: StateFlow<List<tech.capullo.quantumcast.snapcast.Group>> = _snapcastGroups

    private val _streamCanGoNext = MutableStateFlow(false)
    val streamCanGoNext: StateFlow<Boolean> = _streamCanGoNext
    private val _streamCanGoPrevious = MutableStateFlow(false)
    val streamCanGoPrevious: StateFlow<Boolean> = _streamCanGoPrevious
    private val _isStreamLocked = MutableStateFlow(false)
    val isStreamLocked: StateFlow<Boolean> = _isStreamLocked

    private val _sleepTimerActive = MutableStateFlow(false)
    val sleepTimerActive: StateFlow<Boolean> = _sleepTimerActive

    private val _sleepTimerSecondsRemaining = MutableStateFlow(0)
    val sleepTimerSecondsRemaining: StateFlow<Int> = _sleepTimerSecondsRemaining

    private sealed class CountdownResult {
        object Next : CountdownResult()
        object Prev : CountdownResult()
        data class Jump(val targetIndex: Int) : CountdownResult()
    }

    // --- Service binding (replaces Media3 MediaController) ---

    private var playbackService: PlaybackService? = null
    private var serviceStateJob: Job? = null
    private var rotationSkipJob: Job? = null
    private var customNameJob: Job? = null
    private var webCfgJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var rotationJob: Job? = null
    private var shazamJob: Job? = null
    private val skipChannel = kotlinx.coroutines.channels.Channel<CountdownResult>(kotlinx.coroutines.channels.Channel.BUFFERED)
    private var lastSkipMs = 0L
    private val reachabilityCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // Sort state (persists across navigation)
    var searchSortBy by mutableStateOf<String?>(null)
    var searchSortDir by mutableStateOf("DESC")
    var favSortBy by mutableStateOf("NAME")
    var favSortAscending by mutableStateOf(true)
    var countryListSortByName by mutableStateOf(false)
    var countryListSortAscending by mutableStateOf(false)
    var countryStationsSortField by mutableStateOf<Int?>(null)
    var countryStationsSortAsc by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as PlaybackService.LocalBinder).getService()
            playbackService = svc
            svc.onSkipNextRequested = { viewModelScope.launch { skipStation() } }
            svc.onSkipPrevRequested = { viewModelScope.launch { skipPrevStation() } }
            svc.onPlayPauseRequested = { viewModelScope.launch { togglePlayPause() } }
            svc.onStationError = { viewModelScope.launch { handleStationError() } }
            svc.onStationPlaying = { consecutiveErrors = 0; retryJob?.cancel() }
            // Collect (not one-shot): at bind time DataStore may not have emitted the
            // persisted settings yet, so a single read sees the "" default and the
            // server falls back to Build.MODEL until the user re-applies the name.
            customNameJob?.cancel()
            customNameJob = viewModelScope.launch {
                settings.map { it.customServerName }.distinctUntilChanged()
                    .collect { svc.updateCustomServerName(it) }
            }
            webCfgJob?.cancel()
            webCfgJob = viewModelScope.launch {
                settings.map { it.webDebugPanel to it.webAutoplay }.distinctUntilChanged()
                    .collect { (debug, autoplay) -> svc.updateWebConfig(debug, autoplay) }
            }
            rotationSkipJob?.cancel()
            rotationSkipJob = viewModelScope.launch {
                // Skipping only makes sense while rotation is active; tells Snapcast
                // clients to hide prev/next for a single station via canGoNext/Previous.
                _rotationState.collect { svc.updateBroadcastCanSkip(it.isActive) }
            }
            serviceStateJob = viewModelScope.launch {
                svc.state.collect { svcState ->
                    _playerState.update { ps ->
                        val isSnapclient = svcState.broadcastMode == tech.capullo.quantumcast.data.settings.BroadcastMode.SNAPCLIENT
                        val updatedStation = if (isSnapclient) {
                            val clientName = svcState.snapclientDisplayName
                            val stationName = svcState.snapcastStationName
                            val displayName = when {
                                clientName.isNotBlank() && stationName.isNotBlank() -> "$clientName - $stationName"
                                clientName.isNotBlank() -> clientName
                                else -> stationName
                            }
                            if (displayName.isNotBlank() || svcState.snapcastCountry.isNotBlank()) {
                                val base = ps.station ?: tech.capullo.quantumcast.data.model.Station()
                                base.copy(
                                    name = displayName.ifBlank { base.name },
                                    country = svcState.snapcastCountry.ifBlank { base.country },
                                    countryCode = svcState.snapcastCountryCode.ifBlank { base.countryCode },
                                    codec = svcState.snapcastCodec.ifBlank { base.codec },
                                    bitrate = if (svcState.snapcastBitrate > 0) svcState.snapcastBitrate else base.bitrate,
                                    url = svcState.snapcastUrl.ifBlank { base.url },
                                    favicon = svcState.snapcastStreamArtUrl,
                                    tags = svcState.snapcastTags.ifBlank { base.tags },
                                    uuid = svcState.snapcastUuid.ifBlank { base.uuid },
                                )
                            } else ps.station
                        } else ps.station

                        // Mirror local Shazam: when broadcaster has identified the track, build a
                        // synthetic TrackLookup so NowPlayingHero takes the same identified-track
                        // branch as it does locally. When no identification, null → falls back to
                        // station.name + icyTitle, identical to local no-Shazam rule.
                        val snapTrack = if (isSnapclient && svcState.snapcastArtistName.isNotBlank()) {
                            tech.capullo.quantumcast.data.model.TrackLookup(
                                icyTitle = svcState.icyTitle,
                                trackName = svcState.snapcastTrackName,
                                artistName = svcState.snapcastArtistName,
                                artworkUrl = svcState.snapcastStreamArtUrl,
                                youtubeUrl = svcState.snapcastYoutubeUrl,
                                spotifyUrl = svcState.snapcastSpotifyUrl,
                                appleMusicUrl = svcState.snapcastAppleMusicUrl,
                            )
                        } else null

                        ps.copy(
                            isPlaying = svcState.isPlaying,
                            isBuffering = svcState.isBuffering,
                            bufferingPercent = svcState.bufferingPercent,
                            icyTitle = svcState.icyTitle,
                            station = updatedStation,
                            currentTrack = if (isSnapclient) snapTrack else ps.currentTrack,
                        )
                    }
                    _snapclientHost.value = svcState.snapclientHost
                    _snapclientChannel.value = svcState.snapclientChannel
                    _snapclientState.value = svcState.snapclientState
                    _snapcastGroups.value = svcState.snapcastGroups
                    _streamCanGoNext.value = svcState.streamCanGoNext
                    _streamCanGoPrevious.value = svcState.streamCanGoPrevious
                    _isStreamLocked.value = if (svcState.broadcastMode == tech.capullo.quantumcast.data.settings.BroadcastMode.SNAPCLIENT) {
                        svcState.stationName.isNotEmpty() && !svcState.streamCanPlay && !svcState.streamCanPause
                    } else {
                        svcState.isStreamLocked
                    }
                }
            }
            // Bind happens when the activity (re)opens - count it as coming to
            // the foreground so focus-paused local audio resumes reliably even
            // though onResume fired before the service connection was up.
            svc.onAppForeground()
            Log.d(TAG, "PlaybackService connected")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            playbackService?.onSkipNextRequested = null
            playbackService?.onSkipPrevRequested = null
            playbackService?.onPlayPauseRequested = null
            playbackService?.onStationError = null
            playbackService = null
            serviceStateJob?.cancel()
            rotationSkipJob?.cancel()
            customNameJob?.cancel()
            webCfgJob?.cancel()
            Log.d(TAG, "PlaybackService disconnected")
        }
    }

    fun onAppForeground() { playbackService?.onAppForeground() }

    fun connectPlayer() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, PlaybackService::class.java))
        ctx.bindService(Intent(ctx, PlaybackService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun disconnectPlayer() {
        try { getApplication<Application>().unbindService(serviceConnection) } catch (_: Exception) {}
        playbackService = null
        serviceStateJob?.cancel()
    }

    // --- Playback ---

    fun play(station: Station) = playInternal(station, openDetail = true)

    private fun playInternal(station: Station, openDetail: Boolean, resetErrors: Boolean = true) {
        Log.d(TAG, "play: ${station.name}")
        // Error-retries of the SAME station pass resetErrors=false: otherwise the
        // counter zeroed here every retry, so the "skip after 2 failures" gate was
        // never reached and a dead station (e.g. empty body → instant EOS) retried
        // forever instead of advancing the rotation.
        if (resetErrors) consecutiveErrors = 0
        retryJob?.cancel()
        playbackService?.stopErrorAudio()
        if (openDetail) _showDetailScreen.value = true
        _playerState.update { it.copy(station = station, icyTitle = "", currentTrack = null) }
        _trackHistory.value = listOf(TrackLookup(icyTitle = "", isLoading = true))
        _streamStats.value = null
        startShazamLoop(station.streamUrl)

        playbackService?.playStation(
            url = station.streamUrl,
            title = station.name,
            artist = station.country,
            uuid = station.uuid,
            favicon = station.favicon,
            countryCode = station.countryCode,
            codec = station.codec,
            bitrate = station.bitrate,
            tags = station.tags,
            vlcNetworkCachingMs = settings.value.vlcNetworkCachingMs,
        )
    }

    fun playFromFavorite(fav: FavoriteEntity) {
        play(Station(uuid = fav.uuid, name = fav.name, url = fav.url, favicon = fav.favicon,
            country = fav.country, tags = fav.tags, codec = fav.codec, bitrate = fav.bitrate))
    }

    fun togglePlayPause() {
        val svc = playbackService ?: return
        if (_snapclientHost.value.isNotEmpty()) {
            val cmd = if (_playerState.value.isPlaying) "pause" else "play"
            Log.d(TAG, "togglePlayPause [SNAPCLIENT] → $cmd")
            sendPlayerControl(cmd)
            _playerState.update { it.copy(isPlaying = !it.isPlaying) }
            return
        }
        if (_playerState.value.isPlaying) {
            svc.pause()
            shazamJob?.cancel(); shazamJob = null; _isShazamRunning.value = false
            if (_rotationState.value.isActive) _rotationState.update { it.copy(timerPaused = true) }
        } else {
            svc.play()
            _playerState.value.station?.streamUrl?.let { startShazamLoop(it) }
            if (_rotationState.value.isActive) _rotationState.update { it.copy(timerPaused = false) }
        }
    }

    private fun sendPlayerControl(command: String) {
        viewModelScope.launch {
            val streamId = _snapcastGroups.value.firstOrNull()?.streamId
            Log.d(TAG, "sendPlayerControl: command=$command streamId=$streamId groups=${_snapcastGroups.value.size}")
            if (streamId == null) { Log.w(TAG, "sendPlayerControl: no stream id - groups empty, aborting"); return@launch }
            playbackService?.sendPlayerControl(streamId, command)
        }
    }

    fun stop() {
        sleepTimerJob?.cancel(); sleepTimerJob = null
        _sleepTimerActive.value = false; _sleepTimerSecondsRemaining.value = 0
        shazamJob?.cancel(); shazamJob = null; _isShazamRunning.value = false
        playbackService?.stop()
        _playerState.update { it.copy(station = null, isPlaying = false) }
    }

    // --- Rotation ---

    fun startFavRotation() { startRotation(RotationMode.FAVORITES) }

    private val _isShuffleLoading = MutableStateFlow(false)
    val isShuffleLoading: StateFlow<Boolean> = _isShuffleLoading

    // All five entangle dots lit - discovery succeeded; the UI holds on this
    // state briefly before the now-playing screen is revealed.
    private val _shuffleConnected = MutableStateFlow(false)
    val shuffleConnected: StateFlow<Boolean> = _shuffleConnected

    fun startShuffleRotation() {
        if (_isShuffleLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isShuffleLoading.value = true
            var stations = emptyList<Station>()
            var lastError = "No stations returned"
            for (attempt in 1..3) {
                val result = runCatching { repo.getRandomStations(settings.value.randomBatchSize) }
                stations = result.getOrElse { emptyList() }
                if (stations.isNotEmpty()) break
                lastError = result.exceptionOrNull()?.message?.take(80) ?: "No stations returned"
            }
            withContext(Dispatchers.Main) {
                if (stations.isNotEmpty()) {
                    // Show "entangled" (all dots lit) while playback spins up
                    // underneath, then reveal now-playing.
                    _shuffleConnected.value = true
                    startCustomRotation(stations, openDetail = false)
                    delay(900)
                    _isShuffleLoading.value = false
                    _shuffleConnected.value = false
                    _showDetailScreen.value = true
                } else {
                    _isShuffleLoading.value = false
                    android.widget.Toast.makeText(getApplication(), "Shuffle: $lastError", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun startCustomRotation(stations: List<Station>, openDetail: Boolean = true) {
        _rotationQueue.value = stations
        _liveMinutes.value = settings.value.rotationMinutes
        startRotation(RotationMode.CUSTOM, openDetail)
    }

    fun setLiveMinutes(minutes: Int) { _liveMinutes.value = minutes.coerceIn(1, 60) }

    private fun startRotation(mode: RotationMode, openDetailOnFirstPlay: Boolean = true) {
        rotationJob?.cancel(); reachabilityCache.clear()
        rotationJob = viewModelScope.launch {
            _liveMinutes.value = settings.value.rotationMinutes
            _rotationState.value = RotationState(isActive = true, mode = mode)
            var currentIndex = 0
            var firstPlay = true
            while (isActive) {
                val stations: List<Station> = when (mode) {
                    RotationMode.RANDOM -> runCatching { repo.getRandomStations(settings.value.randomBatchSize) }.getOrElse { emptyList() }
                    RotationMode.FAVORITES -> favorites.value.map { fav ->
                        Station(uuid = fav.uuid, name = fav.name, url = fav.url, favicon = fav.favicon, country = fav.country, tags = fav.tags, codec = fav.codec, bitrate = fav.bitrate)
                    }.shuffled()
                    RotationMode.CUSTOM -> _rotationQueue.value
                }
                if (stations.isEmpty()) { _rotationState.value = RotationState(); return@launch }
                _rotationQueue.value = stations; reachabilityCache.clear()
                var consecutiveSkips = 0
                inner@ while (isActive) {
                    val liveStations = if (mode == RotationMode.CUSTOM) _rotationQueue.value else stations
                    if (liveStations.isEmpty()) { _rotationState.value = RotationState(); return@launch }
                    if (currentIndex >= liveStations.size) { currentIndex = 0; break@inner }
                    val station = liveStations[currentIndex]
                    val reachable = reachabilityCache.remove(station.streamUrl) ?: isReachable(station.streamUrl)
                    if (!reachable) {
                        currentIndex++
                        if (++consecutiveSkips >= liveStations.size) { _rotationState.value = RotationState(); return@launch }
                        continue@inner
                    }
                    consecutiveSkips = 0
                    playInternal(station, openDetail = firstPlay && openDetailOnFirstPlay)
                    firstPlay = false
                    val prewarmJob = launch(Dispatchers.IO) {
                        var look = currentIndex + 1; var found = 0
                        while (look < liveStations.size && found < 3 && isActive) {
                            val url = liveStations[look].streamUrl
                            if (!reachabilityCache.containsKey(url)) { val ok = isReachable(url); reachabilityCache[url] = ok; if (ok) found++ }
                            else if (reachabilityCache[url] == true) found++
                            look++
                        }
                    }
                    _rotationState.update { it.copy(isActive = true, mode = mode, secondsRemaining = _liveMinutes.value * 60, totalSeconds = _liveMinutes.value * 60, stationIndex = currentIndex + 1, totalStations = liveStations.size, timerPaused = false) }
                    val direction = runCountdown()
                    prewarmJob.cancel()
                    if (!isActive) return@launch
                    currentIndex = when (direction) {
                        CountdownResult.Prev -> (currentIndex - 1).coerceAtLeast(0)
                        is CountdownResult.Jump -> direction.targetIndex
                        else -> currentIndex + 1
                    }
                }
                currentIndex = 0
            }
        }
    }

    private suspend fun runCountdown(): CountdownResult {
        var elapsed = 0L; var lastLiveMinutes = _liveMinutes.value; val tickMs = 200L
        while (currentCoroutineContext().isActive) {
            skipChannel.tryReceive().getOrNull()?.let { return it }
            val currentMinutes = _liveMinutes.value
            if (currentMinutes != lastLiveMinutes) { lastLiveMinutes = currentMinutes; elapsed = 0L }
            val totalSec = currentMinutes * 60
            val remainingSec = (totalSec - (elapsed / 1000).toInt()).coerceAtLeast(0)
            _rotationState.update { it.copy(secondsRemaining = remainingSec, totalSeconds = totalSec) }
            if (remainingSec <= 0) return CountdownResult.Next
            val signal = withTimeoutOrNull(tickMs) { skipChannel.receive() }
            if (signal != null) return signal
            if (!_rotationState.value.timerPaused) elapsed += tickMs
        }
        return CountdownResult.Next
    }

    private var consecutiveErrors = 0
    private var retryJob: Job? = null

    private fun handleStationError() {
        Log.w(TAG, "Station error - rotation=${_rotationState.value.isActive} consecutive=$consecutiveErrors")
        retryJob?.cancel()

        if (_rotationState.value.isActive) {
            consecutiveErrors++
            if (consecutiveErrors >= 2) {
                consecutiveErrors = 0
                playbackService?.stopErrorAudio()
                skipStation()
                return
            }
            retryJob = viewModelScope.launch {
                playbackService?.startErrorAudio(2_000)
                delay(2_000)
                // openDetail=false: a background retry of the current station must
                // not force the now-playing screen open - that ejected the user
                // out of Settings (any screen) whenever a station kept failing.
                // resetErrors=false so consecutiveErrors keeps climbing to the skip gate.
                _playerState.value.station?.let { playInternal(it, openDetail = false, resetErrors = false) }
            }
            return
        }

        consecutiveErrors++
        if (consecutiveErrors > 5) {
            Log.e(TAG, "Too many consecutive errors - giving up")
            consecutiveErrors = 0
            playbackService?.stopErrorAudio()
            return
        }
        val delayMs = minOf(2_000L * (1L shl (consecutiveErrors - 1)), 30_000L)
        Log.w(TAG, "Retrying in ${delayMs}ms (attempt $consecutiveErrors)")
        retryJob = viewModelScope.launch {
            playbackService?.startErrorAudio(delayMs)
            delay(delayMs)
            // openDetail=false, resetErrors=false - see the rotation branch above
            _playerState.value.station?.let { playInternal(it, openDetail = false, resetErrors = false) }
        }
    }

    fun skipStation() {
        if (_snapclientHost.value.isNotEmpty()) {
            Log.d(TAG, "skipStation [SNAPCLIENT] canGoNext=${_streamCanGoNext.value}")
            if (_streamCanGoNext.value) {
                _playerState.update { it.copy(currentTrack = null, icyTitle = "") }
                sendPlayerControl("next")
            }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastSkipMs < 400) return; lastSkipMs = now
        Log.d(TAG, "skipStation [QUANTUMCAST] → CountdownResult.Next")
        skipChannel.trySend(CountdownResult.Next)
    }
    fun skipPrevStation() {
        if (_snapclientHost.value.isNotEmpty()) {
            Log.d(TAG, "skipPrevStation [SNAPCLIENT] canGoPrevious=${_streamCanGoPrevious.value}")
            if (_streamCanGoPrevious.value) {
                _playerState.update { it.copy(currentTrack = null, icyTitle = "") }
                sendPlayerControl("previous")
            }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastSkipMs < 400) return; lastSkipMs = now
        Log.d(TAG, "skipPrevStation [QUANTUMCAST] → CountdownResult.Prev")
        skipChannel.trySend(CountdownResult.Prev)
    }
    fun jumpToQueueStation(index: Int) { skipChannel.trySend(CountdownResult.Jump(index)) }
    fun removeFromRotationQueue(index: Int) {
        val newQueue = _rotationQueue.value.toMutableList().also { if (index in it.indices) it.removeAt(index) }
        _rotationQueue.value = newQueue
        if (newQueue.isEmpty()) stopRotation()
    }
    fun clearTrackHistory() { _trackHistory.value = emptyList() }
    fun deleteTrackHistoryAt(index: Int) {
        _trackHistory.update { list -> list.toMutableList().also { if (index in it.indices) it.removeAt(index) } }
    }
    fun toggleTimerPause() { _rotationState.update { it.copy(timerPaused = !it.timerPaused) } }
    fun stopRotation() { rotationJob?.cancel(); rotationJob = null; _rotationState.value = RotationState(); stop() }
    fun cancelRotation() { rotationJob?.cancel(); rotationJob = null; _rotationState.value = RotationState() }

    // --- Search ---

    fun resetSearch() { _searchResults.value = UiState.Idle }
    fun searchStations(query: String) {
        if (query.isBlank()) { loadTopStations(); return }
        viewModelScope.launch {
            _searchResults.value = UiState.Loading
            runCatching { repo.search(query, settings.value.searchLimit) }
                .onSuccess { _searchResults.value = UiState.Success(it) }
                .onFailure { _searchResults.value = UiState.Error(it.message ?: "Search failed") }
        }
    }
    fun loadTopStations() {
        viewModelScope.launch {
            _searchResults.value = UiState.Loading
            runCatching { repo.getTopStations(settings.value.searchLimit) }
                .onSuccess { _searchResults.value = UiState.Success(it) }
                .onFailure { _searchResults.value = UiState.Error(it.message ?: "Failed") }
        }
    }
    fun loadRandomStations() {
        viewModelScope.launch {
            _searchResults.value = UiState.Loading
            runCatching { repo.getRandomStations(settings.value.randomBatchSize) }
                .onSuccess { _searchResults.value = UiState.Success(it) }
                .onFailure { _searchResults.value = UiState.Error(it.message ?: "Failed") }
        }
    }

    // --- Country browser ---

    fun loadCountries() {
        if (_countryList.value is UiState.Success) return
        viewModelScope.launch {
            _countryList.value = UiState.Loading
            runCatching { repo.getCountries() }
                .onSuccess { _countryList.value = UiState.Success(it.filter { c -> c.name.isNotBlank() }) }
                .onFailure { _countryList.value = UiState.Error(it.message ?: "Failed") }
        }
    }
    fun selectCountry(country: Country) {
        _selectedCountry.value = country; _countryStations.value = UiState.Loading
        viewModelScope.launch {
            runCatching { repo.getStationsByCountry(country.name, settings.value.searchLimit) }
                .onSuccess { _countryStations.value = UiState.Success(it) }
                .onFailure { _countryStations.value = UiState.Error(it.message ?: "Failed") }
        }
    }
    fun clearCountrySelection() { _selectedCountry.value = null; _countryStations.value = UiState.Idle }

    // --- Favorites ---

    fun toggleFavorite(station: Station) { viewModelScope.launch { runCatching { repo.toggleFavorite(station) } } }
    fun toggleGroupExpanded(id: String) { _expandedGroupIds.update { ids -> if (id in ids) ids - id else ids + id } }
    fun createGroup(name: String, uuids: Set<String>) {
        viewModelScope.launch { val id = repo.createGroup(name, uuids); _expandedGroupIds.update { it + id } }
    }
    fun renameGroup(id: String, name: String) { viewModelScope.launch { repo.renameGroup(id, name) } }
    fun deleteGroup(id: String) { viewModelScope.launch { repo.deleteGroup(id); _expandedGroupIds.update { it - id } } }
    fun assignToGroup(uuids: Set<String>, groupId: String) {
        viewModelScope.launch { repo.assignToGroup(uuids, groupId, startOrder = favorites.value.count { it.groupId == groupId }) }
    }
    fun unassignFromGroup(uuids: Set<String>) { viewModelScope.launch { repo.unassignFromGroup(uuids) } }
    fun reorderFavoriteInGroup(uuid: String, newSortOrder: Int) { viewModelScope.launch { repo.updateFavoriteSortOrder(uuid, newSortOrder) } }
    fun reorderGroup(id: String, newSortOrder: Int) { viewModelScope.launch { repo.updateGroupSortOrder(id, newSortOrder) } }
    fun exportFavorites(outputStream: OutputStream) { viewModelScope.launch(Dispatchers.IO) { runCatching { repo.exportFavorites(outputStream) } } }
    fun importFavorites(inputStream: InputStream) { viewModelScope.launch(Dispatchers.IO) { runCatching { repo.importFavorites(inputStream) } } }

    // --- Snapclient (QuantumCast tab) ---

    fun connectToSnapserver(host: String, port: Int = 1604) {
        // Stop any active rotation and Shazam - we're switching to listener mode
        cancelRotation()
        shazamJob?.cancel(); shazamJob = null; _isShazamRunning.value = false
        // Synthetic station so NowPlayingBar + Screen show the connected server
        _playerState.update { it.copy(
            station = tech.capullo.quantumcast.data.model.Station(uuid = "snapclient-$host", name = host),
            isPlaying = false, icyTitle = "", currentTrack = null,
        ) }
        _trackHistory.value = emptyList()
        _streamStats.value = null
        _showDetailScreen.value = true
        viewModelScope.launch { settingsRepo.setLastManualHost(host) }
        playbackService?.connectAsSnapclient(host, port)
    }

    fun disconnectSnapclient() {
        playbackService?.disconnectSnapclient()
        _playerState.update { it.copy(station = null, isPlaying = false) }
        _showDetailScreen.value = false
    }

    fun setSnapclientChannel(channel: String) {
        playbackService?.setSnapclientChannel(channel)
    }

    fun toggleStreamLock() { playbackService?.toggleStreamLock() }

    fun adjustClientVolume(clientId: String, muted: Boolean, percent: Int) {
        Log.d(TAG, "adjustClientVolume: client=$clientId percent=$percent muted=$muted")
        viewModelScope.launch { playbackService?.adjustClientVolume(clientId, percent, muted) }
    }

    fun adjustClientLatency(clientId: String, latencyMs: Int) {
        viewModelScope.launch { playbackService?.adjustClientLatency(clientId, latencyMs) }
    }

    fun changeClientChannel(clientId: String, channel: String) {
        Log.d(TAG, "changeClientChannel: client=$clientId channel=$channel")
        playbackService?.changeClientChannel(clientId, channel)
    }

    // --- Settings ---

    fun updateSetting(block: suspend SettingsRepository.() -> Unit) { viewModelScope.launch { settingsRepo.block() } }
    fun setShareService(v: tech.capullo.quantumcast.data.settings.ShareService) { viewModelScope.launch { settingsRepo.setShareService(v) } }
    fun setCustomServerName(v: String) { viewModelScope.launch { settingsRepo.setCustomServerName(v); playbackService?.updateCustomServerName(v) } }

    // --- Shazam ---

    fun identifyNow() { _playerState.value.station?.streamUrl?.let { startShazamLoop(it) } }
    fun cancelIdentify() { shazamJob?.cancel(); shazamJob = null; _isShazamRunning.value = false }

    private fun startShazamLoop(streamUrl: String) {
        shazamJob?.cancel(); _isShazamRunning.value = true
        shazamJob = viewModelScope.launch(Dispatchers.IO) {
            delay(4_000)
            while (isActive) {
                val result = runCatching { ShazamRecognizer.recognize(streamUrl, getApplication()) }.getOrNull()
                _isShazamRunning.value = false
                if (AudioCapturer.lastOutRate > 0) {
                    _streamStats.value = StreamStats(codec = AudioCapturer.lastCodec, bitrate = AudioCapturer.lastBitrate / 1000, sampleRate = AudioCapturer.lastOutRate, channels = AudioCapturer.lastOutCh)
                }
                val station = _playerState.value.station
                val stationName = station?.name ?: ""; val stationCountryCode = station?.countryCode ?: ""
                val max = settings.value.maxHistorySongs; val keep = if (max <= 0) 500 else max
                if (result != null) {
                    val entry = result.copy(stationName = stationName, stationCountryCode = stationCountryCode)
                    _playerState.update { it.copy(currentTrack = entry) }
                    _trackHistory.update { list ->
                        val base = if (list.firstOrNull()?.isLoading == true) list.drop(1) else list
                        val top = base.firstOrNull()
                        if (top != null && !top.isLoading && !top.notFound && top.trackName == entry.trackName && top.artistName == entry.artistName) base
                        else listOf(entry) + base.take(keep - 1)
                    }
                    if (result.trackName.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            playbackService?.updateIcyTitle("${result.trackName} - ${result.artistName}")
                            playbackService?.updateIdentifiedTrack(
                                result.trackName, result.artistName,
                                result.youtubeUrl, result.spotifyUrl, result.appleMusicUrl
                            )
                            if (result.artworkUrl.isNotBlank()) {
                                playbackService?.updateArtwork(result.artworkUrl)
                            }
                        }
                    }
                } else {
                    _playerState.update { it.copy(currentTrack = null) }
                    playbackService?.updateIdentifiedTrack("", "")
                    playbackService?.updateArtwork("")
                    _trackHistory.update { list -> if (list.firstOrNull()?.isLoading == true) list.drop(1) else list }
                }
                val intervalMs = (settings.value.shazamIntervalSeconds - 4).coerceAtLeast(10) * 1000L
                delay(intervalMs)
                if (isActive) _isShazamRunning.value = true
            }
        }
    }

    // --- Sleep timer ---

    fun toggleSleepTimer() {
        if (_sleepTimerActive.value) {
            sleepTimerJob?.cancel(); sleepTimerJob = null; _sleepTimerActive.value = false; _sleepTimerSecondsRemaining.value = 0
        } else {
            val totalSec = settings.value.sleepTimerMinutes * 60
            _sleepTimerActive.value = true; _sleepTimerSecondsRemaining.value = totalSec
            sleepTimerJob = viewModelScope.launch {
                val startMs = System.currentTimeMillis()
                while (isActive) {
                    val remaining = (totalSec - ((System.currentTimeMillis() - startMs) / 1000).toInt()).coerceAtLeast(0)
                    _sleepTimerSecondsRemaining.value = remaining
                    if (remaining <= 0) { _sleepTimerActive.value = false; playbackService?.pause(); return@launch }
                    delay(1000)
                }
            }
        }
    }

    // --- Utilities ---

    private suspend fun isReachable(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val parsed = java.net.URL(url)
            val port = if (parsed.port != -1) parsed.port else (if (parsed.protocol == "https") 443 else 80)
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(parsed.host, port), 1500) }
            true
        } catch (_: Exception) { false }
    }

    override fun onCleared() {
        rotationJob?.cancel(); shazamJob?.cancel(); sleepTimerJob?.cancel()
        disconnectPlayer()
        super.onCleared()
    }

    // --- ADB self-test ---
    // Trigger: adb shell am start -n tech.capullo.quantumcast/.MainActivity --es dbg snaptest
    // Monitors: adb logcat | grep -E "SnapTest|QCPlaybackService|SnapserverProcess|SnapclientProcess"
    fun startSnapTest() {
        Log.i("SnapTest", "=== SNAP TEST START ===")
        Log.i("SnapTest", "Mode: QUANTUMCAST | Station: SomaFM Groove Salad")
        _showDetailScreen.value = true
        // Call playStation directly with QUANTUMCAST - bypasses DataStore so mode is guaranteed
        playbackService?.playStation(
            url      = "https://ice2.somafm.com/groovesalad-256-mp3",
            title    = "SnapTest: Groove Salad",
            artist   = "SomaFM",
            uuid     = "snaptest-001",
            favicon  = "",
            broadcastMode = tech.capullo.quantumcast.data.settings.BroadcastMode.QUANTUMCAST,
        ) ?: Log.e("SnapTest", "FAIL: PlaybackService not bound yet - retry in 2s")
        Log.i("SnapTest", "=== SNAP TEST playStation dispatched ===")
    }

    companion object { private const val TAG = "RadioViewModel" }
}
