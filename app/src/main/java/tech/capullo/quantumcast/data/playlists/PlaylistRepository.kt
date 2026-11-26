package tech.capullo.quantumcast.data.playlists

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import tech.capullo.source.radiobrowser.data.model.Station
import tech.capullo.source.radiobrowser.data.model.TrackLookup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The detection history and the per-station playlists: the same rows, kept until the user removes
 * them. Every write notifies PlaylistProvider's URIs so another app's ContentObserver sees it.
 */
@Singleton
class PlaylistRepository @Inject constructor(@ApplicationContext context: Context) {
    private val resolver = context.contentResolver
    private val dao = PlaylistDatabase.get(context).playlistDao()

    /** Unlimited history, collected only while the Detection History sheet is visible. */
    val history: Flow<List<DetectedTrack>> = dao.history()

    /** Stations that have at least one song, most recently updated first. */
    val playlists: Flow<List<StationPlaylist>> = dao.playlists()

    fun station(stationId: Long): Flow<PlaylistStationEntity?> = dao.station(stationId)

    /** Last detected first. */
    fun tracks(stationId: Long): Flow<List<DetectedTrack>> = dao.tracks(stationId)

    /** No-op for a station with no stream URL (a recording, a station this phone only listens to) or a blank track name. maxSongs <= 0: no cap. */
    suspend fun record(station: Station, track: TrackLookup, maxSongs: Int) {
        val key = stationKey(station) ?: return
        val title = track.trackName.trim()
        if (title.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.record(
            station = PlaylistStationEntity(
                stationKey = key,
                name = station.name,
                country = station.country,
                countryCode = station.countryCode,
                favicon = station.favicon,
                streamUrl = station.streamUrl,
                tags = station.tags,
                codec = station.codec,
                bitrate = station.bitrate,
            ),
            track = PlaylistTrackEntity(
                stationId = 0,
                title = title,
                artist = track.artistName.trim(),
                artworkUrl = track.artworkUrl,
                youtubeUrl = track.youtubeUrl,
                spotifyUrl = track.spotifyUrl,
                appleMusicUrl = track.appleMusicUrl,
                firstDetectedAt = now,
                lastDetectedAt = now,
                detections = 1,
            ),
            maxSongs = maxSongs,
        )
        notifyChanged()
    }

    /** Also drops the station row when it was its last song. */
    suspend fun deleteTrack(trackId: Long) {
        dao.deleteTrack(trackId)
        notifyChanged()
    }

    suspend fun deletePlaylist(stationId: Long) {
        dao.deletePlaylist(stationId)
        notifyChanged()
    }

    suspend fun clear() {
        dao.clear()
        notifyChanged()
    }

    // A notification reaches observers of the URI and of every URI below it, so these two cover
    // playlists/{id} and playlists/{id}/tracks too.
    private fun notifyChanged() {
        resolver.notifyChange(PlaylistContract.PLAYLISTS_URI, null)
        resolver.notifyChange(PlaylistContract.TRACKS_URI, null)
    }
}
