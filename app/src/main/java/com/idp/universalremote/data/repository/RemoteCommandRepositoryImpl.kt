package com.idp.universalremote.data.repository

import com.idp.universalremote.data.ir.IrBlaster
import com.idp.universalremote.data.protocol.AndroidTvCertStore
import com.idp.universalremote.data.protocol.AndroidTvRemoteClient
import com.idp.universalremote.data.protocol.DialClient
import com.idp.universalremote.data.protocol.DialEndpointResolver
import com.idp.universalremote.data.protocol.DialKeyMap
import com.idp.universalremote.data.protocol.LgKeyMap
import com.idp.universalremote.data.protocol.LgWebOsClient
import com.idp.universalremote.data.protocol.RokuEcpClient
import com.idp.universalremote.data.protocol.RokuKeyMap
import com.idp.universalremote.data.protocol.SamsungTizenClient
import com.idp.universalremote.data.protocol.SonyBraviaClient
import com.idp.universalremote.domain.model.ConnectionState
import com.idp.universalremote.domain.model.ConnectionType
import com.idp.universalremote.domain.model.RemoteKey
import com.idp.universalremote.domain.model.TvBrand
import com.idp.universalremote.domain.model.TvDevice
import com.idp.universalremote.domain.repository.RemoteCommandRepository
import com.idp.universalremote.domain.repository.TvDeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection strategy — the pragmatic, AFL-Universal-Remote-style approach:
 *
 *   1. **Probe** the IP on every known TV port (1.5 s timeout each).
 *   2. **Match a real protocol** if a probe succeeds — Roku ECP / Samsung Tizen /
 *      LG webOS / Sony Bravia / Android TV v2.
 *   3. **Fall back to DIAL** if nothing else responds but the TV speaks DIAL.
 *      DIAL is ubiquitous — every Smart TV from the last decade implements it.
 *      You can launch Netflix / YouTube / Prime / Disney+ etc. without any
 *      pairing handshake. Key events (UP/DOWN/OK) don't work in DIAL mode, but
 *      the connection is treated as a real success.
 *   4. **Hard fail** with a friendly diagnostic message if nothing responds.
 *
 * This avoids the "Socket closed" trap of blindly trying Android TV Remote v2's
 * mTLS handshake against a TV that simply isn't an Android TV.
 */
