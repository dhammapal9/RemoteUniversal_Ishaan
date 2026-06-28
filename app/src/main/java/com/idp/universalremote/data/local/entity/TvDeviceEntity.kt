package com.idp.universalremote.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tv_devices")
data class TvDeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val brand: String,
    val model: String?,
    val ipAddress: String?,
    val macAddress: String?,
    val type: String,
    val isFavorite: Boolean = false,
    val lastConnectedAt: Long = System.currentTimeMillis(),
    val pairingToken: String? = null
)
