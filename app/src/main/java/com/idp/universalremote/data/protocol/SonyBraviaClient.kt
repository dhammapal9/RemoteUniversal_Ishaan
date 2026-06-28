package com.idp.universalremote.data.protocol

import com.idp.universalremote.domain.model.RemoteKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Sony Bravia IRCC-IP. Sends SOAP envelopes to `http://<ip>/sony/IRCC`.
 *
 * Setup on the TV:
 *   Settings → Network → Home Network Setup → IP Control →
 *     Authentication: "Normal and Pre-Shared Key"
 *     Pre-Shared Key: set any string (the user provides this to the app)
 */
class SonyBraviaClient(
    private val host: String,
    private val preSharedKey: String? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    fun sendKey(key: RemoteKey): Boolean {
        val code = SonyKeyMap.toIrcc(key) ?: return false
        val body = SOAP_TEMPLATE.replace("{{code}}", code).toRequestBody(SOAP_MEDIA)
        val builder = Request.Builder()
            .url("http://$host/sony/IRCC")
            .header("SOAPACTION", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"")
            .header("Content-Type", "text/xml; charset=UTF-8")
            .post(body)
        preSharedKey?.takeIf { it.isNotBlank() }?.let { builder.header("X-Auth-PSK", it) }
        return runCatching {
            client.newCall(builder.build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun ping(): Boolean {
        val req = Request.Builder().url("http://$host/sony/system").get()
            .apply {
                preSharedKey?.takeIf { it.isNotBlank() }?.let { header("X-Auth-PSK", it) }
            }.build()
        return runCatching {
            client.newCall(req).execute().use { it.code in 200..499 }
        }.getOrDefault(false)
    }

    companion object {
        private val SOAP_MEDIA = "text/xml; charset=UTF-8".toMediaType()
        private const val SOAP_TEMPLATE =
            "<?xml version=\"1.0\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:X_SendIRCC xmlns:u=\"urn:schemas-sony-com:service:IRCC:1\">" +
                "<IRCCCode>{{code}}</IRCCCode>" +
                "</u:X_SendIRCC>" +
                "</s:Body></s:Envelope>"
    }
}

/** Base64-encoded IRCC remote codes (taken from Sony's IRCC service description). */
object SonyKeyMap {
    fun toIrcc(key: RemoteKey): String? = when (key) {
        RemoteKey.POWER -> "AAAAAQAAAAEAAAAVAw=="
        RemoteKey.HOME -> "AAAAAQAAAAEAAABgAw=="
        RemoteKey.BACK -> "AAAAAgAAAJcAAAAjAw=="
        RemoteKey.MENU -> "AAAAAQAAAAEAAABgAw=="
        RemoteKey.EXIT -> "AAAAAQAAAAEAAABjAw=="
        RemoteKey.INFO -> "AAAAAQAAAAEAAAA6Aw=="
        RemoteKey.SOURCE -> "AAAAAQAAAAEAAAAlAw=="
        RemoteKey.UP -> "AAAAAQAAAAEAAAB0Aw=="
        RemoteKey.DOWN -> "AAAAAQAAAAEAAAB1Aw=="
        RemoteKey.LEFT -> "AAAAAQAAAAEAAAA0Aw=="
        RemoteKey.RIGHT -> "AAAAAQAAAAEAAAAzAw=="
        RemoteKey.OK -> "AAAAAQAAAAEAAABlAw=="
        RemoteKey.VOL_UP -> "AAAAAQAAAAEAAAASAw=="
        RemoteKey.VOL_DOWN -> "AAAAAQAAAAEAAAATAw=="
        RemoteKey.MUTE -> "AAAAAQAAAAEAAAAUAw=="
        RemoteKey.CH_UP -> "AAAAAQAAAAEAAAAQAw=="
        RemoteKey.CH_DOWN -> "AAAAAQAAAAEAAAARAw=="
        RemoteKey.PLAY -> "AAAAAgAAAJcAAAAaAw=="
        RemoteKey.PAUSE -> "AAAAAgAAAJcAAAAZAw=="
        RemoteKey.STOP -> "AAAAAgAAAJcAAAAYAw=="
        RemoteKey.REWIND -> "AAAAAgAAAJcAAAAbAw=="
        RemoteKey.FORWARD -> "AAAAAgAAAJcAAAAcAw=="
        RemoteKey.NUM_0 -> "AAAAAQAAAAEAAAAJAw=="
        RemoteKey.NUM_1 -> "AAAAAQAAAAEAAAAAAw=="
        RemoteKey.NUM_2 -> "AAAAAQAAAAEAAAABAw=="
        RemoteKey.NUM_3 -> "AAAAAQAAAAEAAAACAw=="
        RemoteKey.NUM_4 -> "AAAAAQAAAAEAAAADAw=="
        RemoteKey.NUM_5 -> "AAAAAQAAAAEAAAAEAw=="
        RemoteKey.NUM_6 -> "AAAAAQAAAAEAAAAFAw=="
        RemoteKey.NUM_7 -> "AAAAAQAAAAEAAAAGAw=="
        RemoteKey.NUM_8 -> "AAAAAQAAAAEAAAAHAw=="
        RemoteKey.NUM_9 -> "AAAAAQAAAAEAAAAIAw=="
        RemoteKey.RED -> "AAAAAgAAAJcAAAAlAw=="
        RemoteKey.GREEN -> "AAAAAgAAAJcAAAAmAw=="
        RemoteKey.YELLOW -> "AAAAAgAAAJcAAAAnAw=="
        RemoteKey.BLUE -> "AAAAAgAAAJcAAAAkAw=="
        else -> null
    }
}
