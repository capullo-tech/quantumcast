package tech.capullo.quantumcast.recording

import android.icu.util.TimeZone
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Station country → time zone(s), using Android's built-in ICU country/zone table. */
object StationZones {

    /** radio-browser country names that don't match ICU's English names. */
    private val ALIASES = mapOf(
        "the united states of america" to "US",
        "united states of america" to "US",
        "usa" to "US",
        "the united kingdom of great britain and northern ireland" to "GB",
        "united kingdom" to "GB",
        "the russian federation" to "RU",
        "the netherlands" to "NL",
        "the republic of korea" to "KR",
        "republic of korea" to "KR",
        "the philippines" to "PH",
        "türkiye" to "TR",
        "turkey" to "TR",
        "viet nam" to "VN",
        "iran (islamic republic of)" to "IR",
        "the islamic republic of iran" to "IR",
        "bolivia (plurinational state of)" to "BO",
        "the plurinational state of bolivia" to "BO",
        "venezuela (bolivarian republic of)" to "VE",
        "the bolivarian republic of venezuela" to "VE",
        "taiwan, republic of china" to "TW",
        "the dominican republic" to "DO",
        "the democratic republic of the congo" to "CD",
        "the united arab emirates" to "AE",
        "the czech republic" to "CZ",
        "czechia" to "CZ",
        "the republic of moldova" to "MD",
        "the united republic of tanzania" to "TZ",
        "the lao people's democratic republic" to "LA",
        "the syrian arab republic" to "SY",
    )

    private val byName: Map<String, String> by lazy {
        Locale.getISOCountries().associateBy { Locale("", it).getDisplayCountry(Locale.ENGLISH).lowercase(Locale.ROOT) }
    }

    /** ISO code for a station: its own code if it has one, else matched from the country name. */
    fun countryCode(station: RecStation): String? {
        station.countryCode.takeIf { it.length == 2 }?.let { return it.uppercase(Locale.ROOT) }
        val n = station.country.trim().lowercase(Locale.ROOT)
        if (n.isEmpty()) return null
        return ALIASES[n] ?: byName[n] ?: byName[n.removePrefix("the ")]
    }

    /** The country's time zones (one for most countries, several for US/BR/MX/CA/RU/AU/ID…), west to east. */
    fun zonesFor(countryCode: String): List<String> {
        val ids = TimeZone.getAvailableIDs(TimeZone.SystemTimeZoneType.CANONICAL_LOCATION, countryCode, null)
        val now = Instant.now()
        return ids.filter { runCatching { ZoneId.of(it) }.isSuccess }
            .sortedWith(compareBy({ ZoneId.of(it).rules.getOffset(now).totalSeconds }, { it }))
    }

    fun zonesFor(station: RecStation): List<String> = countryCode(station)?.let { zonesFor(it) }.orEmpty()

    /** Every zone, for stations whose country can't be resolved. */
    fun allZones(): List<String> {
        val now = Instant.now()
        return ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .sortedWith(compareBy({ ZoneId.of(it).rules.getOffset(now).totalSeconds }, { it }))
    }

    /** Main zone for multi-zone countries (capital / most populous), else the country's first zone. */
    private val PRIMARY = mapOf(
        "US" to "America/New_York", "CA" to "America/Toronto", "MX" to "America/Mexico_City",
        "BR" to "America/Sao_Paulo", "AR" to "America/Argentina/Buenos_Aires", "CL" to "America/Santiago",
        "EC" to "America/Guayaquil", "RU" to "Europe/Moscow", "AU" to "Australia/Sydney", "NZ" to "Pacific/Auckland",
        "ID" to "Asia/Jakarta", "KZ" to "Asia/Almaty", "MN" to "Asia/Ulaanbaatar", "CD" to "Africa/Kinshasa",
        "ES" to "Europe/Madrid", "PT" to "Europe/Lisbon", "DE" to "Europe/Berlin", "UA" to "Europe/Kyiv",
        "CN" to "Asia/Shanghai", "MY" to "Asia/Kuala_Lumpur", "UZ" to "Asia/Tashkent", "GL" to "America/Nuuk",
    )

    /** The zone a "station time" schedule starts with; user can change it for multi-zone countries. */
    fun defaultZone(station: RecStation): String? {
        val zones = zonesFor(station)
        val code = countryCode(station) ?: return zones.firstOrNull()
        return PRIMARY[code]?.takeIf { it in zones } ?: zones.firstOrNull()
    }

    /** "Managua" from "America/Managua"; "Indiana/Knox" keeps its region. */
    fun city(zoneId: String): String = zoneId.substringAfter('/').replace('_', ' ')

    /** "Chicago (UTC−6)" with the offset in force now. */
    fun label(zoneId: String): String {
        val secs = runCatching { ZoneId.of(zoneId).rules.getOffset(Instant.now()).totalSeconds }.getOrDefault(0)
        val sign = if (secs < 0) "−" else "+"
        val a = kotlin.math.abs(secs)
        val off = if (a % 3600 == 0) "${a / 3600}" else "%d:%02d".format(a / 3600, a / 60 % 60)
        return "${city(zoneId)} (UTC$sign$off)"
    }
}
