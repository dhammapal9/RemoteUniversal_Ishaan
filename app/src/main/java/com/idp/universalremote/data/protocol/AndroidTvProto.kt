package com.idp.universalremote.data.protocol

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf wire-format encoder/decoder used by the Android TV Remote v2
 * client. Protocol Buffers' wire format is small enough to write by hand; pulling
 * in the full protobuf-java runtime would add ~1 MB for ~10 messages.
 *
 * See https://protobuf.dev/programming-guides/encoding/ for the spec.
 */
internal object Proto {

    fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun tag(out: ByteArrayOutputStream, fieldNumber: Int, wireType: Int) {
        varint(out, ((fieldNumber.toLong() shl 3) or wireType.toLong()))
    }

    fun bytes(out: ByteArrayOutputStream, fieldNumber: Int, value: ByteArray) {
        tag(out, fieldNumber, 2)
        varint(out, value.size.toLong())
        out.write(value, 0, value.size)
    }

    fun string(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        bytes(out, fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    fun int32(out: ByteArrayOutputStream, fieldNumber: Int, value: Int) {
        tag(out, fieldNumber, 0)
        varint(out, value.toLong())
    }

    /** Encode a nested message (length-delimited). */
    fun embed(out: ByteArrayOutputStream, fieldNumber: Int, builder: ByteArrayOutputStream.() -> Unit) {
        val nested = ByteArrayOutputStream().apply(builder).toByteArray()
        bytes(out, fieldNumber, nested)
    }

    fun build(builder: ByteArrayOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().apply(builder).toByteArray()
}

/**
 * Pairing-stage message kinds used by the Android TV Remote v2 protocol.
 * Field numbers correspond to the PairingMessage `oneof` arms.
 */
internal object PairingFields {
    // Outer PairingMessage envelope fields
    const val PROTOCOL_VERSION = 1
    const val STATUS = 2
    /**
     * REQUIRED in protocol v2. PairingMessage carries a `type` enum that tells the
     * receiver which `optional` nested message to expect. Without it the TV replies
     * with status=400 (STATUS_ERROR) and closes the socket.
     */
    const val MESSAGE_TYPE = 3

    /** PoloMessageType enum values — sent in field [MESSAGE_TYPE]. */
    const val TYPE_PAIRING_REQUEST = 10
    const val TYPE_OPTIONS = 20
    const val TYPE_CONFIGURATION = 30
    const val TYPE_SECRET = 40

    // PairingMessage nested-message fields. These match the actual androidtvremote2
    // .proto file — confirmed by parsing the rejection from a real Thomson TV.
    const val REQUEST = 10        // PairingRequest
    const val REQUEST_ACK = 11    // PairingRequestAck
    const val OPTION = 20         // Options
    const val OPTION_ACK = 21
    const val CONFIGURATION = 30  // Configuration
    const val CONFIGURATION_ACK = 31
    const val SECRET = 40         // Secret
    const val SECRET_ACK = 41

    // Inner PairingRequest fields — Google polo schema uses 10/11. Inferred from
    // the Thomson TV's cert CN ("atvremote/<MAC>") which means it runs the
    // original com.google.polo stack, not the tronikos library's renamed v2 layout.
    const val SERVICE_NAME = 10
    const val CLIENT_NAME = 11

    // Inner Options fields
    const val INPUT_ENCODINGS = 1
    const val OUTPUT_ENCODINGS = 2
    const val PREFERRED_ROLE = 3

    // Inner Encoding fields
    const val ENCODING_TYPE = 1
    const val SYMBOL_LENGTH = 2

    // Inner Configuration fields
    const val ENCODING = 1
    const val CLIENT_ROLE = 2

    // Inner Secret fields
    const val SECRET_BYTES = 1

    const val STATUS_OK = 200
    const val ENCODING_HEXADECIMAL = 3
    const val ROLE_INPUT = 1
}

/**
 * RemoteMessage envelope used on port 6466 (after pairing).
 *
 * The protocol multiplexes many message kinds via a oneof; for sending key
 * events we only need [REMOTE_KEY_INJECT] (= field 10) with a nested
 * RemoteKeyInject carrying [KEY_CODE] (field 1) + [DIRECTION] (field 2).
 */
internal object RemoteFields {
    /**
     * RemoteMessage oneof tags — taken from the canonical Android TV remote .proto
     * (matches the protobuf-generated `Remotemessage.java` in the working sample).
     *
     * The handshake on the messaging port (6466) is:
     *   1. TV → us:   RemoteConfigure
     *   2. us → TV:   RemoteConfigure
     *   3. TV → us:   another message (usually Configure/volume state)
     *   4. us → TV:   RemoteSetActive(622)
     *   5. TV → us:   RemoteStart  ← input service is now live
     *   6. either way: RemoteKeyInject can be sent.
     *
     * Field numbers used to be guessed (3/12/13/16); they were wrong, which is why
     * the TV silently dropped every KeyInject. The TV's protobuf parser sees a
     * field it doesn't recognise as an unknown extension and just skips it.
     */
    const val REMOTE_CONFIGURE = 1
    const val REMOTE_SET_ACTIVE = 2
    const val REMOTE_PING_REQUEST = 8
    const val REMOTE_PING_RESPONSE = 9
    const val REMOTE_KEY_INJECT = 10
    const val REMOTE_START = 40
    /**
     * Field 90 carries [RemoteAppLinkLaunchRequest]. Confirmed from the working
     * sample's protobuf-generated `Remotemessage.java`. The TV's launcher service
     * parses the embedded URL and opens whichever installed app claims it (Netflix
     * for `netflix.com/title.*`, YouTube for `youtube.com`, etc.) — this is how
     * Google's own Android TV remote launches apps, not DIAL.
     */
    const val REMOTE_APP_LINK_LAUNCH_REQUEST = 90

    /** RemoteAppLinkLaunchRequest fields. */
    const val APP_LINK = 1

    // RemoteConfigure fields
    const val CONF_CODE1 = 1
    const val CONF_DEVICE_INFO = 2

    // DeviceInfo fields
    const val DEV_MODEL = 1
    const val DEV_VENDOR = 2
    const val DEV_UNKNOWN1 = 3
    const val DEV_UNKNOWN2 = 4
    const val DEV_PACKAGE_NAME = 5
    const val DEV_APP_VERSION = 6

    // RemoteSetActive fields
    const val SET_ACTIVE = 1

    // RemotePingRequest / Response fields
    const val PING_VAL_1 = 1

    // RemoteKeyInject fields — order matters: key_code=1, direction=2.
    // (Previous code had them swapped, so we were telling the TV
    //  "press SOFT_LEFT in direction 24" which is invalid → dropped.)
    const val KEY_CODE = 1
    const val DIRECTION = 2

    // RemoteDirection enum values
    const val DIR_START_LONG = 1
    const val DIR_END_LONG = 2
    const val DIR_SHORT = 3

    /** Magic activation code the TV expects in RemoteSetActive.active. */
    const val ACTIVE_MAGIC = 622
}

