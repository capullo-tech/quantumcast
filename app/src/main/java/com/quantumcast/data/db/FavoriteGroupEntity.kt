package com.quantumcast.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_groups")
data class FavoriteGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
