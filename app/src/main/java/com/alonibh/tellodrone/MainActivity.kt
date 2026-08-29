package com.alonibh.tellodrone

import android.os.Bundle
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alonibh.tellodrone.data.TelloPermissionPolicy
import com.alonibh.tellodrone.domain.NetworkSelectionState
import com.alonibh.tellodrone.ui.DroneDashboard
import com.alonibh.tellodrone.ui.DroneViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableEdgeToEdge()
        val controller = (application as TelloApplication).droneController
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = TelloGreen,
                    onPrimary = TelloInk,
                    secondary = TelloGreen,
                    background = TelloInk,
                    surface = TelloPanel,
                    surfaceVariant = TelloPanelRaised,
                    error = TelloRed,
                ),
            ) {
                val viewModel: DroneViewModel = viewModel(factory = DroneViewModel.Factory(controller))
                val state = viewModel.uiState.collectAsStateWithLifecycle().value
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    viewModel.onNetworkPermissionsResult(
                        TelloPermissionPolicy.missingPermissions(this@MainActivity).isEmpty(),
                    )
                }
                val exportTraceLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/zip"),
                ) { uri ->
                    if (uri != null) {
                        viewModel.exportVisionTrace(uri.toString())
                    }
                }
                LaunchedEffect(state.networkSelection) {
                    if (state.networkSelection == NetworkSelectionState.PermissionRequired) {
                        permissionLauncher.launch(TelloPermissionPolicy.requiredRuntimePermissions())
                    }
                }
                DroneDashboard(
                    state = state,
                    viewModel = viewModel,
                    onExportTrace = {
                        val timestamp = java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        exportTraceLauncher.launch("tello-follow-trace-$timestamp.zip")
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val decorView = window.decorView
        fun hideSystemBars() = WindowCompat.getInsetsController(window, decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        hideSystemBars()
        // Reapply after the initial decor attachment without relying on an arbitrary delay.
        decorView.post { if (!isFinishing && !isDestroyed && hasWindowFocus()) hideSystemBars() }
    }

    override fun onPause() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
