package com.idp.universalremote.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.idp.universalremote.data.local.entity.FavoriteRemoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteRemoteDao {

    @Query("SELECT * FROM favorite_remotes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FavoriteRemoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(remote: FavoriteRemoteEntity)

    @Query("DELETE FROM favorite_remotes WHERE id = :id")
    suspend fun delete(id: String)
}
