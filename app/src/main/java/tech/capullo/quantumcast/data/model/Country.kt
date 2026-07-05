package tech.capullo.quantumcast.data.model

import com.google.gson.annotations.SerializedName

data class Country(
    @SerializedName("name") val name: String = "",
    @SerializedName("iso_3166_1") val code: String = "",
    @SerializedName("stationcount") val stationCount: Int = 0,
)
