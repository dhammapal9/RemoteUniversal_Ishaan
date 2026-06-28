package com.idp.universalremote

import com.google.common.truth.Truth.assertThat
import com.idp.universalremote.data.ir.IrBlaster
import com.idp.universalremote.data.repository.RemoteCommandRepositoryImpl
import com.idp.universalremote.domain.model.ConnectionState
import com.idp.universalremote.domain.model.ConnectionType
import com.idp.universalremote.domain.model.TvBrand
import com.idp.universalremote.domain.model.TvDevice
import com.idp.universalremote.domain.repository.TvDeviceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteCommandRepositoryTest {

    private val deviceRepo = mockk<TvDeviceRepository>(relaxed = true)
    private val ir = mockk<IrBlaster>(relaxed = true) {
        every { isAvailable() } returns true
    }
    private val sut = RemoteCommandRepositoryImpl(deviceRepo, ir)

    @Test
    fun `wifi connect without pairing token transitions to PairingRequired`() = runTest {
        val device = TvDevice("id", "TV", TvBrand.SAMSUNG, type = ConnectionType.WIFI)
        sut.connect(device)
        assertThat(sut.connectionState.value).isInstanceOf(ConnectionState.PairingRequired::class.java)
    }

    @Test
    fun `pair with valid code emits Connected`() = runTest {
        val device = TvDevice("id", "TV", TvBrand.SAMSUNG, type = ConnectionType.WIFI)
        coEvery { deviceRepo.save(any()) } returns Unit
        sut.connect(device)
        sut.pair("1234")
        assertThat(sut.connectionState.value).isInstanceOf(ConnectionState.Connected::class.java)
        coVerify { deviceRepo.save(any()) }
    }

    @Test
    fun `disconnect resets state`() = runTest {
        sut.disconnect()
        assertThat(sut.connectionState.value).isEqualTo(ConnectionState.Disconnected)
    }
}
