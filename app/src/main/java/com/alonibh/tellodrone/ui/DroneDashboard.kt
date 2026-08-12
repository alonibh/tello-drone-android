@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.alonibh.tellodrone.ui

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.provider.Settings
import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alonibh.tellodrone.data.MockDroneController
import com.alonibh.tellodrone.TelloGreen
import com.alonibh.tellodrone.TelloGreenDark
import com.alonibh.tellodrone.TelloInk
import com.alonibh.tellodrone.TelloLine
import com.alonibh.tellodrone.TelloPanel
import com.alonibh.tellodrone.TelloPanelRaised
import com.alonibh.tellodrone.TelloRed
import com.alonibh.tellodrone.TelloTextMuted
import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.DetectorBackend
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import com.alonibh.tellodrone.domain.DetectorBenchmarkState
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.NetworkSelectionState
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import com.alonibh.tellodrone.vision.formatReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val panelShape = RoundedCornerShape(12.dp)
private val compactCardPadding = 10.dp
private val standardCardPadding = 12.dp
private val actionHeight = 48.dp
private val compactActionHeight = 44.dp
private val sectionSpacing = 10.dp
private val previewFpsBadgeWidth = 76.dp
private val previewFpsBadgeHeight = 38.dp

@Composable
fun DroneDashboard(state: DroneSessionState, viewModel: DroneViewModel, modifier: Modifier = Modifier) {
    var destination by remember { mutableStateOf("Dashboard") }
    BoxWithConstraints(
        modifier.fillMaxSize().background(TelloInk)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(12.dp),
    ) {
        if (isPortraitOperationalWindow(maxWidth, maxHeight)) {
            PortraitSafetyFallback(state, viewModel)
        } else {
            when (windowLayout(maxWidth, maxHeight)) {
                WindowLayout.Expanded -> ExpandedDashboard(state, viewModel, destination) { destination = it }
                WindowLayout.Medium -> MediumDashboard(state, viewModel, destination) { destination = it }
                WindowLayout.CompactHeight -> LandscapeDashboard(state, viewModel, destination, { destination = it })
                WindowLayout.Compact -> CompactDashboard(state, viewModel, destination) { destination = it }
            }
        }
    }
}

internal fun isPortraitOperationalWindow(width: Dp, height: Dp): Boolean = height > width

/** Material window-size-class breakpoints, evaluated from the current app window. */
internal fun windowLayout(width: Dp, height: Dp): WindowLayout = when {
    height < 480.dp -> WindowLayout.CompactHeight
    width >= 840.dp -> WindowLayout.Expanded
    width >= 600.dp -> WindowLayout.Medium
    else -> WindowLayout.Compact
}

internal enum class WindowLayout { Compact, CompactHeight, Medium, Expanded }

internal enum class CompactHeightContent { Dashboard, Controls, Tracking, Media, Status }

internal fun compactHeightContent(destination: String): CompactHeightContent = when (destination) {
    "Controls" -> CompactHeightContent.Controls
    "Tracking" -> CompactHeightContent.Tracking
    "Media" -> CompactHeightContent.Media
    "Status" -> CompactHeightContent.Status
    else -> CompactHeightContent.Dashboard
}

@Composable
private fun PortraitSafetyFallback(state: DroneSessionState, vm: DroneViewModel) = Surface(
    color = TelloInk,
    modifier = Modifier.fillMaxSize(),
) {
    val activeFlight = state.flight in setOf(FlightState.TakingOff, FlightState.Flying, FlightState.Landing, FlightState.Unknown)
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Rotate device to landscape", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            if (activeFlight) "Landscape controls are unavailable in this window. Flight safety controls remain available below."
            else "The operational dashboard is landscape-first.",
            color = TelloTextMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
        )
        if (activeFlight) {
            HoverAction(state, vm, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            LandAction(state, vm, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            EmergencyHoldButton(state.canEmergency(), vm::emergencyMotorKill, Modifier.fillMaxWidth().height(128.dp))
        }
    }
}

