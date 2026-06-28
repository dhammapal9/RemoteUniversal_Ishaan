package com.idp.universalremote.core.common

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDING_DONE, value) }

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = prefs.edit { putInt(KEY_THEME_MODE, value) }

    var amoledEnabled: Boolean
        get() = prefs.getBoolean(KEY_AMOLED, false)
        set(value) = prefs.edit { putBoolean(KEY_AMOLED, value) }

    var dynamicColorsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC, true)
        set(value) = prefs.edit { putBoolean(KEY_DYNAMIC, value) }

    var hapticEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, true)
        set(value) = prefs.edit { putBoolean(KEY_HAPTIC, value) }

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, false)
        set(value) = prefs.edit { putBoolean(KEY_SOUND, value) }

    var animationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANIMATIONS, true)
        set(value) = prefs.edit { putBoolean(KEY_ANIMATIONS, value) }

    var isPremium: Boolean
        get() = prefs.getBoolean(KEY_PREMIUM, false)
        set(value) = prefs.edit { putBoolean(KEY_PREMIUM, value) }

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "system") ?: "system"
        set(value) = prefs.edit { putString(KEY_LANGUAGE, value) }

    var lastConnectedDeviceId: String?
        get() = prefs.getString(KEY_LAST_DEVICE, null)
        set(value) = prefs.edit { putString(KEY_LAST_DEVICE, value) }

    var accentColor: Int
        get() = prefs.getInt(KEY_ACCENT, 0)
        set(value) = prefs.edit { putInt(KEY_ACCENT, value) }

    var wifiSetupSkipped: Boolean
        get() = prefs.getBoolean(KEY_WIFI_SKIPPED, false)
        set(value) = prefs.edit { putBoolean(KEY_WIFI_SKIPPED, value) }

    var hasSeenWifiPrompt: Boolean
        get() = prefs.getBoolean(KEY_WIFI_PROMPT_SEEN, false)
        set(value) = prefs.edit { putBoolean(KEY_WIFI_PROMPT_SEEN, value) }

    companion object {
        private const val PREFS_NAME = "universal_remote_prefs"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_AMOLED = "amoled_enabled"
        private const val KEY_DYNAMIC = "dynamic_colors"
        private const val KEY_HAPTIC = "haptic"
        private const val KEY_SOUND = "sound"
        private const val KEY_ANIMATIONS = "animations"
        private const val KEY_PREMIUM = "is_premium"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_LAST_DEVICE = "last_device_id"
        private const val KEY_ACCENT = "accent_color"
        private const val KEY_WIFI_SKIPPED = "wifi_setup_skipped"
        private const val KEY_WIFI_PROMPT_SEEN = "wifi_prompt_seen"
    }
}
