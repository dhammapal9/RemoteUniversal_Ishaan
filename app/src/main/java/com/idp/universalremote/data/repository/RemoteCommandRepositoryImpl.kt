package com.idp.universalremote.data.repository

import android.content.Context
import android.util.Log
import com.idp.universalremote.data.cast.HttpFileServer
import com.idp.universalremote.data.cast.UpnpCastClient
import com.idp.universalremote.data.ir.IrBlaster
import com.idp.universalremote.data.protocol.AndroidTvAppLinkMap
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
import com.idp.universalremote.domain.model.MediaItem
import com.idp.universalremote.domain.model.MediaType
import com.idp.universalremote.domain.model.RemoteKey
import com.idp.universalremote.domain.model.TvBrand
import com.idp.universalremote.domain.model.TvDevice
import com.idp.universalremote.domain.repository.RemoteCommandRepository
import com.idp.universalremote.domain.repository.TvDeviceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.net.NetworkInterface
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
    @ApplicationContext private val context: Context,
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

    // Lazily-started UPnP cast plumbing. Both pieces stay null until the user
    // actually tries to cast something — there's no point hoarding a TCP listener
    // or doing UPnP discovery for a session that's only using the remote pad.
    private val httpFileServer = HttpFileServer(context.contentResolver)
    private val upnpClient = UpnpCastClient(context)
    private var cachedAvTransportUrl: String? = null
    private var cachedAvTransportHost: String? = null

    /**
     * Step 1: stage the connection. We always end up in [ConnectionState.PairingRequired]
     * for Wi-Fi devices so the SensusTech-style "Enter Pairing Code" dialog always
     * shows. Whatever the user types (or leaves blank) gets routed to the right
     * brand handshake by [pair].
     */
    override suspend fun connect(device: TvDevice): Boolean {
        // Merge any persisted pairing token / saved brand from a prior session.
        // Without this, a freshly-discovered TvDevice from SSDP has no token, so
        // we'd re-run the polo PIN handshake — but the TV won't show a PIN the
        // second time around because our cert is already authorized, leaving the
        // user staring at an empty pairing dialog.
        val withSaved = restoreSavedPairing(device)
        currentDevice = withSaved
        cancelPairingTimeout()
        _state.value = ConnectionState.Connecting(withSaved)

        if (withSaved.type == ConnectionType.IR) return finalizeConnected(withSaved)

        // Fast path: if we already have a token and a non-GENERIC brand from the
        // saved session, skip the network probe entirely. probeBrand() does up to
        // 6 timed HTTP/TCP attempts (~9 seconds total worst-case) which makes
        // every reconnect feel sluggish even though the answer is already known.
        if (!withSaved.pairingToken.isNullOrBlank() && withSaved.brand != TvBrand.GENERIC) {
            return startWithSavedToken(withSaved)
        }

        val probed = if (withSaved.brand == TvBrand.GENERIC || withSaved.brand == TvBrand.ANDROID_TV) {
            probeBrand(withSaved)
        } else null

        val effective = probed?.let {
            val updated = withSaved.copy(brand = it)
            currentDevice = updated
            deviceRepository.save(updated)
            updated
        } ?: withSaved

        if (effective.brand == TvBrand.ROKU) {
            // Roku never asks for a code.
            return startRoku(effective)
        }

        if (!effective.pairingToken.isNullOrBlank()) return startWithSavedToken(effective)

        // Android TV / Google TV: open polo pairing socket in background so the
        // TV can display its 6-char PIN. Dialog is shown via PairingRequired
        // state so the user has UI feedback regardless of how quickly polo
        // completes the handshake.
        if (effective.brand == TvBrand.ANDROID_TV || effective.brand == TvBrand.GOOGLE_TV) {
            val ip = effective.ipAddress
            if (ip.isNullOrBlank()) {
                _state.value = ConnectionState.Failed("Missing IP address")
                return false
            }
            startAndroidTvPrepairing(effective)
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

    /**
     * Look up the device in the local DB by id first, then by IP — discovered
     * devices from SSDP can carry a fresh USN even when the underlying TV is the
     * same one we paired with last week. Returns the passed-in device with the
     * saved `pairingToken` / brand merged in if a match was found.
     */
    private suspend fun restoreSavedPairing(device: TvDevice): TvDevice {
        val saved = deviceRepository.get(device.id)
            ?: device.ipAddress?.let { deviceRepository.findByIp(it) }
            ?: return device
        return device.copy(
            // Prefer the persisted token; otherwise keep whatever came in.
            pairingToken = device.pairingToken ?: saved.pairingToken,
            // Saved brand wins because the user already went through the
            // probe + connect flow once and we know the answer.
            brand = if (saved.brand != TvBrand.GENERIC) saved.brand else device.brand
        )
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
            TvBrand.ANDROID_TV, TvBrand.GOOGLE_TV -> {
                if (trimmed.isBlank()) {
                    // User tapped Connect without typing — keep dialog open by
                    // re-emitting PairingRequired. Don't fall to a fake-Connected.
                    _state.value = ConnectionState.PairingRequired(device)
                    false
                } else {
                    submitAndroidTvPin(device, trimmed)
                }
            }
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
    /**
     * Open the pairing socket eagerly so the TV can show its PIN.
     *
     * **Important**: failures here are NOT surfaced as Failed state — we don't
     * want the dialog to dismiss with "pairing failed" before the user even sees
     * it. Some TVs (Thomson) silently reject every polo variant; when the user
     * taps Connect on the dialog, [submitAndroidTvPin] catches that and falls
     * through to a direct-messaging session. So all we do here is log and let
     * the dialog stay open for user input.
     */
    private fun startAndroidTvPrepairing(device: TvDevice) {
        val ip = device.ipAddress ?: return
        androidTv?.cancel()
        androidTv = AndroidTvRemoteClient(host = ip, certStore = androidTvCertStore).also { client ->
            client.beginPairing(
                onAwaitingPin = {
                    Log.d("RemoteRepo", "TV is displaying the PIN — dialog is already up")
                },
                onFailure = { t ->
                    Log.d("RemoteRepo", "polo pairing background-failed: ${t.message}; " +
                        "leaving dialog open so user can tap Connect → direct-messaging fallback")
                    // Deliberately do NOT change state. Dialog stays open.
                }
            )
        }
    }

    /**
     * The user tapped Connect on the PIN dialog. Validation is real now — no
     * silent fake-Connected fallback. Three outcomes:
     *  1. TV showed the PIN AND polo accepts it    → Connected, navigate to Remote
     *  2. TV showed the PIN but polo rejects it    → Failed("Wrong code")
     *  3. TV never showed the PIN (polo handshake) → Failed("TV didn't show code")
     *
     * Path B (cert-only direct messaging) is only attempted if the device was
     * already paired in a previous session and we have a saved pairing token.
     */
    private suspend fun submitAndroidTvPin(device: TvDevice, pin: String): Boolean {
        var client = androidTv

        if (client == null) {
            // Polo client got cancelled or never started. Spin up a fresh one
            // and let it run the handshake before we send the secret.
            val ip = device.ipAddress ?: return run {
                _state.value = ConnectionState.Failed("Missing IP address")
                false
            }
            client = AndroidTvRemoteClient(host = ip, certStore = androidTvCertStore)
                .also { androidTv = it }
        }

        // Don't gate on `didTvShowPin` — Thomson and several custom skins display
        // the PIN before our handshake reaches the Configuration ACK, so the flag
        // can be false even when the user is staring at a valid code on screen.
        // Just attempt the secret exchange and let polo's own status response
        // tell us whether the PIN is correct.
        if (pin.length < 4 || pin.length > 8) {
            _state.value = ConnectionState.Failed("Please enter the code shown on your TV.")
            return false
        }

        val poloOk = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            client.submitPin(
                pin = pin,
                onSuccess = {
                    finishConnected(device.copy(pairingToken = pin))
                    if (cont.isActive) cont.resumeWith(Result.success(true))
                },
                onFailure = { t ->
                    Log.w("RemoteRepo", "polo PIN exchange failed: ${t.message}")
                    if (cont.isActive) cont.resumeWith(Result.success(false))
                }
            )
        }
        if (poloOk) return true

        _state.value = ConnectionState.Failed("Wrong code. Check the code on your TV and try again.")
        return false
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
        cachedAvTransportUrl = null
        cachedAvTransportHost = null
        runCatching { httpFileServer.stop() }
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
            val handled = when (device.brand) {
                TvBrand.SAMSUNG -> samsung?.sendKey(key) ?: false
                TvBrand.LG -> lg?.let { client ->
                    LgKeyMap.appId(key)?.let { client.launchApp(it) } ?: client.sendKey(key)
                } ?: false
                TvBrand.ROKU -> roku?.let { client ->
                    RokuKeyMap.appId(key)?.let { client.launchApp(it) } ?: client.sendKey(key)
                } ?: false
                TvBrand.SONY -> sony?.sendKey(key) ?: false
                TvBrand.ANDROID_TV, TvBrand.GOOGLE_TV -> androidTv?.let { client ->
                    // App shortcuts (Netflix/YouTube/etc.) on Android TV go via
                    // RemoteAppLinkLaunchRequest — the TV's launcher opens whichever
                    // app claims the URL. Regular keys go through KeyInject.
                    AndroidTvAppLinkMap.urlFor(key)?.let { client.sendAppLink(it) }
                        ?: client.sendKey(key)
                } ?: false
                TvBrand.GENERIC -> dial?.let { client ->
                    DialKeyMap.appName(key)?.let { client.launch(it) } ?: false
                } ?: false
                else -> false
            }
            // Universal app-launch fallback: most Smart TVs (including Android TV,
            // Samsung Tizen, Sony Bravia) implement DIAL alongside their native
            // protocol, but our brand-specific key maps don't cover every streaming
            // app on every brand. If the press was an app shortcut and the brand
            // path didn't handle it, try DIAL once. Skipped for GENERIC since the
            // brand path above IS DIAL and we'd just be retrying the same thing.
            if (!handled && device.brand != TvBrand.GENERIC && DialKeyMap.appName(key) != null) {
                tryDialLaunch(device, key)
            }
        }
    }

    /**
     * Best-effort DIAL launch for streaming apps. Resolves the DIAL Application-URL
     * lazily (cached after first success) so we don't pay the SSDP/HTTP probe cost
     * on every key press.
     */
    private suspend fun tryDialLaunch(device: TvDevice, key: RemoteKey): Boolean {
        val appName = DialKeyMap.appName(key) ?: return false
        val client = dial ?: run {
            val ip = device.ipAddress ?: return false
            val baseUrl = DialEndpointResolver.resolve(ip) ?: return false
            DialClient(baseUrl).also { dial = it }
        }
        return client.launch(appName)
    }

    override suspend fun sendText(text: String) = Unit

    override fun supportsIr(): Boolean = irBlaster.isAvailable()

    override suspend fun autoReconnect(): Boolean {
        // Don't disturb a pairing flow already in progress — but DO reconnect if
        // the state shows Connected but the underlying messaging socket is dead.
        // Android TVs aggressively close idle TLS sockets (varies by firmware:
        // Sony ~60s, Samsung ~5min, Thomson ~30s). Without this check, the user
        // sees a "Connected" state and presses Volume but nothing happens — the
        // bytes go into a closed socket.
        when (val s = _state.value) {
            is ConnectionState.Connecting,
            is ConnectionState.PairingRequired,
            is ConnectionState.WaitingForTvAuth -> return false
            is ConnectionState.Connected -> if (isLiveConnection(s.device)) return true
            else -> Unit
        }
        // Pick the most-recently-used device that actually has a saved pairing
        // token (or is IR-only). Anything older than the cert store gets skipped.
        val recent = runCatching { deviceRepository.recent(limit = 5) }.getOrDefault(emptyList())
        val candidate = currentDevice?.takeIf { !it.pairingToken.isNullOrBlank() }
            ?: recent.firstOrNull {
                it.type == ConnectionType.IR ||
                    (it.type == ConnectionType.WIFI && !it.pairingToken.isNullOrBlank() && !it.ipAddress.isNullOrBlank())
            }
            ?: return false
        Log.d(TAG, "autoReconnect: trying ${candidate.name} @ ${candidate.ipAddress}")
        val ok = runCatching { connect(candidate) }.getOrDefault(false)
        // Silent reconnect: if it didn't succeed, scrub the Failed/PairingRequired
        // state back to Disconnected so the user doesn't see a toast or pairing
        // dialog they didn't ask for.
        if (!ok && _state.value !is ConnectionState.Connected) {
            _state.value = ConnectionState.Disconnected
        }
        return ok
    }

    /**
     * Cheap liveness check on the underlying brand client. Returns false the
     * moment the messaging socket has been closed — at which point caller
     * should treat this as a stale Connected state and force a reconnect.
     */
    private fun isLiveConnection(device: TvDevice): Boolean = when (device.brand) {
        TvBrand.ANDROID_TV, TvBrand.GOOGLE_TV -> androidTv?.isMessagingAlive() == true
        TvBrand.SAMSUNG -> samsung != null
        TvBrand.LG -> lg != null
        TvBrand.ROKU -> roku != null
        TvBrand.SONY -> sony != null
        TvBrand.GENERIC -> dial != null
        else -> false
    }

    override suspend fun castMedia(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        val device = currentDevice ?: return@withContext false
        val tvIp = device.ipAddress ?: return@withContext false

        // 1. Make the media file fetchable on the LAN via the embedded HTTP server.
        val port = runCatching { httpFileServer.start() }.getOrNull() ?: return@withContext false
        val mime = item.mimeType ?: defaultMimeFor(item.mediaType)
        val path = httpFileServer.register(item.uri, mime)
        val phoneIp = localIpReachableFrom(tvIp) ?: run {
            Log.w(TAG, "castMedia: no usable local IP for TV at $tvIp")
            return@withContext false
        }
        val mediaUrl = "http://$phoneIp:$port$path"
        Log.d(TAG, "castMedia: serving $mediaUrl mime=$mime")

        // 2. Locate the TV's AVTransport control URL (cached after first hit).
        val controlUrl = cachedAvTransportUrl?.takeIf { cachedAvTransportHost == tvIp }
            ?: upnpClient.resolve(tvIp)?.also {
                cachedAvTransportUrl = it
                cachedAvTransportHost = tvIp
            }
        if (controlUrl == null) {
            Log.w(TAG, "castMedia: no AVTransport endpoint on $tvIp — TV is not a DLNA renderer")
            return@withContext false
        }

        // 3. Push.
        upnpClient.castUrl(controlUrl, mediaUrl, mime, item.title.ifBlank { "Media" })
    }

    private fun defaultMimeFor(type: MediaType): String = when (type) {
        MediaType.IMAGE -> "image/jpeg"
        MediaType.VIDEO -> "video/mp4"
        MediaType.AUDIO -> "audio/mpeg"
    }

    /**
     * Pick a non-loopback IPv4 address on the same /24 as the TV. This is more
     * reliable than InetAddress.getLocalHost() (which sometimes returns 127.0.0.1
     * on Android) and falls back to any non-loopback IPv4 if the same-subnet
     * heuristic fails.
     */
    private fun localIpReachableFrom(tvIp: String): String? = runCatching {
        val target = tvIp.substringBeforeLast('.')
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        var firstUsable: String? = null
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses) {
                val ip = addr.hostAddress ?: continue
                if (addr.isLoopbackAddress || ip.contains(':')) continue
                if (firstUsable == null) firstUsable = ip
                if (ip.substringBeforeLast('.') == target) return ip
            }
        }
        firstUsable
    }.getOrNull()

    companion object {
        private const val TAG = "RemoteCommandRepo"
        private const val PAIRING_TIMEOUT_MS = 30_000L
    }
}
