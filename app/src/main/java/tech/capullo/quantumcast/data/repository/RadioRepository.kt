package tech.capullo.quantumcast.data.repository

import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tech.capullo.quantumcast.data.api.RadioBrowserApi
import tech.capullo.quantumcast.data.db.AppDatabase
import tech.capullo.quantumcast.data.db.FavoriteEntity
import tech.capullo.quantumcast.data.db.FavoriteGroupEntity
import tech.capullo.quantumcast.data.model.Country
import tech.capullo.quantumcast.data.model.Station
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class FavoritesBackup(
    val version: Int = 1,
    val groups: List<FavoriteGroupEntity> = emptyList(),
    val favorites: List<FavoriteEntity> = emptyList(),
)

class RadioRepository(
    private val db: AppDatabase,
    serverUrl: String = "https://de1.api.radio-browser.info/",
) {

    private var currentServerUrl = serverUrl
    private var api = RadioBrowserApi.create(serverUrl)

    fun setServerUrl(url: String) {
        if (url != currentServerUrl && url.isNotBlank()) {
            currentServerUrl = url
            api = RadioBrowserApi.create(url)
        }
    }

    suspend fun search(query: String, limit: Int = 40): List<Station> = api.searchStations(name = query, limit = limit)

    suspend fun getTopStations(limit: Int = 40): List<Station> = api.getTopStations(limit = limit)

    suspend fun getRandomStations(limit: Int = 10): List<Station> {
        // order=random on the server has a fixed seed - same result every call.
        // A client-picked random offset is the only way to get different stations each run.
        val maxOffset = (55000 - limit).coerceAtLeast(0)
        val offset = (0..maxOffset).random()
        return api.getRandomStations(limit = limit, offset = offset).shuffled()
    }

    suspend fun getByTag(tag: String): List<Station> = api.getStationsByTag(tag)

    suspend fun getCountries(): List<Country> = api.getCountries()

    suspend fun getStationsByCountry(country: String, limit: Int = 40): List<Station> = api.getStationsByCountry(country, limit)

    fun getFavorites(): Flow<List<FavoriteEntity>> = db.favoriteDao().getAll()

    fun getFavoriteUuids(): Flow<Set<String>> = db.favoriteDao().getAllUuids().map { it.toSet() }

    suspend fun toggleFavorite(station: Station) {
        val dao = db.favoriteDao()
        if (dao.exists(station.uuid) > 0) {
            dao.delete(station.uuid)
        } else {
            dao.insert(
                FavoriteEntity(
                    uuid = station.uuid,
                    name = station.name,
                    url = station.streamUrl,
                    favicon = station.favicon,
                    country = station.country,
                    tags = station.tags,
                    codec = station.codec,
                    bitrate = station.bitrate,
                ),
            )
        }
    }

    // --- Groups ---

    fun getGroups(): Flow<List<FavoriteGroupEntity>> = db.favoriteGroupDao().getAll()

    suspend fun createGroup(name: String, uuids: Set<String>): String {
        val id = UUID.randomUUID().toString()
        val groupDao = db.favoriteGroupDao()
        groupDao.insert(
            FavoriteGroupEntity(id = id, name = name, createdAt = System.currentTimeMillis()),
        )
        uuids.forEachIndexed { i, uuid ->
            db.favoriteDao().updateGroupId(uuid, id)
            db.favoriteDao().updateSortOrder(uuid, i)
        }
        return id
    }

    suspend fun renameGroup(id: String, name: String) = db.favoriteGroupDao().rename(id, name)

    suspend fun deleteGroup(id: String) {
        db.favoriteDao().ungroupAll(id)
        db.favoriteGroupDao().delete(id)
    }

    suspend fun assignToGroup(uuids: Set<String>, groupId: String, startOrder: Int = 0) {
        uuids.forEachIndexed { i, uuid ->
            db.favoriteDao().updateGroupId(uuid, groupId)
            db.favoriteDao().updateSortOrder(uuid, startOrder + i)
        }
    }

    suspend fun unassignFromGroup(uuids: Set<String>) {
        uuids.forEach { db.favoriteDao().updateGroupId(it, "") }
    }

    suspend fun updateFavoriteSortOrder(uuid: String, sortOrder: Int) = db.favoriteDao().updateSortOrder(uuid, sortOrder)

    suspend fun updateGroupSortOrder(id: String, sortOrder: Int) = db.favoriteGroupDao().updateSortOrder(id, sortOrder)

    suspend fun exportFavorites(outputStream: OutputStream) {
        val backup = FavoritesBackup(
            groups = db.favoriteGroupDao().getAllOnce(),
            favorites = db.favoriteDao().getAllOnce(),
        )
        outputStream.bufferedWriter().use { Gson().toJson(backup, it) }
    }

    suspend fun importFavorites(inputStream: InputStream) {
        val backup = inputStream.bufferedReader().use {
            runCatching { Gson().fromJson(it, FavoritesBackup::class.java) }.getOrNull()
        } ?: return
        val favorites = backup.favorites ?: emptyList()
        val groups = backup.groups ?: emptyList()
        if (favorites.isEmpty() && groups.isEmpty()) return
        db.favoriteDao().deleteAll()
        db.favoriteGroupDao().deleteAll()
        groups.forEach { db.favoriteGroupDao().insert(it) }
        favorites.forEach { db.favoriteDao().insert(it) }
    }
}
