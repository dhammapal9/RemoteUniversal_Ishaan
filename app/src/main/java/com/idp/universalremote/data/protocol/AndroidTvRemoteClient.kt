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

    /**
     * Set to true the moment polo's PairingConfiguration is ACK'd — that's the
     * step where the TV displays the 6-char PIN. Repository uses this to decide
     * whether to attempt the PIN exchange or fail with a clear error.
     */
    @Volatile
    var didTvShowPin: Boolean = false
        private set

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
        val socket = messagingSocket
        if (socket == null) {
            Log.w(TAG, "sendKey($key): no messaging socket — connection isn't active")
            return false
        }
        if (socket.isClosed || !socket.isConnected) {
            Log.w(TAG, "sendKey($key): socket closed=${socket.isClosed} connected=${socket.isConnected}")
            return false
        }
        val code = AndroidTvKeyMap.toKeyCode(key)
        if (code == null) {
            Log.w(TAG, "sendKey($key): no Android keyCode mapping")
            return false
        }
        val out = messagingWriter ?: run {
            Log.w(TAG, "sendKey($key): writer not initialized")
            return false
        }
        Log.d(TAG, "sendKey($key) → KeyInject(code=$code) on $host (code1=$sessionCode1)")
        return runCatching {
            // SHORT = single tap. The working tronikos-style sample emits exactly
            // one message per press — no separate release. Sending START_LONG +
            // END_LONG works too but the TV interprets it as a press-and-hold,
            // which causes volume to ramp on every tap.
            writeMessage(out, buildKeyInjectPayload(code, RemoteFields.DIR_SHORT))
            true
        }.onFailure { Log.w(TAG, "sendKey($key) write failed: ${it.message}", it) }
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
        messagingWriter = null
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

    /**
     * Session `code1` value that the TV uses. Thomson uses 639, stock Google
     * Android TV uses 622. Default to 622 but overwrite the moment the TV sends
     * its own RemoteConfigure — every subsequent SetActive must echo *this*
     * value or the TV's input service silently drops every KeyInject we send.
     */
    @Volatile
    private var sessionCode1: Int = RemoteFields.ACTIVE_MAGIC

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
                didTvShowPin = true
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

    /**
     * Single shared writer for the messaging socket. All writes — from the UI's
     * sendKey() and from the reader thread's ping/Configure/Active replies — go
     * through this stream and are serialized via writeLock so frames never
     * interleave on the wire. (Interleaved bytes look like garbage to the TV's
     * protobuf parser; it silently drops both messages, which is exactly the
     * "connected but volume doesn't react" symptom.)
     */
    private var messagingWriter: DataOutputStream? = null
    private val writeLock = Any()

    private suspend fun openMessagingSocket() = withContext(Dispatchers.IO) {
        runCatching { messagingSocket?.close() }
        // Standard messaging port is 6466 for both Thomson and stock ATV. Try
        // it first; only fall back to 6467 for the rare custom skins that
        // multiplex messaging on the pairing-style port. The previous order
        // (6467 first) succeeded the TLS handshake against stock ATV's pairing
        // service, then sent messaging-format frames it couldn't parse, so the
        // input service silently ignored every KeyInject.
        val (socket, port) = try {
            openTlsSocket(PORT_MESSAGING) to PORT_MESSAGING
        } catch (t: Throwable) {
            Log.d(TAG, "Messaging on $PORT_MESSAGING failed (${t.message}); falling back to $PORT_MESSAGING_ALT")
            openTlsSocket(PORT_MESSAGING_ALT) to PORT_MESSAGING_ALT
        }
        messagingSocket = socket
        Log.d(TAG, "Messaging TLS up on $host:$port")

        val out = DataOutputStream(socket.outputStream)
        messagingWriter = out
        val ins = DataInputStream(socket.inputStream)

        // tronikos / androidtvremote2 canonical handshake on the messaging port:
        //   1. → Configure(code1=622, deviceInfo)
        //   2. ← (TV sends back its own Configure mirroring our session)
        //   3. → SetActive(active=622)
        //   4. ← (TV may send Active, then opens up for KeyInject)
        //
        // Each step is gated on the prior one. If we send SetActive before the
        // TV has acknowledged our Configure, its input service silently drops
        // every KeyInject we send — which is exactly the "connects but volume
        // doesn't work" symptom we've been chasing.
        socket.soTimeout = 5_000
        Log.d(TAG, "msg-handshake step 1: → Configure(code1=$sessionCode1)")
        writeMessage(out, buildRemoteConfigure())

        val tvResponse = runCatching { readMessage(ins, checkPairingStatus = false) }
            .onFailure { Log.w(TAG, "msg-handshake step 2 read failed: ${it.message}") }
            .getOrNull()
        if (tvResponse != null && tvResponse.isNotEmpty() && (tvResponse[0].toInt() and 0xFF) == 0x0a) {
            parseConfigureCode1(tvResponse)?.let {
                if (it != sessionCode1) {
                    Log.d(TAG, "msg-handshake step 2: TV Configure carries code1=$it (was $sessionCode1) — adopting")
                    sessionCode1 = it
                }
            }
        }

        Log.d(TAG, "msg-handshake step 3: → SetActive(active=$sessionCode1)")
        writeMessage(out, buildRemoteSetActive())

        // Connection is now ready for KeyInject. Disable read timeout so the
        // background reader can sit on `read()` indefinitely without throwing.
        socket.soTimeout = 0
        scope.launch {
            try {
                while (messagingSocket?.isClosed == false) {
                    val frame = runCatching { readMessage(ins, checkPairingStatus = false) }
                        .onFailure { Log.d(TAG, "Reader read failed: ${it.message}") }
                        .getOrNull() ?: break
                    handleIncomingMessage(frame, out)
                }
            } finally {
                Log.d(TAG, "Messaging reader exited (socket closed=${messagingSocket?.isClosed})")
            }
        }
    }

    /**
     * Dispatch on the first byte (or two) of the frame — these are the wire-tag
     * varints of each RemoteMessage oneof arm. Tag = (field_number << 3) | 2.
     *   0x0a       → RemoteConfigure   (field 1)  — adopt code1, reply with Configure
     *   0x12       → RemoteSetActive   (field 2)  — echoed back, no-op
     *   0x42       → RemotePingRequest (field 8)  — reply with PingResponse
     *   0x4a       → RemotePingResponse (field 9) — no-op
     *   0xc2 0x02  → RemoteStart       (field 40) — input service is live
     */
    private fun handleIncomingMessage(frame: ByteArray, out: DataOutputStream) {
        if (frame.isEmpty()) return
        val firstByte = frame[0].toInt() and 0xFF
        when (firstByte) {
            0x0a -> {
                // Extract the TV's code1 from this Configure and use it for our
                // session. Wire: 0a <len> 08 <varint code1> ...
                val tvCode1 = parseConfigureCode1(frame)
                if (tvCode1 != null && tvCode1 != sessionCode1) {
                    Log.d(TAG, "TV Configure carries code1=$tvCode1; was $sessionCode1 — adopting it")
                    sessionCode1 = tvCode1
                }
                Log.d(TAG, "TV sent Configure; replying Configure(code1=$sessionCode1) + SetActive")
                runCatching {
                    writeMessage(out, buildRemoteConfigure())
                    writeMessage(out, buildRemoteSetActive())
                }
            }
            0x42 -> {
                Log.d(TAG, "TV pinged us; replying with PingResponse")
                runCatching { writeMessage(out, buildPingResponse()) }
            }
            0xc2 -> {
                // RemoteStart wire tag is two bytes (0xc2 0x02) because field 40
                // doesn't fit in a 5-bit varint. This is the message that means
                // "input service is open — KeyInject will now reach the TV."
                if (frame.size >= 2 && (frame[1].toInt() and 0xFF) == 0x02) {
                    Log.d(TAG, "TV sent Start — messaging session is live")
                }
            }
            else -> {
                Log.d(TAG, "Unhandled message from TV; first byte=0x${"%02x".format(frame[0])}")
            }
        }
    }

    /**
     * Parse `code1` out of an inbound RemoteConfigure frame.
     * Frame layout: `0a <outer-len-varint> 08 <code1-varint> 12 <devinfo>...`
     */
    private fun parseConfigureCode1(frame: ByteArray): Int? {
        if (frame.size < 4 || frame[0].toInt() != 0x0a) return null
        var i = 1
        // skip outer length varint
        while (i < frame.size && (frame[i].toInt() and 0x80) != 0) i++
        i++ // first non-continuation byte
        if (i >= frame.size || frame[i].toInt() != 0x08) return null
        return decodeVarintAt(frame, i + 1)
    }

    private fun buildRemoteConfigure(): ByteArray = Proto.build {
        Proto.embed(this, RemoteFields.REMOTE_CONFIGURE) {
            Proto.int32(this, RemoteFields.CONF_CODE1, sessionCode1)
            Proto.embed(this, RemoteFields.CONF_DEVICE_INFO) {
                Proto.string(this, RemoteFields.DEV_MODEL, clientName)
                Proto.string(this, RemoteFields.DEV_VENDOR, "Universal Remote")
                Proto.int32(this, RemoteFields.DEV_UNKNOWN1, 1)
                Proto.string(this, RemoteFields.DEV_UNKNOWN2, "1")
                Proto.string(this, RemoteFields.DEV_PACKAGE_NAME, "androidtv-remote")
                Proto.string(this, RemoteFields.DEV_APP_VERSION, "1.0.0")
            }
        }
    }

    private fun buildRemoteSetActive(): ByteArray = Proto.build {
        Proto.embed(this, RemoteFields.REMOTE_SET_ACTIVE) {
            Proto.int32(this, RemoteFields.SET_ACTIVE, sessionCode1)
        }
    }

    private fun buildPingResponse(): ByteArray = Proto.build {
        Proto.embed(this, RemoteFields.REMOTE_PING_RESPONSE) {
            Proto.int32(this, RemoteFields.PING_VAL_1, 1)
        }
    }

    private fun buildKeyInjectPayload(keyCode: Int, direction: Int): ByteArray = Proto.build {
        Proto.embed(this, RemoteFields.REMOTE_KEY_INJECT) {
            Proto.int32(this, RemoteFields.DIRECTION, direction)
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

        // Force TLS 1.2 only. Thomson, TCL and several custom Android-TV skins
        // close the socket the moment we offer TLS 1.3 (encrypted ClientHello
        // hides info their polo verifier expects in cleartext). Locking to 1.2
        // is the most reliable single-version setting across vendors.
        val supported = socket.supportedProtocols.toSet()
        val tls12 = arrayOf("TLSv1.2").filter { it in supported }.toTypedArray()
        runCatching {
            socket.enabledProtocols = if (tls12.isNotEmpty()) tls12 else socket.supportedProtocols
        }
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
    /**
     * Canonical tronikos / androidtvremote2 schema:
     *   protocol_version = 2
     *   status           = STATUS_OK (200)
     *   type             = MessageType enum matching the embedded payload
     *
     * The earlier "no type field" version was wrong — the .proto file carries an
     * `optional MessageType type = 3` and Thomson's strict parser uses it to
     * dispatch the payload. Without it the TV replies status=400 and the PIN
     * never reaches the screen.
     */
    private fun envelope(out: java.io.ByteArrayOutputStream, messageType: Int) {
        Proto.int32(out, PairingFields.PROTOCOL_VERSION, 2)
        Proto.int32(out, PairingFields.STATUS, PairingFields.STATUS_OK)
        Proto.int32(out, PairingFields.MESSAGE_TYPE, messageType)
    }

    private fun buildPairingRequest(variant: PoloVariant): ByteArray = Proto.build {
        envelope(this, PairingFields.TYPE_PAIRING_REQUEST)
        Proto.embed(this, PairingFields.REQUEST) {
            Proto.string(this, variant.innerServiceTag, variant.serviceName)
            Proto.string(this, variant.innerClientTag, clientName)
        }
    }

    private fun buildPairingOption(): ByteArray = Proto.build {
        envelope(this, PairingFields.TYPE_OPTIONS)
        Proto.embed(this, PairingFields.OPTION) {
            Proto.embed(this, PairingFields.INPUT_ENCODINGS) {
                Proto.int32(this, PairingFields.ENCODING_TYPE, PairingFields.ENCODING_HEXADECIMAL)
                Proto.int32(this, PairingFields.SYMBOL_LENGTH, 6)
            }
            Proto.int32(this, PairingFields.PREFERRED_ROLE, PairingFields.ROLE_INPUT)
        }
    }

    private fun buildPairingConfiguration(): ByteArray = Proto.build {
        envelope(this, PairingFields.TYPE_CONFIGURATION)
        Proto.embed(this, PairingFields.CONFIGURATION) {
            Proto.embed(this, PairingFields.ENCODING) {
                Proto.int32(this, PairingFields.ENCODING_TYPE, PairingFields.ENCODING_HEXADECIMAL)
                Proto.int32(this, PairingFields.SYMBOL_LENGTH, 6)
            }
            Proto.int32(this, PairingFields.CLIENT_ROLE, PairingFields.ROLE_INPUT)
        }
    }

    private fun buildPairingSecret(secret: ByteArray): ByteArray = Proto.build {
        envelope(this, PairingFields.TYPE_SECRET)
        Proto.embed(this, PairingFields.SECRET) {
            Proto.bytes(this, PairingFields.SECRET_BYTES, secret)
        }
    }

    // ─── Wire framing: single-byte length + payload ─────────────────────────────
    private fun writeMessage(out: DataOutputStream, payload: ByteArray) {
        // Serialize all writes: sendKey() runs on the caller's coroutine while the
        // reader thread may simultaneously fire ping/Configure/Active replies. Two
        // unsynchronized writeByte+write calls into the same SSLSocket's output
        // stream interleave bytes on the wire and the TV silently drops both.
        synchronized(writeLock) {
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
    }

    /**
     * @param checkPairingStatus only set true for pairing-port reads. Messaging-
     *   port frames don't have a status field at byte 2; the scan would produce
     *   false positives whenever a payload happens to contain `0x10` (status tag).
     */
    private fun readMessage(ins: DataInputStream, checkPairingStatus: Boolean = true): ByteArray {
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
        if (checkPairingStatus) {
            val statusTag = (PairingFields.STATUS shl 3) or 0
            for (i in buf.indices) {
                if (buf[i].toInt() and 0xFF == statusTag) {
                    val status = decodeVarintAt(buf, i + 1)
                    if (status != null && status != PairingFields.STATUS_OK) {
                        throw IOException("TV rejected pairing message with status=$status")
                    }
                    break
                }
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
