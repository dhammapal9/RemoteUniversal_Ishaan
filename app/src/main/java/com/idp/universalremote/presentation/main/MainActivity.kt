package com.idp.universalremote.presentation.main

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.idp.universalremote.R
import com.idp.universalremote.core.common.AppPreferences
import com.idp.universalremote.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var preferences: AppPreferences

    private val bottomNavDestinations = setOf(
        R.id.remoteFragment,
        R.id.appsFragment,
        R.id.mirroringFragment,
        R.id.castFragment,
        R.id.settingsFragment
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        splash.setKeepOnScreenCondition { !viewModel.isReady.value }

        applyEdgeToEdge()
        setupNavigation()
    }

    private fun applyEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { nav, navInsets ->
            val ni = navInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            nav.setPadding(0, 0, 0, ni.bottom)
            navInsets
        }
    }

    private fun setupNavigation() {
        val host = supportFragmentManager.findFragmentById(R.id.navHost) as NavHostFragment
        val navController: NavController = host.navController
        binding.bottomNav.setupWithNavController(navController)

        val graph = navController.navInflater.inflate(R.navigation.nav_graph)
        graph.setStartDestination(
            when {
                !preferences.hasCompletedOnboarding -> R.id.onboardingFragment
                !preferences.hasSeenWifiPrompt && !preferences.wifiSetupSkipped ->
                    R.id.wifiPromptFragment
                else -> R.id.homeFragment
            }
        )
        navController.graph = graph
        preferences.hasSeenWifiPrompt = true

        navController.addOnDestinationChangedListener { _, dest, _ ->
            val show = dest.id in bottomNavDestinations
            binding.bottomNav.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.markReady()
            }
        }
    }
}
