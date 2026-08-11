@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.alonibh.tellodrone.ui

import android.content.Intent
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Videocam
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
import com.alonibh.tellodrone.domain.ControlAuthority
import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.NetworkSelectionState
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.domain.VideoAvailability
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
private const val STATUS_REFRESH_MILLIS = 250L

@Composable
fun DroneDashboard(state: DroneSessionState, viewModel: DroneViewModel, modifier: Modifier = Modifier) {
    var destination by remember { mutableStateOf("Dashboard") }
    BoxWithConstraints(
        modifier.fillMaxSize().background(TelloInk)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(12.dp),
    ) {
        when (windowLayout(maxWidth, maxHeight)) {
            WindowLayout.Expanded -> ExpandedDashboard(state, viewModel, destination) { destination = it }
            WindowLayout.Medium -> MediumDashboard(state, viewModel, destination) { destination = it }
            WindowLayout.CompactHeight -> LandscapeDashboard(state, viewModel, destination) { destination = it }
            WindowLayout.Compact -> CompactDashboard(state, viewModel, destination) { destination = it }
        }
    }
}

/** Material window-size-class breakpoints, evaluated from the current app window. */
private fun windowLayout(width: Dp, height: Dp): WindowLayout = when {
    width >= 840.dp -> WindowLayout.Expanded
    height < 480.dp -> WindowLayout.CompactHeight
    width >= 600.dp -> WindowLayout.Medium
    else -> WindowLayout.Compact
}

