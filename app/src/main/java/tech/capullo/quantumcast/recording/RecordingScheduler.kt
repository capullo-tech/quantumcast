package tech.capullo.quantumcast.recording

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tech.capullo.quantumcast.MainActivity
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecordingEntryPoint {
    fun recordingRepository(): RecordingRepository
}

fun Context.recordingRepository(): RecordingRepository = EntryPointAccessors.fromApplication(applicationContext, RecordingEntryPoint::class.java).recordingRepository()

/**
 * Turns schedules into exact alarms. One alarm per enabled schedule, always for its next
 * occurrence; firing re-arms the following one. setAlarmClock fires in Doze, and an exact alarm
 * firing is an exemption from the background foreground-service start ban (Android 12+).
 *
 * Alarms do not survive reboot, force-stop, time/zone changes or permission revocation, so
 * [armAll] runs on all of those (ScheduleRearmReceiver) and on every app start. If we are
 * already inside a window that never started (reboot mid-show), it arms a near-immediate alarm
 * rather than starting the service directly: Android 15 forbids starting a mediaPlayback
 * service from BOOT_COMPLETED, but an exact alarm may.
 */
object RecordingScheduler {
    private const val TAG = "RecordingScheduler"
    private const val ALERT_CHANNEL = "recording_alerts"

    // All occurrence math runs in the schedule's own zone (the phone's, or the station's), so
    // "19:00 Managua" stays 19:00 Managua across either side's DST change, and weekdays are the
    // station's weekdays. Results are absolute instants; callers may convert as they like.

    private fun startOn(s: Schedule, date: java.time.LocalDate, zone: ZoneId): ZonedDateTime = ZonedDateTime.of(date, LocalTime.of(s.startMinute / 60, s.startMinute % 60), zone)

    fun nextStart(s: Schedule, now: ZonedDateTime): ZonedDateTime? {
        val n = now.withZoneSameInstant(s.zone())
        for (d in 0..7L) {
            val date = n.toLocalDate().plusDays(d)
            if (!s.runsOn(date.dayOfWeek)) continue
            val start = startOn(s, date, n.zone)
            if (start.isAfter(n)) return start
        }
        return null
    }

    /** Start of the window [now] falls in, if any (today's or yesterday's, for windows past midnight). */
    fun currentWindowStart(s: Schedule, now: ZonedDateTime): ZonedDateTime? {
        val n = now.withZoneSameInstant(s.zone())
        for (d in 0..1L) {
            val date = n.toLocalDate().minusDays(d)
            if (!s.runsOn(date.dayOfWeek)) continue
            val start = startOn(s, date, n.zone)
            if (!start.isAfter(n) && n.isBefore(start.plusMinutes(s.durationMinutes.toLong()))) return start
        }
        return null
    }

    /** Start of the most recent occurrence that has fully ended. */
    fun lastEndedStart(s: Schedule, now: ZonedDateTime): ZonedDateTime? {
        val n = now.withZoneSameInstant(s.zone())
        for (d in 0..8L) {
            val date = n.toLocalDate().minusDays(d)
            if (!s.runsOn(date.dayOfWeek)) continue
            val start = startOn(s, date, n.zone)
            if (!start.plusMinutes(s.durationMinutes.toLong()).isAfter(n)) return start
        }
        return null
    }

    suspend fun armAll(context: Context) {
        val repo = context.recordingRepository()
        val prefs = repo.current()
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val running = repo.active.value.values.mapNotNull { it.scheduleId }.toSet()
        for (s in prefs.schedules) {
            cancel(context, s.id)
            if (!s.enabled || s.days == 0) continue
            val window = currentWindowStart(s, now)?.toInstant()?.toEpochMilli()
            if (window != null && prefs.lastStarted[s.id] != window && s.id !in running && window >= s.createdAt - 60_000) {
                set(context, s.id, window, System.currentTimeMillis() + 2_000)
                continue
            }
            val next = nextStart(s, now) ?: continue
            val at = next.toInstant().toEpochMilli()
            set(context, s.id, at, at)
        }
    }

