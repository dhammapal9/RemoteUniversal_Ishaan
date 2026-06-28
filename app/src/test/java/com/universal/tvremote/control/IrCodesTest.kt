package com.idp.universalremote

import com.google.common.truth.Truth.assertThat
import com.idp.universalremote.data.ir.IrCodes
import com.idp.universalremote.domain.model.RemoteKey
import com.idp.universalremote.domain.model.TvBrand
import org.junit.Test

class IrCodesTest {

    @Test
    fun `samsung power is encoded with valid NEC pattern`() {
        val signal = IrCodes.find(TvBrand.SAMSUNG, RemoteKey.POWER)
        assertThat(signal).isNotNull()
        assertThat(signal!!.frequency).isEqualTo(38000)
        // NEC: 1 header burst + 1 header space + 32 bits * 2 + 1 trailing burst
        assertThat(signal.pattern.size).isEqualTo(1 + 1 + 64 + 1)
    }

    @Test
    fun `sony power is encoded with valid SIRC pattern`() {
        val signal = IrCodes.find(TvBrand.SONY, RemoteKey.POWER)
        assertThat(signal).isNotNull()
        assertThat(signal!!.frequency).isEqualTo(40000)
        // SIRC 12: 1 header burst + 1 header gap + 12 bits * 2
        assertThat(signal.pattern.size).isEqualTo(2 + 24)
    }

    @Test
    fun `unknown brand+key combo returns null`() {
        assertThat(IrCodes.find(TvBrand.GENERIC, RemoteKey.POWER)).isNull()
    }
}
