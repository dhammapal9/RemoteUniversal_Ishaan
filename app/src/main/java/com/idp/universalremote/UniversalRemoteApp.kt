package com.idp.universalremote

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.android.material.color.DynamicColors
import com.idp.universalremote.core.common.AppPreferences
import com.idp.universalremote.domain.repository.RemoteCommandRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class UniversalRemoteApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var remoteCommandRepository: RemoteCommandRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applyTheme()
        applyDynamicColors()
        registerForegroundReconnect()
        if (BuildConfig.DEBUG) enableStrictMode()
    }

    /**
     * Every time the app comes back to the foreground we re-check the TV
     * connection. Android TVs close idle TLS sockets aggressively (Sony ~60s,
     * Thomson ~30s, Samsung Tizen ~5min), so a session that "looked" connected
     * in [_state] before backgrounding is often already dead by the time the
     * user returns. The repository's autoReconnect() does a cheap socket
     * liveness check first and short-circuits when the socket is genuinely
     * still alive, so this is free in the common case.
     */
    private fun registerForegroundReconnect() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0

            override fun onActivityStarted(activity: Activity) {
                val wasBackgrounded = startedActivities == 0
                startedActivities++
                if (wasBackgrounded) {
                    appScope.launch {
                        runCatching { remoteCommandRepository.autoReconnect() }
                    }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities--
            }

            // Unused — required by the interface.
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun applyTheme() {
        val mode = preferences.themeMode
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun applyDynamicColors() {
        if (preferences.dynamicColorsEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build()
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.VERBOSE else android.util.Log.INFO)
            .build()
}
