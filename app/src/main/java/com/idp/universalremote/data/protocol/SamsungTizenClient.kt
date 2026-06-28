package com.idp.universalremote.data.protocol

import android.util.Base64
import com.idp.universalremote.domain.model.RemoteKey
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Samsung Tizen WebSocket remote (2016+ Smart TVs).
 *
 * Pairing flow:
 *   1. Open WS to wss://<ip>:8002/api/v2/channels/samsung.remote.control?name=<base64-name>
 *   2. TV shows an Allow / Deny prompt and (on first connection) returns a token in
 *      the "ms.channel.connect" reply that must be stored and reused.
 *
 * Command:
 *   { "method":"ms.remote.control",
 *     "params":{ "Cmd":"Click", "DataOfCmd":"KEY_POWER",
 *                "Option":"false", "TypeOfRemote":"SendRemoteKey" } }
 */
class SamsungTizenClient(
    private val host: String,
    private val token: String? = null,
    private val appName: String = "UniversalRemote"
) {
    private var socket: WebSocket? = null
    private var connectedToken: String? = token
    private var onTokenReceived: ((String) -> Unit)? = null

    private val client: OkHttpClient by lazy {
        val trustAll = arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, trustAll[0])
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    fun connect(
        onConnected: () -> Unit,
        onFailure: (Throwable) -> Unit,
        onToken: (String) -> Unit = {}
    ) {
        onTokenReceived = onToken
        openSocket(SecureEndpoint, onConnected, onFailure, fallbackOnFailure = true)
    }

    private fun openSocket(
        endpoint: Endpoint,
        onConnected: () -> Unit,
        onFailure: (Throwable) -> Unit,
        fallbackOnFailure: Boolean
    ) {
        val encodedName = Base64.encodeToString(appName.toByteArray(), Base64.NO_WRAP)
        val tokenSuffix = connectedToken?.let { "&token=$it" }.orEmpty()
        val url = "${endpoint.scheme}://$host:${endpoint.port}" +
            "/api/v2/channels/samsung.remote.control?name=$encodedName$tokenSuffix"

        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onConnected()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseToken(text)?.let {
                    connectedToken = it
                    onTokenReceived?.invoke(it)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (fallbackOnFailure && endpoint == SecureEndpoint) {
                    // Older Samsung Tizen models (2016-2017) don't have TLS — retry on :8001.
                    runCatching { webSocket.close(1000, null) }
                    openSocket(InsecureEndpoint, onConnected, onFailure, fallbackOnFailure = false)
                } else {
                    onFailure(t)
                }
            }
        })
    }

    private sealed interface Endpoint {
        val scheme: String
        val port: Int
    }
    private data object SecureEndpoint : Endpoint {
        override val scheme = "wss"
        override val port = 8002
    }
    private data object InsecureEndpoint : Endpoint {
        override val scheme = "ws"
        override val port = 8001
    }

    fun sendKey(key: RemoteKey): Boolean {
        val ws = socket ?: return false
        val keyName = SamsungKeyMap.toTizen(key) ?: return false
        val payload = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "Click")
                put("DataOfCmd", keyName)
                put("Option", "false")
                put("TypeOfRemote", "SendRemoteKey")
            })
        }
        return ws.send(payload.toString())
    }

    fun close() {
        runCatching { socket?.close(1000, "client closed") }
        socket = null
    }

    private fun parseToken(message: String): String? = runCatching {
        val json = JSONObject(message)
        if (json.optString("event") != "ms.channel.connect") return@runCatching null
        val data = json.optJSONObject("data") ?: return@runCatching null
        data.optString("token").takeIf { it.isNotBlank() }
    }.getOrNull()
}

object SamsungKeyMap {
    fun toTizen(key: RemoteKey): String? = when (key) {
        RemoteKey.POWER -> "KEY_POWER"
        RemoteKey.HOME -> "KEY_HOME"
        RemoteKey.BACK -> "KEY_RETURN"
        RemoteKey.MENU -> "KEY_MENU"
        RemoteKey.EXIT -> "KEY_EXIT"
        RemoteKey.GUIDE -> "KEY_GUIDE"
        RemoteKey.INFO -> "KEY_INFO"
        RemoteKey.TOOLS -> "KEY_TOOLS"
        RemoteKey.SOURCE -> "KEY_SOURCE"
        RemoteKey.UP -> "KEY_UP"
        RemoteKey.DOWN -> "KEY_DOWN"
        RemoteKey.LEFT -> "KEY_LEFT"
        RemoteKey.RIGHT -> "KEY_RIGHT"
        RemoteKey.OK -> "KEY_ENTER"
        RemoteKey.VOL_UP -> "KEY_VOLUP"
        RemoteKey.VOL_DOWN -> "KEY_VOLDOWN"
        RemoteKey.MUTE -> "KEY_MUTE"
        RemoteKey.CH_UP -> "KEY_CHUP"
        RemoteKey.CH_DOWN -> "KEY_CHDOWN"
        RemoteKey.PRE_CH -> "KEY_PRECH"
        RemoteKey.TTX -> "KEY_TTX_MIX"
        RemoteKey.NUM_0 -> "KEY_0"
        RemoteKey.NUM_1 -> "KEY_1"
        RemoteKey.NUM_2 -> "KEY_2"
        RemoteKey.NUM_3 -> "KEY_3"
        RemoteKey.NUM_4 -> "KEY_4"
        RemoteKey.NUM_5 -> "KEY_5"
        RemoteKey.NUM_6 -> "KEY_6"
        RemoteKey.NUM_7 -> "KEY_7"
        RemoteKey.NUM_8 -> "KEY_8"
        RemoteKey.NUM_9 -> "KEY_9"
        RemoteKey.PLAY -> "KEY_PLAY"
        RemoteKey.PAUSE -> "KEY_PAUSE"
        RemoteKey.PLAY_PAUSE -> "KEY_PLAY"
        RemoteKey.STOP -> "KEY_STOP"
        RemoteKey.REWIND -> "KEY_REWIND"
        RemoteKey.OPTIONS -> "KEY_TOOLS"
        RemoteKey.INSTANT_REPLAY -> "KEY_REWIND"
        RemoteKey.BACKSPACE -> "KEY_DELETE"
        RemoteKey.FORWARD -> "KEY_FF"
        RemoteKey.NEXT -> "KEY_FF"
        RemoteKey.PREVIOUS -> "KEY_REWIND"
        RemoteKey.RED -> "KEY_RED"
        RemoteKey.GREEN -> "KEY_GREEN"
        RemoteKey.YELLOW -> "KEY_YELLOW"
        RemoteKey.BLUE -> "KEY_CYAN"
        else -> null
    }
}
