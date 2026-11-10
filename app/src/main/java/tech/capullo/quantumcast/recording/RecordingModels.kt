package tech.capullo.quantumcast.recording

import android.net.Uri
import kotlinx.serialization.Serializable
import java.time.DayOfWeek

/** The bits of a station a recording needs; persisted inside schedules. */
@Serializable
data class RecStation(
    val uuid: String,
    val name: String,
    val url: String,
    val favicon: String = "",
    /** Country name as radio-browser spells it ("Nicaragua"); for "station time" schedules. */
    val country: String = "",
    /** ISO 3166 code when known (search results have it, favorites don't). */
    val countryCode: String = "",
)

/**
 * A weekly recurring recording. [days] is a bitmask, bit 0 = Monday … bit 6 = Sunday
 * ([DayOfWeek.getValue] - 1). [startMinute] is minutes after midnight in [zone]; the window may
 * cross midnight. Days are counted in that zone too.
 */
@Serializable
data class Schedule(
    val id: String,
    val station: RecStation,
    val days: Int,
    val startMinute: Int,
    val durationMinutes: Int,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    /** Time zone the start time is in; empty = the phone's own ("my time"). */
    val zoneId: String = "",
) {
    fun runsOn(day: DayOfWeek): Boolean = days and (1 shl (day.value - 1)) != 0

    fun zone(): java.time.ZoneId = zoneId.takeIf { it.isNotEmpty() }?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
        ?: java.time.ZoneId.systemDefault()
}

enum class RecState { CONNECTING, RECORDING, RECONNECTING }

/** One recording in progress (in-memory only; lives as long as RecordingService). */
data class ActiveRecording(
    val id: String,
    val station: RecStation,
    val startedAt: Long,
    val endsAt: Long?,
    val state: RecState = RecState.CONNECTING,
    val fileName: String = "",
    val bytes: Long = 0,
    val streamTitle: String = "",
    val scheduleId: String? = null,
)

/** A recording file in the current folder, with what the index knows about it. */
data class RecordingFile(
    val name: String,
    val uri: Uri,
    val size: Long,
    val lastModified: Long,
    val meta: RecMeta,
    val inProgress: Boolean,
) {
    val kbps: Int get() = meta.kbps

    /** Station name; falls back to the file name for files recorded before metadata existed. */
    val stationName: String get() = meta.station.ifEmpty { name.substringBeforeLast('.').substringBeforeLast('_').substringBeforeLast('_') }

    /** Recording start; falls back to the file's own timestamp. */
    val startedAt: Long get() = meta.startedAt.takeIf { it > 0 } ?: lastModified

    /** Duration estimate from size and the bitrate measured while recording; null if unknown. */
    val durationSeconds: Long? get() = if (kbps > 0) size * 8 / (kbps * 1000L) else null
}

/** Thrown for streams we can reach but cannot store (encrypted or fMP4 HLS). Not retried. */
class UnsupportedStreamException(message: String) : Exception(message)
