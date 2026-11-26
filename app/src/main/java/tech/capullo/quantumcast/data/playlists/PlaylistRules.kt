package tech.capullo.quantumcast.data.playlists

import tech.capullo.source.radiobrowser.data.model.Station
import java.net.URI

/**
 * The Shazam loop re-identifies a song every interval while it plays. A detection within this gap
 * of the row's last one is the same play (only lastDetectedAt moves); a later one is a new play.
 */
const val SAME_PLAY_GAP_MS = 10 * 60_000L

/**
 * SQL for "the YouTube link is a video". SQL cannot call [YoutubeLinks.videoId], so this mirrors it
 * for the links QuantumCast itself stores (watch links from Shazam, a search link otherwise).
 * Shared by the DAO's youtubeCount and the provider's `?link=youtube`.
 */
const val YOUTUBE_VIDEO_LINK_SQL =
    "(youtubeUrl LIKE '%/watch?v=%' OR youtubeUrl LIKE '%youtu.be/%' OR youtubeUrl LIKE '%/shorts/%')"

/**
 * The row after the song was identified again at [now]. A blank new link or artwork keeps the old
 * one. An existing YouTube video link is never replaced, so a curated playlist keeps pointing at
 * the same video; a search link gives way to a video link.
 */
fun PlaylistTrackEntity.detectedAgain(
    youtubeUrl: String,
    spotifyUrl: String,
    appleMusicUrl: String,
    artworkUrl: String,
    now: Long,
): PlaylistTrackEntity = copy(
    youtubeUrl = mergeYoutube(this.youtubeUrl, youtubeUrl),
    spotifyUrl = spotifyUrl.ifBlank { this.spotifyUrl },
    appleMusicUrl = appleMusicUrl.ifBlank { this.appleMusicUrl },
    artworkUrl = artworkUrl.ifBlank { this.artworkUrl },
    lastDetectedAt = maxOf(lastDetectedAt, now),
    detections = if (now - lastDetectedAt > SAME_PLAY_GAP_MS) detections + 1 else detections,
)

private fun mergeYoutube(old: String, new: String): String = when {
    old.isBlank() -> new
    new.isBlank() -> old
    YoutubeLinks.videoId(old) != null -> old
    YoutubeLinks.videoId(new) != null -> new
    else -> old
}

/**
 * The key a station's playlist is stored under, null when it has no stream URL: a recording
 * (uuid `rec:<file>`) or a station this phone only listens to (`snapclient-<host>`) is not a
 * radio station anyone can tune into again.
 */
fun stationKey(station: Station): String? {
    val streamUrl = station.streamUrl
    if (streamUrl.isBlank()) return null
    return station.uuid.ifBlank { streamUrl }
}

object YoutubeLinks {
    private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val PATH_FORMS = setOf("shorts", "embed", "live")
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    /** The 11-character video id of a watch / youtu.be / shorts / embed link, null for a search link or anything else. */
    fun videoId(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val candidate = when {
            host == "youtu.be" -> segments.singleOrNull()
            host !in HOSTS -> null
            segments == listOf("watch") -> uri.rawQuery.orEmpty().split('&')
                .firstOrNull { it.startsWith("v=") }
                ?.removePrefix("v=")
            segments.size == 2 && segments[0] in PATH_FORMS -> segments[1]
            else -> null
        }
        return candidate?.takeIf { VIDEO_ID.matches(it) }
    }
}
