package com.alonibh.tellodrone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alonibh.tellodrone.ui.DroneDashboard
import com.alonibh.tellodrone.ui.DroneViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                val viewModel: DroneViewModel = viewModel()
                DroneDashboard(viewModel.uiState.collectAsStateWithLifecycle().value, viewModel)
            }
        }
    }
}
