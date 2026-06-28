package com.idp.universalremote.presentation.mirroring

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.idp.universalremote.R
import com.idp.universalremote.core.common.toast
import com.idp.universalremote.core.common.viewBinding
import com.idp.universalremote.databinding.FragmentMirroringBinding
import com.idp.universalremote.mirroring.MirroringService

class MirroringFragment : Fragment(R.layout.fragment_mirroring) {

    private val binding by viewBinding(FragmentMirroringBinding::bind)

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            MirroringService.start(requireContext(), result.resultCode, result.data!!)
        } else {
            toast(getString(R.string.connection_failed))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.startMirroring.setOnClickListener { requestProjection() }
    }

    private fun requestProjection() {
        val manager = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}
