package tech.capullo.quantumcast.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY groupId ASC, sortOrder ASC, addedAt ASC")
    fun getAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT uuid FROM favorites")
    fun getAllUuids(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE uuid = :uuid")
    suspend fun delete(uuid: String)

    @Query("SELECT COUNT(*) FROM favorites WHERE uuid = :uuid")
    suspend fun exists(uuid: String): Int

    @Query("UPDATE favorites SET groupId = :groupId WHERE uuid = :uuid")
    suspend fun updateGroupId(uuid: String, groupId: String)

    @Query("UPDATE favorites SET sortOrder = :sortOrder WHERE uuid = :uuid")
    suspend fun updateSortOrder(uuid: String, sortOrder: Int)

    @Query("UPDATE favorites SET groupId = '' WHERE groupId = :groupId")
    suspend fun ungroupAll(groupId: String)

    @Query("SELECT * FROM favorites ORDER BY groupId ASC, sortOrder ASC, addedAt ASC")
    suspend fun getAllOnce(): List<FavoriteEntity>

    @Query("DELETE FROM favorites")
    suspend fun deleteAll()
}