@Composable
private fun ExpandedDashboard(state: DroneSessionState, vm: DroneViewModel, destination: String, onDestination: (String) -> Unit) {
    Column(Modifier.testTag("layout_expanded"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(state, vm, expanded = true)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NavigationRail(state, vm, destination, onDestination, Modifier.width(160.dp).fillMaxHeight())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (destination == "Dashboard") {
                    Row(Modifier.weight(1.63f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        VideoPanel(state, vm, Modifier.weight(1f).fillMaxHeight())
                        TabletFlightControls(state, vm, Modifier.widthIn(min = 250.dp, max = 280.dp).fillMaxHeight())
                    }
                    BottomControls(state, vm, modifier = Modifier.weight(1f), tablet = true)
                } else if (destination == "Tracking") TrackingDestination(state, vm, Modifier.fillMaxSize())
                else if (destination == "Status") StatusPanel(state, Modifier.fillMaxSize())
                else DestinationPlaceholder(destination, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun CompactDashboard(state: DroneSessionState, vm: DroneViewModel, destination: String, onDestination: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.testTag("layout_compact"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item { Header(state, vm, expanded = false) }
        item { CompactNav(destination, onDestination) }
        if (destination == "Dashboard") {
            item { VideoPanel(state, vm, Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 460.dp)) }
            item { CriticalFlightControls(state, vm) }
            item { CompactFutureControlsNotice() }
            item { BottomControls(state, vm) }
            item { StatusPanel(state) }
            item { EmergencyHoldButton(state.canEmergency(), vm::emergencyMotorKill, Modifier.fillMaxWidth()) }
        } else item {
            if (destination == "Tracking") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    VideoPanel(state, vm, Modifier.fillMaxWidth().height(280.dp))
                    TrackingControls(state, vm)
                }
            } else if (destination == "Status") StatusPanel(state, Modifier.fillMaxWidth())
            else DestinationPlaceholder(destination, Modifier.fillMaxWidth().height(280.dp))
        }
    }
}

@Composable
private fun MediumDashboard(state: DroneSessionState, vm: DroneViewModel, destination: String, onDestination: (String) -> Unit) {
    Column(Modifier.testTag("layout_medium"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header(state, vm, expanded = false)
        CompactNav(destination, onDestination)
        if (destination == "Dashboard") {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VideoPanel(state, vm, Modifier.weight(1.25f).fillMaxHeight())
                LazyColumn(Modifier.weight(.75f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { CriticalFlightControls(state, vm) }
                    item { EmergencyHoldButton(state.canEmergency(), vm::emergencyMotorKill, Modifier.fillMaxWidth()) }
                    item { TrackingControls(state, vm) }
                    item { StatusPanel(state) }
                }
            }
            BottomControls(state, vm)
        } else if (destination == "Tracking") TrackingDestination(state, vm, Modifier.weight(1f).fillMaxWidth())
        else if (destination == "Status") StatusPanel(state, Modifier.weight(1f).fillMaxWidth())
        else DestinationPlaceholder(destination, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun LandscapeDashboard(
    state: DroneSessionState,
    vm: DroneViewModel,
    destination: String,
    onDestination: (String) -> Unit,
    showApi28WifiAction: Boolean = needsCompactWifiAction(state),
) {
    Column(Modifier.testTag("layout_compact_height"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactHeader(state, vm, showApi28WifiAction)
        CompactNav(destination, onDestination, Modifier.testTag("compact_height_navigation"))
        when (compactHeightContent(destination)) {
            CompactHeightContent.Dashboard -> {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoPanel(state, vm, Modifier.weight(1.65f).fillMaxHeight().testTag("compact_height_dashboard_video"))
                    Column(Modifier.weight(.7f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LandscapeFlightControls(state, vm)
                        EmergencyHoldButton(state.canEmergency(), vm::emergencyMotorKill, Modifier.fillMaxWidth().weight(1f), compact = true)
                    }
                }
            }
            CompactHeightContent.Controls -> CompactHeightControlsDestination(state, vm, Modifier.weight(1f).fillMaxWidth())
            CompactHeightContent.Tracking -> CompactHeightTrackingDestination(state, vm, Modifier.weight(1f).fillMaxWidth())
            CompactHeightContent.Status -> CompactHeightScrollableDestination(Modifier.weight(1f).fillMaxWidth()) { StatusPanel(state) }
            CompactHeightContent.Media -> CompactHeightScrollableDestination(Modifier.weight(1f).fillMaxWidth()) { MediaControls() }
        }
    }
}

@Composable
private fun Header(state: DroneSessionState, vm: DroneViewModel, expanded: Boolean) {
    Surface(shape = panelShape, color = TelloPanelRaised, modifier = Modifier.fillMaxWidth()) {
        if (expanded) Row(Modifier.heightIn(min = 96.dp).padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Brand(state, Modifier.width(190.dp)); HeaderMetric("BATTERY", telemetryValue(state) { it.batteryPercent?.let { value -> "$value%" } })
            HeaderMetric("HEIGHT", telemetryValue(state) { it.heightMeters?.let { value -> "%.1f m".format(value) } }); HeaderMetric("SPEED", telemetryValue(state) { it.speedMetersPerSecond?.let { value -> "%.1f m/s".format(value) } })
            HeaderMetric("FLIGHT TIME", telemetryValue(state) { it.flightTimeSeconds?.let(::formatTime) }); Spacer(Modifier.weight(1f)); HeaderActions(state, vm)
        } else Column(Modifier.padding(standardCardPadding), verticalArrangement = Arrangement.spacedBy(sectionSpacing)) {
            Brand(state); FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HeaderMetric("BATTERY", telemetryValue(state) { it.batteryPercent?.let { value -> "$value%" } }); HeaderMetric("HEIGHT", telemetryValue(state) { it.heightMeters?.let { value -> "%.1f m".format(value) } }); HeaderMetric("SPEED", telemetryValue(state) { it.speedMetersPerSecond?.let { value -> "%.1f m/s".format(value) } })
            }
            ControllerModeSelector(state, vm)
            ConnectionButton(state, vm)
        }
    }
}

@Composable
private fun CompactHeader(state: DroneSessionState, vm: DroneViewModel, showApi28WifiAction: Boolean = needsCompactWifiAction(state)) = Surface(
    shape = panelShape,
    color = TelloPanelRaised,
    modifier = Modifier.fillMaxWidth(),
) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Brand(state, Modifier.weight(1f))
        HeaderMetric("BATTERY", telemetryValue(state) { it.batteryPercent?.let { value -> "$value%" } })
        CompactConnectionActions(state, vm, showApi28WifiAction)
    }
}

private fun needsCompactWifiAction(state: DroneSessionState) = Build.VERSION.SDK_INT == 28 &&
    state.controllerMode == ControllerMode.Real && state.connection != DroneConnectionState.Connected

@Composable private fun CompactConnectionActions(state: DroneSessionState, vm: DroneViewModel, showApi28WifiAction: Boolean) = Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(2.dp),
) {
    ConnectionPrimaryButton(state, vm)
    if (showApi28WifiAction) {
        val context = LocalContext.current
        IconButton(
            onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            modifier = Modifier.testTag("open_wifi_settings_compact"),
        ) { Icon(Icons.Default.Wifi, "Open Wi-Fi settings", tint = TelloGreen, modifier = Modifier.size(20.dp)) }
    }
}

@Composable private fun HeaderActions(state: DroneSessionState, vm: DroneViewModel) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ControllerModeSelector(state, vm)
        ConnectionPrimaryButton(state, vm)
        if (Build.VERSION.SDK_INT == 28 && state.controllerMode == ControllerMode.Real && state.connection != DroneConnectionState.Connected) WifiSettingsButton()
        OutlineAction("SETTINGS", Icons.Default.Settings, false, {}, compact = true)
    }
}

@Composable private fun ConnectionPrimaryButton(state: DroneSessionState, vm: DroneViewModel) {
    val active = state.connection == DroneConnectionState.Connected
    val transition = state.connection in setOf(DroneConnectionState.Connecting, DroneConnectionState.AwaitingPermission)
    val unsafeDisconnect = active && state.flight in setOf(FlightState.TakingOff, FlightState.Flying, FlightState.Landing, FlightState.Unknown)
    OutlinedButton(onClick = if (active) vm::disconnect else vm::connect, enabled = !transition && !unsafeDisconnect) {
        Text(when { transition -> "CONNECTING..."; active -> "DISCONNECT"; state.controllerMode == ControllerMode.Mock -> "CONNECT MOCK"; else -> "CONNECT TELLO" }, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable private fun WifiSettingsButton() {
    val context = LocalContext.current
    OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, modifier = Modifier.testTag("open_wifi_settings")) { Text("WI-FI SETTINGS", fontSize = 10.sp, maxLines = 1) }
}

@Composable private fun ConnectionButton(state: DroneSessionState, vm: DroneViewModel) {
    val context = LocalContext.current
    val active = state.connection == DroneConnectionState.Connected
    val transition = state.connection in setOf(DroneConnectionState.Connecting, DroneConnectionState.AwaitingPermission)
    val unsafeDisconnect = active && state.flight in setOf(FlightState.TakingOff, FlightState.Flying, FlightState.Landing, FlightState.Unknown)
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    OutlinedButton(
        onClick = if (active) vm::disconnect else vm::connect,
        enabled = !transition && !unsafeDisconnect,
    ) {
        Text(
            when {
                transition -> "CONNECTING…"
                active -> "DISCONNECT"
                state.controllerMode == ControllerMode.Mock -> "CONNECT MOCK"
                else -> "CONNECT TELLO"
            },
            fontSize = 11.sp,
        )
    }
    if (Build.VERSION.SDK_INT == 28 && state.controllerMode == ControllerMode.Real &&
        state.connection != DroneConnectionState.Connected
    ) {
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            modifier = Modifier.testTag("open_wifi_settings"),
        ) { Text("OPEN WI-FI SETTINGS", fontSize = 10.sp) }
        state.lastMessage?.let { Text(it, color = TelloTextMuted, fontSize = 10.sp, textAlign = TextAlign.End) }
    }
    }
}

@Composable private fun ControllerModeSelector(state: DroneSessionState, vm: DroneViewModel) {
    val enabled = state.connection in setOf(DroneConnectionState.Disconnected, DroneConnectionState.Error)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlineAction("REAL", Icons.Default.MyLocation, enabled, { vm.setControllerMode(ControllerMode.Real) }, active = state.controllerMode == ControllerMode.Real)
        OutlineAction("MOCK", Icons.Default.Settings, enabled, { vm.setControllerMode(ControllerMode.Mock) }, active = state.controllerMode == ControllerMode.Mock)
    }
}

