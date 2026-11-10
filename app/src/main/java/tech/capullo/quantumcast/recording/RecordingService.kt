package tech.capullo.quantumcast.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tech.capullo.quantumcast.MainActivity
import java.time.LocalDateTime
import java.util.UUID

/**
 * Foreground service that owns every recording in progress (several may run at once, one
 * connection each). Separate from PlaybackService on purpose: it never touches the audio engine,
 * Snapcast or audio focus, and it keeps running when the task is swiped away.
 *
 * Type mediaPlayback: no runtime cap (dataSync is capped at 6 h/day on Android 15), and the
 * closest declared type to a radio DVR. Holds a partial wake lock + Wi-Fi lock while recording.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private lateinit var repo: RecordingRepository
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var tickerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = recordingRepository()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() demands startForeground() promptly, even for a stop command.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        when (intent?.action) {
            ACTION_START -> {
                val station = intent.getStringExtra(EXTRA_STATION)?.let { Json.decodeFromString<RecStation>(it) }
                val endAt = intent.getLongExtra(EXTRA_END_AT, 0L).takeIf { it > 0 }
                val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID)
                if (station != null) start(station, endAt, scheduleId)
            }
            ACTION_STOP -> intent.getStringExtra(EXTRA_ID)?.let { stop(it) }
            ACTION_STOP_ALL -> jobs.keys.toList().forEach { stop(it) }
        }
        stopIfIdle()
        return START_NOT_STICKY
    }

    private fun start(station: RecStation, endAt: Long?, scheduleId: String?) {
        // Same station already recording: extend a scheduled end rather than open a second file.
        repo.active.value.values.firstOrNull { it.station.uuid == station.uuid && station.uuid.isNotEmpty() }?.let { cur ->
            if (cur.endsAt != null && (endAt == null || endAt > cur.endsAt)) repo.updateActive(cur.id) { it.copy(endsAt = endAt) }
            return
        }
        val id = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        repo.putActive(ActiveRecording(id, station, startedAt, endAt, scheduleId = scheduleId))
        acquireLocks()
        jobs[id] = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var fileName = ""
            var steadyAt = 0L
            var steadyBytes = 0L
            try {
                val store = repo.store()
                val recorder = StreamRecorder(
                    station,
                    openOutput = { ext, mime -> store.create(station.name, ext, mime, LocalDateTime.now()) },
                    onEvent = { e ->
                        when (e) {
                            is StreamRecorder.Event.State -> repo.updateActive(id) { it.copy(state = e.state) }
                            is StreamRecorder.Event.Progress -> {
                                repo.updateActive(id) { it.copy(bytes = e.bytes) }
                                if (steadyAt == 0L && System.currentTimeMillis() - startedAt >= STEADY_AFTER_MS) {
                                    steadyAt = System.currentTimeMillis()
                                    steadyBytes = e.bytes
                                }
                            }
                            is StreamRecorder.Event.Title -> repo.updateActive(id) { it.copy(streamTitle = e.title) }
                            is StreamRecorder.Event.FileOpened -> {
                                fileName = e.name
                                repo.updateActive(id) { it.copy(fileName = e.name) }
                                // Index immediately: a hard kill must still leave the file listed and rotatable.
                                scope.launch { repo.indexFile(e.name, RecMeta(0, station.name, station.favicon, startedAt)); repo.filesChanged() }
                            }
                        }
                    },
                )
                // The end time can move (a schedule extending a recording of the same station), so the
                // end is enforced here, not inside the recorder: cancelling closes the file cleanly.
                val watcher = launch {
                    while (isActive) {
                        delay(1_000)
                        val end = repo.active.value[id]?.endsAt
                        if (end != null && System.currentTimeMillis() >= end) jobs[id]?.cancel()
                    }
                }
                recorder.run(null)
                watcher.cancel()
            } catch (e: UnsupportedStreamException) {
                RecordingScheduler.alert(this@RecordingService, id.hashCode(), "Cannot record ${station.name}", e.message ?: "")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // stopped
            } catch (e: Exception) {
                Log.e(TAG, "recording ${station.name} failed", e)
                RecordingScheduler.alert(this@RecordingService, id.hashCode(), "Recording failed: ${station.name}", e.message ?: e.javaClass.simpleName)
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val rec = repo.active.value[id]
                    if (scheduleId == RecordingScheduler.TEST_ID) {
                        // Self-test: report, then drop the throwaway file so it never clutters the list.
                        val got = rec?.bytes ?: 0
                        if (got > 0) {
                            RecordingScheduler.alert(this@RecordingService, RecordingScheduler.TEST_ID.hashCode(), "Schedule test passed",
                                "QuantumCast woke up on its own and recorded ${RecordingService.formatBytes(got)}. Scheduled recordings will work on this phone.")
                        } else {
                            RecordingScheduler.alert(this@RecordingService, RecordingScheduler.TEST_ID.hashCode(), "Schedule test: no audio",
                                "QuantumCast woke up on time (auto start is fine), but ${station.name} sent no audio. Try the test with another station.")
                        }
                        if (fileName.isNotEmpty()) {
                            val store = repo.store()
                            store.list(mapOf(fileName to RecMeta()), emptySet()).firstOrNull()?.let { store.delete(it) }
                            repo.unindexFiles(listOf(fileName))
                        }
                    } else if (fileName.isNotEmpty() && rec != null) {
                        // Bitrate from the steady part only: the connect burst (seconds of past audio
                        // delivered at once) would inflate it and make durations read short.
                        val now = System.currentTimeMillis()
                        val kbps = if (steadyAt > 0 && now - steadyAt >= 5_000) {
                            ((rec.bytes - steadyBytes) * 8 / (now - steadyAt)).toInt()
                        } else {
                            (rec.bytes * 8 / (now - startedAt).coerceAtLeast(1000)).toInt()
                        }
                        repo.indexFile(fileName, RecMeta(kbps, station.name, station.favicon, startedAt))
                    }
                    repo.removeActive(id)
                    repo.filesChanged()
                    runCatching { enforceCap() }
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        jobs.remove(id)
                        stopIfIdle()
                    }
                }
            }
        }
        jobs[id]!!.start()
        if (tickerJob == null) tickerJob = scope.launch { ticker() }
    }

    private fun stop(id: String) {
        jobs[id]?.cancel()
    }

    /** Refreshes the notification and enforces the cap once a minute while recordings grow. */
    private suspend fun ticker() {
        var n = 0
        while (true) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
            if (n++ % 12 == 0) enforceCap()
            delay(5_000)
        }
    }

    private suspend fun enforceCap() {
        val prefs = repo.current()
        val deleted = repo.store().enforceCap(prefs.capMb * 1024L * 1024L, prefs.index, repo.activeFileNames())
        if (deleted.isNotEmpty()) {
            repo.unindexFiles(deleted)
            repo.filesChanged()
        }
    }

    private fun stopIfIdle() {
        if (jobs.isNotEmpty()) return
        tickerJob?.cancel()
        tickerJob = null
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuantumCast:recording").apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(mode, "QuantumCast:recording").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun buildNotification(): Notification {
        val recs = repo.active.value.values.sortedBy { it.startedAt }
        val title = when (recs.size) {
            0 -> "Recording"
            1 -> "Recording ${recs[0].station.name}"
            else -> "Recording ${recs.size} stations"
        }
        val now = System.currentTimeMillis()
        val text = recs.joinToString("\n") { r ->
            val state = when (r.state) {
                RecState.CONNECTING -> "connecting…"
                RecState.RECONNECTING -> "reconnecting…"
                RecState.RECORDING -> formatElapsed((now - r.startedAt) / 1000) + " · " + formatBytes(r.bytes)
            }
            val ends = r.endsAt?.let { " · ends " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) }.orEmpty()
            (if (recs.size > 1) "${r.station.name}: " else "") + state + ends
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAll = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "Stop", stopAll)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Deliberately keep recording: unlike playback, a recording outlives the UI.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 4711
        /** Past the connect burst; bitrate is measured from here on. */
        private const val STEADY_AFTER_MS = 15_000L
        private const val ACTION_START = "tech.capullo.quantumcast.recording.START"
        private const val ACTION_STOP = "tech.capullo.quantumcast.recording.STOP"
        private const val ACTION_STOP_ALL = "tech.capullo.quantumcast.recording.STOP_ALL"
        private const val EXTRA_STATION = "station"
        private const val EXTRA_END_AT = "end_at"
        private const val EXTRA_SCHEDULE_ID = "schedule_id"
        private const val EXTRA_ID = "id"

        fun startIntent(context: Context, station: RecStation, endAt: Long?, scheduleId: String?): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_STATION, Json.encodeToString(station))
                .putExtra(EXTRA_END_AT, endAt ?: 0L)
                .putExtra(EXTRA_SCHEDULE_ID, scheduleId)

        fun stopIntent(context: Context, id: String): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id)

        fun formatElapsed(s: Long): String =
            if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

        fun formatBytes(b: Long): String = when {
            b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / 1024.0 / 1024 / 1024)
            b >= 1024L * 1024 -> "%.1f MB".format(b / 1024.0 / 1024)
            else -> "${b / 1024} KB"
        }
    }
}
