package tech.capullo.quantumcast.data.playlists

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import androidx.sqlite.db.SimpleSQLiteQuery
import tech.capullo.quantumcast.data.playlists.PlaylistContract.Playlists
import tech.capullo.quantumcast.data.playlists.PlaylistContract.Tracks

/**
 * Read-only view of the playlists for other apps (CapulloTube). Every query is fixed SQL: the
 * caller's selection, arguments and sort order are ignored, and the only caller strings that reach
 * the SQL are projection columns checked against the contract's column lists.
 */
class PlaylistProvider : ContentProvider() {

    // The database opens on the first query, not here: onCreate runs on the main thread at startup.
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val match = MATCHER.match(uri)
        val (base, args, columns, order) = when (match) {
            PLAYLISTS -> Query(playlistsSql(byId = false), emptyList(), Playlists.COLUMNS, PLAYLISTS_ORDER)
            PLAYLIST -> Query(playlistsSql(byId = true), listOf(idOf(uri)), Playlists.COLUMNS, PLAYLISTS_ORDER)
            TRACKS -> Query(tracksSql(byStation = false, uri), emptyList(), Tracks.COLUMNS, TRACKS_ORDER)
            PLAYLIST_TRACKS -> Query(tracksSql(byStation = true, uri), listOf(idOf(uri)), Tracks.COLUMNS, TRACKS_ORDER)
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        val selected = when {
            projection == null -> columns
            projection.isEmpty() -> throw IllegalArgumentException("Empty projection")
            else -> projection.map { column ->
                require(column in columns) { "Unknown column: $column" }
                column
            }
        }
        val sql = "SELECT ${selected.joinToString(", ")} FROM ($base) ORDER BY $order"
        val appContext = requireNotNull(context)
        val cursor = PlaylistDatabase.get(appContext).openHelper.readableDatabase
            .query(SimpleSQLiteQuery(sql, args.toTypedArray()))
        cursor.setNotificationUri(appContext.contentResolver, uri)
        return cursor
    }

    override fun getType(uri: Uri): String = when (MATCHER.match(uri)) {
        PLAYLISTS -> PlaylistContract.MIME_PLAYLIST_DIR
        PLAYLIST -> PlaylistContract.MIME_PLAYLIST_ITEM
        TRACKS, PLAYLIST_TRACKS -> PlaylistContract.MIME_TRACK_DIR
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")

    private data class Query(val base: String, val args: List<Any>, val columns: List<String>, val order: String)

    private fun idOf(uri: Uri): Long = uri.pathSegments[1].toLong()

    private fun playlistsSql(byId: Boolean): String = "SELECT s.id AS ${Playlists.ID}, " +
        "s.stationKey AS ${Playlists.STATION_KEY}, s.name AS ${Playlists.NAME}, " +
        "s.country AS ${Playlists.COUNTRY}, s.countryCode AS ${Playlists.COUNTRY_CODE}, " +
        "s.favicon AS ${Playlists.FAVICON}, s.streamUrl AS ${Playlists.STREAM_URL}, " +
        "s.tags AS ${Playlists.TAGS}, s.codec AS ${Playlists.CODEC}, s.bitrate AS ${Playlists.BITRATE}, " +
        "COUNT(t.id) AS ${Playlists.TRACK_COUNT}, " +
        "SUM(CASE WHEN $YOUTUBE_VIDEO_LINK_SQL THEN 1 ELSE 0 END) AS ${Playlists.YOUTUBE_COUNT}, " +
        "MAX(t.lastDetectedAt) AS ${Playlists.UPDATED_AT} " +
        "FROM playlist_station s INNER JOIN playlist_track t ON t.stationId = s.id " +
        (if (byId) "WHERE s.id = ? " else "") +
        "GROUP BY s.id"

    private fun tracksSql(byStation: Boolean, uri: Uri): String {
        val link = when (val value = uri.getQueryParameter(PlaylistContract.PARAM_LINK)) {
            null -> null
            PlaylistContract.LINK_YOUTUBE -> YOUTUBE_VIDEO_LINK_SQL
            PlaylistContract.LINK_SPOTIFY -> "t.spotifyUrl <> ''"
            PlaylistContract.LINK_APPLE_MUSIC -> "t.appleMusicUrl <> ''"
            else -> throw IllegalArgumentException("Unknown ${PlaylistContract.PARAM_LINK}: $value")
        }
        val where = listOfNotNull(if (byStation) "t.stationId = ?" else null, link)
        return "SELECT t.id AS ${Tracks.ID}, t.stationId AS ${Tracks.PLAYLIST_ID}, " +
            "s.name AS ${Tracks.STATION_NAME}, t.title AS ${Tracks.TITLE}, t.artist AS ${Tracks.ARTIST}, " +
            "t.artworkUrl AS ${Tracks.ARTWORK_URL}, t.youtubeUrl AS ${Tracks.YOUTUBE_URL}, " +
            "t.spotifyUrl AS ${Tracks.SPOTIFY_URL}, t.appleMusicUrl AS ${Tracks.APPLE_MUSIC_URL}, " +
            "t.firstDetectedAt AS ${Tracks.FIRST_DETECTED_AT}, t.lastDetectedAt AS ${Tracks.LAST_DETECTED_AT}, " +
            "t.detections AS ${Tracks.DETECTIONS} " +
            "FROM playlist_track t INNER JOIN playlist_station s ON s.id = t.stationId" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND "))
    }

    private companion object {
        const val PLAYLISTS = 1
        const val PLAYLIST = 2
        const val PLAYLIST_TRACKS = 3
        const val TRACKS = 4

        // ORDER BY sits on the outer SELECT (a subquery's order is not guaranteed) and may name a
        // column the projection left out: SQLite resolves it against the subquery.
        const val PLAYLISTS_ORDER = "${Playlists.UPDATED_AT} DESC, ${Playlists.ID} DESC"
        const val TRACKS_ORDER = "${Tracks.LAST_DETECTED_AT} DESC, ${Tracks.ID} DESC"

        val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(PlaylistContract.AUTHORITY, PlaylistContract.PATH_PLAYLISTS, PLAYLISTS)
            addURI(PlaylistContract.AUTHORITY, "${PlaylistContract.PATH_PLAYLISTS}/#", PLAYLIST)
            addURI(
                PlaylistContract.AUTHORITY,
                "${PlaylistContract.PATH_PLAYLISTS}/#/${PlaylistContract.PATH_TRACKS}",
                PLAYLIST_TRACKS,
            )
            addURI(PlaylistContract.AUTHORITY, PlaylistContract.PATH_TRACKS, TRACKS)
        }
    }
}
