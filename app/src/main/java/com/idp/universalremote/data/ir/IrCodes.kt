package com.idp.universalremote.data.ir

import com.idp.universalremote.domain.model.RemoteKey
import com.idp.universalremote.domain.model.TvBrand

data class IrSignal(val frequency: Int, val pattern: IntArray) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = pattern.contentHashCode()
}

object IrCodes {

    private val SAMSUNG_BASE = 38000
    private val LG_BASE = 38000
    private val SONY_BASE = 40000

    private val codes: Map<Pair<TvBrand, RemoteKey>, IrSignal> = buildMap {
        // Representative NEC-encoded sample patterns - real codes loaded from a remote DB.
        put(TvBrand.SAMSUNG to RemoteKey.POWER, necSignal(SAMSUNG_BASE, 0xE0E040BF))
        put(TvBrand.SAMSUNG to RemoteKey.VOL_UP, necSignal(SAMSUNG_BASE, 0xE0E0E01F))
        put(TvBrand.SAMSUNG to RemoteKey.VOL_DOWN, necSignal(SAMSUNG_BASE, 0xE0E0D02F))
        put(TvBrand.SAMSUNG to RemoteKey.MUTE, necSignal(SAMSUNG_BASE, 0xE0E0F00F))
        put(TvBrand.SAMSUNG to RemoteKey.CH_UP, necSignal(SAMSUNG_BASE, 0xE0E048B7))
        put(TvBrand.SAMSUNG to RemoteKey.CH_DOWN, necSignal(SAMSUNG_BASE, 0xE0E008F7))

        put(TvBrand.LG to RemoteKey.POWER, necSignal(LG_BASE, 0x20DF10EF))
        put(TvBrand.LG to RemoteKey.VOL_UP, necSignal(LG_BASE, 0x20DF40BF))
        put(TvBrand.LG to RemoteKey.VOL_DOWN, necSignal(LG_BASE, 0x20DFC03F))
        put(TvBrand.LG to RemoteKey.MUTE, necSignal(LG_BASE, 0x20DF906F))
        put(TvBrand.LG to RemoteKey.CH_UP, necSignal(LG_BASE, 0x20DF00FF))
        put(TvBrand.LG to RemoteKey.CH_DOWN, necSignal(LG_BASE, 0x20DF807F))

        put(TvBrand.SONY to RemoteKey.POWER, sirc12(SONY_BASE, 0x015))
        put(TvBrand.SONY to RemoteKey.VOL_UP, sirc12(SONY_BASE, 0x490))
        put(TvBrand.SONY to RemoteKey.VOL_DOWN, sirc12(SONY_BASE, 0xC90))
        put(TvBrand.SONY to RemoteKey.MUTE, sirc12(SONY_BASE, 0x290))
    }

    fun find(brand: TvBrand, key: RemoteKey): IrSignal? = codes[brand to key]

    private fun necSignal(freq: Int, code: Long): IrSignal {
        val period = 1_000_000.0 / freq
        val burst9000 = (9000 / period).toInt()
        val space4500 = (4500 / period).toInt()
        val burst560 = (560 / period).toInt()
        val space560 = (560 / period).toInt()
        val space1690 = (1690 / period).toInt()
        val pattern = mutableListOf<Int>()
        pattern += burst9000
        pattern += space4500
        for (bit in 31 downTo 0) {
            pattern += burst560
            pattern += if ((code shr bit) and 1L == 1L) space1690 else space560
        }
        pattern += burst560
        return IrSignal(freq, pattern.toIntArray())
    }

    private fun sirc12(freq: Int, code: Long): IrSignal {
        val period = 1_000_000.0 / freq
        val header = (2400 / period).toInt()
        val gap = (600 / period).toInt()
        val one = (1200 / period).toInt()
        val zero = (600 / period).toInt()
        val pattern = mutableListOf<Int>()
        pattern += header
        pattern += gap
        for (bit in 11 downTo 0) {
            pattern += if ((code shr bit) and 1L == 1L) one else zero
            pattern += gap
        }
        return IrSignal(freq, pattern.toIntArray())
    }
}
