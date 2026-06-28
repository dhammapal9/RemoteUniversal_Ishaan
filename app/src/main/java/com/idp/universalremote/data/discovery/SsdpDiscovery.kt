package com.idp.universalremote.data.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.idp.universalremote.domain.model.ConnectionType
import com.idp.universalremote.domain.model.TvBrand
import com.idp.universalremote.domain.model.TvDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSDP M-SEARCH multicast (UPnP / DIAL). Most Smart TVs respond to this even when
 * they don't advertise the modern Bonjour service types Android's NSD scans by default.
 */
@Singleton
class SsdpDiscovery @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun discover(): Flow<TvDevice> = callbackFlow {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("universal-remote-ssdp")
        lock.setReferenceCounted(false)
        lock.acquire()

        val socket = MulticastSocket()
        socket.reuseAddress = true
        socket.soTimeout = 4000
        try {
            val group = InetAddress.getByName(SSDP_HOST)
            SEARCH_TARGETS.forEach { target ->
                val msg = buildSearchPayload(target).toByteArray()
                socket.send(DatagramPacket(msg, msg.size, InetSocketAddress(group, SSDP_PORT)))
            }

            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + 6_000
            while (System.currentTimeMillis() < deadline && !isClosedForSend) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: Throwable) {
                    continue
                }
                val payload = String(packet.data, 0, packet.length)
                parseDevice(payload, packet.address.hostAddress)?.let { trySend(it) }
            }
        } finally {
            runCatching { socket.close() }
            runCatching { lock.release() }
            channel.close()
        }
        awaitClose { runCatching { socket.close() } }
    }.flowOn(Dispatchers.IO)

    private fun buildSearchPayload(target: String): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $target\r\n" +
            "USER-AGENT: UniversalRemote/1.0 UPnP/1.1\r\n\r\n"

    private fun parseDevice(payload: String, ip: String?): TvDevice? {
        if (ip.isNullOrBlank()) return null
        val headers = payload.lines()
            .mapNotNull { line ->
                val sep = line.indexOf(':')
                if (sep <= 0) null else line.substring(0, sep).trim().uppercase() to
                    line.substring(sep + 1).trim()
            }
            .toMap()
        val location = headers["LOCATION"] ?: return null
        val server = headers["SERVER"].orEmpty()
        val st = headers["ST"].orEmpty()
        val usn = headers["USN"].orEmpty()

        // Reject only obvious printers / smart plugs / hubs / NAS storage — anything
        // else (including routers with DLNA enabled) can stay in the list, matching
        // the SensusTech UX. The connect flow itself falls back gracefully when a
        // listed device turns out not to speak any known TV protocol.
        if (HARD_BLOCKLIST.any { server.contains(it, ignoreCase = true) }) return null
        if (HARD_BLOCKLIST.any { usn.contains(it, ignoreCase = true) }) return null

        // Accept anything that looks like a media device or that smells like a TV.
        val isMediaLike = st.contains("dial-multiscreen-org", true) ||
            st.contains("RemoteControlReceiver", true) ||
            st.contains("MediaRenderer", true) ||
            st.contains("MediaServer", true) ||
            st.contains("urn:roku", true) ||
            st.contains("roku:ecp", true) ||
            st.contains("ssdp:all", true) ||
            server.isNotBlank()
        if (!isMediaLike) return null

        val brand = when {
            server.contains("samsung", true) || server.contains("Tizen", true) -> TvBrand.SAMSUNG
            server.contains("roku", true) -> TvBrand.ROKU
            server.contains("LG", true) || server.contains("WebOS", true) -> TvBrand.LG
            server.contains("Sony", true) || server.contains("BRAVIA", true) -> TvBrand.SONY
            server.contains("Hisense", true) || server.contains("VIDAA", true) -> TvBrand.HISENSE
            server.contains("TCL", true) -> TvBrand.TCL
            server.contains("VIZIO", true) -> TvBrand.VIZIO
            else -> TvBrand.GENERIC
        }
        val name = headers["X-RINCON-MODELNAME"]
            ?: headers["X-USER-AGENT"]
            ?: location.substringAfter("//").substringBefore('/').ifBlank { "Smart TV" }

        return TvDevice(
            id = usn.ifBlank { ip },
            name = name.take(48),
            brand = brand,
            ipAddress = ip,
            type = ConnectionType.WIFI
        )
    }

    companion object {
        /**
         * Only reject *obvious* non-TVs. Routers stay in the list (the user can
         * still tap one — the connect flow gracefully degrades to limited mode).
         */
        private val HARD_BLOCKLIST = listOf(
            "Printer", "Sonos", "HUE", "Philips Hue", "Chromecast Audio",
            "Echo Dot", "ZWave", "ZigBee", "smartplug"
        )
        private const val SSDP_HOST = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private val SEARCH_TARGETS = listOf(
            "ssdp:all",
            "urn:dial-multiscreen-org:service:dial:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:samsung.com:device:RemoteControlReceiver:1",
            "roku:ecp"
        )
    }
}
