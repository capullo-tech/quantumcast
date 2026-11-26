package tech.capullo.quantumcast.data.playlists

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PlaylistStationEntity::class, PlaylistTrackEntity::class], version = 1, exportSchema = true)
abstract class PlaylistDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile private var instance: PlaylistDatabase? = null

        /**
         * One instance per process. PlaylistProvider is created before Hilt's Application.onCreate
         * work runs, so it and PlaylistRepository share this instead of a Hilt binding.
         */
        fun get(context: Context): PlaylistDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, PlaylistDatabase::class.java, "playlists.db")
                .build()
                .also { instance = it }
        }
    }
}
