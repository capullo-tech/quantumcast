package tech.capullo.quantumcast.data.playlists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.capullo.source.radiobrowser.data.model.Station

/** The merge and key rules a detection goes through before it reaches the playlists database. */
class PlaylistRulesTest {

    private val video = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    private val search = "https://www.youtube.com/results?search_query=rick+astley"

    private fun track(youtubeUrl: String = "", lastDetectedAt: Long = 1_000_000L) = PlaylistTrackEntity(
        id = 7,
        stationId = 3,
        title = "Never Gonna Give You Up",
        artist = "Rick Astley",
        artworkUrl = "",
        youtubeUrl = youtubeUrl,
        spotifyUrl = "",
        appleMusicUrl = "",
        firstDetectedAt = lastDetectedAt,
        lastDetectedAt = lastDetectedAt,
        detections = 1,
    )

    private fun detected(row: PlaylistTrackEntity, youtubeUrl: String = "", now: Long = row.lastDetectedAt) = row.detectedAgain(youtubeUrl = youtubeUrl, spotifyUrl = "", appleMusicUrl = "", artworkUrl = "", now = now)

    @Test
    fun `a detection exactly at the gap is the same play`() {
        val row = track()
        val again = detected(row, now = row.lastDetectedAt + SAME_PLAY_GAP_MS)
        assertEquals(1, again.detections)
        assertEquals(row.lastDetectedAt + SAME_PLAY_GAP_MS, again.lastDetectedAt)
    }

    @Test
    fun `a detection past the gap is a new play`() {
        val row = track()
        assertEquals(2, detected(row, now = row.lastDetectedAt + SAME_PLAY_GAP_MS + 1).detections)
    }

    @Test
    fun `an existing video link is kept even when another video arrives`() {
        assertEquals(video, detected(track(youtubeUrl = video), "https://youtu.be/oHg5SJYRHA0").youtubeUrl)
        assertEquals(video, detected(track(youtubeUrl = video), search).youtubeUrl)
    }

    @Test
    fun `a search link gives way to a video link`() {
        assertEquals(video, detected(track(youtubeUrl = search), video).youtubeUrl)
    }

    @Test
    fun `a blank new link keeps the stored one`() {
        assertEquals(search, detected(track(youtubeUrl = search), "").youtubeUrl)
    }

    @Test
    fun `a station with a uuid is keyed by it`() {
        assertEquals("uuid-1", stationKey(Station(uuid = "uuid-1", url = "http://radio.example/stream")))
    }

    @Test
    fun `a station without a uuid is keyed by its stream url`() {
        assertEquals(
            "http://radio.example/fallback",
            stationKey(Station(urlFallback = "http://radio.example/fallback")),
        )
    }

    @Test
    fun `a recording and a listened station have no key`() {
        assertNull(stationKey(Station(uuid = "rec:2026-11-25_station.mp3", name = "Recording")))
        assertNull(stationKey(Station(uuid = "snapclient-192.168.1.20", name = "Living room")))
    }

    @Test
    fun `video ids come from watch, youtu_be and music links`() {
        assertEquals("dQw4w9WgXcQ", YoutubeLinks.videoId(video))
        assertEquals("dQw4w9WgXcQ", YoutubeLinks.videoId("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("dQw4w9WgXcQ", YoutubeLinks.videoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RD"))
    }

    @Test
    fun `a search link and a wrong length id have no video id`() {
        assertNull(YoutubeLinks.videoId(search))
        assertNull(YoutubeLinks.videoId("https://www.youtube.com/watch?v=dQw4w9WgXc"))
        assertNull(YoutubeLinks.videoId("https://youtu.be/dQw4w9WgXcQQ"))
    }
}
