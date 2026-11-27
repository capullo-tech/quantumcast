package tech.capullo.quantumcast.ui.player

import tech.capullo.quantumcast.player.PlaybackService
import tech.capullo.source.radiobrowser.data.model.Station

/**
 * The station the player screen shows while this device listens to another one. Until the remote
 * stream says something about itself that is [current], the placeholder named after the host that
 * the join set. After that it is built from the service's state alone: [current] is the previous
 * result of this function, and falling back to it for a blank field kept the previous station's
 * country, codec, URL, tags and favourite id on screen under the next one.
 */
internal fun listeningStation(current: Station?, service: PlaybackService.PlaybackState): Station? {
    val server = service.snapclientDisplayName
    val station = service.snapcastStationName
    val name = when {
        server.isNotBlank() && station.isNotBlank() -> "$server - $station"
        server.isNotBlank() -> server
        else -> station
    }
    if (name.isBlank() && service.snapcastCountry.isBlank()) return current
    return Station(
        // The placeholder's id and name stand in while the stream has not given its own.
        uuid = service.snapcastUuid.ifBlank { "snapclient-${service.snapclientHost}" },
        name = name.ifBlank { service.snapclientHost },
        url = service.snapcastUrl,
        favicon = service.snapcastStreamArtUrl,
        country = service.snapcastCountry,
        countryCode = service.snapcastCountryCode,
        tags = service.snapcastTags,
        codec = service.snapcastCodec,
        bitrate = service.snapcastBitrate,
    )
}
