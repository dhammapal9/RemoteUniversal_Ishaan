package com.idp.universalremote.domain.repository

import com.idp.universalremote.domain.model.TvDevice
import kotlinx.coroutines.flow.Flow

interface DeviceDiscoveryRepository {
    fun discover(): Flow<List<TvDevice>>
    suspend fun stop()
}
