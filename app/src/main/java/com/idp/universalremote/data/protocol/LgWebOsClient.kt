package com.idp.universalremote.data.protocol

import com.idp.universalremote.domain.model.RemoteKey
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * LG webOS Smart TV remote (2014+).
 *
 * Pairing flow (mirrors Samsung's Allow prompt):
 *   1. Open ws://<ip>:3000/ (or wss://<ip>:3001/)
 *   2. Send a "register" payload — TV displays an Allow prompt
 *   3. On Allow → response carries a `client-key` to persist and reuse
 *   4. Open the secondary pointer socket via ssap://com.webos.service.networkinput/getPointerInputSocket
 *      — needed to send d-pad / OK keys
 */
class LgWebOsClient(
    private val host: String,
    private val clientKey: String? = null,
    private val appName: String = "UniversalRemote"
) {
    private var mainSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private var resolvedKey: String? = clientKey
    private var pointerUrl: String? = null
    private val requestId = AtomicInteger(1)

    private var onConnectedCallback: (() -> Unit)? = null
    private var onFailureCallback: ((Throwable) -> Unit)? = null
    private var onKeyCallback: ((String) -> Unit)? = null

    private val client: OkHttpClient by lazy {
        val trustAll = arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
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
        onKey: (String) -> Unit = {}
    ) {
        onConnectedCallback = onConnected
        onFailureCallback = onFailure
        onKeyCallback = onKey
        openMain(secure = false)
    }

    private fun openMain(secure: Boolean) {
        val url = if (secure) "wss://$host:3001/" else "ws://$host:3000/"
        val request = Request.Builder().url(url).build()
        mainSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendRegister(webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!secure) {
                    // Fall back to TLS port for newer models that forced wss://
                    runCatching { webSocket.close(1000, null) }
                    openMain(secure = true)
                } else {
                    onFailureCallback?.invoke(t)
                }
            }
        })
    }

    private fun sendRegister(webSocket: WebSocket) {
        val manifest = JSONObject().apply {
            put("manifest", JSONObject().apply {
                put("manifestVersion", 1)
                put("appVersion", "1.0")
                put("signed", JSONObject().apply {
                    put("created", "20140509")
                    put("appId", "com.lge.test")
                    put("vendorId", "com.lge")
                    put("localizedAppNames", JSONObject().apply { put("", appName) })
                    put("permissions", JSONArray(REQUESTED_PERMISSIONS))
                })
                put("permissions", JSONArray(REQUESTED_PERMISSIONS))
            })
            put("pairingType", "PROMPT")
            resolvedKey?.let { put("client-key", it) }
        }
        val payload = JSONObject().apply {
            put("type", "register")
            put("id", "register_0")
            put("payload", manifest)
        }
        webSocket.send(payload.toString())
    }

    private fun handleMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val type = json.optString("type")
        val id = json.optString("id")
        val payload = json.optJSONObject("payload") ?: JSONObject()

        when {
            // Pairing accepted by user on TV
            type == "registered" -> {
                val key = payload.optString("client-key")
                if (key.isNotBlank()) {
                    resolvedKey = key
                    onKeyCallback?.invoke(key)
                }
                requestPointerSocket()
                onConnectedCallback?.invoke()
            }
            // Some TVs return a "response" with the registered key on subsequent pairs
            type == "response" && id == "register_0" -> {
                payload.optString("client-key").takeIf { it.isNotBlank() }?.let { key ->
                    resolvedKey = key
                    onKeyCallback?.invoke(key)
                }
            }
            // Pointer socket URL response
            type == "response" && id == "pointer_0" -> {
                payload.optString("socketPath").takeIf { it.isNotBlank() }?.let {
                    pointerUrl = it
                    openPointerSocket(it)
                }
            }
        }
    }

    private fun requestPointerSocket() {
        val ws = mainSocket ?: return
        val payload = JSONObject().apply {
            put("type", "request")
            put("id", "pointer_0")
            put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
        }
        ws.send(payload.toString())
    }

    private fun openPointerSocket(url: String) {
        val request = Request.Builder().url(url).build()
        pointerSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = Unit
        })
    }

    fun sendKey(key: RemoteKey): Boolean {
        val mapped = LgKeyMap.toWebOs(key) ?: return false
        return when (mapped) {
            is LgKeyMap.Mapped.Button -> sendButton(mapped.name)
            is LgKeyMap.Mapped.Ssap -> sendSsap(mapped.uri, mapped.payload)
        }
    }

    private fun sendButton(name: String): Boolean {
        val ws = pointerSocket ?: return false
        val frame = "type:button\nname:$name\n\n"
        return ws.send(frame)
    }

    private fun sendSsap(uri: String, payload: JSONObject?): Boolean {
        val ws = mainSocket ?: return false
        val message = JSONObject().apply {
            put("type", "request")
            put("id", "ssap_${requestId.incrementAndGet()}")
            put("uri", uri)
            payload?.let { put("payload", it) }
        }
        return ws.send(message.toString())
    }

    fun launchApp(appId: String): Boolean = sendSsap(
        "ssap://system.launcher/launch",
        JSONObject().apply { put("id", appId) }
    )

    fun close() {
        runCatching { mainSocket?.close(1000, "client closed") }
        runCatching { pointerSocket?.close(1000, "client closed") }
        mainSocket = null
        pointerSocket = null
    }

    companion object {
        private val REQUESTED_PERMISSIONS = listOf(
            "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CONTROL_AUDIO",
            "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING",
            "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV", "CONTROL_POWER",
            "READ_INSTALLED_APPS", "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE",
            "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST",
            "CONTROL_TV_SCREEN", "CONTROL_TV_STANBY", "CONTROL_MEDIA_PLAYBACK",
            "CONTROL_INPUT_TEXT", "READ_INPUT_PORT_LIST", "READ_RUNNING_APPS",
            "READ_LGE_TV_INPUT_EVENTS"
        )
    }
}

