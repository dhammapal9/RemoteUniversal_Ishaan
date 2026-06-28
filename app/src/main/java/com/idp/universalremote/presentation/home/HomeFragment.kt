package com.idp.universalremote.presentation.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.idp.universalremote.R
import com.idp.universalremote.core.common.viewBinding
import com.idp.universalremote.databinding.FragmentHomeBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HomeFragment : Fragment(R.layout.fragment_home) {

    private val binding by viewBinding(FragmentHomeBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.heroCard.setOnClickListener { goConnect() }
        binding.heroArrow.setOnClickListener { goConnect() }

        binding.btnSamsung.setOnClickListener { goRemote("samsung") }
        binding.btnRoku.setOnClickListener { goRemote("roku") }
        binding.btnIr.setOnClickListener { goRemote("ir") }
        binding.btnIptv.setOnClickListener { goMirroring() }
        binding.btnScreenMirror.setOnClickListener { goMirroring() }
        binding.btnScreenCast.setOnClickListener { goCast() }
        binding.btnThemes.setOnClickListener { goSettings() }
        binding.btnMyRemotes.setOnClickListener { goRemote("favorites") }
        binding.btnSettings.setOnClickListener { goSettings() }
        binding.btnPremium.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_premium)
        }
        binding.btnMenu.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_settings)
        }
    }

    private fun goConnect() {
        findNavController().navigate(R.id.action_home_to_search)
    }

    private fun goRemote(profile: String) {
        val args = Bundle().apply { putString("profile", profile) }
        findNavController().navigate(R.id.action_home_to_remote, args)
    }

    private fun goCast() {
        findNavController().navigate(R.id.action_home_to_cast)
    }

    private fun goMirroring() {
        findNavController().navigate(R.id.action_home_to_mirroring)
    }

    private fun goSettings() {
        findNavController().navigate(R.id.action_home_to_settings)
    }
}
