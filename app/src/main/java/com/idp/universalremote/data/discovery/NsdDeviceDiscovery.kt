package com.idp.universalremote.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.idp.universalremote.domain.model.ConnectionType
import com.idp.universalremote.domain.model.TvBrand
import com.idp.universalremote.domain.model.TvDevice
import com.idp.universalremote.domain.repository.DeviceDiscoveryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Combines Android's NSD multicast DNS service discovery (modern TVs) with SSDP
 * (older / UPnP / DIAL TVs). Devices found on either channel are merged into a
 * single ordered list emitted by [discover].
 */
@Singleton
class NsdDeviceDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ssdp: SsdpDiscovery
) : DeviceDiscoveryRepository {

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val discovered = LinkedHashMap<String, TvDevice>()
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()

    override fun discover(): Flow<List<TvDevice>> = combine(
        nsdFlow(),
        ssdpFlow()
    ) { _, _ -> discovered.values.sortedBy { it.name } }

    private fun nsdFlow(): Flow<Unit> = callbackFlow {
        discovered.clear()
        val active = mutableListOf<NsdManager.DiscoveryListener>()
        SERVICE_TYPES.forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "NSD start failed for $serviceType: $errorCode")
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onServiceFound(info: NsdServiceInfo) {
                    resolve(info) { resolved ->
                        val device = resolved.toDevice()
                        merge(device)
                        trySend(Unit)
                    }
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    discovered.remove(info.serviceName ?: return)
                    trySend(Unit)
                }
            }
            active += listener
            runCatching {
                nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        }
        listeners += active
        trySend(Unit)
        awaitClose {
            active.forEach { runCatching { nsdManager.stopServiceDiscovery(it) } }
            listeners.removeAll(active)
        }
    }

    private fun ssdpFlow(): Flow<Unit> = flow {
        emit(Unit) // ensure combine fires at least once
        ssdp.discover().collect { device ->
            merge(device)
            emit(Unit)
        }
    }

    private fun merge(incoming: TvDevice) {
        val key = incoming.id
        val existing = discovered[key]
        if (existing == null ||
            (existing.brand == TvBrand.GENERIC && incoming.brand != TvBrand.GENERIC) ||
            (existing.ipAddress.isNullOrBlank() && !incoming.ipAddress.isNullOrBlank())
        ) {
            discovered[key] = incoming
        }
    }

    override suspend fun stop() {
        listeners.forEach { runCatching { nsdManager.stopServiceDiscovery(it) } }
        listeners.clear()
    }

    private fun resolve(info: NsdServiceInfo, onResolved: (NsdServiceInfo) -> Unit) {
        val cb = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) = onResolved(serviceInfo)
        }
        runCatching { nsdManager.resolveService(info, cb) }
    }

    private fun NsdServiceInfo.toDevice(): TvDevice {
        val name = serviceName ?: "Smart TV"
        val ip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hostAddresses.firstOrNull()?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            host?.hostAddress
        }
        val brand = brandFromServiceType(serviceType) ?: TvBrand.fromName(name)
        return TvDevice(
            id = "${name}@${ip ?: "?"}",
            name = name,
            brand = brand,
            ipAddress = ip,
            type = ConnectionType.WIFI
        )
    }

    private fun brandFromServiceType(type: String?): TvBrand? = when {
        type == null -> null
        type.contains("googlecast", true) -> TvBrand.GOOGLE_TV
        type.contains("androidtvremote", true) -> TvBrand.ANDROID_TV
        type.contains("airplay", true) -> TvBrand.GENERIC
        type.contains("samsungmsf", true) -> TvBrand.SAMSUNG
        type.contains("rsp", true) -> TvBrand.ROKU
        type.contains("webos", true) -> TvBrand.LG
        type.contains("sony", true) || type.contains("bravia", true) -> TvBrand.SONY
        else -> null
    }

    companion object {
        private const val TAG = "NsdDeviceDiscovery"
        private val SERVICE_TYPES = listOf(
            "_googlecast._tcp.",
            "_androidtvremote2._tcp.",
            "_airplay._tcp.",
            "_samsungmsf._tcp.",
            "_samsung-rcr._tcp.",
            "_rsp._tcp.",
            "_dial._tcp.",
            "_webos-second-screen._tcp.",
            "_lge-bravia._tcp.",
            "_workstation._tcp."
        )
    }
}
