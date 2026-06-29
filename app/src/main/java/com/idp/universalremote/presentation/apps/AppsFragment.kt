package com.idp.universalremote.presentation.apps

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.idp.universalremote.R
import com.idp.universalremote.core.common.toast
import com.idp.universalremote.core.common.viewBinding
import com.idp.universalremote.databinding.FragmentAppsBinding
import com.idp.universalremote.domain.model.ConnectionState
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AppsFragment : Fragment(R.layout.fragment_apps) {

    private val binding by viewBinding(FragmentAppsBinding::bind)
    private val viewModel: AppsViewModel by viewModels()

    private val adapter = AppsAdapter { shortcut -> onAppClicked(shortcut) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.appsGrid.layoutManager = GridLayoutManager(requireContext(), GRID_COLUMNS)
        binding.appsGrid.adapter = adapter
        adapter.submitList(AppShortcut.DEFAULTS)
    }

    private fun onAppClicked(shortcut: AppShortcut) {
        // Gate on an active connection so the user gets a useful message instead
        // of a silent no-op when no TV is paired.
        val state = viewModel.connectionState.value
        if (state !is ConnectionState.Connected) {
            toast(getString(R.string.please_connect_tv), long = true)
            return
        }
        viewModel.launch(shortcut)
        toast(getString(R.string.cast_app_launched, shortcut.displayName))
    }

    companion object {
        private const val GRID_COLUMNS = 4
    }
}
