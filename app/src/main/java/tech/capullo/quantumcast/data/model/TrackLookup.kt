package tech.capullo.quantumcast.data.model

data class TrackLookup(
    val icyTitle: String,
    val stationName: String = "",
    val stationCountryCode: String = "",
    val trackName: String = "",
    val artistName: String = "",
    val artworkUrl: String = "",
    val youtubeUrl: String = "",
    val spotifyUrl: String = "",
    val appleMusicUrl: String = "",
    val isLoading: Boolean = false,
    val notFound: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)
