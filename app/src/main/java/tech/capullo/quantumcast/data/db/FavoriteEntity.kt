package tech.capullo.quantumcast.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    val url: String,
    val favicon: String,
    val country: String,
    val tags: String,
    val codec: String,
    val bitrate: Int,
    val addedAt: Long = System.currentTimeMillis(),
    val groupId: String = "",
    val sortOrder: Int = 0,
)