@Composable private fun Brand(state: DroneSessionState, modifier: Modifier = Modifier) = Column(modifier) {
    Text("TELLO DRONE", fontWeight = FontWeight.Bold, fontSize = 20.sp)
    Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(9.dp).clip(CircleShape).background(connectionColor(state.connection))); Spacer(Modifier.width(7.dp)); Text(connectionLabel(state.connection).uppercase(), color = connectionColor(state.connection), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}
@Composable private fun HeaderMetric(label: String, value: String) = Column(Modifier.padding(horizontal = 10.dp)) { Text(label, fontSize = 11.sp, color = TelloTextMuted); Text(value, fontSize = 19.sp, fontWeight = FontWeight.Medium) }

@Composable private fun NavigationRail(state: DroneSessionState, vm: DroneViewModel, destination: String, onDestination: (String) -> Unit, modifier: Modifier = Modifier) = Surface(modifier, shape = panelShape, color = TelloPanel) {
    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf("Dashboard", "Controls", "Tracking", "Media", "Status").forEach { label ->
            val selected = label == destination
            Row(Modifier.fillMaxWidth().height(52.dp).then(if (selected) Modifier.background(TelloGreenDark.copy(alpha = .5f)) else Modifier).clickable { onDestination(label) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (selected) Icons.Default.MyLocation else Icons.Default.Settings, null, tint = if (selected) TelloGreen else TelloTextMuted); Spacer(Modifier.width(14.dp)); Text(label, color = if (selected) TelloGreen else Color.White) }
        }
        Spacer(Modifier.weight(1f)); EmergencyHoldButton(state.canEmergency(), vm::emergencyMotorKill, Modifier.padding(12.dp).fillMaxWidth().height(156.dp), compact = true)
    }
}
@Composable private fun CompactNav(destination: String, onDestination: (String) -> Unit, modifier: Modifier = Modifier) = Surface(color = TelloPanel, shape = panelShape, modifier = modifier.fillMaxWidth()) { Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) { listOf("Dashboard", "Controls", "Tracking", "Media", "Status").forEach { label -> Text(label, modifier = Modifier.weight(1f).clickable { onDestination(label) }.padding(vertical = 5.dp), fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, color = if (label == destination) TelloGreen else TelloTextMuted) } } }
@Composable private fun DestinationPlaceholder(destination: String, modifier: Modifier = Modifier) = Surface(modifier, shape = panelShape, color = TelloPanel) { Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Settings, null, tint = TelloGreen, modifier = Modifier.size(38.dp)); Spacer(Modifier.height(12.dp)); Text(destination, fontWeight = FontWeight.Bold, fontSize = 22.sp); Text("This Phase 1 destination is a lightweight placeholder.", color = TelloTextMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) } }

