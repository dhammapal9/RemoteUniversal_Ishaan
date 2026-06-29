package com.idp.universalremote.domain.repository

import com.idp.universalremote.domain.model.ConnectionState
import com.idp.universalremote.domain.model.MediaItem
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

    /**
     * Cast a local media file to the connected TV via UPnP/DLNA.
     * Returns true if the TV accepted the cast; false otherwise (no TV connected,
     * no DLNA renderer reachable, or the renderer rejected the format).
     */
    suspend fun castMedia(item: MediaItem): Boolean

    /**
     * Silently reconnect to the most-recently-used TV using its saved cert and
     * pairing token. Should be called on app start so the user doesn't have to
     * re-tap "Connect" every cold launch. No-op when no previous session exists
     * or the TV isn't reachable on the current Wi-Fi network.
     */
    suspend fun autoReconnect(): Boolean
}
