package tech.capullo.quantumcast.data.playlists

import android.net.Uri

/** The read-only inter-app API PlaylistProvider serves. README.md documents it for other apps. */
object PlaylistContract {
    const val AUTHORITY = "tech.capullo.quantumcast.playlists"
    const val PERMISSION_READ_PLAYLISTS = "tech.capullo.quantumcast.permission.READ_PLAYLISTS"

    const val PATH_PLAYLISTS = "playlists"
    const val PATH_TRACKS = "tracks"

    const val MIME_PLAYLIST_DIR = "vnd.android.cursor.dir/vnd.tech.capullo.quantumcast.playlist"
    const val MIME_PLAYLIST_ITEM = "vnd.android.cursor.item/vnd.tech.capullo.quantumcast.playlist"
    const val MIME_TRACK_DIR = "vnd.android.cursor.dir/vnd.tech.capullo.quantumcast.track"

    /** Track URIs only: keeps the rows that have this kind of link. */
    const val PARAM_LINK = "link"
    const val LINK_YOUTUBE = "youtube"
    const val LINK_SPOTIFY = "spotify"
    const val LINK_APPLE_MUSIC = "apple_music"

    val PLAYLISTS_URI: Uri = Uri.Builder().scheme("content").authority(AUTHORITY).path(PATH_PLAYLISTS).build()
    val TRACKS_URI: Uri = Uri.Builder().scheme("content").authority(AUTHORITY).path(PATH_TRACKS).build()

    fun playlistUri(id: Long): Uri = PLAYLISTS_URI.buildUpon().appendPath(id.toString()).build()

    fun tracksUri(playlistId: Long): Uri = playlistUri(playlistId).buildUpon().appendPath(PATH_TRACKS).build()

    /** Columns of the playlist URIs, in cursor order. */
    object Playlists {
        const val ID = "_id"
        const val STATION_KEY = "station_key"
        const val NAME = "name"
        const val COUNTRY = "country"
        const val COUNTRY_CODE = "country_code"
        const val FAVICON = "favicon"
        const val STREAM_URL = "stream_url"
        const val TAGS = "tags"
        const val CODEC = "codec"
        const val BITRATE = "bitrate"
        const val TRACK_COUNT = "track_count"
        const val YOUTUBE_COUNT = "youtube_count"
        const val UPDATED_AT = "updated_at"

        val COLUMNS = listOf(
            ID, STATION_KEY, NAME, COUNTRY, COUNTRY_CODE, FAVICON, STREAM_URL, TAGS, CODEC, BITRATE,
            TRACK_COUNT, YOUTUBE_COUNT, UPDATED_AT,
        )
    }

    /** Columns of the track URIs, in cursor order. */
    object Tracks {
        const val ID = "_id"
        const val PLAYLIST_ID = "playlist_id"
        const val STATION_NAME = "station_name"
        const val TITLE = "title"
        const val ARTIST = "artist"
        const val ARTWORK_URL = "artwork_url"
        const val YOUTUBE_URL = "youtube_url"
        const val SPOTIFY_URL = "spotify_url"
        const val APPLE_MUSIC_URL = "apple_music_url"
        const val FIRST_DETECTED_AT = "first_detected_at"
        const val LAST_DETECTED_AT = "last_detected_at"
        const val DETECTIONS = "detections"

        val COLUMNS = listOf(
            ID, PLAYLIST_ID, STATION_NAME, TITLE, ARTIST, ARTWORK_URL, YOUTUBE_URL, SPOTIFY_URL,
            APPLE_MUSIC_URL, FIRST_DETECTED_AT, LAST_DETECTED_AT, DETECTIONS,
        )
    }
}
