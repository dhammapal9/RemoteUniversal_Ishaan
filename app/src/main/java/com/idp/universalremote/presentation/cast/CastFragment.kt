package com.idp.universalremote.presentation.cast

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.idp.universalremote.R
import com.idp.universalremote.core.common.toast
import com.idp.universalremote.core.common.viewBinding
import com.idp.universalremote.databinding.FragmentCastBinding

class CastFragment : Fragment(R.layout.fragment_cast) {

    private val binding by viewBinding(FragmentCastBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tapToMirror.setOnClickListener {
            findNavController().navigate(R.id.action_cast_to_mirroring)
        }
        binding.photoCard.setOnClickListener { openMedia("image") }
        binding.videoCard.setOnClickListener { openMedia("video") }
        binding.audioCard.setOnClickListener { openMedia("audio") }
        // Premium-tier features — give the user feedback so the tile doesn't feel broken.
        val comingSoon = View.OnClickListener { toast(getString(R.string.feature_coming_soon)) }
        binding.iptvCard.setOnClickListener(comingSoon)
        binding.imageOnlineCard.setOnClickListener(comingSoon)
        binding.cameraCard.setOnClickListener(comingSoon)
    }

    private fun openMedia(type: String) {
        val args = Bundle().apply { putString("type", type) }
        findNavController().navigate(R.id.action_cast_to_media_picker, args)
    }
}
