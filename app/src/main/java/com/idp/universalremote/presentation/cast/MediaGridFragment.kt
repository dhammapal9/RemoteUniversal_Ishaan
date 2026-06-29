package com.idp.universalremote.presentation.cast

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.idp.universalremote.R
import com.idp.universalremote.core.common.PermissionExt
import com.idp.universalremote.core.common.collectFlow
import com.idp.universalremote.core.common.toast
import com.idp.universalremote.core.common.viewBinding
import com.idp.universalremote.databinding.FragmentMediaGridBinding
import com.idp.universalremote.domain.model.MediaType
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MediaGridFragment : Fragment(R.layout.fragment_media_grid) {

    private val binding by viewBinding(FragmentMediaGridBinding::bind)
    private val viewModel: MediaGridViewModel by viewModels()
    private val adapter = MediaAdapter { item -> viewModel.cast(item) }

    private val type: MediaType by lazy {
        when (arguments?.getString("type")) {
            "video" -> MediaType.VIDEO
            "audio" -> MediaType.AUDIO
            else -> MediaType.IMAGE
        }
    }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Android 14+ "Selected photos only" reports the visual-user-selected grant
        // even though images/video are denied — still counts as enough access.
        val granted = grants.values.any { it } || PermissionExt.hasMediaReadAccess(requireContext(), type)
        if (granted) viewModel.load(type)
        else toast(getString(R.string.media_permission_required), long = true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), if (type == MediaType.AUDIO) 1 else 3)
        binding.recycler.adapter = adapter
        collectFlow(viewModel.items) { adapter.submitList(it) }
        collectFlow(viewModel.castResults) { result ->
            when (result) {
                is MediaGridViewModel.CastResult.Success ->
                    toast(getString(R.string.cast_app_launched, result.title))
                is MediaGridViewModel.CastResult.Failed ->
                    toast(getString(R.string.cast_media_failed), long = true)
                MediaGridViewModel.CastResult.NotConnected ->
                    toast(getString(R.string.please_connect_tv), long = true)
            }
        }
        ensurePermissionAndLoad()
    }

    private fun ensurePermissionAndLoad() {
        if (PermissionExt.hasMediaReadAccess(requireContext(), type)) {
            viewModel.load(type)
            return
        }
        mediaPermissionLauncher.launch(PermissionExt.mediaReadPermissions(type))
    }
}
