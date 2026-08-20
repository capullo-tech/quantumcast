package tech.capullo.quantumcast.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.metadata.icy.IcyInfo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.capullo.audio.contracts.NowPlaying
import tech.capullo.audio.contracts.PlaybackController
import tech.capullo.audio.player.AudioFocusController
import tech.capullo.audio.player.BalanceAudioProcessor
import tech.capullo.audio.player.FifoAudioBufferSink
import tech.capullo.audio.snapcast.SnapclientProcess
import tech.capullo.audio.snapcast.SnapcontrolPlugin
import tech.capullo.audio.snapcast.SnapserverPorts
import tech.capullo.audio.snapcast.SnapserverProcess
import tech.capullo.audio.snapcast.firstArtist
import tech.capullo.quantumcast.MainActivity
import tech.capullo.quantumcast.data.settings.BroadcastMode
import tech.capullo.quantumcast.data.settings.SettingsRepository
import tech.capullo.source.radiobrowser.resolver.PlaylistResolver
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.random.Random

@AndroidEntryPoint
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : Service() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

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
        /** This broadcaster's resolved HTTP (web player + control) port - for the web/QR URL. */
        val broadcastHttpPort: Int = 1680,
        val snapclientChannel: String = "stereo",
        val snapclientState: tech.capullo.audio.snapcast.SnapclientProcess.ConnectionState =
            tech.capullo.audio.snapcast.SnapclientProcess.ConnectionState.STARTING,
        val snapcastGroups: List<tech.capullo.audio.snapcast.Group> = emptyList(),
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

    // Stereo balance applied to the broadcast mix (before the FIFO tee, so every listener -
    // local snapclient, LAN clients, web players - hears the same image). Persistent across
    // station changes; the per-station renderers factory references this same instance, and
    // an onCreate observer keeps its @Volatile balance in sync with Settings live.
    private val balanceProcessor = BalanceAudioProcessor()

    @Volatile private var triedPlaylistFallback = false

    @Volatile private var customServerName: String = ""

    // Snapserver base port (0 = OS-assigned/random). Cached from Settings so ensureSnapserver
    // can read it synchronously; applied on the next broadcast start.
    @Volatile private var snapserverFixedPort: Int = 0

    // --- Acoustic sync calibration (mic vs broadcast-PCM cross-correlation) ---

    /**
     * The calibration, assembled once instead of at every call site.
     *
     * Everything here that looks app-specific is app-specific: where the journal files live, how a
     * boost lease is published, and which clients this service currently sees. The rest — building
     * the calibrator, ordering the client list, focus suppression, state mirroring, undo, crash
     * recovery — is identical in every app on the platform and lives in the library.
     */
    private val calHost by lazy {
        tech.capullo.audio.calibration.CalibrationHost(
            context = this,
            control = { snapcastControl },
            connectedClients = {
                _state.value.snapcastGroups.flatMap { it.clients }.filter { it.connected }.map {
                    tech.capullo.audio.calibration.SyncCalibrator.CalClient(
                        id = it.id,
                        name = it.config.name,
                        latencyMs = it.config.latency,
                        volumePercent = it.config.volume.percent,
                        muted = it.config.volume.muted,
                    )
                }
            },
            // ALL clients, connected or not — see the parameter's own note. Matches what this
            // service passed before the extraction.
            clientLatencies = {
                _state.value.snapcastGroups.flatMap { it.clients }
                    .associate { it.id to it.config.latency }
            },
            localClientId = { snapclientProcess?.storedHostId.orEmpty() },
            reference = { ring -> armReference(ring, "calibrate") },
            publishOsBoost = { targets, leaseMs -> publishCalBoost(targets, leaseMs) },
            // ColorOS signals a focus loss when this app's own recorder opens, which would stop the
            // local snapclient — the reference speaker — mid measurement.
            suppressAudioFocusLosses = { audioFocus.suppressLosses = it },
            nowPlaying = {
                val st = _state.value
                "${st.stationName} | ${st.icyTitle.ifEmpty { st.snapcastTrackName }}"
            },
            refreshStatus = { snapcastControl?.sendGetStatus() },
            journal = calibrationJournal,
            history = calibrationHistory,
            volumeUndo = calibrationVolumeUndo,
        )
    }

    /** The running calibration, straight from the host. Refusals decided here are published on the
     *  same flow via [CalibrationHost.refuse], so the UI has one source of truth. */
    val calibrationState: StateFlow<tech.capullo.audio.calibration.SyncCalibrator.State>
        get() = calHost.state

    /** Mutex for the DIAGNOSTICS (micz, pcmdump, levelsweep). The calibration keeps its own job
     *  inside the host; [calibrationBusy] checks both. */
    private var calibrationJob: Job? = null

    // Armed ring for the running calibration. Held here (not by the calibrator) so an
    // engine restart mid-run re-arms the NEW sink in startEngine and the tap survives.
    @Volatile private var calibrationTap: tech.capullo.audio.calibration.ReferencePcmRing? = null

    // Crash journal for calibration: restored on control connect (undoes a killed run).
    private val calibrationJournal by lazy { FileCalibrationJournal(this) }

    // Append-only log of verified corrections (data for a future per-sink damping policy).
    private val calibrationHistory by lazy { FileCalibrationHistory(this) }

    // Pre-balance volumes, so the balance's persistent writes are one action away from reverted.
    private val calibrationVolumeUndo by lazy { FileVolumeUndo(this) }

    /** This device's OS media-volume boost, the knob with real headroom when a client is too quiet
     *  to attribute (the snapclient SW gain is already 100% by default). Leased: the calibrating
     *  server refreshes it while it works, and it restores itself if that stops — see
     *  [tech.capullo.audio.calibration.OsVolumeBoost] and SPEC-os-volume-boost.md. */
    private val osVolumeBoost by lazy {
        tech.capullo.audio.calibration.OsVolumeBoost(AndroidOsVolume(this), FileOsVolumeJournal(this))
    }
    private var calBoostTicker: Job? = null

    /** The boost lease this device is currently broadcasting as the calibrating server, or null.
     *  Rides [buildSnapNowPlaying]'s extras out to every client. */
    @Volatile private var calBoostLease: String? = null

    /** Requires RECORD_AUDIO already granted (Settings UI requests it before calling). */
    fun startSyncCalibration() {
        // BUSY covers the diagnostics too. micz, pcmdump and levelsweep share calibrationJob as a
        // mutex, and the host keeps its own job, so checking only one of the two would let a
        // diagnostic and a calibration record the room at the same time — two recorders, one of
        // them writing latencies underneath the other.
        if (calibrationBusy()) {
            Log.w("SyncCalibrator", "calibrate ignored: a run is already in progress")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return calHost.refuse("microphone permission not granted")
        }
        // WHICH ROLE THIS DEVICE IS IN is the app's knowledge, so the check stays here even though
        // the run itself has moved to the library. A broadcaster mirrors its FIFO buffers; a client
        // starts a silent second snapclient; anything else has no reference PCM and cannot measure.
        if (fifoSink == null) {
            val st = _state.value
            if (st.broadcastMode != BroadcastMode.SNAPCLIENT) {
                return calHost.refuse(
                    "neither broadcasting nor connected as a client — nothing to calibrate",
                )
            }
            if (st.snapclientHost.isEmpty()) return calHost.refuse("snapclient host unknown")
        }
        calHost.start(scope)
    }

    /** True while a calibration OR a diagnostic is recording. */
    private fun calibrationBusy() = calibrationJob?.isActive == true || calHost.isRunning

    /**
     * Debug rig tool (non-mutating): one mic capture, correlate against the live broadcast
     * reference, and log every peak's lag/z plus the capture's overall level in dBFS. No
     * SetLatency/SetVolume — so a volume sweep can read z-vs-volume AND level-vs-volume without
     * perturbing sync. Intended use: mute all but one distant speaker, set its volume to each
     * step, fire this, read the one `micz:` logcat line. Answers the kill-test (does boosting
     * a quiet speaker move its z across the z=9 attribution floor) and the leveling question
     * (does level-at-mic respond predictably to SetVolume).
     */

    /**
     * Arm a reference PCM source for [ring] and return the disarm action, or null when this device
     * has no way to obtain one.
     *
     * The two sources are the two roles: a BROADCASTER mirrors the buffers already going into the
     * snapserver FIFO, a CLIENT starts a silent second snapclient (`--player file:`) because its
     * own snapclient hands PCM straight to oboe with nothing to tap. Both end up feeding the same
     * ring, which is why every caller can be indifferent to which one it got.
     *
     * Extracted because the three diagnostics (micz, pcmdump, levelsweep) each opened with
     * `fifoSink ?: return`, which is the question "am I the broadcaster?" wearing the clothes of
     * "can I measure?". They kept answering no on a client long after the calibration itself had
     * stopped caring, so a client could run the feature but not the tools used to debug it.
     */
    private fun armReference(
        ring: tech.capullo.audio.calibration.ReferencePcmRing,
        tag: String,
    ): (() -> Unit)? {
        fifoSink?.let { sink ->
            calibrationTap = ring
            sink.pcmTap = ring
            return {
                sink.pcmTap = null
                calibrationTap = null
            }
        }
        val st = _state.value
        if (st.broadcastMode != BroadcastMode.SNAPCLIENT || st.snapclientHost.isEmpty()) {
            Log.w(
                "SyncCalibrator",
                "$tag: no reference source — neither broadcasting nor connected as a client",
            )
            return null
        }
        val tap = tech.capullo.audio.snapcast.ReferenceTapProcess(this)
        val job = scope.launch { tap.start(st.snapclientHost, st.snapclientPort, ring) }
        calibrationTap = ring
        return {
            job.cancel()
            tap.stop()
            calibrationTap = null
        }
    }

    fun measureOnce() {
        if (calibrationJob?.isActive == true) return
        calibrationJob = scope.launch {
            val ring = tech.capullo.audio.calibration.ReferencePcmRing()
            val mic = tech.capullo.audio.calibration.MicCapture(this@PlaybackService)
            val disarm = armReference(ring, "micz") ?: return@launch
            audioFocus.suppressLosses = true
            try {
                delay(16_000L) // prime the ring past one full capture before measuring
                val cap = mic.record(12_000) ?: run {
                    Log.w("SyncCalibrator", "micz: capture failed")
                    return@launch
                }
                val snap = ring.snapshot()
                val peaks = tech.capullo.audio.calibration.DelayMeasurement
                    .estimateSpeakerDelays(snap, cap, 16)
                fun rmsDb(x: FloatArray): Double {
                    var s = 0.0
                    for (v in x) s += v.toDouble() * v
                    return 20.0 * kotlin.math.log10(kotlin.math.sqrt(s / x.size.coerceAtLeast(1)) + 1e-12)
                }
                // The REFERENCE level as well as the mic's. A volume sweep compares captures taken
                // minutes apart, and the program material moves the mic RMS by more than the gain
                // step does — a quiet passage is indistinguishable from an attenuated speaker.
                // mic−ref divides the program out, and it is the only one of the three figures that
                // can legitimately be compared across captures.
                val micDb = rmsDb(cap.pcm)
                val refDb = rmsDb(snap.pcm)
                Log.i(
                    "SyncCalibrator",
                    "micz: mic=%.1f ref=%.1f mic-ref=%.1f dBFS  peaks=".format(micDb, refDb, micDb - refDb) +
                        peaks.joinToString { "%.1fms(z=%.1f)".format(it.lagMs, it.z) },
                )
            } finally {
                disarm()
                audioFocus.suppressLosses = false
            }
        }
    }

    /**
     * Debug rig tool: dump one capture's PCM to disk so estimator questions stop costing rig time.
     *
     * Writes the two DECIMATED arrays the correlation actually consumes — `refD` (broadcast
     * reference) and `micD` (microphone) — as raw little-endian float32, plus a sidecar text file
     * with the sample rate and the `pre` offset needed to turn a correlation index into a delay.
     *
     * Decimated and windowed rather than raw, deliberately. The alternative is dumping the full
     * 30 s ring and re-implementing `DelayMeasurement`'s alignment offline, which risks answering a
     * subtly different question than the one the calibrator asks — precisely the failure mode this
     * whole effort has been unwinding. These are the exact arrays the estimator sees.
     *
     * The question it exists to answer first: the deconvolution assumes the room is LINEAR and
     * TIME-INVARIANT across the full 12 s capture, and nothing has ever checked that. Deconvolve four
     * 3 s sub-blocks separately and see whether the arrival's lag or amplitude moves. Plain
     * correlation degrades gracefully when time-invariance breaks; deconvolution does not, and the
     * OnePlus sink is already known to wander 25-40 ms BETWEEN captures.
     *
     * [probe] optionally offsets one client's latency first, so the dump can capture the SEPARATED
     * geometry the balance actually harvests from rather than the overlapped baseline.
     */
    fun dumpCapturePcm(targetName: String?, probe: Boolean) {
        if (calibrationJob?.isActive == true) return
        val control = snapcastControl
        val connected = _state.value.snapcastGroups.flatMap { it.clients }.filter { it.connected }
        val localId = snapclientProcess?.storedHostId.orEmpty()
        val target = connected.firstOrNull { targetName != null && it.config.name == targetName }
            ?: connected.firstOrNull { localId.isEmpty() || !it.id.contains(localId) }
        calibrationJob = scope.launch {
            val ring = tech.capullo.audio.calibration.ReferencePcmRing()
            val mic = tech.capullo.audio.calibration.MicCapture(this@PlaybackService)
            val baseLatency = target?.config?.latency ?: 0
            val probing = probe && target != null && control != null
            val disarm = armReference(ring, "pcmdump") ?: return@launch
            audioFocus.suppressLosses = true
            try {
                delay(16_000L) // prime the ring past one full capture
                if (probing) {
                    // Journalled for the same reason levelsweep journals: SetLatency is
                    // server-persisted, so a process kill here would strand the client de-synced.
                    if (!calibrationJournal.save(
                            mapOf(
                                target!!.id to tech.capullo.audio.calibration.ClientSnapshot(
                                    baseLatency,
                                    target.config.volume.percent,
                                    target.config.volume.muted,
                                ),
                            ),
                        )
                    ) {
                        Log.w(TAG_CAL, "pcmdump: could not journal — dumping unprobed to stay recoverable")
                    } else {
                        control!!.sendSetLatency(target.id, baseLatency - SWEEP_PROBE_MS)
                        delay(7_000L)
                    }
                }
                val cap = mic.record(12_000)
                    ?: run {
                        Log.w(TAG_CAL, "pcmdump: capture failed")
                        return@launch
                    }
                val snap = ring.snapshot()
                val p = tech.capullo.audio.calibration.DelayMeasurement.prepare(snap, cap)
                    ?: run {
                        Log.w(TAG_CAL, "pcmdump: ring did not cover the capture")
                        return@launch
                    }

                val dir = getExternalFilesDir(null)
                    ?: run {
                        Log.w(TAG_CAL, "pcmdump: no external files dir")
                        return@launch
                    }
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                fun writeFloats(name: String, data: FloatArray): java.io.File {
                    val f = java.io.File(dir, name)
                    val bb = java.nio.ByteBuffer.allocate(data.size * 4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (v in data) bb.putFloat(v)
                    f.writeBytes(bb.array())
                    return f
                }
                val refF = writeFloats("pcm-$stamp-ref.f32", p.refD)
                val micF = writeFloats("pcm-$stamp-mic.f32", p.micD)
                val meta = java.io.File(dir, "pcm-$stamp-meta.txt")
                meta.writeText(
                    buildString {
                        appendLine("fs=${p.fs}")
                        appendLine("pre=${p.pre}")
                        appendLine("refSamples=${p.refD.size}")
                        appendLine("micSamples=${p.micD.size}")
                        appendLine("format=float32-le-mono")
                        // delay(ms) of correlation index j = (j - pre) * 1000 / fs
                        appendLine("lagFormula=(index-pre)*1000/fs")
                        appendLine("probed=$probing")
                        appendLine("probeMs=${if (probing) SWEEP_PROBE_MS else 0}")
                        appendLine("probeTarget=${if (probing) target?.config?.name else ""}")
                        appendLine(
                            "gains=" + connected.joinToString(",") {
                                "${it.config.name}=${it.config.volume.percent}${if (it.config.volume.muted) ":MUTED" else ""}"
                            },
                        )
                        appendLine("track=${_state.value.icyTitle.ifEmpty { _state.value.snapcastTrackName }}")
                    },
                )
                Log.i(
                    TAG_CAL,
                    "pcmdump: wrote ${refF.name} (${refF.length()}B) ${micF.name} (${micF.length()}B) " +
                        "${meta.name} in ${dir.absolutePath}",
                )
            } finally {
                if (probing && control != null && target != null) {
                    withContext(NonCancellable) {
                        control.sendSetLatency(target.id, baseLatency)
                        control.sendGetStatus()
                        calibrationJournal.clear()
                    }
                }
                disarm()
                audioFocus.suppressLosses = false
            }
        }
    }

    /** True when the last run balanced volumes and they can still be put back. */
    fun canUndoBalancedVolumes(): Boolean = calHost.canUndoBalance()

    /**
     * Put the pre-balance volumes back. Cheap to offer and cheap to get wrong, which is the point:
     * the balance writes server-persisted volumes off an estimator that is bounded rather than proven,
     * so the cost of a bad correction has to be one action rather than re-levelling the room by hand.
     */
    fun undoBalancedVolumes() {
        scope.launch {
            val restored = calHost.undoBalance()
            if (restored.isEmpty()) {
                Log.i(TAG_CAL, "undo: nothing to undo")
            } else {
                snapcastControl?.sendGetStatus() // refresh the volumes shown in the UI
                Log.i(TAG_CAL, "undo: restored ${restored.size} client(s)")
            }
        }
    }

    /**
     * Debug rig tool: the GO/NO-GO measurement for the mic-referenced volume balance.
     *
     * Answers one question and nothing else — **does the reported level ratio track a commanded gain
     * change?** Every rig number so far was taken at EQUAL gains, where an asymmetric reading is the
     * CORRECT answer (the mic sits beside one speaker and across the room from the other), so there
     * has never been any sensitivity data at all and "not achievable" was never supportable. Run this
     * at 0/−6/−12 dB on one client: if the ratio moves by the commanded amount within 3 dB the
     * estimator works and the remaining problem is labelling; if it does not, the feature dies here
     * with evidence behind it.
     *
     * Three deliberate design choices, each avoiding a way the previous attempts fooled themselves:
     *
     *  - **It DUMPS, it does not DECIDE.** Every peak of both captures is logged with its lag, its z
     *    and its un-whitened level. Nothing goes through [tech.capullo.audio.calibration.PeakAttribution],
     *    because attribution is the prime suspect — routing the diagnostic through the component under
     *    suspicion is exactly the mistake that let three fixes be declared on evidence that could not
     *    see the next defect down. Labelling happens offline, by hand, against the sweep itself: the
     *    arrival whose level tracks the command IS the client whose gain was changed.
     *  - **It probes.** The level estimator needs ~30 ms of arrival separation, and in a baseline
     *    capture two speakers can sit a millisecond apart. One client's latency is offset by
     *    [SWEEP_PROBE_MS] to force the separation, then restored in a finally.
     *  - **It writes no volumes.** The experimenter sets the gains; the tool never moves them. That
     *    keeps the commanded value the ground truth instead of something the run also edits.
     */
    fun measureLevelSweep(targetName: String?) {
        if (calibrationJob?.isActive == true) return
        val control = snapcastControl ?: run {
            Log.w(TAG_CAL, "levelsweep: no server control")
            return
        }
        val connected = _state.value.snapcastGroups.flatMap { it.clients }.filter { it.connected }
        // Probe the REMOTE client by default: the reference is this device's own snapclient, sitting
        // beside the mic, and it is the one whose latency must stay put.
        val localId = snapclientProcess?.storedHostId.orEmpty()
        val target = connected.firstOrNull { targetName != null && it.config.name == targetName }
            ?: connected.firstOrNull { localId.isEmpty() || !it.id.contains(localId) }
            ?: run {
                Log.w(TAG_CAL, "levelsweep: no target client")
                return
            }
        if (connected.size < 2) {
            Log.w(TAG_CAL, "levelsweep: need 2 connected clients, got ${connected.size}")
            return
        }
        calibrationJob = scope.launch {
            val ring = tech.capullo.audio.calibration.ReferencePcmRing()
            val mic = tech.capullo.audio.calibration.MicCapture(this@PlaybackService)
            val baseLatency = target.config.latency
            val disarm = armReference(ring, "levelsweep") ?: return@launch
            audioFocus.suppressLosses = true
            // Log the commanded gains FIRST. This line is the ground truth the whole measurement is
            // scored against, and reading it out of the run rather than trusting the operator's notes
            // is what makes the result auditable afterwards.
            Log.i(
                TAG_CAL,
                "levelsweep: gains " + connected.joinToString {
                    "${it.config.name}=${it.config.volume.percent}%${if (it.config.volume.muted) " MUTED" else ""}"
                } + " | probing ${target.config.name} by ${SWEEP_PROBE_MS}ms",
            )
            try {
                delay(16_000L) // prime the ring past one full capture
                // Window the level grid is dumped over, fixed from the BASELINE's strongest arrival
                // and then reused for the probed capture so the two are directly comparable.
                var gridStartMs = -1.0
                suspend fun dump(tag: String) {
                    // ORDER MATTERS AND IS NOT COSMETIC: record FIRST, snapshot the ring SECOND.
                    // DelayMeasurement aligns the two by `ring.lastSampleNanos − mic.firstSampleNanos`,
                    // so a ring snapshot taken BEFORE the capture is one whose newest sample predates
                    // the mic's first — the search window then lands on audio that played before the
                    // capture and every peak is noise. Kotlin evaluates arguments left to right, so
                    // writing measure(ring.snapshot(), mic.record(...)) silently does exactly that.
                    val cap = mic.record(12_000)
                        ?: run {
                            Log.w(TAG_CAL, "levelsweep: $tag capture failed")
                            return
                        }
                    val snap = ring.snapshot()
                    val m = tech.capullo.audio.calibration.DelayMeasurement.measure(snap, cap, 6)
                        ?: run {
                            Log.w(TAG_CAL, "levelsweep: $tag ring did not cover the capture")
                            return
                        }
                    fun rmsDb(x: FloatArray): Double {
                        var s = 0.0
                        for (v in x) s += v.toDouble() * v
                        return 20.0 * kotlin.math.log10(kotlin.math.sqrt(s / x.size.coerceAtLeast(1)) + 1e-12)
                    }
                    // BOTH RMS figures, because the mic's alone is not a level. Program material
                    // changes the mic RMS by more than the gain step being measured does — a quiet
                    // passage looks exactly like an attenuated speaker — so mic dBFS on its own cannot
                    // be swept across captures minutes apart. The difference mic−ref divides the
                    // program out and is the figure to compare between steps.
                    val micDb = rmsDb(cap.pcm)
                    val refDb = rmsDb(snap.pcm)
                    Log.i(
                        TAG_CAL,
                        "levelsweep $tag: mic=%.1f ref=%.1f mic-ref=%.1f dBFS | ".format(
                            micDb,
                            refDb,
                            micDb - refDb,
                        ) + m.peaks.joinToString {
                            "%.1fms(z=%.1f,lvl=%.3e)".format(it.lagMs, it.z, m.levelAt(it.lagMs))
                        },
                    )
                    // A LEVEL GRID OVER A FIXED WINDOW, and this is the part that makes the sweep
                    // work at all. Reading levels only at the PHAT peak lags above would silently
                    // restrict the measurement to whatever was loud enough to make the top-N peak
                    // list — and the quiet speaker, the one whose level is most in question, is
                    // exactly what drops out of that list. Its level would then simply be absent from
                    // the very experiment meant to measure it.
                    //
                    // The window is anchored to the baseline's strongest arrival and reused
                    // unchanged for the probed capture, so a level at a given grid offset means the
                    // same thing in both. It spans the probe offset plus margin on each side, which
                    // covers both arrivals wherever the probe moved them and the OnePlus sink's
                    // 25-40ms wander.
                    if (gridStartMs < 0) {
                        gridStartMs = ((m.peaks.firstOrNull()?.lagMs ?: 1250.0) - GRID_MARGIN_MS)
                            .coerceAtLeast(0.0)
                    }
                    val steps = ((SWEEP_PROBE_MS + 2 * GRID_MARGIN_MS) / GRID_STEP_MS).toInt()
                    val sb = StringBuilder(
                        "levelsweep $tag grid: start=%.1fms step=%.1fms n=$steps |".format(
                            gridStartMs,
                            GRID_STEP_MS,
                        ),
                    )
                    for (i in 0 until steps) {
                        sb.append(" %.2e".format(m.levelAt(gridStartMs + i * GRID_STEP_MS)))
                    }
                    Log.i(TAG_CAL, sb.toString())
                }
                // Baseline first: it is what identifies which arrival MOVED, and its own peak list is
                // the cross-check that the probed capture's extra arrival is the probe and not a
                // music self-similarity ghost.
                dump("baseline")
                // JOURNAL BEFORE THE WRITE. SetLatency is server-persisted, so if ColorOS kills the
                // app during the probe window (it does exactly this when our own mic opens) the
                // client is stranded at latency−180ms permanently, with no record of the original.
                // The finally below cannot run in that case; the journal is what makes it recoverable
                // on the next start. A diagnostic that can leave the room audibly de-synced forever
                // is not non-mutating in any useful sense.
                if (!calibrationJournal.save(
                        mapOf(
                            target.id to tech.capullo.audio.calibration.ClientSnapshot(
                                latencyMs = baseLatency,
                                volumePercent = target.config.volume.percent,
                                volumeMuted = target.config.volume.muted,
                            ),
                        ),
                    )
                ) {
                    Log.w(TAG_CAL, "levelsweep: could not journal — skipping the probe to stay recoverable")
                    return@launch
                }
                val want = baseLatency - SWEEP_PROBE_MS
                control.sendSetLatency(target.id, want)
                control.sendGetStatus()
                delay(7_000L) // settle: the sink has to take the latency, and the status reply to land
                // READ THE PROBE BACK. At 380 ms this asks for a large negative latency (-479 ms on a
                // client already at -99), and a snapclient that clamped it would hand back a capture
                // whose arrivals never separated — which looks exactly like a measurement failure
                // unless the value that actually landed is logged beside the one requested.
                val got = _state.value.snapcastGroups.flatMap { it.clients }
                    .firstOrNull { it.id == target.id }?.config?.latency
                if (got != want) {
                    Log.w(TAG_CAL, "levelsweep: probe requested ${want}ms but read back ${got}ms — CLAMPED")
                } else {
                    Log.i(TAG_CAL, "levelsweep: probe ${want}ms confirmed")
                }
                dump("probed")
            } finally {
                // Restore unconditionally, with a read-back attempt: a stranded probe offset is an
                // audible de-sync that outlives the diagnostic.
                withContext(NonCancellable) {
                    control.sendSetLatency(target.id, baseLatency)
                    control.sendGetStatus()
                    calibrationJournal.clear()
                }
                disarm()
                audioFocus.suppressLosses = false
                Log.i(TAG_CAL, "levelsweep: done, ${target.config.name} latency restored to ${baseLatency}ms")
            }
        }
    }

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
            snapserverNsd = tech.capullo.audio.snapcast.SnapserverNsdRegistrar(this).also { r -> r.start(customServerName) }
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
    private var snapserverNsd: tech.capullo.audio.snapcast.SnapserverNsdRegistrar? = null
    private var snapcastControl: tech.capullo.audio.snapcast.SnapcastControlClient? = null
    private var snapserverJob: Job? = null
    private var snapclientJob: Job? = null
    private var snapcastControlJob: Job? = null
    private var localChannelTagSet = false

    // Own snapclient vol/latency restore + persist (spatial-role memory). Restore applies the saved
    // values on connect and is only marked done once the server reflects them, so the default 100/0
    // during the restore window can't be persisted over the saved value (restore-before-observe).
    @Volatile private var savedVol = 100

    @Volatile private var savedLat = 0
    private var volLatRestored = false
    private var volLatApplied = false
    private var lastPersistedVol = -1
    private var lastPersistedLat = Int.MIN_VALUE

    var onSkipNextRequested: (() -> Unit)? = null
    var onSkipPrevRequested: (() -> Unit)? = null

    // Whether broadcaster-side skipping is meaningful (rotation/queue active).
    // Set by RadioViewModel; pushed to Snapcast clients via canGoNext/canGoPrevious.
    @Volatile private var broadcastCanSkip = false
    fun updateBroadcastCanSkip(canSkip: Boolean) {
        if (broadcastCanSkip == canSkip) return
        broadcastCanSkip = canSkip
        publishNowPlaying()
    }
    var onPlayPauseRequested: (() -> Unit)? = null
    var onStationError: (() -> Unit)? = null
    var onStationPlaying: (() -> Unit)? = null

    // Notifies the VM that audio focus was lost (true) / regained (false) so the rotation
    // countdown can pause while another app owns this phone's speaker - otherwise a
    // backgrounded, focus-lost QC advances the station on the timer and re-grabs focus,
    // stealing audio from the foreground app.
    var onFocusPausedChanged: ((Boolean) -> Unit)? = null

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
    // Focus affects ONLY the local snapclient (the audible part of this device). The broadcast
    // pipeline (ExoPlayer → FIFO → snapserver → remote clients) must never react to focus changes:
    // other rooms keep playing while e.g. a YouTube video takes over this phone's speaker.
    // The shared controller owns the permanent/transient distinction + isMusicActive quiet-watcher
    // recovery; QuantumCast supplies only the local-snapclient stop/start callbacks.
    private val audioFocus by lazy {
        AudioFocusController(
            this,
            onPause = {
                stopLocalSnapclient()
                onFocusPausedChanged?.invoke(true)
            },
            onResume = {
                startLocalSnapclient()
                onFocusPausedChanged?.invoke(false)
            },
        )
    }

    companion object {
        const val ACTION_SKIP_NEXT = "tech.capullo.quantumcast.SKIP_NEXT"
        const val ACTION_SKIP_PREV = "tech.capullo.quantumcast.SKIP_PREV"

        private const val CHANNEL_ID = "quantumcast_playback"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "QCPlaybackService"

        /** Calibration logs go under the LIBRARY's tag, not this service's, so one
         *  `logcat -s SyncCalibrator` shows a run and its diagnostics together. */
        private const val TAG_CAL = "SyncCalibrator"

        /** Arrival separation forced by the level sweep.
         *
         *  The level estimator needs about 30 ms to be accurate (a true 0.25 ratio reads 0.42 at
         *  10 ms, 0.26 from 30 ms), and in a baseline capture two speakers can sit a millisecond
         *  apart. That requirement is what set the old value of 180 ms, and it is not the binding
         *  constraint.
         *
         *  THE BINDING CONSTRAINT IS THE LOUDER SPEAKER'S REVERBERANT TAIL. Separation in time does
         *  not separate a quiet speaker's direct sound from a loud speaker's tail arriving at the
         *  same lag. Measured on this rig at 180 ms of probe (250-290 ms of arrival separation), the
         *  tail is 60-75% of the quiet arrival's own peak — only 2.5-4.4 dB below it — so the level
         *  read there is mostly the other speaker, and the two-speaker sweeps did not track a
         *  commanded gain at all (FINDINGS 15-19).
         *
         *  The tail decays 5.4 dB per 100 ms, measured by fitting the region between the arrivals
         *  across 11 captures (9 of 11 in 4.0-6.7). 380 ms buys 10.9 dB over the old value and should
         *  put the quiet arrival 13-15 dB above the tail instead of 2.5-4.4.
         *
         *  Costs to keep in view. The grid widens with it automatically ([GRID_MARGIN_MS] on each
         *  side of the probe span), so nothing else needs changing. But the probe is written as
         *  `latency - probe`, so on a client already at -99 ms this asks for -479 ms, and a
         *  snapclient that clamps that would look like a measurement failure rather than a write
         *  failure — the write is read back for exactly this reason. */
        private const val SWEEP_PROBE_MS = 380

        /** Level-grid resolution. Matches [tech.capullo.audio.calibration.Dsp.levelAt]'s own ±3 ms
         *  read window, so consecutive grid points overlap slightly and an arrival cannot fall between
         *  two of them. */
        private const val GRID_STEP_MS = 5.0

        /** Margin the level grid extends on each side of the probe span.
         *
         *  220 ms, sized from the rig rather than guessed. The two natural arrivals sit about 130 ms
         *  apart (HK Neo ~1200 ms, Guer ~1330 ms), so the probe has to push the LATER one further out
         *  to separate them — probing the earlier one just slides it onto the other. That puts the
         *  probed arrival near 1510 ms while the grid is anchored 120 ms before the baseline's
         *  strongest peak at ~1200 ms, and at the old 120 ms margin the window ended at 1500 ms with
         *  the probed arrival just outside it. The measurement would have missed the very speaker it
         *  moved. */
        private const val GRID_MARGIN_MS = 220.0

        /** How often a boosted client re-checks its lease. Short relative to the lease so the
         *  volume comes back promptly when a calibrating server stops renewing. */
        private const val CAL_BOOST_TICK_MS = 2_000L

        /** Hard ceiling on a lease this device will honour, whatever expiry the server sends.
         *  Comfortably above the server's own lease so a healthy run is never truncated. */
        private const val MAX_CLIENT_LEASE_MS = 240_000L

        // Snapcast stream identity (the snapserver `name=`), shown in web players / to snapclients.
        // capullo-audio's SnapserverProcess defaults to "Capullo"; QuantumCast keeps its own name so
        // multiple capullo apps stay distinguishable on a LAN (was hardcoded in QC's SnapserverProcess).
        private const val STREAM_NAME = "QuantumCast"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        startForegroundNotification()
        // Undo an OS-volume detectability boost a process death interrupted, before anything else
        // touches the volume. The server's calibration journal cannot cover this one: OS volume is
        // not server state, so the boosted device owns its own recovery. No-op when clean.
        if (osVolumeBoost.recover()) Log.w(TAG, "restored OS media volume from an interrupted boost")
        // Restore this device's persisted spatial role so it applies on the next broadcast/connect
        // (maybeSetInitialChannelTag reads _state.snapclientChannel). Seeded before the client
        // connects, so no observe race - the initial tag is the saved value, not the default.
        scope.launch {
            val saved = settingsRepository.settings.first()
            savedVol = saved.snapclientVolume
            savedLat = saved.snapclientLatency
            balanceProcessor.balance = saved.balance
            snapserverFixedPort = saved.snapserverFixedPort
            _state.update { it.copy(snapclientChannel = saved.snapclientChannel) }
        }
        // Keep the broadcast balance + fixed-port choice in sync with Settings live.
        scope.launch {
            settingsRepository.settings.collect {
                balanceProcessor.balance = it.balance
                snapserverFixedPort = it.snapserverFixedPort
            }
        }
        // Persist own client's volume/latency on ANY change (slider, knob, remote controller).
        // GetStatus-only observation missed incremental ClientOnVolumeChanged updates, so this
        // watches state directly. Gated by volLatRestored so the connect-time default can't be
        // persisted over the saved values.
        scope.launch {
            _state.collect { s ->
                if (!volLatRestored) return@collect
                val localId = snapclientProcess?.storedHostId?.takeIf { it.isNotEmpty() } ?: return@collect
                val own = s.snapcastGroups.flatMap { it.clients }
                    .find { it.id == localId || it.id.contains(localId) } ?: return@collect
                val vol = own.config.volume.percent
                val lat = own.config.latency
                if (vol != lastPersistedVol || lat != lastPersistedLat) {
                    lastPersistedVol = vol
                    lastPersistedLat = lat
                    settingsRepository.setSnapclientVolume(vol)
                    settingsRepository.setSnapclientLatency(lat)
                }
            }
        }
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
            // Starting a station is an explicit "make sound" action: request()
            // unconditionally (re)acquires focus so a co-broadcasting app's local
            // snapclient is evicted, then refocus() recovers ours if a prior focus
            // loss had stopped it (no-op otherwise). Bare refocus() alone no-ops
            // when we're audible-without-focus (initial request failed), so it
            // would never force the other app to yield the speaker.
            audioFocus.request()
            audioFocus.refocus()
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
        // Explicit "make sound" action: request() unconditionally (re)acquires
        // focus so a co-broadcasting app yields the speaker, then refocus()
        // recovers a focus-paused local snapclient (no-op when not focus-paused).
        // Bare refocus() alone no-ops when audible-without-focus, so request()
        // is what forces the other app off the speaker.
        audioFocus.request()
        audioFocus.refocus()
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

    fun connectAsSnapclient(host: String, port: Int = 1604, httpPort: Int = 1680) {
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
        startSnapcastControl(host, httpPort)
        audioFocus.request()
        Log.d(TAG, "Snapclient → $host:$port (control :$httpPort)")
    }

    private fun applyStreamProperties(props: tech.capullo.audio.snapcast.StreamPlayerProperties) {
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
        applyCalBoost(meta?.calBoost, metadataPresent = meta != null)
        updateMediaSession()
    }

    /**
     * Apply (or drop) a calibration detectability-boost lease addressed to THIS device.
     *
     * The calibrating server broadcasts `calBoost = "<clientId>:<osPercent>:<expiryEpochMs>"` in the
     * stream metadata; every client sees it and self-filters by id. Presence renews the lease,
     * absence releases it, and if neither ever arrives again the lease simply lapses (see
     * [tech.capullo.audio.calibration.OsVolumeBoost]) - so a server that crashes mid-run cannot
     * leave this phone loud.
     *
     * [metadataPresent] guards the release: a transport-only properties event (play/pause) carries
     * no metadata block and therefore says nothing about the boost, so it must not be read as
     * "released" mid-measurement.
     */
    private fun applyCalBoost(raw: String?, metadataPresent: Boolean) {
        if (!metadataPresent) return
        if (raw.isNullOrEmpty()) return releaseCalBoost()
        val localId = snapclientProcess?.storedHostId?.takeIf { it.isNotEmpty() } ?: return
        // The payload lists every currently boosted speaker (`id:percent:expiry;…`) because the
        // no-mute batch boosts several at once. Find ours; a lease naming only other speakers means
        // we are not boosted, so it releases.
        // Parse the two numeric fields from the RIGHT: a client id is not guaranteed colon-free
        // (stock snapclients identify by MAC address), so splitting left-to-right would mis-parse
        // and the server would believe it had boosted a speaker that never heard the lease.
        val mine = raw.split(';').firstOrNull { entry ->
            val id = entry.substringBeforeLast(':').substringBeforeLast(':')
            id.isNotEmpty() &&
                // Same matching the status path uses: the server's client id may embed the host id.
                (id == localId || id.contains(localId))
        } ?: return releaseCalBoost()
        val expiryMs = mine.substringAfterLast(':').toLongOrNull() ?: return
        val percent = mine.substringBeforeLast(':').substringAfterLast(':').toIntOrNull() ?: return
        val now = System.currentTimeMillis()
        if (expiryMs <= now) return // already stale (clock skew or a late-delivered lease)
        // Clamp against the SERVER's clock being behind ours: the expiry is absolute epoch ms, so a
        // skewed peer could otherwise hand us a lease lasting hours and the failsafe would stop
        // being a bound at all. A bound that depends on a stranger's clock is not a bound.
        val leaseMs = minOf(expiryMs - now, MAX_CLIENT_LEASE_MS)
        if (osVolumeBoost.apply(percent, now, leaseMs)) startCalBoostTicker()
    }

    private fun releaseCalBoost() {
        if (osVolumeBoost.isBoosted) {
            osVolumeBoost.release()
            Log.i(TAG, "OS volume boost released")
        }
        calBoostTicker?.cancel()
        calBoostTicker = null
    }

    /** Drives the lease failsafe: restores the volume if the server stops renewing. */
    private fun startCalBoostTicker() {
        if (calBoostTicker?.isActive == true) return
        calBoostTicker = scope.launch {
            while (osVolumeBoost.isBoosted) {
                delay(CAL_BOOST_TICK_MS)
                if (osVolumeBoost.expireIfLapsed(System.currentTimeMillis())) {
                    Log.w(TAG, "OS volume boost lease lapsed - volume restored")
                }
            }
        }
    }

    private fun maybeSetInitialChannelTag(groups: List<tech.capullo.audio.snapcast.Group>) {
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

    private fun syncLocalChannelFromName(groups: List<tech.capullo.audio.snapcast.Group>) {
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
            scope.launch { settingsRepository.setSnapclientChannel(newChannel) }
        }
    }

    // Restore this device's saved volume/latency onto its own snapclient on connect, then persist
    // any later change. Gated by volLatRestored - set only once the server reflects the saved values
    // back - so the server's transient default (100/0) during the restore window can't be persisted
    // over what we saved.
    private fun restoreOrPersistOwnVolLat(groups: List<tech.capullo.audio.snapcast.Group>) {
        val localId = snapclientProcess?.storedHostId?.takeIf { it.isNotEmpty() } ?: return
        val own = groups.flatMap { it.clients }
            .find { it.id == localId || it.id.contains(localId) } ?: return
        val vol = own.config.volume.percent
        val lat = own.config.latency
        if (!volLatRestored) {
            if (vol == savedVol && lat == savedLat) {
                volLatRestored = true
                lastPersistedVol = vol
                lastPersistedLat = lat
            } else if (!volLatApplied) {
                volLatApplied = true
                scope.launch {
                    snapcastControl?.sendSetVolume(own.id, own.config.volume.muted, savedVol)
                    snapcastControl?.sendSetLatency(own.id, savedLat)
                    snapcastControl?.sendGetStatus()
                }
            }
            return
        }
    }

    fun setSnapclientChannel(channel: String) {
        val localId = snapclientProcess?.storedHostId?.takeIf { it.isNotEmpty() }
        if (localId != null) {
            changeClientChannelInternal(localId, channel)
        } else {
            // Snapclient not yet connected - just update state and channel for when it does
            _state.update { it.copy(snapclientChannel = channel) }
            scope.launch { settingsRepository.setSnapclientChannel(channel) }
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
            scope.launch { settingsRepository.setSnapclientChannel(channel) }
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
        publishNowPlaying()
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
        publishNowPlaying()
        updateMediaSession()
    }

    fun updateArtwork(artUrl: String) {
        _state.update { it.copy(artworkUrl = artUrl) }
        publishNowPlaying()
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
            // A calibration may be mid-run across an engine restart: the fresh sink must
            // inherit the armed reference tap or the measurement ring silently starves.
            it.pcmTap = calibrationTap
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
        // Local renderers factory = the shared FifoRenderersFactory's chain
        // ([mix → 2ch] → [resample → 44100] → [tee → FIFO]) with the stereo-balance
        // processor inserted before the tee. Mirrors Telecloud; EXTENSION_RENDERER_MODE_ON
        // keeps the FFmpeg fallback decoders for exotic codecs.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            init {
                setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
            }

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val mixer = ChannelMixingAudioProcessor().apply {
                    putChannelMixingMatrix(ChannelMixingMatrix.create(1, 2))
                    putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2))
                }
                // LOCKSTEP with capullo-audio SnapserverProcess.SAMPLE_FORMAT (48000): the FIFO the
                // tee writes is read by snapserver at that rate, so this resampler must output the
                // same. Mismatch (this was 44100 vs a 48000 FIFO) made the server read the PCM ~9%
                // fast and hard-resync constantly - the stutter.
                val resampler = SonicAudioProcessor().apply { setOutputSampleRateHz(48000) }
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false) // keep the chain in 16-bit PCM
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            mixer,
                            resampler,
                            balanceProcessor,
                            TeeAudioProcessor(sink),
                        ),
                    )
                    .build()
            }
        }
        val player = ExoPlayer.Builder(this, renderersFactory)
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
        publishNowPlaying()
        updateNotification()
        updateMediaSession()
    }

    private fun onEnginePaused() {
        _state.update { it.copy(isPlaying = false) }
        publishNowPlaying()
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

    // OS-assigned ports (default) so multiple capullo apps coexist and the ports aren't a fixed
    // guess; the resolved trio is read back off snapserver.ports to wire the snapclient / NSD /
    // control / web URL. A fixed base port (Settings) instead pins the trio so the server address
    // survives broadcast restarts (snapclients reconnect without rediscovery; web origin stable).
    private fun ensureSnapserver(): SnapserverProcess = snapserverProcess ?: SnapserverProcess(
        this,
        STREAM_NAME,
        resolveSnapserverPorts(),
        // Per-app abstract control socket so QC + another capullo app can broadcast at once.
        controlSocketName = SnapserverProcess.controlSocketName(this),
    ).also { snapserverProcess = it }

    /** Fixed base if set AND its trio is actually free; otherwise OS-assigned. A busy fixed
     *  port falls back to random with a Toast so the broadcast never silently dies on a
     *  collision (user-editable base can clash with another app or a stale server). */
    private fun resolveSnapserverPorts(): SnapserverPorts {
        val base = snapserverFixedPort
        if (base <= 0) return SnapserverPorts.free()
        SnapserverPorts.fixedIfFree(base)?.let { return it }
        Log.w(TAG, "fixed snapserver port $base unavailable — falling back to OS-assigned")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                this,
                "Fixed port $base is in use — broadcasting on a random port this session",
                Toast.LENGTH_LONG,
            ).show()
        }
        return SnapserverPorts.free()
    }

    // --- Snapcast control-plugin adapter (capullo-audio SnapcontrolPlugin) ---
    // The engine's SnapcontrolPlugin is driven by the platform contract: a StateFlow<NowPlaying>
    // (read) + a PlaybackController (transport), replacing QuantumCast's former fat
    // SnapcontrolCallbacks. buildSnapNowPlaying() maps this service's PlaybackState onto a
    // NowPlaying; app-specific web-player fields (country/codec/bitrate/streaming links/tags/uuid)
    // ride in NowPlaying.extras, emitted verbatim into the JSON metadata by the default mapper
    // (album -> "station"). Only meaningful in QUANTUMCAST broadcast mode - no plugin runs while
    // listening in as a snapclient.
    // MutableStateFlow is-a StateFlow, so it satisfies SnapcontrolPlugin's read-only param directly
    // (no separate public asStateFlow() view needed - this flow is service-internal).
    private val snapNowPlaying = MutableStateFlow(NowPlaying.EMPTY)

    private val snapController = object : PlaybackController {
        override fun play() = this@PlaybackService.play()
        override fun pause() = this@PlaybackService.pause()
        override fun next() {
            onSkipNextRequested?.invoke()
        }
        override fun previous() {
            onSkipPrevRequested?.invoke()
        }
        override fun seekTo(positionMs: Long) {} // live radio - not seekable
    }

    // Artwork is prepared app-side as base64 (the engine mapper expects NowPlaying.artworkBase64,
    // not a URL): Shazam art wins, else the station favicon - parity with the old
    // SnapcontrolCallbacks.currentArtworkUrl. artUrl also rides in extras for clients that follow it;
    // the base64 (artData) covers clients that embed bytes (e.g. RadioCapullo).
    @Volatile private var snapArtUrl: String? = null

    @Volatile private var snapArtBase64: String? = null

    @Volatile private var snapArtExtension: String? = null
    private var snapArtJob: Job? = null

    // Push the current metadata to web players / snapclients. Replaces the old
    // snapcontrolPlugin?.notifyPropertiesChanged() call sites (no-op until a session connects).
    private fun publishNowPlaying() {
        refreshSnapArt()
        val next = buildSnapNowPlaying()
        if (next == snapNowPlaying.value) return
        snapNowPlaying.value = next
        snapcontrolPlugin?.notifyPropertiesChanged()
    }

    private fun buildSnapNowPlaying(): NowPlaying {
        val st = _state.value
        val title = st.shazamTrackName.ifEmpty { st.icyTitle.ifEmpty { st.stationName } }
        // Skipping is only meaningful with an active station while rotation is on (broadcastCanSkip),
        // matching the old callbacks' canGoNext/canGoPrevious gate.
        val canSkip = broadcastCanSkip && (st.isPlaying || st.stationUrl.isNotEmpty())
        return NowPlaying(
            title = title,
            artist = st.shazamArtistName,
            album = st.stationName,
            streamUrl = st.stationUrl.ifEmpty { null },
            artworkBase64 = snapArtBase64,
            isPlaying = st.isPlaying,
            canGoNext = canSkip,
            canGoPrevious = canSkip,
            extras = buildMap {
                if (st.stationCountry.isNotEmpty()) put("country", st.stationCountry)
                if (st.stationCountryCode.isNotEmpty()) put("countrycode", st.stationCountryCode)
                if (st.stationCodec.isNotEmpty()) put("codec", st.stationCodec)
                if (st.stationBitrate > 0) put("bitrate", st.stationBitrate.toString())
                if (st.shazamYoutubeUrl.isNotEmpty()) put("youtubeUrl", st.shazamYoutubeUrl)
                if (st.shazamSpotifyUrl.isNotEmpty()) put("spotifyUrl", st.shazamSpotifyUrl)
                if (st.shazamAppleMusicUrl.isNotEmpty()) put("appleMusicUrl", st.shazamAppleMusicUrl)
                if (st.stationTags.isNotEmpty()) put("tags", st.stationTags)
                if (st.stationUuid.isNotEmpty()) put("uuid", st.stationUuid)
                snapArtUrl?.takeIf { it.isNotEmpty() }?.let { put("artUrl", it) }
                snapArtExtension?.let { put("artExtension", it) }
                calBoostLease?.let { put("calBoost", it) }
            },
        )
    }

    /**
     * Broadcast a calibration OS-volume boost lease to the addressed client (or clear it with a
     * null [clientId]). Rides the stream metadata, which snapserver rebroadcasts to every control
     * connection; the addressed client self-filters and applies it, and the lease expiry means a
     * crash here cannot leave that phone loud.
     *
     * Deliberately NOT renewed on a short ticker: every extras change invalidates the metadata
     * serialization cache, which re-escapes the (~300 KB) embedded art blob and can starve the
     * real-time FIFO writer. So the lease is issued generously once per round instead. If a round
     * outlives it the boost simply drops and that target reads as unmeasurable, which is a safe fail.
     */
    private fun publishCalBoost(targets: Map<String, Int>, leaseMs: Long) {
        val expiry = System.currentTimeMillis() + leaseMs
        // Several speakers can be boosted at once (the no-mute batch), so the payload is a list of
        // `id:percent:expiry` entries; each client picks out its own and ignores the rest.
        calBoostLease = targets.takeIf { it.isNotEmpty() }
            ?.entries?.joinToString(";") { "${it.key}:${it.value}:$expiry" }
        publishNowPlaying()
    }

    // Download the effective art URL to base64 for the artData embed; re-publish when it lands.
    private fun refreshSnapArt() {
        val url = _state.value.artworkUrl.ifEmpty { _state.value.stationFavicon }
        if (url == snapArtUrl && (url.isEmpty() || snapArtBase64 != null)) return
        if (url.isEmpty()) {
            snapArtUrl = null
            snapArtBase64 = null
            snapArtExtension = null
            snapArtJob?.cancel()
            return
        }
        snapArtUrl = url
        snapArtBase64 = null
        snapArtJob?.cancel()
        snapArtJob = scope.launch {
            val bytes = runCatching {
                withContext(Dispatchers.IO) { java.net.URL(url).openStream().use { it.readBytes() } }
            }.getOrNull() ?: return@launch
            if (snapArtUrl != url) return@launch // art changed while downloading
            snapArtBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            snapArtExtension = url.substringAfterLast('.').substringBefore('?').lowercase().ifEmpty { "jpg" }
            snapNowPlaying.value = buildSnapNowPlaying()
            snapcontrolPlugin?.notifyPropertiesChanged()
        }
    }

    private fun startSnapcast(snapserver: SnapserverProcess, snapserverAddress: String) {
        val ports = snapserver.ports
        _state.update { it.copy(broadcastHttpPort = ports.httpPort) }
        val sc = SnapclientProcess(this).also { snapclientProcess = it }
        snapserverJob = scope.launch { snapserver.start() }
        snapclientJob = scope.launch { sc.start(snapserverAddress, ports.streamPort) }

        snapcontrolPlugin = SnapcontrolPlugin(
            state = snapNowPlaying,
            controller = snapController,
            parentJob = serviceJob,
            // Bind the SAME per-app name the snapserver told libsnapcontrol.so to connect to.
            socketName = snapserver.controlSocketName,
        ).apply {
            isStreamLocked = _state.value.isStreamLocked
            start()
        }
        publishNowPlaying()

        // Register Snapserver via NSD so other devices can discover us (carrying the resolved ports)
        snapserverNsd = tech.capullo.audio.snapcast.SnapserverNsdRegistrar(this)
            .also { it.start(customServerName, ports.streamPort, ports.tcpPort, ports.httpPort) }

        // Connect to our own Snapserver control socket to track connected clients
        startSnapcastControl("localhost", ports.httpPort)

        // Local snapclient is the audible part - join the focus arbitration for it
        audioFocus.request()
    }

    private fun startSnapcastControl(host: String, httpPort: Int) {
        snapcastControlJob?.cancel()
        val client = tech.capullo.audio.snapcast.SnapcastControlClient(host, httpPort)
            .also { snapcastControl = it }
        snapcastControlJob = scope.launch {
            client.initialize()
            // Undo any calibration run a process death interrupted: restore the journaled
            // pre-run latencies AND volumes before anything else touches them (a killed pair
            // round could have left an "other" client muted). No-op when clean.
            calHost.recoverInterrupted()
            client.notifications.collect { notif ->
                when (notif) {
                    is tech.capullo.audio.snapcast.ServerGetStatusResponse -> {
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
                        restoreOrPersistOwnVolLat(groups)
                        // Read current stream state on connect (so NowPlaying shows immediately)
                        val activeStreamId = groups.firstOrNull()?.streamId
                        notif.result.server.streams
                            .find { it.id == activeStreamId }?.properties
                            ?.let { sp ->
                                applyStreamProperties(
                                    tech.capullo.audio.snapcast.StreamPlayerProperties(
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
                    is tech.capullo.audio.snapcast.ServerOnUpdate -> {
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
                        restoreOrPersistOwnVolLat(groups)
                    }
                    is tech.capullo.audio.snapcast.StreamOnProperties -> {
                        val props = notif.params.properties
                        applyStreamProperties(props)
                    }
                    is tech.capullo.audio.snapcast.ClientOnVolumeChanged -> {
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
                    is tech.capullo.audio.snapcast.ClientOnLatencyChanged -> {
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
                    is tech.capullo.audio.snapcast.ClientOnConnect,
                    is tech.capullo.audio.snapcast.ClientOnDisconnect,
                    // Renames carry channel tags ([L]/[R]/[S]) set by web clients; refresh so
                    // syncLocalChannelFromName picks them up - without this, channel changes
                    // made from the web UI are never applied by the app.
                    is tech.capullo.audio.snapcast.ClientOnNameChanged,
                    -> {
                        scope.launch { snapcastControl?.sendGetStatus() }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun mergeGroupsIfNeeded(groups: List<tech.capullo.audio.snapcast.Group>) {
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
                                        volume = tech.capullo.audio.snapcast.Volume(muted, percent),
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

    // Reset controls (broadcaster only). "Reset" = stereo / 100% / 0ms latency.
    private suspend fun resetClientToDefaults(clientId: String) {
        changeClientChannelInternal(clientId, "stereo")
        adjustClientVolume(clientId, 100, false)
        adjustClientLatency(clientId, 0)
    }

    // Reset forgets this device's saved spatial role so it sticks next launch. Only THIS device's
    // persistence is cleared - remote devices restore their own saved config on next reconnect.
    private suspend fun clearOwnPersistence() {
        savedVol = 100
        savedLat = 0
        lastPersistedVol = 100
        lastPersistedLat = 0
        settingsRepository.setSnapclientChannel("stereo")
        settingsRepository.setSnapclientVolume(100)
        settingsRepository.setSnapclientLatency(0)
        _state.update { it.copy(snapclientChannel = "stereo") }
    }

    fun resetSelf() {
        val localId = snapclientProcess?.storedHostId?.takeIf { it.isNotEmpty() }
        scope.launch {
            clearOwnPersistence()
            if (localId != null) resetClientToDefaults(localId)
        }
    }

    fun resetAll() {
        val clients = _state.value.snapcastGroups.flatMap { it.clients }.filter { it.connected }
        scope.launch {
            clearOwnPersistence()
            clients.forEach { resetClientToDefaults(it.id) }
        }
    }

    private fun stopSnapcast() {
        audioFocus.abandon()
        snapcontrolPlugin?.stop()
        snapcontrolPlugin = null
        snapserverNsd?.stop()
        snapserverNsd = null
        snapcastControlJob?.cancel()
        snapcastControlJob = null
        snapcastControl?.close()
        snapcastControl = null
        snapserverJob?.cancel()
        snapserverJob = null
        stopLocalSnapclient()
        snapserverProcess = null
        localChannelTagSet = false
        volLatRestored = false
        volLatApplied = false
    }

    // --- Local snapclient lifecycle (the audible part; the shared AudioFocusController's
    // onPause/onResume callbacks) ---

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
            val sp = snapserverProcess ?: return // broadcast ended meanwhile
            "localhost" to sp.ports.streamPort
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
