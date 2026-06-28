package com.idp.universalremote.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.idp.universalremote.data.local.dao.ConnectionHistoryDao
import com.idp.universalremote.data.local.dao.FavoriteRemoteDao
import com.idp.universalremote.data.local.dao.TvDeviceDao
import com.idp.universalremote.data.local.entity.ConnectionHistoryEntity
import com.idp.universalremote.data.local.entity.FavoriteRemoteEntity
import com.idp.universalremote.data.local.entity.TvDeviceEntity

@Database(
    entities = [
        TvDeviceEntity::class,
        FavoriteRemoteEntity::class,
        ConnectionHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tvDeviceDao(): TvDeviceDao
    abstract fun favoriteRemoteDao(): FavoriteRemoteDao
    abstract fun connectionHistoryDao(): ConnectionHistoryDao

    companion object {
        const val DATABASE_NAME = "universal_remote.db"
    }
}
