package com.idp.universalremote.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.idp.universalremote.data.local.entity.ConnectionHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionHistoryDao {

    @Query("SELECT * FROM connection_history ORDER BY connectedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<ConnectionHistoryEntity>>

    @Insert
    suspend fun insert(entry: ConnectionHistoryEntity)

    @Query("DELETE FROM connection_history WHERE connectedAt < :before")
    suspend fun trim(before: Long)
}
