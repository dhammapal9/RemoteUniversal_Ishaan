package com.idp.universalremote.domain.repository

import com.idp.universalremote.domain.model.ConnectionState
import com.idp.universalremote.domain.model.RemoteKey
import com.idp.universalremote.domain.model.TvDevice
import kotlinx.coroutines.flow.StateFlow

interface RemoteCommandRepository {
    val connectionState: StateFlow<ConnectionState>
    suspend fun connect(device: TvDevice): Boolean
    suspend fun pair(code: String): Boolean
    suspend fun disconnect()
    suspend fun send(key: RemoteKey)
    suspend fun sendText(text: String)
    fun supportsIr(): Boolean
}