@Composable
private fun VideoPanel(state: DroneSessionState, vm: DroneViewModel, modifier: Modifier = Modifier) = Surface(modifier.clip(panelShape), color = Color(0xFF252A2C)) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF42403B), Color(0xFF171B1D))))) {
        if (state.controllerMode == ControllerMode.Real) {
            AndroidView(
                factory = { context -> TelloVideoSurfaceView(context, vm) },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.dispose() },
            )
        } else {
            Canvas(Modifier.fillMaxSize()) { for (x in 0..size.width.toInt() step 36) drawLine(Color.White.copy(alpha = .025f), Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height)); for (y in 0..size.height.toInt() step 36) drawLine(Color.White.copy(alpha = .025f), Offset(0f, y.toFloat()), Offset(size.width, y.toFloat())) }
        }
        Row(Modifier.align(Alignment.TopStart).padding(14.dp).clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = .64f)).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (state.video.availability == VideoAvailability.Mock) "DEMO" else "VIDEO", color = Color.White, fontWeight = FontWeight.Bold); Text(if (state.video.availability == VideoAvailability.Mock) "  MOCK PREVIEW" else "  LIVE PREVIEW", color = TelloTextMuted, fontSize = 12.sp) }
        Text(
            previewFpsBadgeText(state.video.measuredFps),
            modifier = Modifier.align(Alignment.TopEnd).padding(14.dp).clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = .82f)).width(previewFpsBadgeWidth)
                .height(previewFpsBadgeHeight).padding(horizontal = 8.dp, vertical = 9.dp),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        state.target?.let { target ->
            val mapped = VideoOverlayCoordinateMapper.mapFillBounds(target.boundingBox, maxWidth.value, maxHeight.value)
            if (mapped != null) Column(Modifier.offset(mapped.left.dp, mapped.top.dp).size((mapped.right - mapped.left).dp, (mapped.bottom - mapped.top).dp).border(2.dp, TelloGreen, RoundedCornerShape(3.dp))) {
                Text("TARGET SELECTED", color = TelloInk, modifier = Modifier.background(TelloGreen).padding(horizontal = 5.dp, vertical = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        state.personDetections.filterNot { detection -> state.isCurrentTargetDetection(detection) }.forEach { detection ->
            val mapped = VideoOverlayCoordinateMapper.mapFillBounds(
                detection.boundingBox,
                maxWidth.value,
                maxHeight.value,
            ) ?: return@forEach
            val selectable = state.controllerMode == ControllerMode.Mock ||
                (state.connection == DroneConnectionState.Connected &&
                    state.video.availability == VideoAvailability.Streaming &&
                    state.video.personDetectionState == PersonDetectionState.Detecting &&
                    state.video.processedDetectorFrameSequence == detection.frameSequence &&
                    state.video.processedDetectorSourceTimestampNanos == detection.sourceTimestampNanos)
            Column(
                Modifier.offset(mapped.left.dp, mapped.top.dp)
                    .size((mapped.right - mapped.left).dp, (mapped.bottom - mapped.top).dp)
                    .border(2.dp, Color(0xFFFFC857), RoundedCornerShape(3.dp))
                    .clickable(enabled = selectable) { vm.selectTarget(detection) },
            ) {
                Text(
                    "PERSON ${(detection.confidence * 100f).roundToInt()}%",
                    color = TelloInk,
                    modifier = Modifier.background(Color(0xFFFFC857)).padding(horizontal = 5.dp, vertical = 2.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text("H: ${telemetryValue(state) { it.heightMeters?.let { value -> "%.1f m".format(value) } }}", Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .82f)).padding(horizontal = 12.dp, vertical = 9.dp), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        val centerMessage = when {
            state.video.availability == VideoAvailability.Mock -> "Mock preview • no physical video"
            state.video.availability == VideoAvailability.Error -> "VIDEO UNAVAILABLE\n${state.video.errorReason ?: "Video pipeline error"}"
            state.connection in setOf(DroneConnectionState.Connecting, DroneConnectionState.Connected) &&
                state.video.availability == VideoAvailability.Unavailable -> "STARTING VIDEO…"
            state.video.availability == VideoAvailability.Streaming -> null
            else -> "NO VIDEO / WAITING"
        }
        centerMessage?.let { Text(it, Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .52f)).padding(10.dp), color = if (state.video.availability == VideoAvailability.Error) TelloRed else TelloTextMuted, fontSize = 12.sp, textAlign = TextAlign.Center) }
    }
}

private class TelloVideoSurfaceView(
    context: Context,
    private val viewModel: DroneViewModel,
) : SurfaceView(context), SurfaceHolder.Callback {
    private var attached = false

    init {
        setZOrderOnTop(false)
        holder.setFixedSize(TELLO_VIDEO_WIDTH, TELLO_VIDEO_HEIGHT)
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attached = true
        viewModel.attachVideoSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (attached) viewModel.detachVideoSurface(holder.surface)
        attached = false
    }

    fun dispose() {
        if (attached) viewModel.detachVideoSurface(holder.surface)
        attached = false
        holder.removeCallback(this)
    }

    companion object {
        private const val TELLO_VIDEO_WIDTH = 960
        private const val TELLO_VIDEO_HEIGHT = 720
    }
}

@Composable private fun ControlCard(title: String, compact: Boolean = false, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = TelloPanel), shape = panelShape) { Column(Modifier.padding(if (compact) compactCardPadding else standardCardPadding), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else sectionSpacing)) { Text(title, color = TelloTextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium); content() } }

@Composable private fun TabletFlightControls(state: DroneSessionState, vm: DroneViewModel, modifier: Modifier) = ControlCard("FLIGHT CONTROLS", modifier = modifier) {
    Text(if (state.flight == FlightState.Grounded && state.telemetry.isFresh) "READY TO FLY" else connectionLabel(state.connection).uppercase(), color = if (state.flight == FlightState.Grounded && state.telemetry.isFresh) TelloGreen else TelloTextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    TakeoffAction(state, vm, Modifier.fillMaxWidth())
    LandAction(state, vm, Modifier.fillMaxWidth())
    if (state.hoverActive) HoverActiveStatus()
    FlightActionHint(state)
}

@Composable private fun CriticalFlightControls(state: DroneSessionState, vm: DroneViewModel) = ControlCard("FLIGHT CONTROLS") {
    AdaptiveActionPair(
        { TakeoffAction(state, vm, Modifier.fillMaxWidth()) },
        { LandAction(state, vm, Modifier.fillMaxWidth()) },
    )
    HoverAction(state, vm, Modifier.fillMaxWidth().testTag("stop_hover"))
    FlightActionHint(state)
}

@Composable private fun FlightActionHint(state: DroneSessionState) = Text(when {
    state.flight == FlightState.TakingOff -> "Takeoff command accepted; waiting for airborne telemetry."
    state.flight == FlightState.Landing -> "Landing command accepted; waiting for grounded telemetry."
    state.connection != DroneConnectionState.Connected -> "Connect to TELLO to enable flight actions."
    !state.telemetry.isFresh -> "Fresh telemetry is required before takeoff or manual control."
    state.flight == FlightState.Grounded -> "Takeoff is available when telemetry is fresh."
    state.flight == FlightState.Flying -> "Land and STOP/HOVER are available while flying."
    else -> "Aircraft state is uncertain; land before normal flight commands."
}, color = TelloTextMuted, fontSize = 11.sp)

@Composable private fun FlightReadinessHint(state: DroneSessionState) = Surface(color = TelloPanel, shape = panelShape, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(standardCardPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("FLIGHT STATUS", color = TelloTextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium); Text(connectionLabel(state.connection), color = connectionColor(state.connection), fontWeight = FontWeight.SemiBold); FlightActionHint(state) } }
@Composable private fun HoverActiveStatus() = Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = TelloGreen, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("HOVER ACTIVE", color = TelloGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }

@Composable private fun LandscapeFlightControls(state: DroneSessionState, vm: DroneViewModel) = ControlCard("FLIGHT", compact = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TakeoffAction(state, vm, Modifier.weight(1f), compact = true)
        LandAction(state, vm, Modifier.weight(1f), compact = true)
    }
    HoverAction(state, vm, Modifier.fillMaxWidth().testTag("stop_hover"), compact = true)
}

@Composable
private fun TakeoffAction(state: DroneSessionState, vm: DroneViewModel, modifier: Modifier, compact: Boolean = false) {
    val gate = remember { TakeoffConfirmationGate() }
    var dialogVisible by remember { mutableStateOf(false) }
    val eligible = state.isTakeoffEligible()
    LaunchedEffect(state.controllerMode, eligible, state.flight) {
        if (!gate.dismissIfIneligible(state)) dialogVisible = false
    }
    ActionButton(
        if (state.flight == FlightState.TakingOff) "TAKING OFF…" else "TAKE OFF",
        Icons.Default.ArrowUpward,
        eligible,
        onClick = {
            if (state.controllerMode == ControllerMode.Real) dialogVisible = gate.request(state)
            else vm.takeOff()
        },
        modifier = modifier,
        active = state.flight == FlightState.TakingOff,
        compact = compact,
    )
    if (dialogVisible && state.controllerMode == ControllerMode.Real && eligible) {
        AlertDialog(
            onDismissRequest = { gate.cancel(); dialogVisible = false },
            title = { Text("Confirm takeoff") },
            text = { Text("Make sure the area above and around the drone is clear. The drone will take off and hover.") },
            dismissButton = { TextButton(onClick = { gate.cancel(); dialogVisible = false }) { Text("CANCEL") } },
            confirmButton = {
                TextButton(onClick = {
                    val confirmed = gate.confirm(state) { vm.takeOff() }
                    dialogVisible = false
                    if (!confirmed) gate.cancel()
                }) { Text("TAKE OFF") }
            },
        )
    }
}
@Composable private fun LandAction(state: DroneSessionState, vm: DroneViewModel, modifier: Modifier, compact: Boolean = false) {
    val landing = state.flight == FlightState.Landing
    if (landing) ActionButton("LANDING…", Icons.Default.ArrowDownward, false, {}, modifier, active = true, compact = compact)
    else OutlineAction("LAND", Icons.Default.ArrowDownward, state.connection == DroneConnectionState.Connected && state.flight in setOf(FlightState.Flying, FlightState.Unknown), vm::land, modifier, compact = compact)
}
@Composable private fun HoverAction(state: DroneSessionState, vm: DroneViewModel, modifier: Modifier, compact: Boolean = false) {
    val enabled = state.connection == DroneConnectionState.Connected && state.flight == FlightState.Flying
    if (state.hoverActive) ActionButton("HOVER ACTIVE", Icons.Default.CheckCircle, enabled, vm::stopAndHover, modifier, active = true, compact = compact)
    else OutlineAction("STOP / HOVER", Icons.Default.PauseCircle, enabled, vm::stopAndHover, modifier, compact = compact)
}

@Composable private fun TrackingControls(state: DroneSessionState, vm: DroneViewModel) = ControlCard("PERSON DETECTION / DRY RUN • PHASE 4F") {
    val canStart = (state.controllerMode == ControllerMode.Mock && state.connection == DroneConnectionState.Connected) ||
        (state.controllerMode == ControllerMode.Real &&
            state.connection == DroneConnectionState.Connected &&
            state.video.availability == VideoAvailability.Streaming &&
            state.video.analysisLatestSequence != null)
    AdaptiveActionPair(
        { OutlineAction("OFF", Icons.Default.Close, true, { vm.setTrackingMode(TrackingMode.Off) }, Modifier.fillMaxWidth(), active = state.video.personDetectionState == PersonDetectionState.Off) },
        { ActionButton("DETECT PEOPLE", Icons.Default.PersonSearch, canStart, { vm.setTrackingMode(TrackingMode.DetectOnly) }, Modifier.fillMaxWidth(), active = state.tracking == TrackingMode.DetectOnly) },
    )
    val canSelectBackend = state.tracking == TrackingMode.Off &&
        state.video.personDetectionState !in setOf(PersonDetectionState.Starting, PersonDetectionState.Detecting)
    AdaptiveActionPair(
        {
            OutlineAction(
                "GPU PREFERRED",
                Icons.Default.Settings,
                canSelectBackend,
                { vm.setDetectorBackendPreference(DetectorBackendPreference.Accelerated) },
                Modifier.fillMaxWidth(),
                active = state.video.detectorBackendPreference == DetectorBackendPreference.Accelerated,
            )
        },
        {
            OutlineAction(
                "CPU COMPARE",
                Icons.Default.Settings,
                canSelectBackend,
                { vm.setDetectorBackendPreference(DetectorBackendPreference.Cpu) },
                Modifier.fillMaxWidth(),
                active = state.video.detectorBackendPreference == DetectorBackendPreference.Cpu,
            )
        },
    )
    val status = when (state.video.personDetectionState) {
        PersonDetectionState.Off -> "OFF"
        PersonDetectionState.Starting -> "STARTING"
        PersonDetectionState.Detecting -> "DETECTING"
        PersonDetectionState.Error -> "ERROR"
    }
    StatusLine("State", status, if (state.video.personDetectionState == PersonDetectionState.Error) TelloRed else TelloGreen)
    state.video.detectorModelName?.let { StatusLine("Model", it) }
    state.video.detectorBackend?.let { backend ->
        StatusLine("Backend", if (backend == DetectorBackend.Gpu) "GPU" else "CPU (4 threads)")
    }
    if (state.video.detectorFellBackFromGpu) StatusLine("Fallback", "GPU failed -> CPU")
    state.video.detectorInferenceMillis?.let { StatusLine("Inference", "$it ms") }
    state.video.detectorMeasuredFps?.let { StatusLine("Detector rate", "%.1f FPS".format(it)) }
    state.video.detectorErrorReason?.let { Text(it, color = TelloRed, fontSize = 11.sp) }
    DetectorBenchmarkControls(state, vm)
    if (state.controllerMode == ControllerMode.Mock && state.personDetections.isNotEmpty()) {
        Text("Tap a mock person box to select the dry-run target.", color = TelloTextMuted, fontSize = 11.sp)
        state.personDetections.forEachIndexed { index, detection ->
            OutlineAction(
                "SELECT PERSON ${index + 1}",
                Icons.Default.PersonSearch,
                true,
                { vm.selectTarget(detection) },
                Modifier.fillMaxWidth(),
                active = state.target?.boundingBox == detection.boundingBox,
            )
        }
    }
    val targetStatus = when (state.targetAssociationState) {
        TargetAssociationState.None -> null
        TargetAssociationState.Selected, TargetAssociationState.Matched -> "TARGET SELECTED"
        TargetAssociationState.TemporarilyMissing -> "TARGET MISSING"
        TargetAssociationState.Lost -> "TARGET LOST"
        TargetAssociationState.Ambiguous -> "TARGET AMBIGUOUS"
    }
    targetStatus?.let { StatusLine("Target", it, if (state.targetAssociationState == TargetAssociationState.Lost) TelloRed else TelloGreen) }
    val distanceEligibility = com.alonibh.tellodrone.domain.FollowDistanceEligibility.evaluate(state)
    val canSetDistance = state.controllerMode == ControllerMode.Real && distanceEligibility == com.alonibh.tellodrone.domain.FollowDistanceEligibilityReason.READY
    val distanceLabel = when (state.followDistanceCalibrationState) {
        com.alonibh.tellodrone.domain.FollowDistanceCalibrationState.NotSet -> "NOT SET"
        com.alonibh.tellodrone.domain.FollowDistanceCalibrationState.Calibrating -> "CALIBRATING"
        com.alonibh.tellodrone.domain.FollowDistanceCalibrationState.Set -> "SET"
    }
    val distanceStatus = if (state.followDistanceCalibrationState == com.alonibh.tellodrone.domain.FollowDistanceCalibrationState.Calibrating) "CALIBRATING ${state.followDistanceCalibrationSamples}/7" else distanceLabel
    StatusLine("Follow distance", distanceStatus, if (distanceLabel == "SET") TelloGreen else TelloTextMuted)
    if (state.followDistanceCalibrationState != com.alonibh.tellodrone.domain.FollowDistanceCalibrationState.Set) StatusLine("Distance", distanceEligibility.name.replace('_', ' '), TelloTextMuted)
    state.followDistanceReference?.let { StatusLine("Visual scale", "%.3f".format(it.visualScale)) }
    if (state.controllerMode == ControllerMode.Real) ActionButton("SET CURRENT DISTANCE", Icons.Default.PersonSearch, canSetDistance, vm::setCurrentFollowDistance, Modifier.fillMaxWidth())
    state.trackingErrors?.let { errors ->
        StatusLine("Dry-run yaw", "%.3f".format(errors.yawError))
        StatusLine("Dry-run vertical", "%.3f".format(errors.verticalError))
        StatusLine("Dry-run distance", "%.3f".format(errors.forwardBackError))
    }
    state.dryRunControlIntent?.let { intent ->
        StatusLine("DRY RUN", if (intent.actionable) "ACTIONABLE" else "NO COMMANDS SENT")
        StatusLine("Yaw intent", "%.3f".format(intent.yaw))
        StatusLine("Vertical intent", "%.3f".format(intent.vertical))
        StatusLine("Forward/back", "%.3f".format(intent.forwardBack))
        StatusLine("Reason", intent.reason.name.replace('_', ' '))
        Text("NO COMMANDS SENT", color = TelloTextMuted, fontSize = 11.sp)
    }
    if (state.controllerMode == ControllerMode.Mock) {
        state.shadowAutonomyDecision?.let { decision ->
            StatusLine("SHADOW AUTONOMY", decision.state.name)
            StatusLine("Eligibility", if (decision.eligible) "YES" else "NO")
            StatusLine("Reason", decision.reason.name.replace('_', ' '))
            if (decision.requiresExplicitRearm) Text("RE-ARM REQUIRED", color = TelloRed, fontSize = 11.sp)
        }
        AdaptiveActionPair(
            { OutlineAction("ARM DRY RUN", Icons.Default.CheckCircle, true, { vm.setShadowAutonomyArmed(true) }, Modifier.fillMaxWidth()) },
            { OutlineAction("DISARM", Icons.Default.Close, true, { vm.setShadowAutonomyArmed(false) }, Modifier.fillMaxWidth()) },
        )
        Text("SHADOW ONLY • NO AUTONOMOUS COMMANDS", color = TelloTextMuted, fontSize = 11.sp)
    }
    Text("Frame-local boxes only • Manual authority", color = TelloTextMuted, fontSize = 11.sp)
}

internal fun DroneSessionState.isCurrentTargetDetection(detection: com.alonibh.tellodrone.domain.PersonDetection): Boolean = target?.let {
    detection.frameSequence == it.lastSeenFrameSequence && detection.sourceTimestampNanos == it.lastSeenSourceTimestampNanos && detection.boundingBox == it.boundingBox
} == true

@Composable
private fun DetectorBenchmarkControls(state: DroneSessionState, vm: DroneViewModel) {
    val context = LocalContext.current
    val running = state.video.detectorBenchmarkState == DetectorBenchmarkState.Running
    val available = state.controllerMode == ControllerMode.Real && state.connection == DroneConnectionState.Connected &&
        state.video.availability == VideoAvailability.Streaming && state.video.analysisLatestSequence != null &&
        state.tracking == TrackingMode.Off && state.video.personDetectionState == PersonDetectionState.Off
    HorizontalDivider(color = TelloLine)
    Text("BENCHMARK", color = TelloTextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    if (running) {
        StatusLine("Benchmark", "RUNNING - 30s valid inference", TelloGreen)
        OutlineAction("CANCEL BENCHMARK", Icons.Default.StopCircle, true, vm::cancelDetectorBenchmark, Modifier.fillMaxWidth())
    } else {
        ActionButton("RUN 30s BENCHMARK", Icons.Default.PersonSearch, available, vm::runDetectorBenchmark, Modifier.fillMaxWidth())
    }
    state.video.detectorBenchmarkReason?.let { Text(it, color = TelloRed, fontSize = 11.sp) }
    state.video.detectorBenchmarkResult?.let { result ->
        Text("DEVICE", color = TelloTextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        StatusLine("Device", "${result.manufacturer} ${result.model}")
        StatusLine("Android", "${result.androidVersion} / API ${result.sdkLevel}")
        Text("PERFORMANCE", color = TelloTextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        StatusLine("Startup", result.startupMillis?.let { "$it ms" } ?: "Unavailable")
        StatusLine("Inference p50", result.inferenceP50Millis?.let { "$it ms" } ?: "Unavailable")
        StatusLine("Inference p95", result.inferenceP95Millis?.let { "$it ms" } ?: "Unavailable")
        StatusLine("Detector", result.detectorFps?.let { "%.1f FPS".format(it) } ?: "Unavailable")
        StatusLine("Preview", result.previewFps?.let { "%.1f FPS".format(it) } ?: "Unavailable")
        StatusLine("Frames", result.completedInferences.toString())
        OutlineAction("COPY REPORT", Icons.Default.CheckCircle, true, {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Tello detector benchmark", result.formatReport()))
        }, Modifier.fillMaxWidth())
    }
}

@Composable private fun TrackingDestination(state: DroneSessionState, vm: DroneViewModel, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VideoPanel(state, vm, Modifier.weight(1.4f).fillMaxHeight())
        Column(Modifier.weight(.8f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TrackingControls(state, vm)
            StatusPanel(state, Modifier.fillMaxWidth())
        }
    }
}

/** Short landscape keeps the camera visible while its independently scrollable pane exposes every detector action. */
@Composable private fun CompactHeightTrackingDestination(state: DroneSessionState, vm: DroneViewModel, modifier: Modifier = Modifier) = Row(
    modifier,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    VideoPanel(state, vm, Modifier.weight(1.15f).fillMaxHeight().testTag("compact_height_tracking_video"))
    LazyColumn(
        modifier = Modifier.weight(.85f).fillMaxHeight().testTag("compact_height_tracking_scroll"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        item { TrackingControls(state, vm) }
        item { StatusPanel(state) }
    }
}

@Composable private fun CompactHeightControlsDestination(state: DroneSessionState, vm: DroneViewModel, modifier: Modifier = Modifier) = ControlCard(
    "MANUAL CONTROL",
    compact = true,
    modifier = modifier.testTag("compact_height_manual_controls"),
) {
    ManualControlPanel(state, vm, compact = true)
}

@Composable private fun CompactHeightScrollableDestination(modifier: Modifier = Modifier, content: @Composable () -> Unit) = LazyColumn(
    modifier = modifier.testTag("compact_height_destination_scroll"),
    contentPadding = PaddingValues(bottom = 8.dp),
) { item { content() } }

@Composable private fun MediaControls() = ControlCard("MEDIA") {
    AdaptiveActionPair(
        { OutlineAction("RECORD", Icons.Default.Videocam, false, {}, Modifier.fillMaxWidth()) },
        { OutlineAction("TAKE PHOTO", Icons.Default.CameraAlt, false, {}, Modifier.fillMaxWidth()) },
    )
    Text("Available in a future media phase", color = TelloTextMuted, fontSize = 11.sp)
}
@Composable private fun CompactFutureControlsNotice() = Surface(color = TelloPanel, shape = panelShape, modifier = Modifier.fillMaxWidth()) { Text("Tracking and media controls remain available from the navigation bar.", modifier = Modifier.padding(compactCardPadding), color = TelloTextMuted, fontSize = 11.sp) }

@Composable
private fun AdaptiveActionPair(first: @Composable () -> Unit, second: @Composable () -> Unit) = BoxWithConstraints {
    if (maxWidth < 340.dp) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { first(); second() }
    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Box(Modifier.weight(1f)) { first() }; Box(Modifier.weight(1f)) { second() } }
}

@Composable
private fun StatusPanel(
    state: DroneSessionState,
    modifier: Modifier = Modifier,
) = ControlCard("STATUS", modifier = modifier) {
    StatusLine("Battery", telemetryValue(state) { it.batteryPercent?.let { value -> "$value%" } }, if (state.telemetry.isFresh) TelloGreen else TelloTextMuted)
    StatusLine("Temperature", telemetryValue(state) { it.temperatureCelsius?.let { value -> "%.0f°C".format(value) } })
    StatusLine("Velocity X/Y/Z", telemetryValue(state) { telemetry ->
        listOf(telemetry.velocityXCentimetersPerSecond, telemetry.velocityYCentimetersPerSecond, telemetry.velocityZCentimetersPerSecond)
            .takeIf { values -> values.all { it != null } }
            ?.joinToString(" / ") { "${it}cm/s" }
    })
    StatusLine("Network", state.networkSelection.name)
    StatusLine("Connection", connectionLabel(state.connection))
    StatusLine("Flight", state.flight.name)
    state.lastMessage?.let { Text(it, color = if (state.connection == DroneConnectionState.Error) TelloRed else TelloTextMuted, fontSize = 11.sp) }
}
@Composable private fun StatusLine(label: String, value: String, color: Color = Color.White) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = TelloTextMuted, fontSize = 13.sp); Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium) }

@Composable
private fun BottomControls(state: DroneSessionState, vm: DroneViewModel, modifier: Modifier = Modifier, tablet: Boolean = false) = ControlCard("MANUAL CONTROL", modifier = modifier) { ManualControlPanel(state, vm, tablet = tablet) }

@Composable
private fun ManualControlPanel(state: DroneSessionState, vm: DroneViewModel, compact: Boolean = false, tablet: Boolean = false) {
    val enabled = state.connection == DroneConnectionState.Connected &&
        state.flight == FlightState.Flying &&
        (state.controllerMode == ControllerMode.Mock || state.telemetry.isFresh)
    var leftStick by remember { mutableStateOf(JoystickVector()) }
    var rightStick by remember { mutableStateOf(JoystickVector()) }
    fun publish() {
        vm.setManualVector(manualVectorFromSticks(leftStick, rightStick))
    }
    DisposableEffect(enabled) {
        onDispose { if (enabled) vm.setManualVector(ManualControlVector()) }
    }
    BoxWithConstraints {
        val stickDiameter = when {
            tablet -> 190.dp
            compact -> 104.dp
            maxWidth < 520.dp -> 140.dp
            else -> 156.dp
        }
        val sticks: @Composable () -> Unit = {
            VirtualJoystick("", leftStick, enabled, stickDiameter, { leftStick = it; publish() }, { publish() }, { leftStick = JoystickVector(); publish() })
            VirtualJoystick("", rightStick, enabled, stickDiameter, { rightStick = it; publish() }, { publish() }, { rightStick = JoystickVector(); publish() })
        }
        if (maxWidth < 520.dp && !compact) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sectionSpacing), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { sticks() }
                ManualFlightCenter(state, vm, Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                VirtualJoystick("", leftStick, enabled, stickDiameter, { leftStick = it; publish() }, { publish() }, { leftStick = JoystickVector(); publish() })
                ManualFlightCenter(state, vm, if (tablet) Modifier.width(420.dp) else Modifier.weight(1f).padding(horizontal = if (compact) 12.dp else 28.dp), tablet = tablet)
                VirtualJoystick("", rightStick, enabled, stickDiameter, { rightStick = it; publish() }, { publish() }, { rightStick = JoystickVector(); publish() })
            }
        }
    }
}

