package tech.capullo.quantumcast.data.model

import com.google.gson.annotations.SerializedName

data class Station(
    @SerializedName("stationuuid") val uuid: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("url_resolved") val url: String = "",
    @SerializedName("url") val urlFallback: String = "",
    @SerializedName("favicon") val favicon: String = "",
    @SerializedName("country") val country: String = "",
    @SerializedName("countrycode") val countryCode: String = "",
    @SerializedName("language") val language: String = "",
    @SerializedName("tags") val tags: String = "",
    @SerializedName("votes") val votes: Int = 0,
    @SerializedName("clickcount") val clickCount: Int = 0,
    @SerializedName("codec") val codec: String = "",
    @SerializedName("bitrate") val bitrate: Int = 0,
) {
    val streamUrl: String get() = url.ifEmpty { urlFallback }
    val displayTags: List<String> get() = tags.split(",").map {
        it.trim()
    }.filter { it.isNotEmpty() }.take(3)
}
