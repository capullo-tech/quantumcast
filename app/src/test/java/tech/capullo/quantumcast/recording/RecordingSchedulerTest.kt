package tech.capullo.quantumcast.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/** Occurrence math for weekly schedules: weekday bitmask, windows past midnight, zones, DST. */
class RecordingSchedulerTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val managua = ZoneId.of("America/Managua") // UTC-6, no DST

    private fun schedule(days: Set<DayOfWeek>, start: String, minutes: Int, zone: ZoneId) = Schedule(
        id = "s",
        station = RecStation(uuid = "u", name = "Station", url = "http://example.invalid/"),
        days = days.fold(0) { m, d -> m or (1 shl (d.value - 1)) },
        startMinute = start.substringBefore(':').toInt() * 60 + start.substringAfter(':').toInt(),
        durationMinutes = minutes,
        createdAt = 0,
        zoneId = zone.id,
    )

    // 2026-11-16 is a Monday.
    private fun at(iso: String, zone: ZoneId): ZonedDateTime = ZonedDateTime.of(java.time.LocalDateTime.parse(iso), zone)

    @Test
    fun `days bitmask is bit 0 Monday through bit 6 Sunday`() {
        val monOnly = schedule(setOf(DayOfWeek.MONDAY), "08:00", 60, berlin).copy(days = 0b0000001)
        assertTrue(monOnly.runsOn(DayOfWeek.MONDAY))
        assertFalse(monOnly.runsOn(DayOfWeek.TUESDAY))
        assertFalse(monOnly.runsOn(DayOfWeek.SUNDAY))

        val sunOnly = monOnly.copy(days = 0b1000000)
        assertTrue(sunOnly.runsOn(DayOfWeek.SUNDAY))
        assertFalse(sunOnly.runsOn(DayOfWeek.SATURDAY))
        assertFalse(sunOnly.runsOn(DayOfWeek.MONDAY))
    }

    @Test
    fun `nextStart is later today when today's start is still ahead`() {
        val s = schedule(setOf(DayOfWeek.MONDAY), "20:00", 60, berlin)
        assertEquals(at("2026-11-16T20:00", berlin), RecordingScheduler.nextStart(s, at("2026-11-16T19:59", berlin)))
    }

    @Test
    fun `nextStart for a once-a-week schedule already past today is the same weekday next week`() {
        val s = schedule(setOf(DayOfWeek.MONDAY), "08:00", 60, berlin)
        assertEquals(at("2026-11-23T08:00", berlin), RecordingScheduler.nextStart(s, at("2026-11-16T09:00", berlin)))
    }

    @Test
    fun `nextStart is strictly after now - at the exact start it moves on to the next occurrence`() {
        val s = schedule(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), "08:00", 60, berlin)
        assertEquals(at("2026-11-18T08:00", berlin), RecordingScheduler.nextStart(s, at("2026-11-16T08:00", berlin)))
    }

    @Test
    fun `nextStart returns null for a schedule with no days`() {
        val s = schedule(emptySet(), "08:00", 60, berlin)
        assertNull(RecordingScheduler.nextStart(s, at("2026-11-16T07:00", berlin)))
    }

    @Test
    fun `station-time schedule uses the station's weekday and wall clock, not the phone's`() {
        // Monday 19:00 in Managua is Tuesday 02:00 in Berlin (CET, UTC+1).
        val s = schedule(setOf(DayOfWeek.MONDAY), "19:00", 60, managua)
        val phoneNow = at("2026-11-17T01:30", berlin) // Tuesday for the phone, Monday 18:30 in Managua
        val next = RecordingScheduler.nextStart(s, phoneNow)!!
        assertEquals(at("2026-11-16T19:00", managua).toInstant(), next.toInstant())
        assertEquals(at("2026-11-17T02:00", berlin).toInstant(), next.toInstant())
    }

    @Test
    fun `occurrences keep their wall-clock time across a DST change in the schedule's zone`() {
        // Berlin springs forward on 2026-03-29: 08:00 is 07:00Z the day before, 06:00Z that day.
        val s = schedule(DayOfWeek.entries.toSet(), "08:00", 60, berlin)
        val next = RecordingScheduler.nextStart(s, at("2026-03-28T12:00", berlin))!!
        assertEquals(java.time.Instant.parse("2026-03-29T06:00:00Z"), next.toInstant())
    }

    @Test
    fun `currentWindowStart finds a window that started yesterday and runs past midnight`() {
        val s = schedule(setOf(DayOfWeek.MONDAY), "23:30", 60, berlin)
        assertEquals(at("2026-11-16T23:30", berlin), RecordingScheduler.currentWindowStart(s, at("2026-11-17T00:15", berlin)))
    }

    @Test
    fun `currentWindowStart window is start-inclusive and end-exclusive`() {
        val s = schedule(setOf(DayOfWeek.MONDAY), "23:30", 60, berlin)
        assertEquals(at("2026-11-16T23:30", berlin), RecordingScheduler.currentWindowStart(s, at("2026-11-16T23:30", berlin)))
        assertNull(RecordingScheduler.currentWindowStart(s, at("2026-11-16T23:29:59", berlin)))
        assertNull(RecordingScheduler.currentWindowStart(s, at("2026-11-17T00:30", berlin)))
    }

    @Test
    fun `currentWindowStart ignores a window on a day the schedule does not run`() {
        val s = schedule(setOf(DayOfWeek.TUESDAY), "23:30", 60, berlin)
        assertNull(RecordingScheduler.currentWindowStart(s, at("2026-11-17T00:15", berlin)))
    }

    @Test
    fun `lastEndedStart skips the occurrence still in progress and returns the previous week's`() {
        val s = schedule(setOf(DayOfWeek.MONDAY), "23:30", 60, berlin)
        assertEquals(at("2026-11-09T23:30", berlin), RecordingScheduler.lastEndedStart(s, at("2026-11-17T00:15", berlin)))
    }

    @Test
    fun `lastEndedStart counts an occurrence as ended exactly at its end time`() {
        val s = schedule(setOf(DayOfWeek.MONDAY), "23:30", 60, berlin)
        assertEquals(at("2026-11-16T23:30", berlin), RecordingScheduler.lastEndedStart(s, at("2026-11-17T00:30", berlin)))
    }

    @Test
    fun `an unknown zone id falls back to the phone's zone instead of crashing`() {
        val s = schedule(setOf(DayOfWeek.MONDAY), "08:00", 60, berlin).copy(zoneId = "Mars/Olympus_Mons")
        assertEquals(ZoneId.systemDefault(), s.zone())
    }
}
