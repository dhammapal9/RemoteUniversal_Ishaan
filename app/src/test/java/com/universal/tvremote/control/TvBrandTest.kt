package com.idp.universalremote

import com.google.common.truth.Truth.assertThat
import com.idp.universalremote.domain.model.TvBrand
import org.junit.Test

class TvBrandTest {

    @Test
    fun `fromName matches known brand case-insensitively`() {
        assertThat(TvBrand.fromName("samsung")).isEqualTo(TvBrand.SAMSUNG)
        assertThat(TvBrand.fromName("LG")).isEqualTo(TvBrand.LG)
        assertThat(TvBrand.fromName("sony")).isEqualTo(TvBrand.SONY)
    }

    @Test
    fun `fromName falls back to GENERIC for unknown`() {
        assertThat(TvBrand.fromName(null)).isEqualTo(TvBrand.GENERIC)
        assertThat(TvBrand.fromName("not-a-tv")).isEqualTo(TvBrand.GENERIC)
    }
}
