package com.idp.universalremote.presentation.cast

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayoutMediator
import com.idp.universalremote.R
import com.idp.universalremote.core.common.viewBinding
import com.idp.universalremote.databinding.FragmentMediaPickerBinding

class MediaPickerFragment : Fragment(R.layout.fragment_media_picker) {

    private val binding by viewBinding(FragmentMediaPickerBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val type = arguments?.getString("type") ?: "image"
        binding.title.text = when (type) {
            "video" -> getString(R.string.cast_video)
            "audio" -> getString(R.string.cast_audio)
            else -> getString(R.string.cast_photo)
        }
        binding.pager.adapter = MediaPagerAdapter(this, type)
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.text = if (position == 0) "All" else "Album"
        }.attach()
        binding.back.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
    }
}