@Composable
private fun ManualFlightCenter(state: DroneSessionState, vm: DroneViewModel, modifier: Modifier = Modifier, tablet: Boolean = false) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("FLIGHT", color = TelloTextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    HoverAction(state, vm, Modifier.fillMaxWidth().testTag("manual_stop_hover"), compact = true)
    if (state.hoverActive) HoverActiveStatus()
    SpeedControl(state, vm, Modifier.fillMaxWidth(), carded = tablet)
}

@Composable private fun SpeedControl(state: DroneSessionState, vm: DroneViewModel, modifier: Modifier, carded: Boolean = false) {
    val content: @Composable () -> Unit = { Column(Modifier.padding(if (carded) 14.dp else 0.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text("SPEED", color = TelloTextMuted, fontWeight = FontWeight.Medium); Spacer(Modifier.width(12.dp)); Text("${state.speedPercent}%", color = TelloGreen, fontWeight = FontWeight.Bold, fontSize = 18.sp) }; Slider(value = state.speedPercent.toFloat(), onValueChange = { vm.setSpeed(it.roundToInt()) }, valueRange = 10f..40f); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("10%", fontSize = 11.sp, color = TelloTextMuted); Text("40% MAX", fontSize = 11.sp, color = TelloTextMuted) } } }
    if (carded) Surface(modifier, color = TelloPanelRaised, shape = panelShape) { content() } else Box(modifier) { content() }
}

