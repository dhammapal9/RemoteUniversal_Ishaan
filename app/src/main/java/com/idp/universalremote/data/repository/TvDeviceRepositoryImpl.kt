package com.idp.universalremote.data.repository

import com.idp.universalremote.data.local.dao.TvDeviceDao
import com.idp.universalremote.data.local.entity.TvDeviceEntity
import com.idp.universalremote.domain.model.ConnectionType
import com.idp.universalremote.domain.model.TvBrand
import com.idp.universalremote.domain.model.TvDevice
import com.idp.universalremote.domain.repository.TvDeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvDeviceRepositoryImpl @Inject constructor(
    private val dao: TvDeviceDao
) : TvDeviceRepository {

    override fun observeAll(): Flow<List<TvDevice>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeFavorites(): Flow<List<TvDevice>> =
        dao.observeFavorites().map { list -> list.map { it.toDomain() } }

    override suspend fun recent(limit: Int): List<TvDevice> =
        dao.recent(limit).map { it.toDomain() }

    override suspend fun get(id: String): TvDevice? =
        dao.findById(id)?.toDomain()

    override suspend fun findByIp(ip: String): TvDevice? =
        dao.findByIp(ip)?.toDomain()

    override suspend fun save(device: TvDevice) {
        dao.upsert(device.toEntity())
    }

    override suspend fun toggleFavorite(id: String): Boolean {
        val current = dao.findById(id) ?: return false
        val toggled = !current.isFavorite
        dao.update(current.copy(isFavorite = toggled))
        return toggled
    }

    override suspend fun touch(id: String) {
        dao.touch(id)
    }

    override suspend fun remove(id: String) {
        dao.deleteById(id)
    }

    private fun TvDeviceEntity.toDomain() = TvDevice(
        id = id,
        name = name,
        brand = TvBrand.fromName(brand),
        model = model,
        ipAddress = ipAddress,
        macAddress = macAddress,
        type = ConnectionType.valueOf(type),
        isFavorite = isFavorite,
        pairingToken = pairingToken,
        lastConnectedAt = lastConnectedAt
    )

    private fun TvDevice.toEntity() = TvDeviceEntity(
        id = id,
        name = name,
        brand = brand.displayName,
        model = model,
        ipAddress = ipAddress,
        macAddress = macAddress,
        type = type.name,
        isFavorite = isFavorite,
        pairingToken = pairingToken,
        lastConnectedAt = lastConnectedAt
    )
}