object LgKeyMap {
    sealed interface Mapped {
        data class Button(val name: String) : Mapped
        data class Ssap(val uri: String, val payload: JSONObject? = null) : Mapped
    }

    fun toWebOs(key: RemoteKey): Mapped? = when (key) {
        RemoteKey.POWER -> Mapped.Ssap("ssap://system/turnOff")
        RemoteKey.HOME -> Mapped.Button("HOME")
        RemoteKey.BACK -> Mapped.Button("BACK")
        RemoteKey.MENU -> Mapped.Button("MENU")
        RemoteKey.EXIT -> Mapped.Button("EXIT")
        RemoteKey.INFO -> Mapped.Button("INFO")
        RemoteKey.UP -> Mapped.Button("UP")
        RemoteKey.DOWN -> Mapped.Button("DOWN")
        RemoteKey.LEFT -> Mapped.Button("LEFT")
        RemoteKey.RIGHT -> Mapped.Button("RIGHT")
        RemoteKey.OK -> Mapped.Button("ENTER")
        RemoteKey.VOL_UP -> Mapped.Ssap("ssap://audio/volumeUp")
        RemoteKey.VOL_DOWN -> Mapped.Ssap("ssap://audio/volumeDown")
        RemoteKey.MUTE -> Mapped.Ssap(
            "ssap://audio/setMute",
            JSONObject().apply { put("mute", true) }
        )
        RemoteKey.CH_UP -> Mapped.Ssap("ssap://tv/channelUp")
        RemoteKey.CH_DOWN -> Mapped.Ssap("ssap://tv/channelDown")
        RemoteKey.PLAY -> Mapped.Button("PLAY")
        RemoteKey.PAUSE -> Mapped.Button("PAUSE")
        RemoteKey.STOP -> Mapped.Button("STOP")
        RemoteKey.REWIND -> Mapped.Button("REWIND")
        RemoteKey.FORWARD -> Mapped.Button("FASTFORWARD")
        RemoteKey.NUM_0 -> Mapped.Button("0")
        RemoteKey.NUM_1 -> Mapped.Button("1")
        RemoteKey.NUM_2 -> Mapped.Button("2")
        RemoteKey.NUM_3 -> Mapped.Button("3")
        RemoteKey.NUM_4 -> Mapped.Button("4")
        RemoteKey.NUM_5 -> Mapped.Button("5")
        RemoteKey.NUM_6 -> Mapped.Button("6")
        RemoteKey.NUM_7 -> Mapped.Button("7")
        RemoteKey.NUM_8 -> Mapped.Button("8")
        RemoteKey.NUM_9 -> Mapped.Button("9")
        RemoteKey.RED -> Mapped.Button("RED")
        RemoteKey.GREEN -> Mapped.Button("GREEN")
        RemoteKey.YELLOW -> Mapped.Button("YELLOW")
        RemoteKey.BLUE -> Mapped.Button("BLUE")
        else -> null
    }

    fun appId(key: RemoteKey): String? = when (key) {
        RemoteKey.APP_NETFLIX -> "netflix"
        RemoteKey.APP_YOUTUBE -> "youtube.leanback.v4"
        RemoteKey.APP_PRIME -> "amazon"
        RemoteKey.APP_DISNEY -> "com.disney.disneyplus-prod"
        RemoteKey.APP_HBO -> "com.hbo.hbomax"
        RemoteKey.APP_SPOTIFY -> "spotify-beehive"
        RemoteKey.APP_HULU -> "hulu"
        RemoteKey.APP_PLEX -> "cdp-30"
        else -> null
    }
}
