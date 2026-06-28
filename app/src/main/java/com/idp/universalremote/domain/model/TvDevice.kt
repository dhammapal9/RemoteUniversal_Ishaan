package com.idp.universalremote.domain.model

data class TvDevice(
    val id: String,
    val name: String,
    val brand: TvBrand,
    val model: String? = null,
    val ipAddress: String? = null,
    val macAddress: String? = null,
    val type: ConnectionType = ConnectionType.WIFI,
    val isFavorite: Boolean = false,
    val pairingToken: String? = null,
    val lastConnectedAt: Long = System.currentTimeMillis()
)

enum class ConnectionType { WIFI, IR, BLUETOOTH }

enum class TvBrand(val displayName: String) {
    SAMSUNG("Samsung"),
    LG("LG"),
    SONY("Sony"),
    HISENSE("Hisense"),
    TCL("TCL"),
    PANASONIC("Panasonic"),
    PHILIPS("Philips"),
    SHARP("Sharp"),
    VIZIO("Vizio"),
    XIAOMI("Xiaomi"),
    INSIGNIA("Insignia"),
    TOSHIBA("Toshiba"),
    HITACHI("Hitachi"),
    ANDROID_TV("Android TV"),
    GOOGLE_TV("Google TV"),
    ROKU("Roku"),
    FIRE_TV("Fire TV"),
    GENERIC("Universal");

    companion object {
        fun fromName(name: String?): TvBrand =
            entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) } ?: GENERIC
    }
}
