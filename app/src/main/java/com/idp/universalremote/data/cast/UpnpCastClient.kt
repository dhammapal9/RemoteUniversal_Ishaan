package com.idp.universalremote.data.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * UPnP / DLNA AVTransport client for casting media URLs to a Smart TV.
 *
 * Discovery uses **SSDP M-SEARCH** (the protocol UPnP itself defines) — we
 * unicast-receive on a one-shot UDP socket while sending an M-SEARCH multicast
 * for `urn:schemas-upnp-org:service:AVTransport:1`. Every UPnP MediaRenderer in
 * the LAN answers with a `LOCATION` URL pointing at its device description.
 *
 * Why not hardcoded ports? Smart TVs use ephemeral ports for UPnP services:
 *   - Samsung Tizen: 7676 (varies by model)
 *   - LG webOS: 9197 (sometimes 60000+)
 *   - Sony Bravia: random ephemeral (40000+)
 *   - Xiaomi Mi TV / Mi Box: random
 *   - Hisense VIDAA: random
 *
 * Probing fixed ports finds maybe 30% of TVs. SSDP finds 100% of UPnP-capable
 * TVs because each TV advertises its own dynamic port.
 */
class UpnpCastClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    /**
     * Resolve the AVTransport control URL of the TV at [host]. Returns null when
     * the TV doesn't expose a UPnP MediaRenderer (typical for some Android TVs
     * that ship only Google Cast).
     */
    fun resolve(host: String): String? {
        // 1. SSDP M-SEARCH — finds every UPnP MediaRenderer on the LAN.
        ssdpDiscoverLocations(host).forEach { location ->
            extractAvTransportFromLocation(location)?.let { return it }
        }
        // 2. Fallback — probe a handful of well-known device-description paths in
        // case the TV firewalls SSDP but still exposes UPnP HTTP.
        FALLBACK_DESCRIPTION_PATHS.forEach { path ->
            val url = "http://$host:$path"
            extractAvTransportFromLocation(url)?.let { return it }
        }
        return null
    }

    /**
     * @param controlUrl resolved via [resolve]
     * @param mediaUrl absolute HTTP URL the TV can fetch from (served by
     *                 HttpFileServer running on the phone)
     * @param mime e.g. "image/jpeg", "video/mp4", "audio/mpeg"
     */
    fun castUrl(controlUrl: String, mediaUrl: String, mime: String, title: String): Boolean {
        // Some TVs refuse to play a previous URI's leftover state — issue Stop
        // first so SetAVTransportURI lands cleanly.
        runCatching { postSoap(controlUrl, "Stop", soap("Stop", "<InstanceID>0</InstanceID>")) }

        val setUriBody = soap(
            action = "SetAVTransportURI",
            payload = """
                <InstanceID>0</InstanceID>
                <CurrentURI>${escape(mediaUrl)}</CurrentURI>
                <CurrentURIMetaData>${escape(metadata(mediaUrl, mime, title))}</CurrentURIMetaData>
            """.trimIndent()
        )
        if (!postSoap(controlUrl, "SetAVTransportURI", setUriBody)) return false
        val playBody = soap("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
        return postSoap(controlUrl, "Play", playBody)
    }

    // ── SSDP M-SEARCH ────────────────────────────────────────────────────────────
    /**
     * Send M-SEARCH for AVTransport and collect LOCATION URLs whose host matches
     * [tvIp]. Runs synchronously with a 3-second receive window — UPnP responses
     * arrive within ~MX seconds (we send MX=2).
     */
    private fun ssdpDiscoverLocations(tvIp: String): List<String> {
        val locations = mutableListOf<String>()
        // Android's Wi-Fi power-save filters multicast traffic unless we hold a
        // MulticastLock. Without it, SSDP replies silently never reach our socket
        // even though the TV is replying. Released at the end of this call.
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("universal-remote-cast")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        try {
            val socket = runCatching {
                DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 700
                }
            }.getOrNull() ?: return emptyList()

            socket.use { s ->
                val group = InetAddress.getByName(SSDP_HOST)
                SEARCH_TARGETS.forEach { target ->
                    val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $target\r\n" +
                        "USER-AGENT: UniversalRemote/1.0 UPnP/1.1\r\n\r\n").toByteArray()
                    runCatching { s.send(DatagramPacket(msg, msg.size, InetSocketAddress(group, SSDP_PORT))) }
                }

                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    val received = runCatching { s.receive(packet) }.isSuccess
                    if (!received) continue
                    val payload = String(packet.data, 0, packet.length)
                    val location = LOCATION_REGEX.find(payload)?.groupValues?.getOrNull(1)?.trim() ?: continue
                    // Only consider responses from the TV we're paired with —
                    // there might be other UPnP devices on the LAN.
                    if (location.contains(tvIp)) locations += location
                }
            }
        } finally {
            runCatching { lock?.release() }
        }
        Log.d(TAG, "SSDP found ${locations.size} location(s) for $tvIp: $locations")
        return locations.distinct()
    }

    private fun extractAvTransportFromLocation(locationUrl: String): String? {
        val xml = runCatching {
            client.newCall(Request.Builder().url(locationUrl).get().build())
                .execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return null

        // Some TVs ship multiple service blocks; we want the AVTransport one.
        val serviceBlocks = SERVICE_REGEX.findAll(xml)
        for (m in serviceBlocks) {
            val block = m.value
            if (!block.contains("AVTransport", ignoreCase = true)) continue
            val control = CONTROL_REGEX.find(block)?.groupValues?.getOrNull(1)?.trim() ?: continue
            return resolveRelativeUrl(locationUrl, xml, control)
        }
        return null
    }

    /** Resolve a possibly-relative `<controlURL>` against the device description URL. */
    private fun resolveRelativeUrl(locationUrl: String, xml: String, control: String): String {
        if (control.startsWith("http", ignoreCase = true)) return control
        // `<URLBase>` overrides the LOCATION host if present.
        val base = URLBASE_REGEX.find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        val parent = base ?: locationUrl
        return runCatching { URI(parent).resolve(control).toString() }
            .getOrElse {
                // Hand-stitch on URI parse failure: chop the path from LOCATION,
                // keep scheme+host:port, append the (slash-prefixed) controlURL.
                val origin = parent.substringBefore("://") + "://" +
                    parent.substringAfter("://").substringBefore("/")
                origin + (if (control.startsWith("/")) control else "/$control")
            }
    }

    // ── SOAP ────────────────────────────────────────────────────────────────────
    private fun postSoap(controlUrl: String, action: String, body: String): Boolean = runCatching {
        val req = Request.Builder()
            .url(controlUrl)
            .header("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use {
            val ok = it.isSuccessful
            if (!ok) Log.w(TAG, "SOAP $action → HTTP ${it.code}: ${it.body?.string()?.take(200)}")
            ok
        }
    }.onFailure { Log.w(TAG, "SOAP $action failed: ${it.message}") }
        .getOrDefault(false)

    private fun soap(action: String, payload: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
              $payload
            </u:$action>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    /** DIDL-Lite metadata — many TVs refuse playback if this is empty or malformed. */
    private fun metadata(url: String, mime: String, title: String): String {
        val upnpClass = when {
            mime.startsWith("image/") -> "object.item.imageItem.photo"
            mime.startsWith("video/") -> "object.item.videoItem.movie"
            mime.startsWith("audio/") -> "object.item.audioItem.musicTrack"
            else -> "object.item"
        }
        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite"
                       xmlns:dc="http://purl.org/dc/elements/1.1/"
                       xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
              <item id="0" parentID="-1" restricted="1">
                <dc:title>${escape(title)}</dc:title>
                <upnp:class>$upnpClass</upnp:class>
                <res protocolInfo="http-get:*:$mime:*">${escape(url)}</res>
              </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    companion object {
        private const val TAG = "UpnpCastClient"
        private const val SSDP_HOST = "239.255.255.250"
        private const val SSDP_PORT = 1900

        /**
         * Search targets the M-SEARCH sends. AVTransport is the canonical target
         * for casting; we also send ssdp:all and MediaRenderer in case the TV's
         * firmware only answers to broader queries.
         */
        private val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "ssdp:all"
        )

        /** Last-resort port probes if SSDP gets no answer. */
        private val FALLBACK_DESCRIPTION_PATHS = listOf(
            "1400/xml/device_description.xml",
            "7676/smp_14_",
            "9197/dmr",
            "52323/dmr/SmpDmr.xml",
            "49152/description.xml",
            "8008/ssdp/device-desc.xml",
            "8200/rootdesc.xml"
        )

        private val LOCATION_REGEX = Regex("(?im)^LOCATION:\\s*(.+)$")
        private val SERVICE_REGEX = Regex("<service>.*?</service>", RegexOption.DOT_MATCHES_ALL)
        private val CONTROL_REGEX = Regex("<controlURL>(.*?)</controlURL>", RegexOption.DOT_MATCHES_ALL)
        private val URLBASE_REGEX = Regex("<URLBase>(.*?)</URLBase>", RegexOption.DOT_MATCHES_ALL)
    }
}
