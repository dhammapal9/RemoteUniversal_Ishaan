package com.idp.universalremote.presentation.wifi

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.idp.universalremote.R
import com.idp.universalremote.core.common.AppPreferences
import com.idp.universalremote.core.common.NetworkState
import com.idp.universalremote.core.common.collectFlow
import com.idp.universalremote.core.common.viewBinding
import com.idp.universalremote.databinding.FragmentWifiPromptBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class WifiPromptFragment : Fragment(R.layout.fragment_wifi_prompt) {

    private val binding by viewBinding(FragmentWifiPromptBinding::bind)

    @Inject lateinit var networkState: NetworkState
    @Inject lateinit var preferences: AppPreferences

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.turnOnWifi.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        }
        binding.skip.setOnClickListener {
            preferences.wifiSetupSkipped = true
            goHome()
        }
        binding.cantFind.setOnClickListener {
            goSearchDevices()
        }
        binding.close.setOnClickListener {
            preferences.wifiSetupSkipped = true
            goHome()
        }

        collectFlow(networkState.observeWifiConnected()) { connected ->
            binding.statusIcon.setImageResource(
                if (connected) R.drawable.ic_check_circle else R.drawable.ic_wifi
            )
            binding.statusTitle.text =
                getString(if (connected) R.string.connected else R.string.wifi_not_connected)
            binding.statusDesc.text =
                getString(if (connected) R.string.same_wifi_hint else R.string.wifi_not_connected_desc)
            binding.turnOnWifi.text =
                getString(if (connected) R.string.continue_text else R.string.turn_on_wifi)
            binding.turnOnWifi.setOnClickListener {
                if (connected) goSearchDevices() else runCatching {
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
            }
        }
    }

    private fun goHome() {
        findNavController().navigate(R.id.action_wifi_to_home)
    }

    private fun goSearchDevices() {
        findNavController().navigate(R.id.action_wifi_to_search)
    }
}
