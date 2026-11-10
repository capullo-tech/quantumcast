package tech.capullo.quantumcast.recording

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recordingStore: DataStore<Preferences> by preferencesDataStore(name = "recording")

/** What we know about a recording file. Its presence in the index is also the ownership mark rotation obeys. */
@Serializable
data class RecMeta(
    /** Measured while recording (0 = unknown / still recording). */
    val kbps: Int = 0,
    val station: String = "",
    val favicon: String = "",
    val startedAt: Long = 0,
)

/** Where playback of a recording stopped, and when (smart rewind measures the gap from [savedAt]). */
@Serializable
data class ResumePoint(val posMs: Long, val savedAt: Long)

data class RecordingPrefs(
    /** 0 = no cap. */
    val capMb: Int = DEFAULT_CAP_MB,
    /** Persisted SAF tree URI; empty = the app's own folder. */
    val folderUri: String = "",
    val schedules: List<Schedule> = emptyList(),
    val index: Map<String, RecMeta> = emptyMap(),
    /** file name → resume point. */
    val resume: Map<String, ResumePoint> = emptyMap(),
    /** schedule id → occurrence start (epoch ms) last started. Prevents double starts. */
    val lastStarted: Map<String, Long> = emptyMap(),
    val lastMissed: Map<String, Long> = emptyMap(),
    /** Pending schedule self-test (station + alarm time), null/0 when none. */
    val test: RecStation? = null,
    val testAt: Long = 0,
    /** Process that armed the test; a wake-up in the same process proves nothing. */
    val testPid: Int = 0,
    /** The one-time "this phone needs auto start" warning was shown. */
    val oemWarned: Boolean = false,
    // --- playback of recordings ---
    val skipSeconds: Int = 5,
    val smartRewind: Boolean = true,
    val smartRewindLimitSeconds: Int = 30,
    val pitchCorrection: Boolean = true,
    /** Current speed, shared by all recordings. */
    val speed: Float = 1f,
    /** Last non-1× speed; long-press play toggles 1× ⇄ this. */
    val customSpeed: Float = 1.5f,
) {
    val treeUri: Uri? get() = folderUri.takeIf { it.isNotEmpty() }?.let(Uri::parse)

    companion object {
        const val DEFAULT_CAP_MB = 1024

        /** Choices for both the skip interval and the smart rewind limit, in seconds. */
        val INTERVAL_CHOICES = listOf(2, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 300)

        fun intervalLabel(s: Int): String = when {
            s < 60 -> "$s s"
            s % 60 == 0 -> "${s / 60} min"
            else -> "${s / 60} min ${s % 60} s"
        }
    }
}

/**
 * Single source of truth for recording: persisted prefs (cap, folder, schedules, file index,
 * resume points, playback options) and the in-memory set of recordings in progress, shared by
 * RecordingService, the scheduler, the player and the UI (same process).
 */
