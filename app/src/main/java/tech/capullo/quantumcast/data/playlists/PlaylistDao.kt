package tech.capullo.quantumcast.data.playlists

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PlaylistDao {

    /** Every identified song across all stations, newest first. */
    @Query(
        "SELECT t.*, s.name AS stationName, s.countryCode AS stationCountryCode " +
            "FROM playlist_track t INNER JOIN playlist_station s ON s.id = t.stationId " +
            "ORDER BY t.lastDetectedAt DESC, t.id DESC",
    )
    abstract fun history(): Flow<List<DetectedTrack>>

    @Query(
        "SELECT s.*, COUNT(t.id) AS trackCount, " +
            "SUM(CASE WHEN $YOUTUBE_VIDEO_LINK_SQL THEN 1 ELSE 0 END) AS youtubeCount, " +
            "MAX(t.lastDetectedAt) AS updatedAt " +
            "FROM playlist_station s INNER JOIN playlist_track t ON t.stationId = s.id " +
            "GROUP BY s.id ORDER BY updatedAt DESC, s.id DESC",
    )
    abstract fun playlists(): Flow<List<StationPlaylist>>

    @Query("SELECT * FROM playlist_station WHERE id = :stationId")
    abstract fun station(stationId: Long): Flow<PlaylistStationEntity?>

    @Query(
        "SELECT t.*, s.name AS stationName, s.countryCode AS stationCountryCode " +
            "FROM playlist_track t INNER JOIN playlist_station s ON s.id = t.stationId " +
            "WHERE t.stationId = :stationId " +
            "ORDER BY t.lastDetectedAt DESC, t.id DESC",
    )
    abstract fun tracks(stationId: Long): Flow<List<DetectedTrack>>

    /**
     * Stores one detection. [station] carries the latest metadata (its id is ignored); [track] is
     * the row a first detection would insert (its id and stationId are ignored, lastDetectedAt is
     * the detection time). maxSongs <= 0: no cap.
     */
    @Transaction
    open suspend fun record(station: PlaylistStationEntity, track: PlaylistTrackEntity, maxSongs: Int) {
        val existingStation = stationByKey(station.stationKey)
        val stationId = if (existingStation == null) {
            insertStation(station.copy(id = 0))
        } else {
            // Written only when something changed: an identical row would still invalidate every
            // query on the table, once per identification.
            val latest = station.copy(id = existingStation.id)
            if (latest != existingStation) updateStation(latest)
            existingStation.id
        }
        val existingTrack = trackOf(stationId, track.title, track.artist)
        if (existingTrack == null) {
            insertTrack(track.copy(id = 0, stationId = stationId))
        } else {
            updateTrack(
                existingTrack.detectedAgain(
                    youtubeUrl = track.youtubeUrl,
                    spotifyUrl = track.spotifyUrl,
                    appleMusicUrl = track.appleMusicUrl,
                    artworkUrl = track.artworkUrl,
                    now = track.lastDetectedAt,
                ),
            )
        }
        if (maxSongs > 0) {
            keepNewest(maxSongs)
            deleteEmptyStations()
        }
    }

    /** Also drops the station when this was its last song, so no empty playlist is left behind. */
    @Transaction
    open suspend fun deleteTrack(trackId: Long) {
        deleteTrackRow(trackId)
        deleteEmptyStations()
    }

    /** Deletes the tracks explicitly instead of relying on the foreign key's cascade. */
    @Transaction
    open suspend fun deletePlaylist(stationId: Long) {
        deleteTracksOf(stationId)
        deleteStationRow(stationId)
    }

    @Transaction
    open suspend fun clear() {
        deleteAllTracks()
        deleteAllStations()
    }

    @Query("SELECT * FROM playlist_station WHERE stationKey = :stationKey")
    abstract suspend fun stationByKey(stationKey: String): PlaylistStationEntity?

    @Insert
    abstract suspend fun insertStation(station: PlaylistStationEntity): Long

    @Update
    abstract suspend fun updateStation(station: PlaylistStationEntity)

    @Query("SELECT * FROM playlist_track WHERE stationId = :stationId AND title = :title AND artist = :artist")
    abstract suspend fun trackOf(stationId: Long, title: String, artist: String): PlaylistTrackEntity?

    @Insert
    abstract suspend fun insertTrack(track: PlaylistTrackEntity): Long

    @Update
    abstract suspend fun updateTrack(track: PlaylistTrackEntity)

    /** The cap: the [maxSongs] most recently detected songs across all stations survive. */
    @Query(
        "DELETE FROM playlist_track WHERE id NOT IN " +
            "(SELECT id FROM playlist_track ORDER BY lastDetectedAt DESC, id DESC LIMIT :maxSongs)",
    )
    abstract suspend fun keepNewest(maxSongs: Int)

    @Query(
        "DELETE FROM playlist_station WHERE NOT EXISTS " +
            "(SELECT 1 FROM playlist_track WHERE playlist_track.stationId = playlist_station.id)",
    )
    abstract suspend fun deleteEmptyStations()

    @Query("DELETE FROM playlist_track WHERE id = :trackId")
    abstract suspend fun deleteTrackRow(trackId: Long)

    @Query("DELETE FROM playlist_track WHERE stationId = :stationId")
    abstract suspend fun deleteTracksOf(stationId: Long)

    @Query("DELETE FROM playlist_station WHERE id = :stationId")
    abstract suspend fun deleteStationRow(stationId: Long)

    @Query("DELETE FROM playlist_track")
    abstract suspend fun deleteAllTracks()

    @Query("DELETE FROM playlist_station")
    abstract suspend fun deleteAllStations()
}
