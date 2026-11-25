package tech.capullo.quantumcast.player

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.capullo.audio.snapcast.StreamMetadata

class PlaybackStateTest {

    @Test
    fun `joining a station drops what the previous stream said about itself`() {
        // What the service holds while broadcasting a radio: its own stream's properties, read back.
        val broadcasting = PlaybackService.PlaybackState(
            stationName = "91.1 KLSU Baton Rouge",
            stationUrl = "https://listen.lsureveille.com/stream",
            isBroadcasting = true,
            icyTitle = "Turnover - Sunshine Type",
            snapcastStationName = "91.1 KLSU Baton Rouge",
            snapcastTrackName = "Sunshine Type",
            snapcastArtistName = "Turnover",
            snapcastStreamArtUrl = "http://192.168.1.20:1680/art.jpg",
            snapcastCountry = "The United States Of America",
            snapcastCountryCode = "US",
            snapcastCodec = "MP3",
            snapcastBitrate = 128,
            snapcastUrl = "https://listen.lsureveille.com/stream",
            snapcastYoutubeUrl = "https://youtu.be/x",
            snapcastSpotifyUrl = "https://open.spotify.com/track/x",
            snapcastAppleMusicUrl = "https://music.apple.com/x",
            snapcastTags = "college,indie",
            snapcastUuid = "klsu",
            snapclientDisplayName = "PFFM10",
            streamCanPlay = true,
            streamCanPause = true,
            streamCanGoNext = true,
            streamCanGoPrevious = true,
        )

        // A station that sends none of these fields must find none of them: the listener's screen
        // is built from this state, and a blank field keeps whatever was there.
        val expected = PlaybackService.PlaybackState(
            stationName = "91.1 KLSU Baton Rouge",
            stationUrl = "https://listen.lsureveille.com/stream",
            isBroadcasting = true,
        )
        assertEquals(expected, broadcasting.withoutRemoteStream())
    }

    @Test
    fun `a station with fewer details replaces the previous one's, it does not inherit them`() {
        // A QuantumCast broadcaster on a radio with every detail and an identified song...
        val full = PlaybackService.PlaybackState().describedBy(
            StreamMetadata(
                title = "Sunshine Type",
                artist = JsonArray(listOf(JsonPrimitive("Turnover"))),
                station = "91.1 KLSU Baton Rouge",
                url = "https://listen.lsureveille.com/stream",
                country = "The United States Of America",
                countryCode = "US",
                codec = "MP3",
                bitrate = 128,
                youtubeUrl = "https://youtu.be/x",
                spotifyUrl = "https://open.spotify.com/track/x",
                appleMusicUrl = "https://music.apple.com/x",
                tags = "college,indie",
                uuid = "klsu",
            ),
            artUrl = "http://192.168.1.20:1680/art/1.jpg",
        )
        // ...then on one that has a name and a title only, and no identified song.
        val bare = full.describedBy(StreamMetadata(title = "Morning Show", station = "Radio Bare"), artUrl = "")

        assertEquals(
            PlaybackService.PlaybackState(
                icyTitle = "Morning Show",
                snapcastTrackName = "Morning Show",
                snapcastStationName = "Radio Bare",
            ),
            bare,
        )
    }

    @Test
    fun `a blank title keeps the last one`() {
        val state = PlaybackService.PlaybackState(icyTitle = "Sunshine Type").describedBy(StreamMetadata(station = "KLSU"), artUrl = "")
        assertEquals("Sunshine Type", state.icyTitle)
    }
}