@Singleton
class RecordingRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val CAP_MB = intPreferencesKey("cap_mb")
        val FOLDER = stringPreferencesKey("folder_uri")
        val SCHEDULES = stringPreferencesKey("schedules")
        val FILES = stringPreferencesKey("files")
        val RESUME = stringPreferencesKey("resume")
        val LAST_STARTED = stringPreferencesKey("last_started")
        val LAST_MISSED = stringPreferencesKey("last_missed")
        val TEST = stringPreferencesKey("test_station")
        val TEST_AT = longPreferencesKey("test_at")
        val OEM_WARNED = booleanPreferencesKey("oem_warned")
        val TEST_PID = intPreferencesKey("test_pid")
        val SKIP_SECONDS = intPreferencesKey("skip_seconds")
        val SMART_REWIND = booleanPreferencesKey("smart_rewind")
        val SMART_REWIND_LIMIT = intPreferencesKey("smart_rewind_limit")
        val PITCH_CORRECTION = booleanPreferencesKey("pitch_correction")
        val SPEED = floatPreferencesKey("speed")
        val CUSTOM_SPEED = floatPreferencesKey("custom_speed")
    }

    private inline fun <reified T> Preferences.decode(key: Preferences.Key<String>, default: T): T =
        this[key]?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() } ?: default

    val prefs: Flow<RecordingPrefs> = context.recordingStore.data.map { p ->
        RecordingPrefs(
            capMb = p[Keys.CAP_MB] ?: RecordingPrefs.DEFAULT_CAP_MB,
            folderUri = p[Keys.FOLDER] ?: "",
            schedules = p.decode(Keys.SCHEDULES, emptyList()),
            index = p.decode(Keys.FILES, emptyMap()),
            resume = p.decode(Keys.RESUME, emptyMap()),
            lastStarted = p.decode(Keys.LAST_STARTED, emptyMap()),
            lastMissed = p.decode(Keys.LAST_MISSED, emptyMap()),
            test = p[Keys.TEST]?.takeIf { it.isNotEmpty() }?.let { runCatching { json.decodeFromString<RecStation>(it) }.getOrNull() },
            testAt = p[Keys.TEST_AT] ?: 0L,
            testPid = p[Keys.TEST_PID] ?: 0,
            oemWarned = p[Keys.OEM_WARNED] ?: false,
            skipSeconds = p[Keys.SKIP_SECONDS] ?: 5,
            smartRewind = p[Keys.SMART_REWIND] ?: true,
            smartRewindLimitSeconds = p[Keys.SMART_REWIND_LIMIT] ?: 30,
            pitchCorrection = p[Keys.PITCH_CORRECTION] ?: true,
            speed = p[Keys.SPEED] ?: 1f,
            customSpeed = p[Keys.CUSTOM_SPEED] ?: 1.5f,
        )
    }

    suspend fun current(): RecordingPrefs = prefs.first()

    private val _active = MutableStateFlow<Map<String, ActiveRecording>>(emptyMap())
    val active: StateFlow<Map<String, ActiveRecording>> = _active

    /** Bumped whenever files change so the list screen reloads. */
    private val _filesVersion = MutableStateFlow(0)
    val filesVersion: StateFlow<Int> = _filesVersion

    fun putActive(rec: ActiveRecording) = _active.update { it + (rec.id to rec) }
    fun updateActive(id: String, f: (ActiveRecording) -> ActiveRecording) =
        _active.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }
    fun removeActive(id: String) = _active.update { it - id }
    fun filesChanged() = _filesVersion.update { it + 1 }

    fun activeFileNames(): Set<String> = _active.value.values.map { it.fileName }.filter { it.isNotEmpty() }.toSet()

    suspend fun store(): RecordingStore = RecordingStore(context, current().treeUri)

    // --- edits ---

    suspend fun setCapMb(mb: Int) = context.recordingStore.edit { it[Keys.CAP_MB] = mb.coerceAtLeast(0) }

    /** null = back to the app's own folder. Takes/releases the persistable SAF grant. */
    suspend fun setFolder(uri: Uri?) {
        val old = current().treeUri
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (uri != null) context.contentResolver.takePersistableUriPermission(uri, flags)
        if (old != null && old != uri) runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) }
        context.recordingStore.edit { it[Keys.FOLDER] = uri?.toString() ?: "" }
        filesChanged()
    }

    suspend fun indexFile(name: String, meta: RecMeta) = editMap<RecMeta>(Keys.FILES) { it + (name to meta) }

    /** Forgets files (index + resume points), e.g. after deletion. */
    suspend fun unindexFiles(names: Collection<String>) {
        if (names.isEmpty()) return
        val set = names.toSet()
        editMap<RecMeta>(Keys.FILES) { it - set }
        editMap<ResumePoint>(Keys.RESUME) { it - set }
    }

    suspend fun setResume(name: String, posMs: Long) =
        editMap<ResumePoint>(Keys.RESUME) { it + (name to ResumePoint(posMs, System.currentTimeMillis())) }

    suspend fun clearResume(name: String) = editMap<ResumePoint>(Keys.RESUME) { it - name }

    private suspend inline fun <reified V> editMap(key: Preferences.Key<String>, crossinline f: (Map<String, V>) -> Map<String, V>) =
        context.recordingStore.edit { p ->
            val m: Map<String, V> = p[key]?.let { runCatching { json.decodeFromString<Map<String, V>>(it) }.getOrNull() } ?: emptyMap()
            p[key] = json.encodeToString(f(m))
        }

    suspend fun saveSchedule(s: Schedule) = editSchedules { list ->
        if (list.any { it.id == s.id }) list.map { if (it.id == s.id) s else it } else list + s
    }

    suspend fun deleteSchedule(id: String) = editSchedules { list -> list.filterNot { it.id == id } }

    private suspend fun editSchedules(f: (List<Schedule>) -> List<Schedule>) = context.recordingStore.edit { p ->
        p[Keys.SCHEDULES] = json.encodeToString(f(p.decode(Keys.SCHEDULES, emptyList<Schedule>())))
    }

    suspend fun markStarted(scheduleId: String, occurrence: Long) = editMap<Long>(Keys.LAST_STARTED) { it + (scheduleId to occurrence) }
    suspend fun markMissed(scheduleId: String, occurrence: Long) = editMap<Long>(Keys.LAST_MISSED) { it + (scheduleId to occurrence) }

    suspend fun setTest(station: RecStation?, at: Long = 0, pid: Int = 0) = context.recordingStore.edit {
        it[Keys.TEST] = station?.let { s -> json.encodeToString(s) } ?: ""
        it[Keys.TEST_AT] = if (station == null) 0L else at
        it[Keys.TEST_PID] = pid
    }

    suspend fun setOemWarned() = context.recordingStore.edit { it[Keys.OEM_WARNED] = true }

    // --- playback options ---

    suspend fun setSkipSeconds(s: Int) = context.recordingStore.edit { it[Keys.SKIP_SECONDS] = s }
    suspend fun setSmartRewind(on: Boolean) = context.recordingStore.edit { it[Keys.SMART_REWIND] = on }
    suspend fun setSmartRewindLimit(s: Int) = context.recordingStore.edit { it[Keys.SMART_REWIND_LIMIT] = s }
    suspend fun setPitchCorrection(on: Boolean) = context.recordingStore.edit { it[Keys.PITCH_CORRECTION] = on }

    /** Sets the speed; a non-1× value also becomes the long-press toggle target. */
    suspend fun setSpeed(speed: Float) = context.recordingStore.edit {
        val s = (Math.round(speed * 100) / 100f).coerceIn(MIN_SPEED, MAX_SPEED)
        it[Keys.SPEED] = s
        if (s != 1f) it[Keys.CUSTOM_SPEED] = s
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 4f
    }
}
