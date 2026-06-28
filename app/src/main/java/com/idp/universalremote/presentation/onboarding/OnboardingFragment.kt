package com.idp.universalremote.presentation.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.idp.universalremote.R
import com.idp.universalremote.core.common.AppPreferences
import com.idp.universalremote.core.common.viewBinding
import com.idp.universalremote.databinding.FragmentOnboardingBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class OnboardingFragment : Fragment(R.layout.fragment_onboarding) {

    private val binding by viewBinding(FragmentOnboardingBinding::bind)

    @Inject lateinit var preferences: AppPreferences

    private val pages = listOf(
        OnboardingPage(
            titleRes = R.string.onboarding_welcome_title,
            descRes = R.string.onboarding_welcome_desc,
            iconRes = R.drawable.ic_tv
        ),
        OnboardingPage(R.string.onboarding_smart_title, R.string.onboarding_smart_desc, R.drawable.ic_wifi),
        OnboardingPage(R.string.onboarding_ir_title, R.string.onboarding_ir_desc, R.drawable.ic_remote),
        OnboardingPage(R.string.onboarding_mirror_title, R.string.onboarding_mirror_desc, R.drawable.ic_mirror),
        OnboardingPage(R.string.onboarding_cast_title, R.string.onboarding_cast_desc, R.drawable.ic_cast),
        OnboardingPage(R.string.onboarding_theme_title, R.string.onboarding_theme_desc, R.drawable.ic_palette)
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = OnboardingAdapter(pages)
        binding.pager.adapter = adapter
        TabLayoutMediator(binding.tabIndicator, binding.pager) { _, _ -> }.attach()

        binding.skip.setOnClickListener { finish() }
        binding.next.setOnClickListener {
            if (binding.pager.currentItem == pages.lastIndex) finish()
            else binding.pager.currentItem += 1
        }
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val isLast = position == pages.lastIndex
                binding.next.setText(if (isLast) R.string.get_started else R.string.continue_text)
            }
        })
    }

    private fun finish() {
        preferences.hasCompletedOnboarding = true
        findNavController().navigate(R.id.action_onboarding_to_home)
    }
}

data class OnboardingPage(
    val titleRes: Int,
    val descRes: Int,
    val iconRes: Int
)
