package com.quantumcast.data.api

import com.quantumcast.data.model.Country
import com.quantumcast.data.model.Station
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface RadioBrowserApi {

    @GET("json/stations/search")
    suspend fun searchStations(
        @Query("name") name: String,
        @Query("limit") limit: Int = 40,
        @Query("hidebroken") hidebroken: Boolean = true,
        @Query("order") order: String = "clickcount",
        @Query("reverse") reverse: Boolean = true
    ): List<Station>

    @GET("json/stations/search")
    suspend fun getRandomStations(
        @Query("limit") limit: Int = 10,
        @Query("offset") offset: Int = 0,
        @Query("hidebroken") hidebroken: Boolean = true,
        @Query("order") order: String = "random"
    ): List<Station>

    @GET("json/stations/search")
    suspend fun getTopStations(
        @Query("limit") limit: Int = 40,
        @Query("hidebroken") hidebroken: Boolean = true,
        @Query("order") order: String = "clickcount",
        @Query("reverse") reverse: Boolean = true
    ): List<Station>

    @GET("json/countries")
    suspend fun getCountries(
        @Query("order") order: String = "stationcount",
        @Query("reverse") reverse: Boolean = true,
        @Query("hidebroken") hidebroken: Boolean = true
    ): List<Country>

    @GET("json/stations/bycountry/{country}")
    suspend fun getStationsByCountry(
        @Path("country") country: String,
        @Query("limit") limit: Int = 40,
        @Query("hidebroken") hidebroken: Boolean = true,
        @Query("order") order: String = "clickcount",
        @Query("reverse") reverse: Boolean = true
    ): List<Station>

    @GET("json/stations/search")
    suspend fun getStationsByTag(
        @Query("tag") tag: String,
        @Query("limit") limit: Int = 40,
        @Query("hidebroken") hidebroken: Boolean = true,
        @Query("order") order: String = "clickcount",
        @Query("reverse") reverse: Boolean = true
    ): List<Station>

    companion object {
        fun create(baseUrl: String = "https://de1.api.radio-browser.info/"): RadioBrowserApi =
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(RadioBrowserApi::class.java)
    }
}
