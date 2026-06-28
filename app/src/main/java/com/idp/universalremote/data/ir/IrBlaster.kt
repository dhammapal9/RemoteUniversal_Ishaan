package com.idp.universalremote.data.ir

import android.content.Context
import android.hardware.ConsumerIrManager
import com.idp.universalremote.domain.model.RemoteKey
import com.idp.universalremote.domain.model.TvBrand
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IrBlaster @Inject constructor(
    @ApplicationContext context: Context
) {
    private val manager: ConsumerIrManager? =
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    fun isAvailable(): Boolean = manager?.hasIrEmitter() == true

    fun transmit(brand: TvBrand, key: RemoteKey): Boolean {
        val mgr = manager ?: return false
        if (!mgr.hasIrEmitter()) return false
        val pattern = IrCodes.find(brand, key) ?: return false
        return runCatching {
            mgr.transmit(pattern.frequency, pattern.pattern)
            true
        }.getOrDefault(false)
    }
}
