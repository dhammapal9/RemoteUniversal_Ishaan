package com.idp.universalremote.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_remotes")
data class FavoriteRemoteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val brand: String,
    val type: String,
    val layoutKey: String,
    val createdAt: Long = System.currentTimeMillis()
)
