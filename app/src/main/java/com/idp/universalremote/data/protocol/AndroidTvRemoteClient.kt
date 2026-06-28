package com.idp.universalremote.data.protocol

import android.util.Log
import com.idp.universalremote.domain.model.RemoteKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Android TV Remote v2 client — pairing + messaging.
 *
 *  - **Port 6467 (pairing)**: 4-step handshake → TV displays a 6-char PIN → app
 *    hashes the PIN with both certs' public keys → TV validates.
 *  - **Port 6466 (messaging)**: opened only *after* pairing finishes. Carries
 *    RemoteMessage frames with RemoteKeyInject for every button press.
 *
 * Both sockets use the persistent client cert from [AndroidTvCertStore]; once
 * a TV has authorized our cert (first-time pairing) future reconnects use the
 * messaging port silently with no PIN prompt.
 */
class AndroidTvRemoteClient(
    private val host: String,
    private val certStore: AndroidTvCertStore,
    private val clientName: String = "UniversalRemote"
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pairingJob: Job? = null
    private var pairingSocket: SSLSocket? = null
    private var messagingSocket: SSLSocket? = null
    private var serverCert: X509Certificate? = null

    private var onPinRequested: (() -> Unit)? = null

    fun beginPairing(
        onAwaitingPin: () -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        this.onPinRequested = onAwaitingPin
        pairingJob = scope.launch {
            runCatching { performPairingHandshake() }
                .onFailure { err ->
                    Log.w(TAG, "Pairing handshake failed: ${err.message}", err)
                    closePairingSocket()
                    onFailure(err)
                }
        }
    }

    fun submitPin(pin: String, onSuccess: () -> Unit, onFailure: (Throwable) -> Unit) {
        val clean = pin.trim().uppercase()
        if (clean.length != 6) {
            onFailure(IllegalArgumentException("PIN must be 6 characters"))
            return
        }
        scope.launch {
            runCatching {
                performSecretExchange(clean)
                openMessagingSocket()
            }
                .onSuccess { onSuccess() }
                .onFailure { err ->
                    Log.w(TAG, "Secret exchange failed: ${err.message}", err)
                    onFailure(err)
                }
                .also { closePairingSocket() }
        }
    }

    fun sendKey(key: RemoteKey): Boolean {
        val socket = messagingSocket ?: return false
        val code = AndroidTvKeyMap.toKeyCode(key) ?: return false
        return runCatching {
            writeMessage(DataOutputStream(socket.outputStream), buildKeyInjectPayload(code))
            true
        }.onFailure { Log.w(TAG, "sendKey failed: ${it.message}", it) }
            .getOrDefault(false)
    }

    fun connectSavedSession(onSuccess: () -> Unit, onFailure: (Throwable) -> Unit) {
        scope.launch {
            runCatching { openMessagingSocket() }
                .onSuccess { onSuccess() }
                .onFailure { onFailure(it) }
        }
    }

    fun cancel() {
        pairingJob?.cancel()
        closePairingSocket()
        runCatching { messagingSocket?.close() }
        messagingSocket = null
    }

    /**
     * Cache of which pairing port the TV accepted. Thomson and some other custom
     * Android skins use **6466** for both pairing and messaging; standard Google-
     * certified Android TVs use 6467 for pairing and 6466 for messaging.
     */
    private var negotiatedPairingPort: Int = PORT_PAIRING_THOMSON

    /** Variant of the polo protocol that the TV's firmware understands. */
    data class PoloVariant(
        val label: String,
        val serviceName: String,
        val innerServiceTag: Int,
        val innerClientTag: Int
    )

    private var negotiatedVariant: PoloVariant = VARIANTS.first()

    // ─── Pairing socket — try Thomson port 6466 first, then standard 6467 ─────
    private suspend fun performPairingHandshake() = withContext(Dispatchers.IO) {
        var lastFailure: Throwable? = null

        for (variant in VARIANTS) {
            Log.d(TAG, "Attempting pairing with variant: ${variant.label}")
            // Fresh socket per attempt — the TV closes the socket on a 400 reply.
            closePairingSocket()
            val socket = try {
                openTlsSocket(PORT_PAIRING_THOMSON)
                    .also { negotiatedPairingPort = PORT_PAIRING_THOMSON }
            } catch (t: Throwable) {
                Log.d(TAG, "Pairing on 6466 failed (${t.message}); retrying on 6467")
                openTlsSocket(PORT_PAIRING_STANDARD)
                    .also { negotiatedPairingPort = PORT_PAIRING_STANDARD }
            }
            pairingSocket = socket
            serverCert = (socket.session.peerCertificates.firstOrNull() as? X509Certificate)

            val out = DataOutputStream(socket.outputStream)
            val ins = DataInputStream(socket.inputStream)
            try {
                writeMessage(out, buildPairingRequest(variant))
                readMessage(ins) // PairingRequestAck

                writeMessage(out, buildPairingOption())
                readMessage(ins) // PairingOptionAck

                writeMessage(out, buildPairingConfiguration())
                readMessage(ins) // PairingConfigurationAck — TV displays the PIN

                Log.d(TAG, "✓ Variant '${variant.label}' accepted; TV should now show the PIN.")
                negotiatedVariant = variant
                onPinRequested?.invoke()
                return@withContext
            } catch (t: Throwable) {
                Log.d(TAG, "✗ Variant '${variant.label}' rejected: ${t.message}")
                lastFailure = t
                continue
            }
        }
        throw lastFailure ?: java.io.IOException("All pairing variants rejected by TV.")
    }

    private suspend fun performSecretExchange(pin: String) = withContext(Dispatchers.IO) {
        val socket = pairingSocket ?: error("Pairing socket closed")
        val out = DataOutputStream(socket.outputStream)
        val ins = DataInputStream(socket.inputStream)

        val secret = computeSecret(pin)
        writeMessage(out, buildPairingSecret(secret))
        readMessage(ins) // PairingSecretAck — throws on rejection
        Log.d(TAG, "PIN accepted; switching to messaging socket")
    }

    private fun computeSecret(pin: String): ByteArray {
        require(pin.length == 6) { "PIN must be exactly 6 hex characters" }
        val server = serverCert?.publicKey as? RSAPublicKey
            ?: error("Missing server certificate")
        val clientKey = clientCertKey()

        val nonceByte = hexToBytes(pin.substring(0, 2))[0]
        val pinTailBytes = hexToBytes(pin.substring(2))

        val md = MessageDigest.getInstance("SHA-256")
        md.update(clientKey.modulus.toUnsignedBytes())
        md.update(clientKey.publicExponent.toUnsignedBytes())
        md.update(server.modulus.toUnsignedBytes())
        md.update(server.publicExponent.toUnsignedBytes())
        md.update(pinTailBytes)
        val digest = md.digest()

        if (digest[0] != nonceByte) {
            throw IllegalArgumentException(
                "Invalid PIN — the code on your TV doesn't match what you typed."
            )
        }
        return digest
    }

    // ─── Messaging socket ───────────────────────────────────────────────────────
    // Both Thomson (paired on 6466) and standard ATV (paired on 6467) message on
    // port 6466. The messaging session is REACTIVE — the TV drives the handshake
    // by sending RemoteConfigure first, then RemoteActive, and we respond. Until
    // we've responded to both, the TV silently drops every RemoteKeyInject.
    private val messagingOut: DataOutputStream?
        get() = messagingSocket?.let { DataOutputStream(it.outputStream) }

    private suspend fun openMessagingSocket() = withContext(Dispatchers.IO) {
        runCatching { messagingSocket?.close() }
        // Thomson accepts TLS on 6467 but rejects it on 6466. Standard Google TV
        // does the opposite. Try 6467 first then fall back to 6466.
        val (socket, port) = try {
            openTlsSocket(PORT_MESSAGING_ALT) to PORT_MESSAGING_ALT
        } catch (t: Throwable) {
            Log.d(TAG, "Messaging on $PORT_MESSAGING_ALT failed (${t.message}); falling back to $PORT_MESSAGING")
            openTlsSocket(PORT_MESSAGING) to PORT_MESSAGING
        }
        messagingSocket = socket
        Log.d(TAG, "Messaging TLS up on $host:$port; entering reactive loop")

        val out = DataOutputStream(socket.outputStream)
        val ins = DataInputStream(socket.inputStream)

        // Send our Configure proactively — some TVs need this to start the flow.
        // The TV may also send its own Configure; both orderings are tolerated by
        // the reactive loop below.
        writeMessage(out, buildRemoteConfigure())

        // Reactive reader: react to whatever the TV sends.
        scope.launch {
            while (messagingSocket?.isClosed == false) {
                val frame = runCatching { readMessage(ins) }.getOrNull() ?: break
                handleIncomingMessage(frame, out)
            }
            Log.d(TAG, "Messaging reader exited.")
        }
    }

    /**
     * Dispatch on the first byte of the frame, which is the field tag of the
     * RemoteMessage's `optional` oneof arm. Tags:
     *   0x0a  → RemoteConfigure (field 1)
     *   0x12  → RemoteActive    (field 2) — respond with SetActive
     *   0x62  → RemotePingRequest (field 12) — respond with PingResponse
     *   0x82 0x01 → RemoteKeyInject (field 16) — server would never push this
     */
    private fun handleIncomingMessage(frame: ByteArray, out: DataOutputStream) {
        if (frame.isEmpty()) return
        when (frame[0].toInt() and 0xFF) {
            0x0a -> {
                Log.d(TAG, "TV sent Configure; nothing to do (we already sent ours)")
            }
            0x12 -> {
                Log.d(TAG, "TV sent Active; replying with SetActive(active=${RemoteFields.ACTIVE_MAGIC})")
                runCatching { writeMessage(out, buildRemoteSetActive()) }
            }
            0x62 -> {
                Log.d(TAG, "TV pinged us; replying with PingResponse")
                runCatching { writeMessage(out, buildPingResponse()) }
            }
            else -> {
                Log.d(TAG, "Unhandled message from TV; first byte=0x${"%02x".format(frame[0])}")
            }
        }
    }

    private fun buildRemoteConfigure(): ByteArray = Proto.build {
        Proto.embed(this, RemoteFields.REMOTE_CONFIGURE) {
            Proto.int32(this, RemoteFields.CONF_CODE1, RemoteFields.ACTIVE_MAGIC)
            Proto.embed(this, RemoteFields.CONF_DEVICE_INFO) {
                Proto.string(this, RemoteFields.DEV_MODEL, clientName)
                Proto.string(this, RemoteFields.DEV_VENDOR, "Universal Remote")
                Proto.int32(this, RemoteFields.DEV_UNKNOWN1, 1)
                Proto.string(this, RemoteFields.DEV_UNKNOWN2, "1")
                Proto.string(this, RemoteFields.DEV_PACKAGE_NAME, "atvremote")
                Proto.string(this, RemoteFields.DEV_APP_VERSION, "1.0.0")
            }
        }
    }

    private fun buildRemoteSetActive(): ByteArray = Proto.build {
        Proto.embed(this, RemoteFields.REMOTE_SET_ACTIVE) {
            Proto.int32(this, RemoteFields.SET_ACTIVE, RemoteFields.ACTIVE_MAGIC)
        }
    }

    private fun buildPingResponse(): ByteArray = Proto.build {
        Proto.embed(this, RemoteFields.REMOTE_PING_RESPONSE) {
            Proto.int32(this, RemoteFields.PING_VAL_1, 1)
        }
    }

    private fun buildKeyInjectPayload(keyCode: Int): ByteArray = Proto.build {
        Proto.embed(this, RemoteFields.REMOTE_KEY_INJECT) {
            Proto.int32(this, RemoteFields.DIRECTION, RemoteFields.DIR_SHORT)
            Proto.int32(this, RemoteFields.KEY_CODE, keyCode)
        }
    }

    // ─── Shared TLS plumbing ────────────────────────────────────────────────────
    private fun clientCertKey(): RSAPublicKey {
        val ks = certStore.loadOrCreate()
        val cert = ks.getCertificate(AndroidTvCertStore.ALIAS) as X509Certificate
        return cert.publicKey as RSAPublicKey
    }

    private fun openTlsSocket(port: Int): SSLSocket {
        val ks: KeyStore = certStore.loadOrCreate()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(ks, AndroidTvCertStore.PASSPHRASE) }
        val tm = arrayOf<X509TrustManager>(TrustAnyTrustManager())
        val ctx = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, tm, SecureRandom())
        }
        val factory: SSLSocketFactory = ctx.socketFactory
        val socket = factory.createSocket(host, port) as SSLSocket

        // Enable EVERY supported TLS protocol and cipher suite. Thomson firmwares
        // are known to reject the handshake when the phone offers only TLS 1.3
        // (some Thomson models still negotiate via TLS 1.2 / 1.1 ciphers). Letting
        // JSSE pick whatever the TV accepts gives the widest compatibility.
        runCatching { socket.enabledProtocols = socket.supportedProtocols }
        runCatching { socket.enabledCipherSuites = socket.supportedCipherSuites }

        socket.soTimeout = 20_000
        try {
            socket.startHandshake()
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            // Wrap the JSSE message so callers can render something readable.
            throw java.io.IOException(
                "TLS handshake rejected by TV on port $port. " +
                    "Reason: ${e.message ?: "unknown"}. " +
                    "The TV may require a specific cipher/protocol our SSL stack didn't offer.",
                e
            )
        }
        return socket
    }

    private fun closePairingSocket() {
        runCatching { pairingSocket?.close() }
        pairingSocket = null
    }

    // ─── Pairing message builders ───────────────────────────────────────────────
    private fun envelope(out: java.io.ByteArrayOutputStream) {
        // The tronikos `androidtvremote2` schema has NO `type` field. The TV's
        // strict parser rejects unknown field tags with status=400.
        // Send protocol_version=2 to match the TV's own reply.
        Proto.int32(out, PairingFields.PROTOCOL_VERSION, 2)
        Proto.int32(out, PairingFields.STATUS, PairingFields.STATUS_OK)
    }

    private fun buildPairingRequest(variant: PoloVariant): ByteArray = Proto.build {
        envelope(this)
        Proto.embed(this, PairingFields.REQUEST) {
            Proto.string(this, variant.innerServiceTag, variant.serviceName)
            Proto.string(this, variant.innerClientTag, clientName)
        }
    }

    private fun buildPairingOption(): ByteArray = Proto.build {
        envelope(this)
        Proto.embed(this, PairingFields.OPTION) {
            Proto.embed(this, PairingFields.INPUT_ENCODINGS) {
                Proto.int32(this, PairingFields.ENCODING_TYPE, PairingFields.ENCODING_HEXADECIMAL)
                Proto.int32(this, PairingFields.SYMBOL_LENGTH, 6)
            }
            Proto.int32(this, PairingFields.PREFERRED_ROLE, PairingFields.ROLE_INPUT)
        }
    }

    private fun buildPairingConfiguration(): ByteArray = Proto.build {
        envelope(this)
        Proto.embed(this, PairingFields.CONFIGURATION) {
            Proto.embed(this, PairingFields.ENCODING) {
                Proto.int32(this, PairingFields.ENCODING_TYPE, PairingFields.ENCODING_HEXADECIMAL)
                Proto.int32(this, PairingFields.SYMBOL_LENGTH, 6)
            }
            Proto.int32(this, PairingFields.CLIENT_ROLE, PairingFields.ROLE_INPUT)
        }
    }

    private fun buildPairingSecret(secret: ByteArray): ByteArray = Proto.build {
        envelope(this)
        Proto.embed(this, PairingFields.SECRET) {
            Proto.bytes(this, PairingFields.SECRET_BYTES, secret)
        }
    }

    // ─── Wire framing: single-byte length + payload ─────────────────────────────
    private fun writeMessage(out: DataOutputStream, payload: ByteArray) {
        Log.d(TAG, "→ TV ${payload.size}B: ${payload.joinToString("") { "%02x".format(it) }}")
        // Varint length prefix — single byte for messages < 128 (matches what the
        // pairing port accepted), multi-byte for the larger messaging frames.
        var len = payload.size
        while (len >= 0x80) {
            out.writeByte((len and 0x7F) or 0x80)
            len = len ushr 7
        }
        out.writeByte(len)
        out.write(payload)
        out.flush()
    }

    private fun readMessage(ins: DataInputStream): ByteArray {
        var len = 0
        var shift = 0
        while (shift <= 28) {
            val b = ins.readUnsignedByte()
            len = len or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        if (len <= 0) throw IOException("TV closed the socket with no response.")
        val buf = ByteArray(len)
        ins.readFully(buf)
        Log.d(TAG, "← TV ${buf.size}B: ${buf.joinToString("") { "%02x".format(it) }}")
        // Quick sanity-check the status byte the TV echoes back. Status field
        // tag in PairingMessage = 2 (varint), payload follows directly.
        val statusTag = (PairingFields.STATUS shl 3) or 0  // varint wire type
        for (i in buf.indices) {
            if (buf[i].toInt() and 0xFF == statusTag) {
                val status = decodeVarintAt(buf, i + 1)
                if (status != null && status != PairingFields.STATUS_OK) {
                    throw IOException("TV rejected pairing message with status=$status")
                }
                break
            }
        }
        return buf
    }

    private fun decodeVarintAt(data: ByteArray, offset: Int): Int? {
        var v = 0
        var shift = 0
        var i = offset
        while (i < data.size && shift <= 28) {
            val b = data[i].toInt() and 0xFF
            v = v or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return v
            shift += 7
            i++
        }
        return null
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or
                Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    private fun java.math.BigInteger.toUnsignedBytes(): ByteArray {
        val raw = toByteArray()
        return if (raw.isNotEmpty() && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    }

    private class TrustAnyTrustManager : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, socket: java.net.Socket?) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, socket: java.net.Socket?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val TAG = "AndroidTvRemoteClient"
        /** Thomson + several custom-skin Android TVs handle pairing here. */
        private const val PORT_PAIRING_THOMSON = 6466
        /** Standard Google-certified Android TV / Google TV pairing port. */
        private const val PORT_PAIRING_STANDARD = 6467
        /** Standard Google TV messaging port. */
        private const val PORT_MESSAGING = 6466
        /** Thomson + custom-skin messaging port (same port as their pairing). */
        private const val PORT_MESSAGING_ALT = 6467

        /**
         * Polo protocol variants we'll try in order. Different Android TV firmwares
         * use different (service_name, inner field tag) combinations and there's no
         * way to detect which without sending and observing the status response.
         * As soon as one variant returns status=OK, we keep using it for the rest
         * of the session.
         */
        private val VARIANTS = listOf(
            PoloVariant("polo-classic", "com.google.atvremote", innerServiceTag = 10, innerClientTag = 11),
            PoloVariant("polo-modern",  "androidtvremote2",     innerServiceTag = 1,  innerClientTag = 2),
            PoloVariant("polo-short",   "atvremote",            innerServiceTag = 10, innerClientTag = 11),
            PoloVariant("polo-hybrid",  "com.google.atvremote", innerServiceTag = 1,  innerClientTag = 2)
        )
    }
}

