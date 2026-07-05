package tech.capullo.quantumcast.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteGroupDao {
    @Query("SELECT * FROM favorite_groups ORDER BY sortOrder ASC, createdAt ASC")
    fun getAll(): Flow<List<FavoriteGroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: FavoriteGroupEntity)

    @Query("DELETE FROM favorite_groups WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE favorite_groups SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE favorite_groups SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("SELECT * FROM favorite_groups ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAllOnce(): List<FavoriteGroupEntity>

    @Query("DELETE FROM favorite_groups")
    suspend fun deleteAll()
}
