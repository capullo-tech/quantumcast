package tech.capullo.quantumcast.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.metadata.icy.IcyInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.capullo.quantumcast.MainActivity
import tech.capullo.quantumcast.data.settings.BroadcastMode
import tech.capullo.quantumcast.snapcast.SnapclientProcess
import tech.capullo.quantumcast.snapcast.SnapcontrolCallbacks
import tech.capullo.quantumcast.snapcast.SnapcontrolPlugin
import tech.capullo.quantumcast.snapcast.SnapserverProcess
import tech.capullo.quantumcast.snapcast.firstArtist
import java.io.FileOutputStream
import kotlin.random.Random

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : Service() {

    // --- State exposed to ViewModel ---

    data class PlaybackState(
        val stationName: String = "",
        val stationUrl: String = "",
        val stationFavicon: String = "",
        val stationCountry: String = "",
        val stationUuid: String = "",
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val bufferingPercent: Float = 0f,
        val icyTitle: String = "",
        val broadcastMode: BroadcastMode = BroadcastMode.QUANTUMCAST,
        val snapclientHost: String = "",
        val snapclientPort: Int = 1604,
        val snapclientChannel: String = "stereo",
        val snapclientState: tech.capullo.quantumcast.snapcast.SnapclientProcess.ConnectionState =
            tech.capullo.quantumcast.snapcast.SnapclientProcess.ConnectionState.STARTING,
        val snapcastGroups: List<tech.capullo.quantumcast.snapcast.Group> = emptyList(),
        val snapcastStreamArtUrl: String = "",
        val artworkUrl: String = "", // Shazam artwork; falls back to favicon
        val streamCanPlay: Boolean = false,
        val streamCanPause: Boolean = false,
        val streamCanGoNext: Boolean = false,
        val streamCanGoPrevious: Boolean = false,
        val snapserverHostname: String = "",
        val snapcastStationName: String = "",
        val snapcastTrackName: String = "",
        val snapcastArtistName: String = "",
        val snapcastCountry: String = "",
        val snapcastCountryCode: String = "",
        val snapcastCodec: String = "",
        val snapcastBitrate: Int = 0,
        val snapcastUrl: String = "",
        val snapcastYoutubeUrl: String = "",
        val snapcastSpotifyUrl: String = "",
        val snapcastAppleMusicUrl: String = "",
        val snapcastTags: String = "",
        val snapcastUuid: String = "",
        val snapclientDisplayName: String = "",
        val stationCountryCode: String = "",
        val stationCodec: String = "",
        val stationBitrate: Int = 0,
        val stationTags: String = "",
        val shazamTrackName: String = "",
        val shazamArtistName: String = "",
        val shazamYoutubeUrl: String = "",
        val shazamSpotifyUrl: String = "",
        val shazamAppleMusicUrl: String = "",
        val isStreamLocked: Boolean = false,
    )

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // --- IPC ---

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    // --- Playback engine (ExoPlayer → TeeAudioProcessor → FIFO) ---

    // ExoPlayer decodes the stream; a custom DefaultAudioSink chain (see
    // FifoAudioSink.kt) forces 44100:16:2 and tees the PCM into the Snapcast
    // FIFO. Player volume is 0 - local audio comes from the snapclient; the
    // tee sits before volume so the FIFO always gets full-scale PCM.
    private var exoPlayer: ExoPlayer? = null
    private var fifoSink: FifoAudioBufferSink? = null

    @Volatile private var intentionalStop = false

    // Playlist resolution (.pls/.m3u/.asx → first stream entry; VLC did this
    // internally, ExoPlayer has no extractor for plain playlists).
    private var resolveJob: Job? = null
    private var engineUrl = "" // URL actually handed to ExoPlayer (post-resolution)
    private var engineFifoPath = ""
    private var engineCachingMs = 1500

    @Volatile private var triedPlaylistFallback = false

    @Volatile private var customServerName: String = ""

    fun updateCustomServerName(name: String) {
        if (name.trim() == customServerName) return
        customServerName = name.trim()
        // Allow the local snapclient's channel tag (which embeds the name) to be
        // re-applied with the new name on the next GetStatus - covers the case
        // where the name resolves from DataStore after the client already connected.
        localChannelTagSet = false
        // Restart NSD advertisement with the new name if the server is already running
        snapserverNsd?.let {
            it.stop()
            snapserverNsd = tech.capullo.quantumcast.snapcast.SnapserverNsdRegistrar(this).also { r -> r.start(customServerName) }
        }
        // Rename the local Snapcast client if it is already connected
        val localId = snapclientProcess?.storedHostId?.takeIf { it.isNotEmpty() } ?: return
        val channel = _state.value.snapclientChannel
        val tag = when (channel) {
            "left" -> "[L]"
            "right" -> "[R]"
            else -> "[S]"
        }
        val newName = "${customServerName.ifBlank { Build.MODEL }} $tag"
        _state.update { state ->
            state.copy(
                snapcastGroups = state.snapcastGroups.map { group ->
                    group.copy(
                        clients = group.clients.map { c ->
                            if (c.id == localId || c.id.contains(localId) || localId.contains(c.id)) {
                                c.copy(config = c.config.copy(name = newName))
                            } else {
                                c
                            }
                        },
                    )
                },
            )
        }
        scope.launch {
            snapcastControl?.sendSetClientName(localId, newName)
            snapcastControl?.sendGetStatus()
        }
    }

    // Engine zombie-state watchdog: fires every 10 s while playing in QUANTUMCAST mode.
    // Detects the case where the player reports playing but the FIFO write is blocked
    // (FIFO full because Snapserver stalled reading) - the blocked tee stalls the playback
    // thread, no error event fires. If player position hasn't advanced in two consecutive
    // checks (~20 s), the pipeline is silently dead and we trigger a full restart.
    // Threshold can be widened if legitimate buffering pauses exceed 20 s on slow connections.
    private var engineWatchdogJob: Job? = null
    private var lastEnginePosMs = -1L

    // Held between engine start and first "playing" - Snapcast starts only once the
    // FIFO write end is open and PCM is about to flow (the sink opens O_RDWR eagerly
    // at engine start, so the read end never sees EOF).
    private var pendingSnapserver: SnapserverProcess? = null

    // --- Snapcast ---

    private var snapserverProcess: SnapserverProcess? = null
    private var snapclientProcess: SnapclientProcess? = null
    private var snapcontrolPlugin: SnapcontrolPlugin? = null
    private var snapserverNsd: tech.capullo.quantumcast.snapcast.SnapserverNsdRegistrar? = null
    private var snapcastControl: tech.capullo.quantumcast.snapcast.SnapcastControlClient? = null
    private var snapserverJob: Job? = null
    private var snapclientJob: Job? = null
    private var snapcastControlJob: Job? = null
    private var localChannelTagSet = false

    var onSkipNextRequested: (() -> Unit)? = null
    var onSkipPrevRequested: (() -> Unit)? = null

    // Whether broadcaster-side skipping is meaningful (rotation/queue active).
    // Set by RadioViewModel; pushed to Snapcast clients via canGoNext/canGoPrevious.
    @Volatile private var broadcastCanSkip = false
    fun updateBroadcastCanSkip(canSkip: Boolean) {
        if (broadcastCanSkip == canSkip) return
        broadcastCanSkip = canSkip
        snapcontrolPlugin?.notifyPropertiesChanged()
    }
    var onPlayPauseRequested: (() -> Unit)? = null
    var onStationError: (() -> Unit)? = null
    var onStationPlaying: (() -> Unit)? = null

    private var errorAudioJob: Job? = null

    private var bufferingTimeoutJob: Job? = null

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    // --- MediaSession ---

    private var mediaSession: MediaSessionCompat? = null

    // Art for the media notification / lock screen. System UI does not fetch
    // http(s) ART_URIs on its own, so the bitmap is loaded here (via Coil) and
    // attached to both the metadata and the notification large icon.
    private var sessionArtUrl: String? = null
    private var sessionArtBitmap: android.graphics.Bitmap? = null
    private var sessionArtJob: Job? = null

    // --- Audio focus ---
    // Focus affects ONLY the local snapclient (the audible part of this device).
    // The broadcast pipeline (ExoPlayer → FIFO → snapserver → remote clients) must
    // never react to focus changes: other rooms keep playing while e.g. a
    // YouTube video takes over this phone's speaker.
    private var focusRequest: AudioFocusRequest? = null
    private var focusPausedLocalClient = false
    private var focusResumeWatcher: Job? = null

    // Resume triggers, in order of reliability:
    // 1. App brought to foreground (onAppForeground) - always reclaims audio.
    // 2. AUDIOFOCUS_GAIN - delivered only after TRANSIENT losses (calls, nav).
    // 3. Quiet-watcher - after a PERMANENT loss Android never redistributes
    //    focus (no GAIN when Spotify/YouTube stop), so poll isMusicActive and
    //    resume once the other player has been silent for a few seconds.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (snapclientProcess != null) {
                    focusPausedLocalClient = true
                    stopLocalSnapclient()
                    startFocusResumeWatcher()
                    Log.d(TAG, "Audio focus lost (permanent) → local snapclient stopped, watcher armed")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // No watcher here: during a voice call isMusicActive is false
                // and the watcher would blast radio into the call. GAIN is
                // reliably delivered after transient losses.
                if (snapclientProcess != null) {
                    focusPausedLocalClient = true
                    stopLocalSnapclient()
                    Log.d(TAG, "Audio focus lost (transient) → local snapclient stopped")
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> resumeLocalAudioAfterFocusLoss("focus gain")
            // CAN_DUCK: keep playing at full volume - a native process can't be
            // ducked cheaply and radio over a brief beep is acceptable.
        }
    }

    // Called from the Activity (via ViewModel) whenever the app returns to the
    // foreground: the user is looking at QuantumCast, so local audio comes back
    // even if another app still holds focus - reclaiming focus pauses it.
    fun onAppForeground() {
        resumeLocalAudioAfterFocusLoss("app foreground")
    }

    private fun resumeLocalAudioAfterFocusLoss(reason: String) {
        if (!focusPausedLocalClient) return
        focusPausedLocalClient = false
        focusResumeWatcher?.cancel()
        focusResumeWatcher = null
        requestAudioFocus() // reclaim so the next loss is detected again
        startLocalSnapclient()
        Log.d(TAG, "Local snapclient restarted ($reason)")
    }

    private fun startFocusResumeWatcher() {
        focusResumeWatcher?.cancel()
        focusResumeWatcher = scope.launch {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            var quietSince = 0L
            while (isActive && focusPausedLocalClient) {
                delay(2_000)
                if (am.isMusicActive) {
                    quietSince = 0L
                } else {
                    val now = System.currentTimeMillis()
                    if (quietSince == 0L) {
                        quietSince = now
                    } else if (now - quietSince >= 3_000) {
                        withContext(Dispatchers.Main) { resumeLocalAudioAfterFocusLoss("other player quiet") }
                        break
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_SKIP_NEXT = "tech.capullo.quantumcast.SKIP_NEXT"
        const val ACTION_SKIP_PREV = "tech.capullo.quantumcast.SKIP_PREV"

        private const val CHANNEL_ID = "quantumcast_playback"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "QCPlaybackService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    // --- Public API called from ViewModel ---

    fun playStation(
        url: String,
        title: String,
        artist: String,
        uuid: String,
        favicon: String,
        countryCode: String = "",
        codec: String = "",
        bitrate: Int = 0,
        tags: String = "",
        @Suppress("UNUSED_PARAMETER") broadcastMode: BroadcastMode = BroadcastMode.QUANTUMCAST,
        vlcNetworkCachingMs: Int = 1500,
    ) {
        val snapcastAlive = snapserverProcess != null && snapserverJob?.isActive == true
        Log.d(TAG, "playStation: $title snapcastAlive=$snapcastAlive")
        _state.update {
            it.copy(
                stationName = title, stationUrl = url, stationFavicon = favicon,
                stationCountry = artist, stationUuid = uuid,
                stationCountryCode = countryCode, stationCodec = codec, stationBitrate = bitrate,
                stationTags = tags,
                isBuffering = true, broadcastMode = BroadcastMode.QUANTUMCAST,
                artworkUrl = "",
                snapclientHost = "",
                icyTitle = "",
                shazamTrackName = "", shazamArtistName = "",
                shazamYoutubeUrl = "", shazamSpotifyUrl = "", shazamAppleMusicUrl = "",
            )
        }
        stopEngine()

        if (snapcastAlive) {
            // Station change while Snapcast is already running - keep the entire Snapcast stack
            // alive. Only restart VLC into the same FIFO. Destroying SnapcontrolPlugin while
            // libsnapcontrol.so is still owned by the running Snapserver causes a native crash
            // (SIGPIPE on the dead controlscript pipe).
            startExoToFifo(url, snapserverProcess!!.pipeFilepath, vlcNetworkCachingMs)
            // Starting a station is an explicit "make sound" action - recover a
            // focus-paused local snapclient too (no-op when not focus-paused).
            resumeLocalAudioAfterFocusLoss("station play")
        } else {
            // First play or after a full stop - build the Snapcast stack from scratch.
            stopSnapcast()
            val snapserver = ensureSnapserver()
            pendingSnapserver = snapserver
            startExoToFifo(url, snapserver.pipeFilepath, vlcNetworkCachingMs)
        }

        updateNotification()
        updateMediaSession()
    }

    // play()/pause() are called from arbitrary threads (SnapcontrolPlugin's
    // control-socket reader dispatches web/remote commands on Dispatchers.IO).
    // ExoPlayer throws when accessed off its application thread - unlike VLC,
    // which was thread-safe - so marshal to main. The plugin's catch(Throwable)
    // used to swallow that exception silently (web pause/play did nothing).
    private fun runOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(block)
        }
    }

    fun play() = runOnMain {
        exoPlayer?.play()
        _state.update { it.copy(isPlaying = true) }
        // Explicit "make sound" action doubles as last-resort recovery of a
        // focus-paused local snapclient (no-op when not focus-paused).
        resumeLocalAudioAfterFocusLoss("user play")
        updateNotification()
        updateMediaSession()
    }

    fun pause() = runOnMain {
        exoPlayer?.pause()
        _state.update { it.copy(isPlaying = false) }
        updateNotification()
        updateMediaSession()
    }

    fun stop() {
        stopEngine()
        stopSnapcast()
        _state.update { PlaybackState() }
        updateNotification()
        updateMediaSession()
    }

    fun toggleStreamLock() {
        val locked = !_state.value.isStreamLocked
        _state.update { it.copy(isStreamLocked = locked) }
        snapcontrolPlugin?.isStreamLocked = locked
    }

    fun connectAsSnapclient(host: String, port: Int = 1604) {
        stopEngine()
        stopSnapcast()
        val sc = SnapclientProcess(this).also { snapclientProcess = it }
        _state.update {
            it.copy(
                broadcastMode = BroadcastMode.SNAPCLIENT,
                snapclientHost = host,
                snapclientPort = port,
                stationName = "",
                stationUrl = "",
                icyTitle = "",
                snapcastStreamArtUrl = "",
            )
        }
        scope.launch { sc.connectionState.collect { s -> _state.update { it.copy(snapclientState = s) } } }
        snapclientJob = scope.launch {
            sc.start(
                snapserverAddress = host,
                snapserverPort = port,
                audioChannel = _state.value.snapclientChannel,
            )
        }
        startSnapcastControl(host)
        requestAudioFocus()
        Log.d(TAG, "Snapclient → $host:$port")
    }

    private fun applyStreamProperties(props: tech.capullo.quantumcast.snapcast.StreamPlayerProperties) {
        val meta = props.metadata
        val title = meta?.title ?: ""
        val artist = meta?.firstArtist() ?: ""
        val stationName = meta?.station ?: ""
        val art = meta?.artUrl ?: ""
        val country = meta?.country ?: ""
        val countryCode = meta?.countryCode ?: ""
        val codec = meta?.codec ?: ""
        val bitrate = meta?.bitrate ?: 0
        val url = meta?.url ?: ""
        val youtubeUrl = meta?.youtubeUrl ?: ""
        val spotifyUrl = meta?.spotifyUrl ?: ""
        val appleMusicUrl = meta?.appleMusicUrl ?: ""
        val tags = meta?.tags ?: ""
        val uuid = meta?.uuid ?: ""
        Log.d(TAG, "applyStreamProperties: status=${props.playbackStatus} canNext=${props.canGoNext} title=$title artist=$artist station=$stationName")
        _state.update { st ->
            st.copy(
                icyTitle = if (title.isNotBlank()) title else st.icyTitle,
                snapcastTrackName = if (title.isNotBlank()) title else st.snapcastTrackName,
                snapcastArtistName = artist,
                snapcastStationName = if (stationName.isNotBlank()) stationName else st.snapcastStationName,
                snapcastStreamArtUrl = when {
                    art.isNotBlank() -> art // new art arrived
                    meta != null -> "" // metadata present but art cleared
                    else -> st.snapcastStreamArtUrl // no metadata (play/pause event)
                },
                snapcastCountry = if (country.isNotBlank()) country else st.snapcastCountry,
                snapcastCountryCode = if (countryCode.isNotBlank()) countryCode else st.snapcastCountryCode,
                snapcastCodec = if (codec.isNotBlank()) codec else st.snapcastCodec,
                snapcastBitrate = if (bitrate > 0) bitrate else st.snapcastBitrate,
                snapcastUrl = if (url.isNotBlank()) url else st.snapcastUrl,
                snapcastYoutubeUrl = if (youtubeUrl.isNotBlank()) youtubeUrl else st.snapcastYoutubeUrl,
                snapcastSpotifyUrl = if (spotifyUrl.isNotBlank()) spotifyUrl else st.snapcastSpotifyUrl,
                snapcastAppleMusicUrl = if (appleMusicUrl.isNotBlank()) appleMusicUrl else st.snapcastAppleMusicUrl,
                snapcastTags = if (tags.isNotBlank()) tags else st.snapcastTags,
                snapcastUuid = if (uuid.isNotBlank()) uuid else st.snapcastUuid,
                streamCanPlay = props.canPlay,
                streamCanPause = props.canPause,
                streamCanGoNext = props.canGoNext,
                streamCanGoPrevious = props.canGoPrevious,
                isPlaying = if (st.broadcastMode == BroadcastMode.SNAPCLIENT) {
                    props.playbackStatus == "playing"
                } else {
                    st.isPlaying
                },
            )
        }
        // Do NOT call notifyPropertiesChanged() here - echoes back in QUANTUMCAST mode
        updateMediaSession()
    }

    private fun maybeSetInitialChannelTag(groups: List<tech.capullo.quantumcast.snapcast.Group>) {
        if (localChannelTagSet) return
        val localId = snapclientProcess?.storedHostId?.takeIf { it.isNotEmpty() } ?: return
        val client = groups.flatMap { it.clients }
            .find { it.id == localId || it.id.contains(localId) } ?: return
        localChannelTagSet = true
        val channel = _state.value.snapclientChannel
        val tag = when (channel) {
            "left" -> "[L]"
            "right" -> "[R]"
            else -> "[S]"
        }
        val baseName = customServerName.ifBlank {
            client.config.name.replace(Regex("\\s*\\[[LRS]\\]$"), "").trim()
                .ifBlank { client.host.name.ifBlank { client.host.ip } }
        }
        Log.d(TAG, "Setting initial channel tag for ${client.id}: $baseName $tag")
        scope.launch {
            snapcastControl?.sendSetClientName(client.id, "$baseName $tag")
            snapcastControl?.sendGetStatus()
        }
    }

    private fun syncLocalChannelFromName(groups: List<tech.capullo.quantumcast.snapcast.Group>) {
        val localId = snapclientProcess?.storedHostId?.takeIf { it.isNotEmpty() } ?: return
        val client = groups.flatMap { it.clients }
            .find { it.id == localId || it.id.contains(localId) } ?: return
        val tagMatch = Regex("\\s*\\[([LRS])\\]$").find(client.config.name)?.groupValues?.get(1) ?: return
        val newChannel = when (tagMatch) {
            "L" -> "left"
            "R" -> "right"
            else -> "stereo"
        }
        if (newChannel != _state.value.snapclientChannel) {
            Log.d(TAG, "Remote channel change detected → $newChannel")
            _state.update { it.copy(snapclientChannel = newChannel) }
            snapclientProcess?.setChannel(newChannel)
        }
    }

    fun setSnapclientChannel(channel: String) {
        val localId = snapclientProcess?.storedHostId?.takeIf { it.isNotEmpty() }
        if (localId != null) {
            changeClientChannelInternal(localId, channel)
        } else {
            // Snapclient not yet connected - just update state and channel for when it does
            _state.update { it.copy(snapclientChannel = channel) }
        }
    }

    fun changeClientChannel(clientId: String, channel: String) {
        scope.launch { changeClientChannelInternal(clientId, channel) }
    }

    private fun changeClientChannelInternal(clientId: String, channel: String) {
        val tag = when (channel) {
            "left" -> "[L]"
            "right" -> "[R]"
            else -> "[S]"
        }
        val client = _state.value.snapcastGroups.flatMap { it.clients }
            .find { it.id == clientId || it.id.contains(clientId) || clientId.contains(it.id) }
        val baseName = client?.config?.name?.replace(Regex("\\s*\\[[LRS]\\]$"), "")?.trim()
            ?.ifBlank { client.host.name.ifBlank { client.host.ip } }
            ?: return
        val newName = "$baseName $tag"
        Log.d(TAG, "Updating channel tag for $clientId: $newName")

        // Optimistic update so badge refreshes immediately
        _state.update { state ->
            state.copy(
                snapcastGroups = state.snapcastGroups.map { group ->
                    group.copy(
                        clients = group.clients.map { c ->
                            if (c.id == clientId || c.id.contains(clientId) || clientId.contains(c.id)) {
                                c.copy(config = c.config.copy(name = newName))
                            } else {
                                c
                            }
                        },
                    )
                },
            )
        }

        scope.launch {
            snapcastControl?.sendSetClientName(clientId, newName)
            snapcastControl?.sendGetStatus()
        }

        // If this is our own local client, also switch the audio channel
        val localId = snapclientProcess?.storedHostId?.takeIf { it.isNotEmpty() }
        if (localId != null && (clientId == localId || clientId.contains(localId) || localId.contains(clientId))) {
            _state.update { it.copy(snapclientChannel = channel) }
            snapclientProcess?.setChannel(channel)
        }
    }

    fun disconnectSnapclient() {
        stopSnapcast()
        _state.update { it.copy(broadcastMode = BroadcastMode.QUANTUMCAST, snapclientHost = "") }
    }

    // Written next to the served index.html (snapserver doc_root); the web
    // player fetches webcfg.json on load. Missing file → web defaults apply.
    fun updateWebConfig(debug: Boolean, autoplay: Boolean) {
        scope.launch {
            try {
                val dir = java.io.File(filesDir, "webui").apply { mkdirs() }
                java.io.File(dir, "webcfg.json").writeText("""{"debug":$debug,"autoplay":$autoplay}""")
            } catch (e: Exception) {
                Log.w(TAG, "webcfg.json write failed: ${e.message}")
            }
        }
    }

    fun updateIcyTitle(title: String) {
        _state.update { it.copy(icyTitle = title) }
        snapcontrolPlugin?.notifyPropertiesChanged()
        updateMediaSession()
    }

    fun updateIdentifiedTrack(
        trackName: String,
        artistName: String,
        youtubeUrl: String = "",
        spotifyUrl: String = "",
        appleMusicUrl: String = "",
    ) {
        _state.update {
            it.copy(
                shazamTrackName = trackName,
                shazamArtistName = artistName,
                shazamYoutubeUrl = youtubeUrl,
                shazamSpotifyUrl = spotifyUrl,
                shazamAppleMusicUrl = appleMusicUrl,
            )
        }
        snapcontrolPlugin?.notifyPropertiesChanged()
        updateMediaSession()
    }

    fun updateArtwork(artUrl: String) {
        _state.update { it.copy(artworkUrl = artUrl) }
        snapcontrolPlugin?.notifyPropertiesChanged()
        updateMediaSession()
    }

    // --- ExoPlayer engine ---

    private fun startExoToFifo(url: String, fifoPath: String, cachingMs: Int = 1500) {
        engineFifoPath = fifoPath
        engineCachingMs = cachingMs
        triedPlaylistFallback = false
        if (PlaylistResolver.hasPlaylistExtension(url)) {
            // Resolve to the first stream entry before handing to ExoPlayer.
            // engineUrl marks this resolution as current; a station change
            // (stopEngine cancels resolveJob) or a newer start invalidates it.
            engineUrl = url
            startBufferingTimeout()
            resolveJob?.cancel()
            resolveJob = scope.launch {
                val r = PlaylistResolver.resolve(url)
                withContext(Dispatchers.Main) {
                    if (engineUrl != url || !isActive) return@withContext
                    if (r != null) {
                        Log.d(TAG, "Playlist resolved: $url → ${r.url}")
                    } else {
                        Log.w(TAG, "Playlist resolution failed, trying raw URL: $url")
                    }
                    startEngine(r?.url ?: url, r?.mimeType)
                }
            }
        } else {
            startEngine(url, null)
        }
    }

    private fun startEngine(url: String, mimeType: String? = null) {
        engineUrl = url
        // stopEngine() removes the listener before stop(), so the IDLE event that
        // would consume intentionalStop never arrives - reset it here instead.
        intentionalStop = false
        // Open the FIFO write end (O_RDWR) BEFORE anything else - parity with
        // VLC's sout, which held the write end open from sout start onward.
        val sink = FifoAudioBufferSink(engineFifoPath).also {
            fifoSink = it
            it.open()
        }

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(10_000)
            .setUserAgent("QuantumCast")
        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
            // Generous retries ≈ VLC's --http-reconnect for flaky radio streams
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */
                maxOf(engineCachingMs * 4, 15_000),
                /* maxBufferMs = */
                maxOf(engineCachingMs * 4, 50_000),
                /* bufferForPlaybackMs = */
                engineCachingMs.coerceIn(500, 10_000),
                /* bufferForPlaybackAfterRebufferMs = */
                (engineCachingMs * 2).coerceIn(1_000, 20_000),
            )
            .build()
        val player = ExoPlayer.Builder(this, FifoRenderersFactory(this, sink))
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { exoPlayer = it }
        player.volume = 0f // local audio comes from the snapclient; tee is pre-volume
        player.addListener(exoListener)
        // mimeType hint: set when resolution found an HLS manifest behind a
        // playlist-looking URL that lacks the .m3u8 extension the factory sniffs.
        val item = MediaItem.Builder().setUri(url)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
        startBufferingTimeout()
        Log.d(TAG, "ExoPlayer → tee→FIFO $engineFifoPath (caching=${engineCachingMs}ms)")
    }

    private val exoListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    if (exoPlayer?.playWhenReady == true) onEnginePlaying() else onEnginePaused()
                }
                Player.STATE_BUFFERING -> {
                    _state.update {
                        it.copy(
                            isBuffering = true,
                            bufferingPercent = (exoPlayer?.bufferedPercentage ?: 0).toFloat(),
                        )
                    }
                }
                Player.STATE_ENDED -> {
                    // Live radio should never end - treat like VLC's EndReached
                    val wasIntentional = intentionalStop
                    intentionalStop = false
                    stopEngineWatchdog()
                    _state.update { it.copy(isPlaying = false, isBuffering = false, bufferingPercent = 0f) }
                    updateNotification()
                    if (!wasIntentional) onStationError?.invoke()
                }
                Player.STATE_IDLE -> {
                    // After stop() (intentional) or error (handled in onPlayerError)
                    intentionalStop = false
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (exoPlayer?.playbackState == Player.STATE_READY) {
                if (playWhenReady) onEnginePlaying() else onEnginePaused()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            cancelBufferingTimeout()
            Log.e(TAG, "ExoPlayer error: ${error.errorCodeName}", error)
            if (maybeResolvePlaylistAndRetry(error)) return
            _state.update { it.copy(isPlaying = false, isBuffering = false, bufferingPercent = 0f, icyTitle = "") }
            onStationError?.invoke()
        }

        // ICY in-stream titles (StreamTitle) - VLC never surfaced these; Shazam
        // results still overwrite the field when a track is identified.
        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val entry = metadata.get(i)
                if (entry is IcyInfo) {
                    entry.title?.trim()?.takeIf { it.isNotEmpty() }?.let { updateIcyTitle(it) }
                }
            }
        }
    }

    // Some stations serve a .pls/.m3u body from an extension-less URL
    // (Content-Type audio/x-scpls etc.) - the proactive resolution in
    // startExoToFifo never triggers and ExoPlayer fails to parse. Sniff the
    // body once and retry with the first entry; also catches a nested
    // playlist behind an already-resolved URL. One attempt per station start
    // (triedPlaylistFallback), so a genuine bad stream still error-skips.
    private fun maybeResolvePlaylistAndRetry(error: PlaybackException): Boolean {
        val parseError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        if (!parseError || triedPlaylistFallback) return false
        triedPlaylistFallback = true
        val url = engineUrl
        Log.d(TAG, "Parse error - sniffing for a playlist body: $url")
        _state.update { it.copy(isBuffering = true) }
        startBufferingTimeout()
        resolveJob?.cancel()
        resolveJob = scope.launch {
            val r = PlaylistResolver.resolve(url)
            withContext(Dispatchers.Main) {
                if (engineUrl != url || !isActive) return@withContext
                if (r == null || (r.url == url && r.mimeType == null)) {
                    cancelBufferingTimeout()
                    _state.update { it.copy(isPlaying = false, isBuffering = false, bufferingPercent = 0f, icyTitle = "") }
                    onStationError?.invoke()
                    return@withContext
                }
                Log.d(TAG, "Playlist sniffed after parse error: $url → ${r.url}")
                releasePlayer()
                startEngine(r.url, r.mimeType)
            }
        }
        return true
    }

    // Tear down the player + sink WITHOUT touching pendingSnapserver or the
    // watchdog/timeout bookkeeping - used for the in-place playlist retry,
    // where the Snapcast stack must stay queued for onEnginePlaying().
    private fun releasePlayer() {
        exoPlayer?.let { p ->
            p.removeListener(exoListener)
            p.release()
        }
        exoPlayer = null
        fifoSink?.close()
        fifoSink = null
    }

    private fun onEnginePlaying() {
        cancelBufferingTimeout()
        stopErrorAudio()
        onStationPlaying?.invoke()
        startEngineWatchdog()
        _state.update { it.copy(isPlaying = true, isBuffering = false, bufferingPercent = 100f) }
        // Only NOW start feeding the FIFO (VLC parity: sout wrote only after
        // Event.Playing). Writing during preroll deadlocks: the 64KB pipe has
        // no reader yet and a blocked tee stalls READY forever.
        fifoSink?.enableWrites()
        pendingSnapserver?.let { snapserver ->
            pendingSnapserver = null
            Log.d(TAG, "Engine playing: starting Snapcast (FIFO writes enabled)")
            startSnapcast(snapserver, snapserverAddress = "localhost")
        }
        snapcontrolPlugin?.notifyPropertiesChanged()
        updateNotification()
        updateMediaSession()
    }

    private fun onEnginePaused() {
        _state.update { it.copy(isPlaying = false) }
        snapcontrolPlugin?.notifyPropertiesChanged()
        updateNotification()
        updateMediaSession()
    }

    private fun startBufferingTimeout(timeoutMs: Long = 15_000L) {
        bufferingTimeoutJob?.cancel()
        bufferingTimeoutJob = scope.launch {
            delay(timeoutMs)
            Log.w(TAG, "Buffering timeout - station unreachable")
            _state.update { it.copy(isPlaying = false, isBuffering = false, bufferingPercent = 0f, icyTitle = "") }
            onStationError?.invoke()
        }
    }

    private fun cancelBufferingTimeout() {
        bufferingTimeoutJob?.cancel()
        bufferingTimeoutJob = null
    }

    private fun startEngineWatchdog() {
        engineWatchdogJob?.cancel()
        lastEnginePosMs = -1L
        engineWatchdogJob = scope.launch {
            delay(20_000)
            while (isActive) {
                if (_state.value.isPlaying && _state.value.broadcastMode == BroadcastMode.QUANTUMCAST) {
                    // ExoPlayer must be accessed from its application thread
                    val t = withContext(Dispatchers.Main) { exoPlayer?.currentPosition ?: -1L }
                    if (t > 0 && t == lastEnginePosMs) {
                        Log.w(TAG, "Engine watchdog: position stuck at ${t}ms - FIFO write likely blocked, restarting")
                        onStationError?.invoke()
                        return@launch
                    }
                    if (t > 0) lastEnginePosMs = t
                }
                delay(10_000)
            }
        }
    }

    private fun stopEngineWatchdog() {
        engineWatchdogJob?.cancel()
        engineWatchdogJob = null
        lastEnginePosMs = -1L
    }

    fun startErrorAudio(durationMs: Long) {
        val fifo = snapserverProcess?.pipeFilepath ?: return
        errorAudioJob?.cancel()
        errorAudioJob = scope.launch(Dispatchers.IO) {
            val chunkSamples = 4410 // 100ms at 44100Hz
            val buf = ByteArray(chunkSamples * 4)
            try {
                FileOutputStream(fifo, true).use { out ->
                    var elapsed = 0L
                    while (elapsed < durationMs && isActive) {
                        for (i in 0 until chunkSamples) {
                            val s = Random.nextInt(-3276, 3276).toShort() // ~10% amplitude
                            val b = i * 4
                            buf[b] = s.toByte()
                            buf[b + 1] = (s.toInt() shr 8).toByte()
                            buf[b + 2] = s.toByte()
                            buf[b + 3] = (s.toInt() shr 8).toByte()
                        }
                        out.write(buf)
                        elapsed += 100
                        delay(100)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun stopErrorAudio() {
        errorAudioJob?.cancel()
        errorAudioJob = null
    }

    private fun stopEngine() {
        intentionalStop = true
        stopEngineWatchdog()
        stopErrorAudio()
        cancelBufferingTimeout()
        resolveJob?.cancel()
        resolveJob = null
        engineUrl = "" // invalidates any resolve completion that raced the cancel
        pendingSnapserver = null
        exoPlayer?.let { p ->
            p.removeListener(exoListener)
            p.stop()
            p.release()
        }
        exoPlayer = null
        fifoSink?.close()
        fifoSink = null
    }

    // --- Snapcast ---

    private fun ensureSnapserver(): SnapserverProcess = snapserverProcess ?: SnapserverProcess(this).also { snapserverProcess = it }

    private fun startSnapcast(snapserver: SnapserverProcess, snapserverAddress: String) {
        val sc = SnapclientProcess(this).also { snapclientProcess = it }
        snapserverJob = scope.launch { snapserver.start() }
        snapclientJob = scope.launch { sc.start(snapserverAddress) }

        snapcontrolPlugin = SnapcontrolPlugin(
            callbacks = object : SnapcontrolCallbacks {
                override val isPlaying get() = _state.value.isPlaying
                override val isPaused get() = !_state.value.isPlaying && _state.value.stationUrl.isNotEmpty()
                override val canSkip get() = broadcastCanSkip
                override val currentTitle get() = _state.value.shazamTrackName
                    .ifEmpty { _state.value.icyTitle.ifEmpty { _state.value.stationName } }
                override val currentArtist get() = _state.value.shazamArtistName
                override val currentAlbum get() = _state.value.stationName
                override val currentUrl get() = _state.value.stationUrl
                override val currentCountry get() = _state.value.stationCountry
                override val currentCountryCode get() = _state.value.stationCountryCode
                override val currentCodec get() = _state.value.stationCodec
                override val currentBitrate get() = _state.value.stationBitrate
                override val currentYoutubeUrl get() = _state.value.shazamYoutubeUrl
                override val currentSpotifyUrl get() = _state.value.shazamSpotifyUrl
                override val currentAppleMusicUrl get() = _state.value.shazamAppleMusicUrl
                override val currentTags get() = _state.value.stationTags
                override val currentUuid get() = _state.value.stationUuid
                override val currentArtworkUrl get() = _state.value.artworkUrl.ifEmpty { _state.value.stationFavicon }
                override val currentArtworkFile: String? get() = null
                override fun onPlay() {
                    play()
                }
                override fun onPause() {
                    pause()
                }
                override fun onSkipNext() {
                    onSkipNextRequested?.invoke()
                }
                override fun onSkipPrev() {
                    onSkipPrevRequested?.invoke()
                }
            },
            parentJob = serviceJob,
        ).also { it.start() }

        // Register Snapserver via NSD so other devices can discover us
        snapserverNsd = tech.capullo.quantumcast.snapcast.SnapserverNsdRegistrar(this).also { it.start(customServerName) }

        // Connect to our own Snapserver control socket to track connected clients
        startSnapcastControl("localhost")

        // Local snapclient is the audible part - join the focus arbitration for it
        requestAudioFocus()
    }

    private fun startSnapcastControl(host: String) {
        snapcastControlJob?.cancel()
        val client = tech.capullo.quantumcast.snapcast.SnapcastControlClient(host)
            .also { snapcastControl = it }
        snapcastControlJob = scope.launch {
            client.initialize()
            client.notifications.collect { notif ->
                when (notif) {
                    is tech.capullo.quantumcast.snapcast.ServerGetStatusResponse -> {
                        val groups = notif.result.server.groups
                        val hostname = notif.result.server.server.host.name
                        val displayName = if (_state.value.broadcastMode == BroadcastMode.SNAPCLIENT) {
                            val serverHost = notif.result.server.server.host.name
                            groups.flatMap { it.clients }
                                .find { it.host.name == serverHost }
                                ?.config?.name
                                ?.replace(Regex("\\s*\\[[LRS]\\]$"), "")?.trim()
                                ?.ifBlank { serverHost } ?: serverHost
                        } else {
                            ""
                        }
                        _state.update {
                            it.copy(
                                snapcastGroups = groups,
                                snapserverHostname = hostname.ifBlank { it.snapserverHostname },
                                snapclientDisplayName = displayName.ifBlank { it.snapclientDisplayName },
                            )
                        }
                        mergeGroupsIfNeeded(groups)
                        maybeSetInitialChannelTag(groups)
                        syncLocalChannelFromName(groups)
                        // Read current stream state on connect (so NowPlaying shows immediately)
                        val activeStreamId = groups.firstOrNull()?.streamId
                        notif.result.server.streams
                            .find { it.id == activeStreamId }?.properties
                            ?.let { sp ->
                                applyStreamProperties(
                                    tech.capullo.quantumcast.snapcast.StreamPlayerProperties(
                                        playbackStatus = sp.playbackStatus ?: "",
                                        canPlay = sp.canPlay,
                                        canPause = sp.canPause,
                                        canGoNext = sp.canGoNext,
                                        canGoPrevious = sp.canGoPrevious,
                                        canControl = sp.canControl,
                                        metadata = sp.metadata,
                                    ),
                                )
                            }
                    }
                    is tech.capullo.quantumcast.snapcast.ServerOnUpdate -> {
                        val groups = notif.params.server.groups
                        val displayName = if (_state.value.broadcastMode == BroadcastMode.SNAPCLIENT) {
                            val serverHost = notif.params.server.server.host.name
                            groups.flatMap { it.clients }
                                .find { it.host.name == serverHost }
                                ?.config?.name
                                ?.replace(Regex("\\s*\\[[LRS]\\]$"), "")?.trim()
                                ?.ifBlank { serverHost } ?: serverHost
                        } else {
                            ""
                        }
                        _state.update {
                            it.copy(
                                snapcastGroups = groups,
                                snapclientDisplayName = displayName.ifBlank { it.snapclientDisplayName },
                            )
                        }
                        mergeGroupsIfNeeded(groups)
                        maybeSetInitialChannelTag(groups)
                        syncLocalChannelFromName(groups)
                    }
                    is tech.capullo.quantumcast.snapcast.StreamOnProperties -> {
                        val props = notif.params.properties
                        applyStreamProperties(props)
                    }
                    is tech.capullo.quantumcast.snapcast.ClientOnVolumeChanged -> {
                        Log.d(TAG, "ClientOnVolumeChanged: client=${notif.params.clientId} volume=${notif.params.volume}")
                        _state.update { state ->
                            state.copy(
                                snapcastGroups = state.snapcastGroups.map { group ->
                                    group.copy(
                                        clients = group.clients.map { client ->
                                            if (client.id == notif.params.clientId) {
                                                client.copy(config = client.config.copy(volume = notif.params.volume))
                                            } else {
                                                client
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }
                    is tech.capullo.quantumcast.snapcast.ClientOnLatencyChanged -> {
                        _state.update { state ->
                            state.copy(
                                snapcastGroups = state.snapcastGroups.map { group ->
                                    group.copy(
                                        clients = group.clients.map { client ->
                                            if (client.id == notif.params.clientId) {
                                                client.copy(config = client.config.copy(latency = notif.params.latency))
                                            } else {
                                                client
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }
                    is tech.capullo.quantumcast.snapcast.ClientOnConnect,
                    is tech.capullo.quantumcast.snapcast.ClientOnDisconnect,
                    // Renames carry channel tags ([L]/[R]/[S]) set by web clients; refresh so
                    // syncLocalChannelFromName picks them up - without this, channel changes
                    // made from the web UI are never applied by the app.
                    is tech.capullo.quantumcast.snapcast.ClientOnNameChanged,
                    -> {
                        scope.launch { snapcastControl?.sendGetStatus() }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun mergeGroupsIfNeeded(groups: List<tech.capullo.quantumcast.snapcast.Group>) {
        // NOTE: do NOT delete disconnected clients here. Web players (and phones)
        // briefly disconnect when backgrounded, then reconnect with the same
        // persistent ID. Deleting on disconnect wiped their stored name (→ card
        // fell back to the raw IP) and latency calibration. The UI only renders
        // connected clients, so stale entries are harmless and get reused.
        //
        // Merge ONLY when CONNECTED clients are split across multiple groups.
        // Keying off groups.size would loop forever now that disconnected clients
        // (in their own leftover groups) keep groups.size > 1: setClients →
        // ServerOnUpdate → setClients → … a storm that floods the UI and drowns
        // volume/channel updates. Groups holding only disconnected clients are
        // ignored, so once all connected clients share a group we stop.
        val groupsWithConnected = groups.filter { g -> g.clients.any { it.connected } }
        if (groupsWithConnected.size <= 1) return
        val targetGroupId = groupsWithConnected.first().id
        val connectedIds = groups.flatMap { it.clients }.filter { it.connected }.map { it.id }
        scope.launch { snapcastControl?.sendGroupSetClients(targetGroupId, connectedIds) }
        Log.d(TAG, "Merging ${groupsWithConnected.size} groups with connected clients → $targetGroupId (${connectedIds.size} clients)")
    }

    suspend fun adjustClientVolume(clientId: String, percent: Int, muted: Boolean) {
        Log.d(TAG, "adjustClientVolume: client=$clientId percent=$percent muted=$muted")
        // Snapcast sends a Response (not a notification) back to the sender of Client.SetVolume,
        // and we don't parse arbitrary responses. Apply the change optimistically so the local
        // card updates immediately, same as every other client does via ClientOnVolumeChanged.
        _state.update { state ->
            state.copy(
                snapcastGroups = state.snapcastGroups.map { group ->
                    group.copy(
                        clients = group.clients.map { client ->
                            if (client.id == clientId) {
                                client.copy(
                                    config = client.config.copy(
                                        volume = tech.capullo.quantumcast.snapcast.Volume(muted, percent),
                                    ),
                                )
                            } else {
                                client
                            }
                        },
                    )
                },
            )
        }
        snapcastControl?.sendSetVolume(clientId, muted, percent)
    }

    suspend fun sendPlayerControl(streamId: String, command: String) {
        Log.d(TAG, "sendPlayerControl: stream=$streamId command=$command control=${snapcastControl != null}")
        snapcastControl?.sendStreamControl(streamId, command)
    }

    suspend fun adjustClientLatency(clientId: String, latencyMs: Int) {
        snapcastControl?.sendSetLatency(clientId, latencyMs)
    }

    private fun stopSnapcast() {
        abandonAudioFocus()
        snapcontrolPlugin?.stop()
        snapcontrolPlugin = null
        snapserverNsd?.stop()
        snapserverNsd = null
        snapcastControlJob?.cancel()
        snapcastControlJob = null
        snapcastControl?.client?.close()
        snapcastControl = null
        snapserverJob?.cancel()
        snapserverJob = null
        stopLocalSnapclient()
        snapserverProcess = null
        localChannelTagSet = false
    }

    // --- Audio focus helpers (local snapclient only - see focusListener) ---

    private fun requestAudioFocus() {
        // Re-requesting with the same AudioFocusRequest after a loss is valid -
        // no early return: after AUDIOFOCUS_LOSS our request is inactive and
        // must be submitted again to reclaim focus.
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
            .build()
            .also { focusRequest = it }
        // A FAILED result must not silence this device - keep playing regardless.
        (getSystemService(AUDIO_SERVICE) as AudioManager).requestAudioFocus(req)
    }

    private fun abandonAudioFocus() {
        focusResumeWatcher?.cancel()
        focusResumeWatcher = null
        focusRequest?.let { (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        focusRequest = null
        focusPausedLocalClient = false
    }

    private fun stopLocalSnapclient() {
        snapclientJob?.cancel()
        snapclientJob = null
        snapclientProcess?.destroy()
        snapclientProcess = null
    }

    // Restart after focus regain, with the same target/channel the session used.
    private fun startLocalSnapclient() {
        if (snapclientProcess != null) return
        val st = _state.value
        val (host, port) = if (st.broadcastMode == BroadcastMode.SNAPCLIENT) {
            if (st.snapclientHost.isEmpty()) return
            st.snapclientHost to st.snapclientPort
        } else {
            if (snapserverProcess == null) return // broadcast ended meanwhile
            "localhost" to 1604
        }
        val sc = SnapclientProcess(this).also { snapclientProcess = it }
        if (st.broadcastMode == BroadcastMode.SNAPCLIENT) {
            scope.launch { sc.connectionState.collect { s -> _state.update { it.copy(snapclientState = s) } } }
        }
        snapclientJob = scope.launch {
            sc.start(host, port, audioChannel = st.snapclientChannel)
        }
    }

    // --- Notification / MediaSession ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "QuantumCast Playback", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "QuantumCast").apply {
            // Without a callback the lockscreen/notification transport buttons -
            // even though PlaybackState advertises the actions - route nowhere
            // and do nothing. Wire them to the same play/pause/skip used in-app.
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    play()
                }
                override fun onPause() {
                    pause()
                }
                override fun onStop() {
                    pause()
                }
                override fun onSkipToNext() {
                    onSkipNextRequested?.invoke()
                }
                override fun onSkipToPrevious() {
                    onSkipPrevRequested?.invoke()
                }
            })
            isActive = true
        }
    }

    private fun buildNotification(): Notification {
        val st = _state.value
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(st.stationName.ifEmpty { "QuantumCast" })
            .setContentText(
                st.icyTitle.ifEmpty {
                    when (st.broadcastMode) {
                        BroadcastMode.QUANTUMCAST -> "Broadcasting"
                        BroadcastMode.SNAPCLIENT -> "Listening in"
                    }
                },
            )
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(tap)
            .setOngoing(st.isPlaying)
            .setSilent(true)

        sessionArtBitmap?.let { builder.setLargeIcon(it) }

        mediaSession?.sessionToken?.let {
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(it)
                    .setShowActionsInCompactView(),
            )
        }
        return builder.build()
    }

    private fun startForegroundNotification() {
        val notif = buildNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun updateMediaSession() {
        val st = _state.value
        refreshSessionArt(st)
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(
                    if (st.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build(),
        )
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, st.icyTitle.ifEmpty { st.stationName })
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, st.stationCountry)
                .apply {
                    sessionArtUrl?.takeIf { it.isNotBlank() }?.let {
                        putString(MediaMetadataCompat.METADATA_KEY_ART_URI, it)
                    }
                    sessionArtBitmap?.let {
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                    }
                }
                .build(),
        )
    }

    // Shazam art wins, then stream art (snapclient mode), then station favicon.
    // Loads asynchronously; re-posts metadata + notification once the bitmap is in.
    private fun refreshSessionArt(st: PlaybackState) {
        val url = when {
            st.artworkUrl.isNotBlank() -> st.artworkUrl
            st.snapcastStreamArtUrl.isNotBlank() -> st.snapcastStreamArtUrl
            else -> st.stationFavicon
        }
        if (url == sessionArtUrl) return
        sessionArtUrl = url
        sessionArtJob?.cancel()
        val hadArt = sessionArtBitmap != null
        sessionArtBitmap = null
        if (url.isBlank()) {
            if (hadArt) updateNotification()
            return
        }
        sessionArtJob = scope.launch {
            val result = runCatching {
                coil.Coil.imageLoader(this@PlaybackService).execute(
                    coil.request.ImageRequest.Builder(this@PlaybackService)
                        .data(url)
                        .allowHardware(false)
                        .size(512)
                        .build(),
                )
            }.getOrNull()
            val bmp = (
                (result as? coil.request.SuccessResult)?.drawable
                    as? android.graphics.drawable.BitmapDrawable
                )?.bitmap
            if (bmp != null) {
                withContext(Dispatchers.Main) {
                    if (url == sessionArtUrl) {
                        sessionArtBitmap = bmp
                        updateNotification()
                        updateMediaSession()
                    }
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stop()
        mediaSession?.release()
        mediaSession = null
        serviceJob.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
