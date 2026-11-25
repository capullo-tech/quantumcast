package tech.capullo.quantumcast.data.playlists

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlist_station", indices = [Index(value = ["stationKey"], unique = true)])
data class PlaylistStationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Radio Browser uuid, or the stream URL of a station that has none. */
    val stationKey: String,
    val name: String,
    val country: String,
    val countryCode: String,
    val favicon: String,
    val streamUrl: String,
    val tags: String,
    val codec: String,
    val bitrate: Int,
)

@Entity(
    tableName = "playlist_track",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistStationEntity::class,
            parentColumns = ["id"],
            childColumns = ["stationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["stationId", "title", "artist"], unique = true), Index("lastDetectedAt")],
)
data class PlaylistTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: Long,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val title: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val artist: String,
    val artworkUrl: String,
    val youtubeUrl: String,
    val spotifyUrl: String,
    val appleMusicUrl: String,
    val firstDetectedAt: Long,
    val lastDetectedAt: Long,
    /** Separate plays heard, not Shazam polls: see SAME_PLAY_GAP_MS. */
    val detections: Int,
)

/** A station's playlist as the list screen shows it. */
data class StationPlaylist(
    @Embedded val station: PlaylistStationEntity,
    val trackCount: Int,
    /** Songs whose YouTube link is a video (playable in CapulloTube), not only a search link. */
    val youtubeCount: Int,
    val updatedAt: Long,
)

/** A song with the station it was heard on: a history row and a playlist row. */
data class DetectedTrack(
    @Embedded val track: PlaylistTrackEntity,
    val stationName: String,
    val stationCountryCode: String,
)
