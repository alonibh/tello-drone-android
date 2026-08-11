package com.alonibh.tellodrone

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
                LaunchedEffect(state.networkSelection) {
                    if (state.networkSelection == NetworkSelectionState.PermissionRequired) {
                        permissionLauncher.launch(TelloPermissionPolicy.requiredRuntimePermissions())
                    }
                }
                DroneDashboard(state, viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
}
