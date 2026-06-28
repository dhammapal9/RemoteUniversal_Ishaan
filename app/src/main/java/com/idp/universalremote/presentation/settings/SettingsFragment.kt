package com.idp.universalremote.presentation.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.idp.universalremote.R
import com.idp.universalremote.core.common.AppPreferences
import com.idp.universalremote.core.common.toast
import com.idp.universalremote.core.common.viewBinding
import com.idp.universalremote.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val binding by viewBinding(FragmentSettingsBinding::bind)

    @Inject lateinit var preferences: AppPreferences

    private val languageCodes = listOf(
        "system", "en", "hi", "es", "fr", "de", "it", "ar", "ru", "tr",
        "ja", "ko", "zh", "pt", "in", "vi", "th", "pl", "nl", "bn", "ta", "te", "mr"
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshCurrentValues()

        binding.premiumCard.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_premium)
        }
        binding.themeRow.setOnClickListener { showThemeDialog() }
        binding.languageRow.setOnClickListener { showLanguageDialog() }
        binding.reconnectRow.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_search)
        }
        binding.restoreRow.setOnClickListener {
            toast(getString(R.string.restore_purchases))
        }
        binding.contactRow.setOnClickListener {
            sendEmail("support@universalremote.app", "Universal Remote feedback")
        }
        binding.featureRow.setOnClickListener {
            sendEmail("features@universalremote.app", "Feature request")
        }
        binding.shareRow.setOnClickListener { shareApp() }
        binding.rateRow.setOnClickListener { rateApp() }
        binding.privacyRow.setOnClickListener { openPrivacyPolicy() }

        binding.hapticSwitch.isChecked = preferences.hapticEnabled
        binding.hapticSwitch.setOnCheckedChangeListener { _, value ->
            preferences.hapticEnabled = value
        }
        binding.animationsSwitch.isChecked = preferences.animationsEnabled
        binding.animationsSwitch.setOnCheckedChangeListener { _, value ->
            preferences.animationsEnabled = value
        }
        binding.soundSwitch.isChecked = preferences.soundEnabled
        binding.soundSwitch.setOnCheckedChangeListener { _, value ->
            preferences.soundEnabled = value
        }

        binding.versionLabel.text = buildString {
            append("v")
            append(runCatching {
                requireContext().packageManager
                    .getPackageInfo(requireContext().packageName, 0).versionName
            }.getOrDefault("1.0.0"))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCurrentValues()
    }

    private fun refreshCurrentValues() {
        binding.themeValue.text = themeLabel()
        binding.languageValue.text = languageLabel()
    }

    private fun themeLabel(): String = when {
        preferences.themeMode == AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.theme_light)
        preferences.themeMode == AppCompatDelegate.MODE_NIGHT_YES && preferences.amoledEnabled ->
            getString(R.string.theme_amoled)
        preferences.themeMode == AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.theme_dark)
        else -> getString(R.string.theme_system)
    }

    private fun languageLabel(): String {
        val code = preferences.language
        if (code == "system") return getString(R.string.theme_system)
        val idx = languageCodes.indexOf(code).coerceAtLeast(0)
        return languageNames()[idx]
    }

    private fun languageNames(): List<String> = listOf(
        getString(R.string.theme_system),
        getString(R.string.lang_english),
        getString(R.string.lang_hindi),
        getString(R.string.lang_spanish),
        getString(R.string.lang_french),
        getString(R.string.lang_german),
        getString(R.string.lang_italian),
        getString(R.string.lang_arabic),
        getString(R.string.lang_russian),
        getString(R.string.lang_turkish),
        getString(R.string.lang_japanese),
        getString(R.string.lang_korean),
        getString(R.string.lang_chinese),
        getString(R.string.lang_portuguese),
        getString(R.string.lang_indonesian),
        getString(R.string.lang_vietnamese),
        getString(R.string.lang_thai),
        getString(R.string.lang_polish),
        getString(R.string.lang_dutch),
        getString(R.string.lang_bengali),
        getString(R.string.lang_tamil),
        getString(R.string.lang_telugu),
        getString(R.string.lang_marathi)
    )

    private fun showThemeDialog() {
        val options = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
            getString(R.string.theme_amoled)
        )
        val current = when {
            preferences.themeMode == AppCompatDelegate.MODE_NIGHT_NO -> 1
            preferences.themeMode == AppCompatDelegate.MODE_NIGHT_YES && preferences.amoledEnabled -> 3
            preferences.themeMode == AppCompatDelegate.MODE_NIGHT_YES -> 2
            else -> 0
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(options, current) { dialog, which ->
                when (which) {
                    0 -> {
                        preferences.themeMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        preferences.amoledEnabled = false
                    }
                    1 -> {
                        preferences.themeMode = AppCompatDelegate.MODE_NIGHT_NO
                        preferences.amoledEnabled = false
                    }
                    2 -> {
                        preferences.themeMode = AppCompatDelegate.MODE_NIGHT_YES
                        preferences.amoledEnabled = false
                    }
                    3 -> {
                        preferences.themeMode = AppCompatDelegate.MODE_NIGHT_YES
                        preferences.amoledEnabled = true
                    }
                }
                AppCompatDelegate.setDefaultNightMode(preferences.themeMode)
                dialog.dismiss()
                requireActivity().recreate()
            }
            .show()
    }

    private fun showLanguageDialog() {
        val names = languageNames()
        val current = languageCodes.indexOf(preferences.language).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_languages)
            .setSingleChoiceItems(names.toTypedArray(), current) { dialog, which ->
                preferences.language = languageCodes[which]
                dialog.dismiss()
                requireActivity().recreate()
            }
            .show()
    }

    private fun sendEmail(address: String, subject: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$address")
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        runCatching { startActivity(intent) }
            .onFailure { toast(getString(R.string.connection_failed)) }
    }

    private fun shareApp() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "Check out Universal Remote: " +
                    "https://play.google.com/store/apps/details?id=${requireContext().packageName}"
            )
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_app)))
    }

    private fun rateApp() {
        val uri = Uri.parse("market://details?id=${requireContext().packageName}")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .recoverCatching {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=${requireContext().packageName}")
                    )
                )
            }
    }

    private fun openPrivacyPolicy() {
        val uri = Uri.parse("https://universalremote.app/privacy")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
}