@Composable
private fun VirtualJoystick(label: String, value: JoystickVector, enabled: Boolean, diameter: Dp, onVector: (JoystickVector) -> Unit, onHeartbeat: () -> Unit, onReleased: () -> Unit) {
    val currentVector by rememberUpdatedState(onVector)
    val currentHeartbeat by rememberUpdatedState(onHeartbeat)
    val currentReleased by rememberUpdatedState(onReleased)
    val activeColor = if (enabled) TelloGreen else TelloLine
    val captureInset = if (diameter <= 140.dp) 12.dp else 24.dp
    DisposableEffect(Unit) { onDispose { currentReleased() } }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (label.isNotBlank()) Text(label, fontSize = 11.sp, color = TelloTextMuted)
        Box(
            Modifier.size(diameter + captureInset * 2).pointerInput(enabled) {
                coroutineScope {
                    var heartbeatJob: kotlinx.coroutines.Job? = null
                    fun update(position: Offset) {
                        val center = size.width / 2f
                        val visualRadius = center - captureInset.toPx()
                        currentVector(normalizedJoystickVector((position.x - center) / visualRadius, (center - position.y) / visualRadius))
                    }
                    detectDragGestures(
                        onDragStart = { position ->
                            if (enabled) {
                                update(position)
                                heartbeatJob = launch { while (true) { delay(MANUAL_HEARTBEAT_MILLIS); currentHeartbeat() } }
                            }
                        },
                        onDrag = { change, _ -> if (enabled) { change.consume(); update(change.position) } },
                        onDragEnd = { heartbeatJob?.cancel(); currentReleased() },
                        onDragCancel = { heartbeatJob?.cancel(); currentReleased() },
                    )
                }
            },
            contentAlignment = Alignment.Center,
        ) { Canvas(Modifier.size(diameter)) {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(TelloPanelRaised, radius, center)
                drawCircle(activeColor.copy(alpha = if (value == JoystickVector()) .65f else 1f), radius, center, style = Stroke(width = 3.dp.toPx()))
                drawLine(TelloLine, Offset(center.x - radius * .65f, center.y), Offset(center.x + radius * .65f, center.y), strokeWidth = 1.dp.toPx())
                drawLine(TelloLine, Offset(center.x, center.y - radius * .65f), Offset(center.x, center.y + radius * .65f), strokeWidth = 1.dp.toPx())
                val thumb = Offset(center.x + value.horizontal * radius * .68f, center.y - value.vertical * radius * .68f)
                drawCircle(if (enabled) TelloGreen else TelloTextMuted, radius * .23f, thumb)
                drawCircle(TelloInk.copy(alpha = .5f), radius * .23f, thumb, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

@Composable private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, compact: Boolean = false) = Button(onClick = onClick, enabled = enabled, modifier = modifier.defaultMinSize(minHeight = if (compact) compactActionHeight else actionHeight).testTag(label.lowercase().replace(' ', '_')), contentPadding = PaddingValues(horizontal = if (compact) 8.dp else 12.dp), colors = ButtonDefaults.buttonColors(containerColor = if (active) TelloGreenDark else TelloGreen, disabledContainerColor = if (active) TelloGreenDark else TelloLine.copy(alpha = .55f), disabledContentColor = if (active) Color.White else TelloTextMuted)) { Icon(icon, null, Modifier.size(if (compact) 16.dp else 18.dp)); Spacer(Modifier.width(if (compact) 4.dp else 6.dp)); Text(label, fontSize = if (compact) 11.sp else 12.sp, maxLines = 1) }
@Composable private fun OutlineAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, compact: Boolean = false) = OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.defaultMinSize(minHeight = if (compact) compactActionHeight else actionHeight), contentPadding = PaddingValues(horizontal = if (compact) 8.dp else 12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, when { active -> TelloGreen; enabled -> TelloLine; else -> TelloLine.copy(alpha = .45f) }), colors = ButtonDefaults.outlinedButtonColors(disabledContainerColor = Color(0xFF293033), disabledContentColor = Color(0xFF9BA7A4))) { Icon(icon, null, Modifier.size(if (compact) 16.dp else 18.dp)); Spacer(Modifier.width(if (compact) 4.dp else 6.dp)); Text(label, fontSize = if (compact) 11.sp else 12.sp, textAlign = TextAlign.Center, maxLines = 1) }

@Composable
private fun EmergencyHoldButton(enabled: Boolean, onTriggered: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    var pressing by remember { mutableStateOf(false) }; var triggered by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { pressing = false } }
    val progress by animateFloatAsState(if (pressing) 1f else 0f, label = "emergency hold")
    LaunchedEffect(pressing) { if (pressing) { delay(900); if (pressing && !triggered) { triggered = true; onTriggered() } } else triggered = false }
    Surface(modifier = modifier.heightIn(min = 64.dp).clip(panelShape).background(if (enabled) TelloRed else TelloLine).pointerInput(enabled) { detectTapGestures(onPress = { if (enabled) { pressing = true; tryAwaitRelease(); pressing = false } }) }.testTag("emergency_motor_kill"), color = Color.Transparent, shape = panelShape) {
        Column(Modifier.fillMaxSize().padding(if (compact) 8.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(Icons.Default.Emergency, null, modifier = Modifier.size(if (compact) 22.dp else 24.dp)); Spacer(Modifier.height(4.dp)); Text(if (compact) "EMERGENCY\nMOTOR KILL" else "EMERGENCY MOTOR KILL", fontWeight = FontWeight.Bold, fontSize = if (compact) 12.sp else 13.sp, textAlign = TextAlign.Center); Text(if (enabled) "Hold for 0.9 seconds" else "Available while flying", fontSize = if (compact) 10.sp else 11.sp, textAlign = TextAlign.Center); if (pressing) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp), color = Color.White, trackColor = Color.Black.copy(alpha = .35f)) }
    }
}

