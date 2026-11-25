package tech.capullo.quantumcast.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import tech.capullo.quantumcast.player.PlaybackService.PlaybackState
import tech.capullo.source.radiobrowser.data.model.Station

class ListeningStationTest {

    private val host = "192.168.1.20"
    private val placeholder = Station(uuid = "snapclient-$host", name = host)

    private val onKlsu = PlaybackState(
        snapclientHost = host,
        snapclientDisplayName = "Kitchen",
        snapcastStationName = "91.1 KLSU Baton Rouge",
        snapcastCountry = "The United States Of America",
        snapcastCountryCode = "US",
        snapcastCodec = "MP3",
        snapcastBitrate = 128,
        snapcastUrl = "https://listen.lsureveille.com/stream",
        snapcastStreamArtUrl = "http://192.168.1.20:1680/art/1.jpg",
        snapcastTags = "college,indie",
        snapcastUuid = "klsu",
    )

    @Test
    fun `the placeholder stays until the stream says something about itself`() {
        assertSame(placeholder, listeningStation(placeholder, PlaybackState(snapclientHost = host)))
    }

    @Test
    fun `the stream's details are what is shown`() {
        assertEquals(
            Station(
                uuid = "klsu",
                name = "Kitchen - 91.1 KLSU Baton Rouge",
                url = "https://listen.lsureveille.com/stream",
                favicon = "http://192.168.1.20:1680/art/1.jpg",
                country = "The United States Of America",
                countryCode = "US",
                tags = "college,indie",
                codec = "MP3",
                bitrate = 128,
            ),
            listeningStation(placeholder, onKlsu),
        )
    }

    @Test
    fun `the next station inherits nothing from the one shown before it`() {
        // The broadcaster moves to a station that sends a name only. What the screen showed for
        // KLSU is the input here, and none of it may survive: not the details, and not the id,
        // or the favourite button would save KLSU under the new name.
        val shownForKlsu = listeningStation(placeholder, onKlsu)
        val onBare = PlaybackState(snapclientHost = host, snapclientDisplayName = "Kitchen", snapcastStationName = "Radio Bare")
        assertEquals(
            Station(uuid = "snapclient-$host", name = "Kitchen - Radio Bare"),
            listeningStation(shownForKlsu, onBare),
        )
    }
}
