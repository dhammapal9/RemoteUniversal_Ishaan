package com.idp.universalremote.domain.model

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Searching : ConnectionState()
    data class Connecting(val device: TvDevice) : ConnectionState()

    /** TV is showing an Allow/Deny prompt (Samsung Tizen, AirPlay…) — user must accept on the TV. */
    data class WaitingForTvAuth(val device: TvDevice) : ConnectionState()

    /** TV is showing a PIN (LG webOS, Android TV v2, Apple TV) — user must type it. */
    data class PairingRequired(val device: TvDevice) : ConnectionState()

    data class Connected(val device: TvDevice) : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}