private fun connectionColor(state: DroneConnectionState) = when (state) { DroneConnectionState.Connected -> TelloGreen; DroneConnectionState.Error -> TelloRed; else -> TelloTextMuted }
private fun connectionLabel(state: DroneConnectionState) = when (state) { DroneConnectionState.Connected -> "Connected"; DroneConnectionState.Connecting -> "Connecting"; DroneConnectionState.AwaitingPermission -> "Permission required"; DroneConnectionState.Disconnected -> "Disconnected"; DroneConnectionState.Error -> "Error" }
private fun formatTime(totalSeconds: Int) = "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
private fun telemetryValue(state: DroneSessionState, value: (com.alonibh.tellodrone.domain.TelemetrySnapshot) -> String?): String =
    if (!state.telemetry.isFresh) "STALE / —" else value(state.telemetry) ?: "—"
private fun DroneSessionState.canEmergency() = connection == DroneConnectionState.Connected &&
    flight in setOf(FlightState.TakingOff, FlightState.Flying, FlightState.Landing, FlightState.Unknown)
private const val MANUAL_HEARTBEAT_MILLIS = 100L

@Preview(name = "Tablet landscape – flying controls", widthDp = 1280, heightDp = 800)
@Composable private fun ExpandedGroundedPreview() = PreviewDashboard(tabletPreviewState(FlightState.Grounded))
@Preview(name = "Tablet landscape flying", widthDp = 1280, heightDp = 800)
@Composable private fun TabletFlyingPreview() = PreviewDashboard(tabletPreviewState(FlightState.Flying))
@Preview(name = "Tablet landscape hover active", widthDp = 1280, heightDp = 800)
@Composable private fun TabletHoverActivePreview() = PreviewDashboard(tabletPreviewState(FlightState.Flying, hoverActive = true))
@Preview(name = "Tablet landscape disconnected", widthDp = 1280, heightDp = 800)
@Composable private fun TabletDisconnectedPreview() = PreviewDashboard(DroneSessionState())
@Preview(name = "Portrait – disconnected", widthDp = 420, heightDp = 900)
@Composable private fun PortraitDisconnectedPreview() = PreviewDashboard(DroneSessionState())
@Preview(name = "Phone portrait – flying controls", widthDp = 360, heightDp = 640)
@Composable private fun MiA1PortraitPreview() = PreviewDashboard(DroneSessionState(connection = DroneConnectionState.Connected, flight = FlightState.Grounded))
@Preview(name = "Phone landscape Dashboard connected video", widthDp = 640, heightDp = 360)
@Composable private fun CompactLandscapePreview() = PreviewCompactHeightDestination(
    DroneSessionState(connection = DroneConnectionState.Connected, flight = FlightState.Flying, video = VideoState(availability = VideoAvailability.Streaming)),
    "Dashboard",
)
@Preview(name = "Phone landscape Dashboard disconnected API28", widthDp = 640, heightDp = 360)
@Composable private fun CompactLandscapeDisconnectedPreview() = PreviewCompactHeightDestination(DroneSessionState(), "Dashboard", showApi28WifiAction = true)
@Preview(name = "Phone landscape Controls", widthDp = 640, heightDp = 360)
@Composable private fun CompactLandscapeControlsPreview() = PreviewCompactHeightDestination(DroneSessionState(connection = DroneConnectionState.Connected, flight = FlightState.Flying), "Controls")
@Preview(name = "Phone landscape Tracking benchmark", widthDp = 640, heightDp = 360)
@Composable private fun CompactLandscapeTrackingPreview() = PreviewCompactHeightDestination(
    DroneSessionState(connection = DroneConnectionState.Connected, video = VideoState(availability = VideoAvailability.Streaming, analysisLatestSequence = 1L)),
    "Tracking",
)
@Preview(name = "Medium window", widthDp = 700, heightDp = 600)
@Composable private fun MediumPreview() = PreviewDashboard(DroneSessionState(connection = DroneConnectionState.Connected, flight = FlightState.Flying))
@Preview(name = "Flying manual", widthDp = 1280, heightDp = 800)
@Composable private fun FlyingManualPreview() = PreviewDashboard(DroneSessionState(connection = DroneConnectionState.Connected, flight = FlightState.Flying))
@Preview(name = "Flying detection", widthDp = 1280, heightDp = 800)
@Composable private fun FlyingDetectPreview() = PreviewDashboard(DroneSessionState(connection = DroneConnectionState.Connected, flight = FlightState.Flying, tracking = TrackingMode.DetectOnly))
@Preview(name = "Emergency", widthDp = 1280, heightDp = 800)
@Composable private fun EmergencyPreview() = PreviewDashboard(DroneSessionState(connection = DroneConnectionState.Connected, flight = FlightState.Emergency))
@Composable private fun PreviewDashboard(state: DroneSessionState) {
    val controller = remember(state) { MockDroneController(state) }
    val previewViewModel: DroneViewModel = viewModel(factory = DroneViewModel.Factory(controller))
    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = TelloGreen, background = TelloInk, surface = TelloPanel, surfaceVariant = TelloPanelRaised, error = TelloRed)) { DroneDashboard(state, previewViewModel) }
}
@Composable private fun PreviewCompactHeightDestination(state: DroneSessionState, destination: String, showApi28WifiAction: Boolean = false) {
    val controller = remember(state) { MockDroneController(state) }
    val previewViewModel: DroneViewModel = viewModel(factory = DroneViewModel.Factory(controller))
    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = TelloGreen, background = TelloInk, surface = TelloPanel, surfaceVariant = TelloPanelRaised, error = TelloRed)) {
        LandscapeDashboard(state, previewViewModel, destination, {}, showApi28WifiAction)
    }
}
private fun tabletPreviewState(flight: FlightState, hoverActive: Boolean = false) = DroneSessionState(connection = DroneConnectionState.Connected, flight = flight, telemetry = com.alonibh.tellodrone.domain.TelemetrySnapshot(isFresh = true), hoverActive = hoverActive)