    fun cancel(context: Context, scheduleId: String) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, scheduleId, 0))
    }

    fun canScheduleExact(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun set(context: Context, scheduleId: String, occurrence: Long, triggerAt: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context, scheduleId, occurrence)
        if (canScheduleExact(context)) {
            val show = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        Log.i(TAG, "armed $scheduleId for ${Instant.ofEpochMilli(triggerAt)} (occurrence ${Instant.ofEpochMilli(occurrence)})")
    }

    private fun alarmIntent(context: Context, scheduleId: String, occurrence: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        scheduleId.hashCode(),
        Intent(context, ScheduleAlarmReceiver::class.java)
            .putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            .putExtra(EXTRA_OCCURRENCE, occurrence),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Reports occurrences that ended without ever starting (phone off, app force-stopped…). */
    suspend fun reportMissed(context: Context) {
        val repo = context.recordingRepository()
        val prefs = repo.current()
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        prefs.testAt.takeIf { it > 0 && System.currentTimeMillis() > it + 30_000 }?.let {
            repo.setTest(null)
            alert(
                context,
                TEST_ID.hashCode(),
                "Schedule test failed",
                "QuantumCast was not woken up at the test time, so scheduled recordings will not run. " +
                    "Allow auto start for QuantumCast (Recordings → Storage shows where), then test again.",
            )
        }
        for (s in prefs.schedules) {
            if (!s.enabled) continue
            val last = lastEndedStart(s, now)?.toInstant()?.toEpochMilli() ?: continue
            if (last < s.createdAt) continue
            if ((prefs.lastStarted[s.id] ?: 0) >= last || (prefs.lastMissed[s.id] ?: 0) >= last) continue
            repo.markMissed(s.id, last)
            alert(
                context,
                s.id.hashCode(),
                "Missed recording: ${s.station.name}",
                "The scheduled recording did not run (phone off, or the app was force-stopped). Check Recordings → Storage for the reliability checklist.",
            )
        }
    }

    fun alert(context: Context, id: Int, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Recording alerts", NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            "rec-alert",
            id,
            NotificationCompat.Builder(context, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    const val EXTRA_SCHEDULE_ID = "schedule_id"
    const val EXTRA_OCCURRENCE = "occurrence"

    // --- self-test: the only way to learn whether the vendor auto-start switch lets us wake ---

    const val TEST_ID = "selftest"
    const val TEST_DELAY_MS = 60_000L
    const val TEST_LENGTH_MS = 20_000L

    /**
     * Arms a one-off alarm [TEST_DELAY_MS] ahead that records [station] for [TEST_LENGTH_MS].
     * The caller then kills the process, exactly like a swipe-away/OEM kill: if the vendor blocks
     * the wake-up, nothing runs and [reportMissed] reports the failure on the next app start.
     */
    suspend fun armTest(context: Context, station: RecStation) {
        val at = System.currentTimeMillis() + TEST_DELAY_MS
        context.recordingRepository().setTest(station, at, android.os.Process.myPid())
        set(context, TEST_ID, at, at)
    }
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** A schedule's start time arrived: start the recording, then arm the next occurrence. */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(RecordingScheduler.EXTRA_SCHEDULE_ID) ?: return
        val occurrence = intent.getLongExtra(RecordingScheduler.EXTRA_OCCURRENCE, 0L)
        val pending = goAsync()
        receiverScope.launch {
            try {
                val repo = context.recordingRepository()
                val prefs = repo.current()
                if (id == RecordingScheduler.TEST_ID) {
                    // Self-test: reaching this line from a killed process is the whole point.
                    val st = prefs.test ?: return@launch
                    repo.setTest(null)
                    if (prefs.testPid == android.os.Process.myPid()) {
                        // The app was never closed, so this proves nothing about waking up.
                        RecordingScheduler.alert(
                            context,
                            id.hashCode(),
                            "Schedule test inconclusive",
                            "QuantumCast was still running at the test time, so the wake-up wasn't tested. Run the test again.",
                        )
                        return@launch
                    }
                    try {
                        ContextCompat.startForegroundService(
                            context,
                            RecordingService.startIntent(context, st, System.currentTimeMillis() + RecordingScheduler.TEST_LENGTH_MS, id),
                        )
                    } catch (e: Exception) {
                        RecordingScheduler.alert(context, id.hashCode(), "Schedule test failed", e.message ?: e.javaClass.simpleName)
                    }
                    return@launch
                }
                val s = prefs.schedules.firstOrNull { it.id == id }
                val endAt = occurrence + (s?.durationMinutes ?: 0) * 60_000L
                if (s != null && s.enabled && System.currentTimeMillis() < endAt && prefs.lastStarted[id] != occurrence) {
                    try {
                        ContextCompat.startForegroundService(context, RecordingService.startIntent(context, s.station, endAt, s.id))
                        repo.markStarted(id, occurrence)
                    } catch (e: Exception) {
                        // ForegroundServiceStartNotAllowedException and friends: tell the user now.
                        Log.e("ScheduleAlarmReceiver", "start failed", e)
                        RecordingScheduler.alert(context, id.hashCode(), "Recording could not start: ${s.station.name}", e.message ?: e.javaClass.simpleName)
                    }
                }
                RecordingScheduler.armAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Boot, app update, clock/zone change, exact-alarm permission change: alarms must be re-armed. */
class ScheduleRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exported (system broadcasts need it), so any app can target it explicitly. Act only on
        // the actions the manifest lists; those are protected broadcasts only the system can send.
        if (intent.action !in REARM_ACTIONS) return
        val pending = goAsync()
        receiverScope.launch {
            try {
                RecordingScheduler.armAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}

private val REARM_ACTIONS = setOf(
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_MY_PACKAGE_REPLACED,
    Intent.ACTION_TIME_CHANGED,
    Intent.ACTION_TIMEZONE_CHANGED,
    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
)
