package com.idp.universalremote.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.idp.universalremote.data.local.entity.TvDeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TvDeviceDao {

    @Query("SELECT * FROM tv_devices ORDER BY lastConnectedAt DESC")
    fun observeAll(): Flow<List<TvDeviceEntity>>

    @Query("SELECT * FROM tv_devices WHERE isFavorite = 1 ORDER BY name ASC")
    fun observeFavorites(): Flow<List<TvDeviceEntity>>

    @Query("SELECT * FROM tv_devices ORDER BY lastConnectedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 10): List<TvDeviceEntity>

    @Query("SELECT * FROM tv_devices WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): TvDeviceEntity?

    /**
     * USNs returned by SSDP can change across power cycles; the IP is the more
     * reliable handle when an already-paired TV is rediscovered after the app
     * was force-killed. Picks the most-recently-connected match in case the
     * user has multiple TVs on the same IP at different times.
     */
    @Query("SELECT * FROM tv_devices WHERE ipAddress = :ip ORDER BY lastConnectedAt DESC LIMIT 1")
    suspend fun findByIp(ip: String): TvDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: TvDeviceEntity)

    @Update
    suspend fun update(device: TvDeviceEntity)

    @Query("DELETE FROM tv_devices WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE tv_devices SET lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: String, timestamp: Long = System.currentTimeMillis())
}
