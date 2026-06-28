package com.idp.universalremote.data.protocol

import com.idp.universalremote.domain.model.RemoteKey
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Roku External Control Protocol (ECP): plain HTTP on port 8060.
 *   POST http://<ip>:8060/keypress/<KEY>
 */
class RokuEcpClient(private val host: String) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    fun sendKey(key: RemoteKey): Boolean {
        val keyName = RokuKeyMap.toEcp(key) ?: return false
        val request = Request.Builder()
            .url("http://$host:8060/keypress/$keyName")
            .post(EMPTY)
            .build()
        return runCatching {
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun launchApp(appId: String): Boolean {
        val request = Request.Builder()
            .url("http://$host:8060/launch/$appId")
            .post(EMPTY)
            .build()
        return runCatching {
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    companion object {
        private val EMPTY = "".toRequestBody(null)
    }
}

object RokuKeyMap {
    fun toEcp(key: RemoteKey): String? = when (key) {
        RemoteKey.POWER -> "Power"
        RemoteKey.HOME -> "Home"
        RemoteKey.BACK -> "Back"
        RemoteKey.UP -> "Up"
        RemoteKey.DOWN -> "Down"
        RemoteKey.LEFT -> "Left"
        RemoteKey.RIGHT -> "Right"
        RemoteKey.OK -> "Select"
        RemoteKey.PLAY, RemoteKey.PAUSE, RemoteKey.PLAY_PAUSE -> "Play"
        RemoteKey.REWIND -> "Rev"
        RemoteKey.FORWARD -> "Fwd"
        RemoteKey.VOL_UP -> "VolumeUp"
        RemoteKey.VOL_DOWN -> "VolumeDown"
        RemoteKey.MUTE -> "VolumeMute"
        RemoteKey.INFO, RemoteKey.OPTIONS -> "Info"
        RemoteKey.MENU, RemoteKey.INSTANT_REPLAY -> "InstantReplay"
        RemoteKey.SOURCE -> "InputAV1"
        RemoteKey.BACKSPACE -> "Backspace"
        else -> null
    }

    /** Roku application IDs (channel store). */
    fun appId(key: RemoteKey): String? = when (key) {
        RemoteKey.APP_NETFLIX -> "12"
        RemoteKey.APP_YOUTUBE -> "837"
        RemoteKey.APP_PRIME -> "13"
        RemoteKey.APP_DISNEY -> "291097"
        RemoteKey.APP_HULU -> "2285"
        RemoteKey.APP_HBO -> "61322"
        RemoteKey.APP_SPOTIFY -> "22297"
        RemoteKey.APP_PLEX -> "13535"
        RemoteKey.APP_APPLE_TV -> "551012"
        RemoteKey.APP_PARAMOUNT -> "31440"
        RemoteKey.APP_PEACOCK -> "593099"
        RemoteKey.APP_TUBI -> "41468"
        RemoteKey.APP_PLUTO -> "74519"
        else -> null
    }
}
