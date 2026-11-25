package tech.capullo.quantumcast.player

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
