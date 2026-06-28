package com.idp.universalremote.data.protocol

import com.idp.universalremote.domain.model.RemoteKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * DIAL (Discovery and Launch) — the Netflix/Chromecast-pioneered UPnP protocol
 * that's baked into nearly every Smart TV's network stack (Samsung, LG, Sony,
 * Vizio, Hisense, TCL, Philips, Panasonic, Sharp, Toshiba…).
 *
 * Limitations:
 *   - DIAL can ONLY *launch apps*. It cannot send key events (no UP/DOWN/OK).
 *   - That said, on TVs that expose nothing else over the network, this is the
 *     only thing that works without pairing — and it works for **every** brand.
 *
 * Protocol:
 *   1. POST http://<app-base-url>/<AppName>                        → launch app
 *   2. DELETE http://<app-base-url>/<AppName>/run                  → close app
 *   3. GET http://<app-base-url>/<AppName>                         → query state
 *
 * The app-base-url is discovered via SSDP (the `Application-URL` HTTP header on
 * the description URL) or by trying the well-known ports 8060 / 8008 / 56789.
 */
class DialClient(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    fun launch(appName: String, payload: String? = null): Boolean {
        val url = "$baseUrl/$appName"
        val body = (payload ?: "").toRequestBody("text/plain".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        return runCatching {
            client.newCall(request).execute().use { resp ->
                resp.isSuccessful || resp.code == 201
            }
        }.getOrDefault(false)
    }

    fun ping(): Boolean {
        val request = Request.Builder().url(baseUrl).get().build()
        return runCatching {
            client.newCall(request).execute().use { it.code in 200..404 }
        }.getOrDefault(false)
    }

    fun launchForKey(key: RemoteKey): Boolean {
        val app = DialKeyMap.appName(key) ?: return false
        return launch(app)
    }
}

object DialKeyMap {
    /** Standardised DIAL application names. */
    fun appName(key: RemoteKey): String? = when (key) {
        RemoteKey.APP_NETFLIX -> "Netflix"
        RemoteKey.APP_YOUTUBE -> "YouTube"
        RemoteKey.APP_PRIME -> "PrimeVideo"
        RemoteKey.APP_DISNEY -> "DisneyPlus"
        RemoteKey.APP_HBO -> "HBOGo"
        RemoteKey.APP_HULU -> "Hulu"
        RemoteKey.APP_SPOTIFY -> "Spotify"
        RemoteKey.APP_APPLE_TV -> "AppleTV"
        RemoteKey.APP_PARAMOUNT -> "ParamountPlus"
        RemoteKey.APP_PEACOCK -> "PeacockTV"
        RemoteKey.APP_PLEX -> "Plex"
        RemoteKey.APP_TUBI -> "Tubi"
        RemoteKey.APP_PLUTO -> "PlutoTV"
        else -> null
    }
}
