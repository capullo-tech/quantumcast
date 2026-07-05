package com.quantumcast.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [FavoriteEntity::class, FavoriteGroupEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun favoriteGroupDao(): FavoriteGroupDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorites ADD COLUMN groupId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE favorites ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS favorite_groups (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "radio_db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { INSTANCE = it }
        }
    }
}