private enum class WindowLayout { Compact, CompactHeight, Medium, Expanded }

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
                } else if (destination == "Status") StatusPanel(state, Modifier.fillMaxSize())
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
            item { StatusPanel(state, previewSurfaceAttached = true) }
            item { EmergencyHoldButton(state.canEmergency(), vm::emergencyMotorKill, Modifier.fillMaxWidth()) }
        } else item {
            if (destination == "Status") StatusPanel(state, Modifier.fillMaxWidth())
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
                    item { StatusPanel(state, previewSurfaceAttached = true) }
                }
            }
            BottomControls(state, vm)
        } else if (destination == "Status") StatusPanel(state, Modifier.weight(1f).fillMaxWidth())
        else DestinationPlaceholder(destination, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun LandscapeDashboard(state: DroneSessionState, vm: DroneViewModel, destination: String, onDestination: (String) -> Unit) {
    Column(Modifier.testTag("layout_compact_height"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactHeader(state, vm)
        if (destination == "Dashboard") {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoPanel(state, vm, Modifier.weight(1.4f).fillMaxHeight())
                Column(Modifier.weight(.85f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LandscapeFlightControls(state, vm)
                    EmergencyHoldButton(state.canEmergency(), vm::emergencyMotorKill, Modifier.fillMaxWidth())
                }
            }
            CompactLandscapeManualControls(state, vm)
        } else {
            CompactNav(destination, onDestination)
            if (destination == "Status") StatusPanel(state, Modifier.weight(1f).fillMaxWidth())
            else DestinationPlaceholder(destination, Modifier.weight(1f).fillMaxWidth())
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
private fun CompactHeader(state: DroneSessionState, vm: DroneViewModel) = Surface(
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
        ConnectionButton(state, vm)
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
@Composable private fun CompactNav(destination: String, onDestination: (String) -> Unit) = Surface(color = TelloPanel, shape = panelShape, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.SpaceEvenly) { listOf("Dashboard", "Controls", "Tracking", "Media", "Status").forEach { label -> Text(label, modifier = Modifier.clickable { onDestination(label) }.padding(7.dp), fontSize = 12.sp, color = if (label == destination) TelloGreen else TelloTextMuted) } } }
@Composable private fun DestinationPlaceholder(destination: String, modifier: Modifier = Modifier) = Surface(modifier, shape = panelShape, color = TelloPanel) { Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Settings, null, tint = TelloGreen, modifier = Modifier.size(38.dp)); Spacer(Modifier.height(12.dp)); Text(destination, fontWeight = FontWeight.Bold, fontSize = 22.sp); Text("This Phase 1 destination is a lightweight placeholder.", color = TelloTextMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) } }

@Composable
private fun VideoPanel(state: DroneSessionState, vm: DroneViewModel, modifier: Modifier = Modifier) = Surface(modifier.clip(panelShape), color = Color(0xFF252A2C)) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF42403B), Color(0xFF171B1D))))) {
        val analysis = rememberAnalysisDiagnostics(state.video, previewSurfaceAttached = true)
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
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(14.dp).clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = .82f)).padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                when {
                    state.video.measuredFps != null -> "PREVIEW ${state.video.measuredFps.roundToInt()} FPS"
                    state.video.availability == VideoAvailability.Streaming -> "PREVIEW WAITING"
                    else -> "NO VIDEO"
                },
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            if (state.controllerMode == ControllerMode.Real && state.video.availability == VideoAvailability.Streaming) {
                Text(
                    "ANALYSIS ${analysis.rate} · ${analysis.frame} · ${analysis.age}",
                    color = TelloTextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        state.target?.let { target ->
            val boxWidth = maxWidth * (target.boundingBox.right - target.boundingBox.left)
            val boxHeight = maxHeight * (target.boundingBox.bottom - target.boundingBox.top)
            Column(Modifier.offset(maxWidth * target.boundingBox.left, maxHeight * target.boundingBox.top).size(boxWidth, boxHeight).border(2.dp, if (target.locked) TelloGreen else Color(0xFFFFC857), RoundedCornerShape(3.dp))) { Text(if (target.locked) "TARGET LOCK" else "PERSON • MOCK", color = TelloInk, modifier = Modifier.background(if (target.locked) TelloGreen else Color(0xFFFFC857)).padding(horizontal = 6.dp, vertical = 3.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { state.target?.estimatedDistanceMeters?.let { Text("EST. DISTANCE %.1f m".format(it), Modifier.clip(RoundedCornerShape(7.dp)).background(Color.Black.copy(alpha = .72f)).padding(8.dp), color = Color.White, fontSize = 12.sp) }; Text("H: ${telemetryValue(state) { it.heightMeters?.let { value -> "%.1f m".format(value) } }}", Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .82f)).padding(horizontal = 12.dp, vertical = 9.dp), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
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

@Composable private fun TrackingControls(state: DroneSessionState, vm: DroneViewModel) = ControlCard("TRACKING • PHASE 3+") {
    val mock = state.controllerMode == ControllerMode.Mock
    ActionButton("DETECT PERSON", Icons.Default.PersonSearch, mock && state.connection == DroneConnectionState.Connected, { vm.setTrackingMode(TrackingMode.DetectOnly) }, Modifier.fillMaxWidth(), active = state.tracking == TrackingMode.DetectOnly)
    AdaptiveActionPair(
        { OutlineAction("TARGET LOCK", Icons.Default.Lock, mock && state.flight == FlightState.Flying && state.target != null, { vm.setTargetLock(state.target?.locked != true) }, Modifier.fillMaxWidth(), active = state.target?.locked == true) },
        { ActionButton("FOLLOW", Icons.Default.PlayArrow, mock && state.flight == FlightState.Flying && state.target?.locked == true, { vm.setTrackingMode(TrackingMode.Follow) }, Modifier.fillMaxWidth(), active = state.tracking == TrackingMode.Follow) },
    )
    if (!mock) Text("Person detection is not implemented; authority remains Manual", color = TelloTextMuted, fontSize = 11.sp)
}
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
    previewSurfaceAttached: Boolean = false,
) = ControlCard("STATUS", modifier = modifier) {
    val analysis = rememberAnalysisDiagnostics(state.video, previewSurfaceAttached)
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
    StatusLine("Analysis rate", analysis.rate)
    StatusLine("Analysis frame", analysis.frame)
    StatusLine("Analysis frame age", analysis.age)
    if (analysis.paused) Text("Analysis capture runs with the live preview.", color = TelloTextMuted, fontSize = 11.sp)
    state.lastMessage?.let { Text(it, color = if (state.connection == DroneConnectionState.Error) TelloRed else TelloTextMuted, fontSize = 11.sp) }
}

@Composable
private fun rememberAnalysisDiagnostics(
    video: com.alonibh.tellodrone.domain.VideoState,
    previewSurfaceAttached: Boolean,
): AnalysisDiagnosticsPresentation {
    var nowNanos by remember { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowNanos = System.nanoTime()
            delay(STATUS_REFRESH_MILLIS)
        }
    }
    return analysisDiagnosticsPresentation(video, previewSurfaceAttached, nowNanos)
}
@Composable private fun StatusLine(label: String, value: String, color: Color = Color.White) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = TelloTextMuted, fontSize = 13.sp); Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium) }

@Composable
private fun BottomControls(state: DroneSessionState, vm: DroneViewModel, modifier: Modifier = Modifier, tablet: Boolean = false) = ControlCard("MANUAL CONTROL", modifier = modifier) { ManualControlPanel(state, vm, tablet = tablet) }

@Composable
private fun CompactLandscapeManualControls(state: DroneSessionState, vm: DroneViewModel) = ControlCard("MANUAL CONTROL", compact = true) { ManualControlPanel(state, vm, compact = true) }

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
            compact -> 112.dp
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
@Preview(name = "Phone landscape – flying controls", widthDp = 640, heightDp = 360)
@Composable private fun CompactLandscapePreview() = PreviewDashboard(DroneSessionState(connection = DroneConnectionState.Connected, flight = FlightState.Flying))
@Preview(name = "Medium window", widthDp = 700, heightDp = 600)
@Composable private fun MediumPreview() = PreviewDashboard(DroneSessionState(connection = DroneConnectionState.Connected, flight = FlightState.Flying))
@Preview(name = "Flying manual", widthDp = 1280, heightDp = 800)
@Composable private fun FlyingManualPreview() = PreviewDashboard(DroneSessionState(connection = DroneConnectionState.Connected, flight = FlightState.Flying))
@Preview(name = "Flying detection", widthDp = 1280, heightDp = 800)
@Composable private fun FlyingDetectPreview() = PreviewDashboard(DroneSessionState(connection = DroneConnectionState.Connected, flight = FlightState.Flying, tracking = TrackingMode.DetectOnly, target = previewTarget(false)))
@Preview(name = "Flying target locked", widthDp = 1280, heightDp = 800)
@Composable private fun FlyingLockedPreview() = PreviewDashboard(DroneSessionState(connection = DroneConnectionState.Connected, flight = FlightState.Flying, tracking = TrackingMode.TargetLocked, target = previewTarget(true)))
@Preview(name = "Flying follow", widthDp = 1280, heightDp = 800)
@Composable private fun FlyingFollowPreview() = PreviewDashboard(DroneSessionState(connection = DroneConnectionState.Connected, flight = FlightState.Flying, tracking = TrackingMode.Follow, authority = ControlAuthority.Autonomous, target = previewTarget(true)))
@Preview(name = "Emergency", widthDp = 1280, heightDp = 800)
@Composable private fun EmergencyPreview() = PreviewDashboard(DroneSessionState(connection = DroneConnectionState.Connected, flight = FlightState.Emergency))
@Composable private fun PreviewDashboard(state: DroneSessionState) {
    val controller = remember(state) { MockDroneController(state) }
    val previewViewModel: DroneViewModel = viewModel(factory = DroneViewModel.Factory(controller))
    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = TelloGreen, background = TelloInk, surface = TelloPanel, surfaceVariant = TelloPanelRaised, error = TelloRed)) { DroneDashboard(state, previewViewModel) }
}
private fun previewTarget(locked: Boolean) = com.alonibh.tellodrone.domain.TrackedTarget(androidx.compose.ui.geometry.Rect(.40f, .20f, .62f, .82f), .92f, 1.8f, locked = locked)
private fun tabletPreviewState(flight: FlightState, hoverActive: Boolean = false) = DroneSessionState(connection = DroneConnectionState.Connected, flight = flight, telemetry = com.alonibh.tellodrone.domain.TelemetrySnapshot(isFresh = true), hoverActive = hoverActive)