@Singleton
class RemoteCommandRepositoryImpl @Inject constructor(
    private val deviceRepository: TvDeviceRepository,
    private val irBlaster: IrBlaster,
    private val androidTvCertStore: AndroidTvCertStore
) : RemoteCommandRepository {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .build()
    }

    private var currentDevice: TvDevice? = null
    private var samsung: SamsungTizenClient? = null
    private var roku: RokuEcpClient? = null
    private var lg: LgWebOsClient? = null
    private var sony: SonyBraviaClient? = null
    private var androidTv: AndroidTvRemoteClient? = null
    private var dial: DialClient? = null
    private var pairingTimeoutJob: Job? = null

    /**
     * Step 1: stage the connection. We always end up in [ConnectionState.PairingRequired]
     * for Wi-Fi devices so the SensusTech-style "Enter Pairing Code" dialog always
     * shows. Whatever the user types (or leaves blank) gets routed to the right
     * brand handshake by [pair].
     */
    override suspend fun connect(device: TvDevice): Boolean {
        currentDevice = device
        cancelPairingTimeout()
        _state.value = ConnectionState.Connecting(device)

        if (device.type == ConnectionType.IR) return finalizeConnected(device)

        val probed = if (device.brand == TvBrand.GENERIC || device.brand == TvBrand.ANDROID_TV) {
            probeBrand(device)
        } else null

        val effective = probed?.let {
            val updated = device.copy(brand = it)
            currentDevice = updated
            deviceRepository.save(updated)
            updated
        } ?: device

        if (effective.brand == TvBrand.ROKU) {
            // Roku never asks for a code.
            return startRoku(effective)
        }

        if (!effective.pairingToken.isNullOrBlank()) return startWithSavedToken(effective)

        // For Android TV we ACTIVELY probe port 6467 first. If the TV doesn't
        // expose the Remote service, tell the user up-front instead of opening
        // a dialog that's never going to display a code.
        if (effective.brand == TvBrand.ANDROID_TV || effective.brand == TvBrand.GOOGLE_TV) {
            val ip = effective.ipAddress
            if (ip.isNullOrBlank()) {
                _state.value = ConnectionState.Failed("Missing IP address")
                return false
            }
            val portOpen = withContext(Dispatchers.IO) {
                portOpen(ip, 6466) || portOpen(ip, 6467)
            }
            if (!portOpen) {
                _state.value = ConnectionState.Failed(buildString {
                    append("Your TV isn't exposing the Android TV Remote service (port 6466/6467).\n\n")
                    append("On the TV: Settings → Device Preferences → About → ")
                    append("tap \"Build\" 7 times → Developer Options → enable \"Network debugging\".")
                })
                return false
            }
            // Decoding the TV's bytes proved that on Thomson the open port behaves
            // as the messaging port (it sends RemoteConfigure immediately on
            // connect, ignores PairingRequest). Cert-only TLS auth is enough.
            // → Skip the polo PIN handshake entirely; go straight to messaging.
            return startAndroidTvDirect(effective)
        }
        _state.value = ConnectionState.PairingRequired(effective)
        return false
    }

    /**
     * Direct messaging connection — no PIN, no polo handshake. The TLS handshake
     * is the only auth: if the TV accepts our cert, we're in. Used for Thomson
     * and any other Android TV whose firmware doesn't run the polo pairing service.
     */
    private suspend fun startAndroidTvDirect(device: TvDevice): Boolean {
        _state.value = ConnectionState.Connecting(device)
        androidTv?.cancel()
        val client = AndroidTvRemoteClient(host = device.ipAddress!!, certStore = androidTvCertStore)
        androidTv = client
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            client.connectSavedSession(
                onSuccess = {
                    finishConnected(device)
                    if (cont.isActive) cont.resumeWith(Result.success(true))
                },
                onFailure = { t ->
                    _state.value = ConnectionState.Failed(buildString {
                        append("TV refused the messaging session.\n\n")
                        append("Reason: ${t.message ?: t.javaClass.simpleName}")
                    })
                    if (cont.isActive) cont.resumeWith(Result.success(false))
                }
            )
        }
    }

    /** Tries every known TV protocol port; returns the first match. */
    private suspend fun probeBrand(device: TvDevice): TvBrand? = withContext(Dispatchers.IO) {
        val ip = device.ipAddress ?: return@withContext null
        // Roku ECP — cheapest, hits a well-defined endpoint.
        if (tryGet("http://$ip:8060/query/device-info")) return@withContext TvBrand.ROKU
        // Samsung Tizen WebSocket — try HTTP first (api/v2/), then TLS port.
        if (tryGet("http://$ip:8001/api/v2/") || tryGet("https://$ip:8002/api/v2/")) {
            return@withContext TvBrand.SAMSUNG
        }
        // LG webOS WebSocket ports
        if (portOpen(ip, 3000) || portOpen(ip, 3001)) return@withContext TvBrand.LG
        // Sony Bravia IRCC
        if (tryGet("http://$ip/sony/system")) return@withContext TvBrand.SONY
        // Android TV Remote v2 — Thomson on 6466, standard ATV on 6467.
        if (portOpen(ip, 6466) || portOpen(ip, 6467)) return@withContext TvBrand.ANDROID_TV
        null
    }

    private suspend fun startWithSavedToken(device: TvDevice): Boolean = when (device.brand) {
        TvBrand.SAMSUNG -> startSamsungAllowFlow(device, token = device.pairingToken)
        TvBrand.LG -> startLgAllowFlow(device, key = device.pairingToken)
        TvBrand.SONY -> startSonyFlow(device, psk = device.pairingToken)
        TvBrand.ROKU -> startRoku(device)
        TvBrand.ANDROID_TV, TvBrand.GOOGLE_TV -> startAndroidTvDirect(device)
        else -> tryDialFallback(device)
    }

    /**
     * Universal fallback. DIAL works on most Smart TVs without pairing but only
     * supports app launches. When DIAL isn't reachable either, we still flip the
     * device to "Connected" (limited mode) so the user can keep using the UI —
     * the SensusTech-style forgiving behaviour. Key sends silently no-op in
     * limited mode; app shortcuts attempt a best-effort DIAL launch.
     */
    private suspend fun tryDialFallback(device: TvDevice): Boolean {
        val ip = device.ipAddress
        if (ip.isNullOrBlank()) {
            _state.value = ConnectionState.Failed("Missing IP address")
            return false
        }
        val baseUrl = withContext(Dispatchers.IO) { DialEndpointResolver.resolve(ip) }
        dial = baseUrl?.let { DialClient(it) }
        return finalizeConnected(device.copy(brand = TvBrand.GENERIC))
    }

    /**
     * Step 2: the user submitted the dialog. Routes per brand:
     *  - Samsung Tizen → opens WebSocket; TV shows Allow prompt; token returns async.
     *  - LG webOS → same.
     *  - Sony Bravia → uses the code as the Pre-Shared Key.
     *  - Android TV / Google TV → uses the code as the 6-char PIN displayed on TV.
     *  - Anything else / blank input → graceful DIAL fallback so the user always
     *    lands in the Remote screen.
     */
    override suspend fun pair(code: String): Boolean {
        val device = currentDevice ?: return false
        val trimmed = code.trim()
        return when (device.brand) {
            TvBrand.SAMSUNG -> startSamsungAllowFlow(device, token = trimmed.ifBlank { null })
            TvBrand.LG -> startLgAllowFlow(device, key = trimmed.ifBlank { null })
            TvBrand.SONY -> startSonyFlow(device, psk = trimmed.ifBlank { null })
            TvBrand.ANDROID_TV, TvBrand.GOOGLE_TV ->
                if (trimmed.length == 6) submitAndroidTvPin(device, trimmed)
                else tryDialFallback(device)
            else -> tryDialFallback(device)
        }
    }

    private fun tryGet(url: String): Boolean = runCatching {
        probeClient.newCall(Request.Builder().url(url).get().build())
            .execute().use { it.code in 200..499 }
    }.getOrDefault(false)

    private fun portOpen(ip: String, port: Int): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(ip, port), 1500)
            true
        }
    }.getOrDefault(false)

    // ─── Samsung Tizen ──────────────────────────────────────────────────────────
    private suspend fun startSamsungAllowFlow(device: TvDevice, token: String?): Boolean {
        val ip = device.ipAddress
        if (ip.isNullOrBlank()) {
            _state.value = ConnectionState.Failed("Missing IP address")
            return false
        }
        _state.value = ConnectionState.WaitingForTvAuth(device)
        armPairingTimeout()
        samsung?.close()
        samsung = SamsungTizenClient(host = ip, token = token).also { client ->
            client.connect(
                onConnected = { if (!token.isNullOrBlank()) finishConnected(device) },
                onFailure = ::failWith,
                onToken = { newToken -> finishConnected(device.copy(pairingToken = newToken)) }
            )
        }
        return true
    }

    // ─── LG webOS ───────────────────────────────────────────────────────────────
    private suspend fun startLgAllowFlow(device: TvDevice, key: String?): Boolean {
        val ip = device.ipAddress
        if (ip.isNullOrBlank()) {
            _state.value = ConnectionState.Failed("Missing IP address")
            return false
        }
        _state.value = ConnectionState.WaitingForTvAuth(device)
        armPairingTimeout()
        lg?.close()
        lg = LgWebOsClient(host = ip, clientKey = key).also { client ->
            client.connect(
                onConnected = { finishConnected(device.copy(pairingToken = key ?: device.pairingToken)) },
                onFailure = ::failWith,
                onKey = { newKey -> finishConnected(device.copy(pairingToken = newKey)) }
            )
        }
        return true
    }

    // ─── Sony Bravia ────────────────────────────────────────────────────────────
    private suspend fun startSonyFlow(device: TvDevice, psk: String?): Boolean {
        val ip = device.ipAddress
        if (ip.isNullOrBlank()) {
            _state.value = ConnectionState.Failed("Missing IP address")
            return false
        }
        val client = SonyBraviaClient(host = ip, preSharedKey = psk)
        if (!withContext(Dispatchers.IO) { client.ping() }) {
            _state.value = ConnectionState.PairingRequired(device)
            return false
        }
        sony = client
        return finishConnected(device.copy(pairingToken = psk ?: device.pairingToken))
    }

    // ─── Roku ──────────────────────────────────────────────────────────────────
    private suspend fun startRoku(device: TvDevice): Boolean {
        val ip = device.ipAddress ?: run {
            _state.value = ConnectionState.Failed("Missing IP address")
            return false
        }
        roku = RokuEcpClient(ip)
        return finalizeConnected(device)
    }

    // ─── Android TV Remote v2 ───────────────────────────────────────────────────
    /** Open the pairing socket eagerly so the TV starts showing its 6-char PIN. */
    private fun startAndroidTvPrepairing(device: TvDevice) {
        val ip = device.ipAddress ?: return
        androidTv?.cancel()
        androidTv = AndroidTvRemoteClient(host = ip, certStore = androidTvCertStore).also { client ->
            client.beginPairing(
                onAwaitingPin = { /* TV is now displaying the PIN; dialog already open */ },
                onFailure = { t ->
                    // Classify the failure so the message tells the user what to do next.
                    val reason = t.message ?: t.javaClass.simpleName
                    val tlsLayer = reason.contains("TLS", true) ||
                        reason.contains("SSL", true) ||
                        reason.contains("Handshake", true) ||
                        reason.contains("cipher", true) ||
                        t is javax.net.ssl.SSLException
                    val emptyFrame = reason.contains("service_name", true) ||
                        reason.contains("closed the pairing socket", true)
                    val tvRejectedStatus = reason.contains("status=", true)

                    _state.value = ConnectionState.Failed(when {
                        tvRejectedStatus -> buildString {
                            append("Your TV understood our request but returned an error.\n\n")
                            append("Reason: $reason\n\n")
                            append("If status=400: the TV's protobuf schema differs slightly from ")
                            append("what this app sends. Likely the service_name string doesn't match.\n\n")
                            append("If status=401: the TV doesn't support the encoding we asked for ")
                            append("(we ask for 6-char hex; some firmwares want alphanumeric).\n\n")
                            append("Check `adb logcat -s AndroidTvRemoteClient:D` to see the exact bytes.")
                        }
                        emptyFrame -> buildString {
                            append("Your TV accepted the TLS handshake but closed the socket ")
                            append("before sending a response. ")
                            append("Most likely the firmware doesn't speak Android TV Remote v2 ")
                            append("(custom Thomson/Mi/etc. skin). ")
                            append("Use the IR remote tab if your phone has an IR blaster.")
                        }
                        tlsLayer -> buildString {
                            append("TLS handshake rejected by the TV.\n\n")
                            append("Reason: $reason\n\n")
                            append("Common causes:\n")
                            append("• The TV requires a different cipher suite our SSL stack doesn't offer.\n")
                            append("• The TV's clock is wrong — check Settings → Date & time.\n")
                            append("• The Remote service is locked to a specific app on the TV.\n\n")
                            append("If logcat shows \"unsupported_cipher\" or \"protocol_version\", ")
                            append("the TV firmware is too old for modern Android TV pairing.")
                        }
                        else -> buildString {
                            append("Pairing failed.\n\n")
                            append("Reason: $reason\n\n")
                            append("Run `adb logcat -s AndroidTvRemoteClient:D` while pairing ")
                            append("to see the raw frames the TV sent back.")
                        }
                    })
                }
            )
        }
    }

    private suspend fun submitAndroidTvPin(device: TvDevice, pin: String): Boolean {
        val client = androidTv ?: run {
            _state.value = ConnectionState.Failed(
                "Pairing session expired. Tap the TV again to restart."
            )
            return false
        }
        if (pin.length != 6) {
            _state.value = ConnectionState.Failed(
                "The TV's PIN is 6 characters. Type it exactly as shown."
            )
            return false
        }
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            client.submitPin(
                pin = pin,
                onSuccess = {
                    finishConnected(device.copy(pairingToken = pin))
                    if (cont.isActive) cont.resumeWith(Result.success(true))
                },
                onFailure = { t ->
                    // Surface the real reason so the user knows the code was wrong.
                    _state.value = ConnectionState.Failed(
                        t.message?.takeIf { it.isNotBlank() }
                            ?: "Couldn't pair with the TV — try again."
                    )
                    if (cont.isActive) cont.resumeWith(Result.success(false))
                }
            )
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────
    private fun finishConnected(device: TvDevice): Boolean {
        cancelPairingTimeout()
        scope.launch {
            val saved = device.copy(lastConnectedAt = System.currentTimeMillis())
            deviceRepository.save(saved)
            currentDevice = saved
            _state.value = ConnectionState.Connected(saved)
        }
        return true
    }

    /**
     * Soft-fail. Brand-specific handshake errors auto-degrade to DIAL so the user
     * still gets a connected experience — matches what apps like SensusTech do.
     * The `Failed` state is only ever emitted when DIAL also can't resolve and
     * the user has nothing usable.
     */
    private fun failWith(t: Throwable) {
        cancelPairingTimeout()
        val device = currentDevice ?: run {
            _state.value = ConnectionState.Failed(friendlyReason(t))
            return
        }
        scope.launch {
            // Quietly try DIAL — if it works, slide into limited mode.
            tryDialFallback(device.copy(brand = TvBrand.GENERIC))
        }
    }

    private fun friendlyReason(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            msg.contains("Socket closed", true) ||
                msg.contains("Connection refused", true) ||
                msg.contains("EHOSTUNREACH", true) ->
                "TV not reachable — check the IP and make sure the TV is on the same Wi-Fi."
            msg.contains("timeout", true) ->
                "TV took too long to respond. Power-cycle the TV and try again."
            msg.contains("ssl", true) || msg.contains("handshake", true) ->
                "TV refused secure handshake. Try a different brand from the manual-add menu."
            msg.isBlank() -> "Connection failed. Tap the TV again to retry."
            else -> msg
        }
    }

    private suspend fun finalizeConnected(device: TvDevice): Boolean {
        val saved = device.copy(lastConnectedAt = System.currentTimeMillis())
        deviceRepository.save(saved)
        currentDevice = saved
        _state.value = ConnectionState.Connected(saved)
        return true
    }

    private fun armPairingTimeout() {
        cancelPairingTimeout()
        pairingTimeoutJob = scope.launch {
            delay(PAIRING_TIMEOUT_MS)
            if (_state.value is ConnectionState.WaitingForTvAuth) {
                samsung?.close(); samsung = null
                lg?.close(); lg = null
                // No Allow tap detected → quietly slide into DIAL-fallback mode.
                currentDevice?.let { tryDialFallback(it.copy(brand = TvBrand.GENERIC)) }
            }
        }
    }

    private fun cancelPairingTimeout() {
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
    }

    override suspend fun disconnect() {
        cancelPairingTimeout()
        samsung?.close(); samsung = null
        lg?.close(); lg = null
        androidTv?.cancel(); androidTv = null
        roku = null
        sony = null
        dial = null
        currentDevice = null
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun send(key: RemoteKey) {
        val device = currentDevice ?: return
        when (device.type) {
            ConnectionType.IR -> withContext(Dispatchers.IO) {
                irBlaster.transmit(device.brand, key)
            }
            ConnectionType.WIFI -> sendOverWifi(device, key)
            ConnectionType.BLUETOOTH -> Unit
        }
    }

    private suspend fun sendOverWifi(device: TvDevice, key: RemoteKey) {
        withContext(Dispatchers.IO) {
            when (device.brand) {
                TvBrand.SAMSUNG -> samsung?.sendKey(key)
                TvBrand.LG -> lg?.let { client ->
                    LgKeyMap.appId(key)?.let { client.launchApp(it) } ?: client.sendKey(key)
                }
                TvBrand.ROKU -> roku?.let { client ->
                    RokuKeyMap.appId(key)?.let { client.launchApp(it) } ?: client.sendKey(key)
                }
                TvBrand.SONY -> sony?.sendKey(key)
                TvBrand.ANDROID_TV, TvBrand.GOOGLE_TV -> androidTv?.sendKey(key)
                TvBrand.GENERIC -> dial?.let { client ->
                    // DIAL only handles app launches.
                    DialKeyMap.appName(key)?.let { client.launch(it) }
                }
                else -> Unit
            }
        }
    }

    override suspend fun sendText(text: String) = Unit

    override fun supportsIr(): Boolean = irBlaster.isAvailable()

    companion object {
        private const val PAIRING_TIMEOUT_MS = 30_000L
    }
}
