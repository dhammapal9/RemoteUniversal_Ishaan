package com.idp.universalremote.presentation.search

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.idp.universalremote.R
import com.idp.universalremote.core.common.collectFlow
import com.idp.universalremote.core.common.toast
import com.idp.universalremote.core.common.viewBinding
import com.idp.universalremote.databinding.DialogManualDeviceBinding
import com.idp.universalremote.databinding.DialogPairingCodeBinding
import com.idp.universalremote.databinding.FragmentSearchDevicesBinding
import com.idp.universalremote.domain.model.ConnectionState
import com.idp.universalremote.domain.model.ConnectionType
import com.idp.universalremote.domain.model.TvBrand
import com.idp.universalremote.domain.model.TvDevice
import dagger.hilt.android.AndroidEntryPoint
import java.util.regex.Pattern

@AndroidEntryPoint
class SearchDevicesFragment : Fragment(R.layout.fragment_search_devices) {

    private val binding by viewBinding(FragmentSearchDevicesBinding::bind)
    private val viewModel: SearchDevicesViewModel by viewModels()

    private val adapter = DiscoveredDeviceAdapter { device ->
        viewModel.connect(device)
    }

    private var activeDialog: AlertDialog? = null
    private var activeDeviceId: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.btnRefresh.setOnClickListener {
            binding.progress.visibility = View.VISIBLE
            viewModel.refresh()
            scheduleProgressTimeout()
        }
        binding.btnClose.setOnClickListener { findNavController().navigateUp() }
        binding.btnAddManually.setOnClickListener { showManualEntryDialog() }

        collectFlow(viewModel.devices) { list ->
            adapter.submitList(list)
            val count = list.size
            binding.subtitle.text = if (count == 0) {
                getString(R.string.searching_devices)
            } else {
                getString(R.string.found_devices_format, count)
            }
            binding.emptyHint.visibility = if (count == 0) View.VISIBLE else View.GONE
            // Hide spinner as soon as we have any results.
            if (count > 0) binding.progress.visibility = View.GONE
        }

        scheduleProgressTimeout()

        collectFlow(viewModel.state) { state ->
            when (state) {
                is ConnectionState.WaitingForTvAuth -> showPairingCodeDialog(state.device)
                is ConnectionState.PairingRequired -> showPairingCodeDialog(state.device)
                is ConnectionState.Connected -> {
                    dismissDialog()
                    val args = Bundle().apply { putString("deviceId", state.device.id) }
                    findNavController().navigate(R.id.action_search_to_remote, args)
                }
                is ConnectionState.Failed -> {
                    dismissDialog()
                    toast(state.reason, long = true)
                }
                ConnectionState.Disconnected,
                ConnectionState.Searching,
                is ConnectionState.Connecting -> Unit
            }
        }

        viewModel.refresh()
    }

    override fun onDestroyView() {
        binding.progress.removeCallbacks(progressTimeout)
        dismissDialog()
        super.onDestroyView()
    }

    private fun showPairingCodeDialog(device: TvDevice) {
        if (activeDeviceId == device.id && activeDialog?.isShowing == true) return
        dismissDialog()

        val dialogBinding = DialogPairingCodeBinding.inflate(layoutInflater)
        dialogBinding.deviceName.text = device.name
        dialogBinding.hintText.setText(hintFor(device.brand))

        activeDeviceId = device.id
        activeDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.enter_pairing_code)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.connect) { _, _ ->
                viewModel.pair(dialogBinding.codeInput.text?.toString().orEmpty().trim())
            }
            .setNegativeButton(R.string.cancel) { _, _ -> viewModel.cancelConnect() }
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun showManualEntryDialog() {
        val dialogBinding = DialogManualDeviceBinding.inflate(layoutInflater)
        val brandLabels = BRAND_PICKER.map { it.second }
        val brandAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            brandLabels
        )
        dialogBinding.brandInput.setAdapter(brandAdapter)
        dialogBinding.brandInput.setText(brandLabels.first(), false)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.manual_device_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.add, null) // override below for validation
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ip = dialogBinding.ipInput.text?.toString().orEmpty().trim()
                if (!IP_PATTERN.matcher(ip).matches()) {
                    dialogBinding.ipInput.error = getString(R.string.manual_device_invalid_ip)
                    return@setOnClickListener
                }
                val brand = BRAND_PICKER.first {
                    it.second == dialogBinding.brandInput.text?.toString()
                }.first
                val name = dialogBinding.nameInput.text?.toString()
                    .orEmpty().trim().ifBlank { brand.displayName + " TV" }
                viewModel.connect(
                    TvDevice(
                        id = "manual_$ip",
                        name = name,
                        brand = brand,
                        ipAddress = ip,
                        type = ConnectionType.WIFI
                    )
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun dismissDialog() {
        activeDialog?.dismiss()
        activeDialog = null
        activeDeviceId = null
    }

    private fun scheduleProgressTimeout() {
        binding.progress.removeCallbacks(progressTimeout)
        binding.progress.postDelayed(progressTimeout, PROGRESS_TIMEOUT_MS)
    }

    private val progressTimeout = Runnable {
        // Even if nothing showed up, stop spinning after the search window.
        if (view != null) binding.progress.visibility = View.GONE
    }

    private fun hintFor(brand: TvBrand): Int = when (brand) {
        TvBrand.SONY -> R.string.pairing_hint_sony
        TvBrand.ANDROID_TV, TvBrand.GOOGLE_TV -> R.string.pairing_hint_pin
        else -> R.string.pairing_hint_default
    }

    companion object {
        private const val PROGRESS_TIMEOUT_MS = 8_000L
        private val IP_PATTERN: Pattern =
            Pattern.compile("^(25[0-5]|2[0-4]\\d|[01]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[01]?\\d?\\d)){3}$")

        private val BRAND_PICKER = listOf(
            TvBrand.SAMSUNG to "Samsung (Tizen 2016+)",
            TvBrand.ROKU to "Roku",
            TvBrand.LG to "LG (webOS)",
            TvBrand.SONY to "Sony Bravia",
            TvBrand.ANDROID_TV to "Android TV",
            TvBrand.GOOGLE_TV to "Google TV",
            TvBrand.HISENSE to "Hisense",
            TvBrand.TCL to "TCL",
            TvBrand.VIZIO to "Vizio",
            TvBrand.PANASONIC to "Panasonic",
            TvBrand.PHILIPS to "Philips",
            TvBrand.SHARP to "Sharp",
            TvBrand.XIAOMI to "Xiaomi",
            TvBrand.GENERIC to "Other / Universal"
        )
    }
}
