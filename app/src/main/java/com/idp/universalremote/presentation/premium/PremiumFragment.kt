package com.idp.universalremote.presentation.premium

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.idp.universalremote.R
import com.idp.universalremote.core.common.AppPreferences
import com.idp.universalremote.core.common.toast
import com.idp.universalremote.core.common.viewBinding
import com.idp.universalremote.databinding.FragmentPremiumBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PremiumFragment : Fragment(R.layout.fragment_premium) {

    private val binding by viewBinding(FragmentPremiumBinding::bind)

    @Inject lateinit var preferences: AppPreferences

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.close.setOnClickListener { findNavController().navigateUp() }
        binding.continueBtn.setOnClickListener {
            preferences.isPremium = true
            toast(getString(R.string.unlock_premium))
            findNavController().navigateUp()
        }
    }
}
