package tech.capullo.quantumcast.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Station country → ISO code (for "station time" schedules) and the zone labels shown for them. */
class StationZonesTest {

    private fun station(country: String = "", code: String = "") = RecStation(uuid = "u", name = "n", url = "http://x/", country = country, countryCode = code)

    @Test
    fun `the station's own two-letter code wins over its country name and is upper-cased`() {
        assertEquals("NI", StationZones.countryCode(station(country = "Germany", code = "ni")))
    }

    @Test
    fun `a malformed code is ignored and the country name is used`() {
        assertEquals("NI", StationZones.countryCode(station(country = "Nicaragua", code = "NIC")))
    }

    @Test
    fun `country names match case-insensitively and trimmed`() {
        assertEquals("NI", StationZones.countryCode(station(country = "  nicaragua ")))
    }

    @Test
    fun `radio-browser's official long names resolve through the aliases`() {
        assertEquals("US", StationZones.countryCode(station(country = "The United States Of America")))
        assertEquals("GB", StationZones.countryCode(station(country = "The United Kingdom Of Great Britain And Northern Ireland")))
        assertEquals("TR", StationZones.countryCode(station(country = "Türkiye")))
    }

    @Test
    fun `a leading 'The' is dropped when only the short name is known`() {
        assertEquals("BS", StationZones.countryCode(station(country = "The Bahamas")))
    }

    @Test
    fun `unknown or missing country yields null`() {
        assertNull(StationZones.countryCode(station(country = "Atlantis")))
        assertNull(StationZones.countryCode(station()))
    }

    @Test
    fun `label shows whole-hour, half-hour and quarter-hour offsets with a real minus sign`() {
        // Zones without DST, so the offset "in force now" is fixed.
        assertEquals("Managua (UTC\u22126)", StationZones.label("America/Managua"))
        assertEquals("Kolkata (UTC+5:30)", StationZones.label("Asia/Kolkata"))
        assertEquals("Kathmandu (UTC+5:45)", StationZones.label("Asia/Kathmandu"))
        assertEquals("Marquesas (UTC\u22129:30)", StationZones.label("Pacific/Marquesas"))
    }

    @Test
    fun `city keeps sub-regions and replaces underscores`() {
        assertEquals("Indiana/Knox", StationZones.city("America/Indiana/Knox"))
        assertEquals("Buenos Aires", StationZones.city("America/Buenos_Aires"))
    }

    @Test
    fun `label for an unknown zone id does not crash`() {
        assertEquals("Nowhere (UTC+0)", StationZones.label("Mars/Nowhere"))
    }
}
