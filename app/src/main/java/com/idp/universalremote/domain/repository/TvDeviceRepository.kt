package com.idp.universalremote.domain.repository

import com.idp.universalremote.domain.model.TvDevice
import kotlinx.coroutines.flow.Flow

interface TvDeviceRepository {
    fun observeAll(): Flow<List<TvDevice>>
    fun observeFavorites(): Flow<List<TvDevice>>
    suspend fun recent(limit: Int = 10): List<TvDevice>
    suspend fun get(id: String): TvDevice?
    /** Fallback lookup when SSDP returns a new USN for the same physical TV. */
    suspend fun findByIp(ip: String): TvDevice?
    suspend fun save(device: TvDevice)
    suspend fun toggleFavorite(id: String): Boolean
    suspend fun touch(id: String)
    suspend fun remove(id: String)
}
