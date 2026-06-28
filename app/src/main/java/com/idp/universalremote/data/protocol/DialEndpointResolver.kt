package com.idp.universalremote.data.protocol

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves a DIAL-capable TV's "Application-URL" base, given just an IP address.
 *
 * Strategy:
 *   1. Fetch the UPnP device description from each likely port (1900 SSDP target,
 *      8008, 8060, 56789, 49152) and read the `Application-URL` HTTP header.
 *   2. If that fails, fall back to the well-known DIAL paths.
 *
 * Returns the base URL (no trailing slash) on success, null otherwise.
 */
object DialEndpointResolver {
    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private val candidatePaths = listOf(
        "http://%s:8008/ssdp/device-desc.xml",   // Chromecast / Android TV / Google TV
        "http://%s:8060/dial/dd.xml",            // Roku
        "http://%s:8080/upnp/dev/00000000/desc", // Samsung
        "http://%s:49152/description.xml",
        "http://%s:56789/dmr/SamsungMRDesc.xml",
        "http://%s:7676/smp_4_/" // Samsung SMP
    )

    fun resolve(host: String): String? {
        candidatePaths.forEach { template ->
            val url = template.format(host)
            val request = Request.Builder().url(url).get().build()
            runCatching {
                client.newCall(request).execute().use { resp ->
                    val appUrl = resp.header("Application-URL")
                        ?: resp.header("Application-Url")
                        ?: resp.header("APPLICATION-URL")
                    if (!appUrl.isNullOrBlank()) {
                        return appUrl.trimEnd('/')
                    }
                }
            }
        }
        // Last-resort: try the de-facto DIAL base path served on common ports.
        val fallbacks = listOf(
            "http://$host:8008/apps",
            "http://$host:8060",
            "http://$host:8001/api/v2/applications",
            "http://$host:80/dial/apps"
        )
        fallbacks.forEach { url ->
            val request = Request.Builder().url(url).get().build()
            runCatching {
                client.newCall(request).execute().use { resp ->
                    if (resp.code in 200..404) return url
                }
            }
        }
        return null
    }
}
