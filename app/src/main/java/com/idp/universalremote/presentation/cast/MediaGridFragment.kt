package com.idp.universalremote.presentation.cast

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.idp.universalremote.R
import com.idp.universalremote.core.common.collectFlow
import com.idp.universalremote.core.common.viewBinding
import com.idp.universalremote.databinding.FragmentMediaGridBinding
import com.idp.universalremote.domain.model.MediaType
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MediaGridFragment : Fragment(R.layout.fragment_media_grid) {

    private val binding by viewBinding(FragmentMediaGridBinding::bind)
    private val viewModel: MediaGridViewModel by viewModels()
    private val adapter = MediaAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val type = when (arguments?.getString("type")) {
            "video" -> MediaType.VIDEO
            "audio" -> MediaType.AUDIO
            else -> MediaType.IMAGE
        }
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), if (type == MediaType.AUDIO) 1 else 3)
        binding.recycler.adapter = adapter

        collectFlow(viewModel.items) { adapter.submitList(it) }
        viewModel.load(type)
    }
}
