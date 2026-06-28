package com.idp.universalremote.presentation.cast

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.idp.universalremote.R
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
        binding.iptvCard.setOnClickListener { /* IPTV M3U browser */ }
        binding.imageOnlineCard.setOnClickListener { /* browser */ }
        binding.cameraCard.setOnClickListener { /* live cam */ }
    }

    private fun openMedia(type: String) {
        val args = Bundle().apply { putString("type", type) }
        findNavController().navigate(R.id.action_cast_to_media_picker, args)
    }
}
