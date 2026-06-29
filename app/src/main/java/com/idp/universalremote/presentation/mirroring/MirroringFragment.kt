package com.idp.universalremote.presentation.mirroring

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.fragment.app.Fragment
import com.idp.universalremote.R
import com.idp.universalremote.core.common.toast
import com.idp.universalremote.core.common.viewBinding
import com.idp.universalremote.databinding.FragmentMirroringBinding

/**
 * Mirroring is delegated to the platform's Cast picker (Miracast / Google Cast).
 *
 * Why: actually streaming captured frames from MediaProjection to a TV requires a
 * Cast SDK receiver app + Google Cast Framework dependency + a Cast App ID
 * registered with Google. Building our own RTP/Miracast server doesn't scale to
 * arbitrary TV brands. The system's Cast picker, on the other hand, talks to
 * whatever cast receiver the TV exposes (Chromecast, Miracast on Roku/Fire TV/
 * older Samsung, AirPlay on Apple TV-likes via third-party apps).
 *
 * So we open `Settings.ACTION_CAST_SETTINGS`; the user picks their TV in the
 * native dialog and Android starts streaming. Best of all worlds for a generic
 * universal-remote app.
 */
class MirroringFragment : Fragment(R.layout.fragment_mirroring) {

    private val binding by viewBinding(FragmentMirroringBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.startMirroring.setOnClickListener { openSystemCastPicker() }
    }

    private fun openSystemCastPicker() {
        // ACTION_CAST_SETTINGS exists from API 21 and is the canonical way to
        // surface Miracast / Google Cast receivers. Fall back to wireless display
        // settings (older Samsung One UI), then generic display settings, so the
        // user always lands on a screen where mirroring can be enabled.
        val candidates = listOf(
            Intent(Settings.ACTION_CAST_SETTINGS),
            Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
            Intent(Settings.ACTION_DISPLAY_SETTINGS)
        )
        for (intent in candidates) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // try next candidate
            }
        }
        toast(getString(R.string.connection_failed), long = true)
    }
}