object AndroidTvKeyMap {
    fun toKeyCode(key: RemoteKey): Int? = when (key) {
        RemoteKey.POWER -> 26
        RemoteKey.HOME -> 3
        RemoteKey.BACK -> 4
        RemoteKey.MENU -> 82
        RemoteKey.UP -> 19
        RemoteKey.DOWN -> 20
        RemoteKey.LEFT -> 21
        RemoteKey.RIGHT -> 22
        RemoteKey.OK -> 23
        RemoteKey.VOL_UP -> 24
        RemoteKey.VOL_DOWN -> 25
        RemoteKey.MUTE -> 164
        RemoteKey.CH_UP -> 166
        RemoteKey.CH_DOWN -> 167
        RemoteKey.PLAY -> 126
        RemoteKey.PAUSE -> 127
        RemoteKey.PLAY_PAUSE -> 85
        RemoteKey.STOP -> 86
        RemoteKey.REWIND -> 89
        RemoteKey.FORWARD -> 90
        RemoteKey.NEXT -> 87
        RemoteKey.PREVIOUS -> 88
        RemoteKey.NUM_0 -> 7
        RemoteKey.NUM_1 -> 8
        RemoteKey.NUM_2 -> 9
        RemoteKey.NUM_3 -> 10
        RemoteKey.NUM_4 -> 11
        RemoteKey.NUM_5 -> 12
        RemoteKey.NUM_6 -> 13
        RemoteKey.NUM_7 -> 14
        RemoteKey.NUM_8 -> 15
        RemoteKey.NUM_9 -> 16
        else -> null
    }
}
