@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.alonibh.tellodrone.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
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

@Composable
fun DroneDashboard(state: DroneSessionState, viewModel: DroneViewModel, modifier: Modifier = Modifier) {
    var destination by remember { mutableStateOf("Dashboard") }
    BoxWithConstraints(modifier.fillMaxSize().background(TelloInk).padding(12.dp)) {
        val expanded = maxWidth >= 960.dp
        if (expanded) ExpandedDashboard(state, viewModel, destination) { destination = it }
        else CompactDashboard(state, viewModel, destination) { destination = it }
    }
}

@Composable
private fun ExpandedDashboard(state: DroneSessionState, vm: DroneViewModel, destination: String, onDestination: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header(state, vm, expanded = true)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NavigationRail(state, vm, destination, onDestination, Modifier.width(176.dp).fillMaxHeight())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (destination == "Dashboard") {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VideoPanel(state, Modifier.weight(1f).fillMaxHeight())
                        RightControls(state, vm, Modifier.width(274.dp).fillMaxHeight())
                    }
                    BottomControls(state, vm)
                } else DestinationPlaceholder(destination, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun CompactDashboard(state: DroneSessionState, vm: DroneViewModel, destination: String, onDestination: (String) -> Unit) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item { Header(state, vm, expanded = false) }
        item { CompactNav(destination, onDestination) }
        if (destination == "Dashboard") {
            item { VideoPanel(state, Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 460.dp)) }
            item { CriticalFlightControls(state, vm) }
            item { TrackingControls(state, vm) }
            item { BottomControls(state, vm) }
            item { StatusPanel(state) }
            item { EmergencyHoldButton(state.canEmergency(), vm::emergencyMotorKill, Modifier.fillMaxWidth()) }
        } else item { DestinationPlaceholder(destination, Modifier.fillMaxWidth().height(280.dp)) }
    }
}

@Composable
private fun Header(state: DroneSessionState, vm: DroneViewModel, expanded: Boolean) {
    Surface(shape = panelShape, color = TelloPanelRaised, modifier = Modifier.fillMaxWidth()) {
        if (expanded) Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Brand(state, Modifier.width(205.dp)); HeaderMetric("BATTERY", telemetryValue(state) { it.batteryPercent?.let { value -> "$value%" } })
            HeaderMetric("HEIGHT", telemetryValue(state) { it.heightMeters?.let { value -> "%.1f m".format(value) } }); HeaderMetric("SPEED", telemetryValue(state) { it.speedMetersPerSecond?.let { value -> "%.1f m/s".format(value) } })
            HeaderMetric("FLIGHT TIME", telemetryValue(state) { it.flightTimeSeconds?.let(::formatTime) }); Spacer(Modifier.weight(1f)); ControllerModeSelector(state, vm); ConnectionButton(state, vm); Icon(Icons.Default.Settings, null, tint = TelloTextMuted); Text(" Settings", modifier = Modifier.padding(start = 4.dp))
        } else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Brand(state); FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HeaderMetric("BATTERY", telemetryValue(state) { it.batteryPercent?.let { value -> "$value%" } }); HeaderMetric("HEIGHT", telemetryValue(state) { it.heightMeters?.let { value -> "%.1f m".format(value) } }); HeaderMetric("SPEED", telemetryValue(state) { it.speedMetersPerSecond?.let { value -> "%.1f m/s".format(value) } })
            }
            ControllerModeSelector(state, vm)
            ConnectionButton(state, vm)
        }
    }
}

@Composable private fun ConnectionButton(state: DroneSessionState, vm: DroneViewModel) {
    val active = state.connection == DroneConnectionState.Connected
    val transition = state.connection in setOf(DroneConnectionState.Connecting, DroneConnectionState.AwaitingPermission)
    val unsafeDisconnect = active && state.flight in setOf(FlightState.TakingOff, FlightState.Flying, FlightState.Landing, FlightState.Unknown)
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
@Composable private fun HeaderMetric(label: String, value: String) = Column(Modifier.padding(horizontal = 12.dp)) { Text(label, fontSize = 11.sp, color = TelloTextMuted); Text(value, fontSize = 19.sp, fontWeight = FontWeight.Medium) }

@Composable private fun NavigationRail(state: DroneSessionState, vm: DroneViewModel, destination: String, onDestination: (String) -> Unit, modifier: Modifier = Modifier) = Surface(modifier, shape = panelShape, color = TelloPanel) {
    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf("Dashboard", "Controls", "Tracking", "Media", "Status").forEach { label ->
            val selected = label == destination
            Row(Modifier.fillMaxWidth().height(52.dp).then(if (selected) Modifier.background(TelloGreenDark.copy(alpha = .5f)) else Modifier).clickable { onDestination(label) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (selected) Icons.Default.MyLocation else Icons.Default.Settings, null, tint = if (selected) TelloGreen else TelloTextMuted); Spacer(Modifier.width(14.dp)); Text(label, color = if (selected) TelloGreen else Color.White) }
        }
        Spacer(Modifier.weight(1f)); EmergencyHoldButton(state.canEmergency(), vm::emergencyMotorKill, Modifier.padding(12.dp).fillMaxWidth())
    }
}
@Composable private fun CompactNav(destination: String, onDestination: (String) -> Unit) = Surface(color = TelloPanel, shape = panelShape, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.SpaceEvenly) { listOf("Dashboard", "Controls", "Tracking", "Media", "Status").forEach { label -> Text(label, modifier = Modifier.clickable { onDestination(label) }.padding(7.dp), fontSize = 12.sp, color = if (label == destination) TelloGreen else TelloTextMuted) } } }
@Composable private fun DestinationPlaceholder(destination: String, modifier: Modifier = Modifier) = Surface(modifier, shape = panelShape, color = TelloPanel) { Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Settings, null, tint = TelloGreen, modifier = Modifier.size(38.dp)); Spacer(Modifier.height(12.dp)); Text(destination, fontWeight = FontWeight.Bold, fontSize = 22.sp); Text("This Phase 1 destination is a lightweight placeholder.", color = TelloTextMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) } }

@Composable
private fun VideoPanel(state: DroneSessionState, modifier: Modifier = Modifier) = Surface(modifier.clip(panelShape), color = Color(0xFF252A2C)) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF42403B), Color(0xFF171B1D))))) {
        Canvas(Modifier.fillMaxSize()) { for (x in 0..size.width.toInt() step 36) drawLine(Color.White.copy(alpha = .025f), Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height)); for (y in 0..size.height.toInt() step 36) drawLine(Color.White.copy(alpha = .025f), Offset(0f, y.toFloat()), Offset(size.width, y.toFloat())) }
        Row(Modifier.align(Alignment.TopStart).padding(14.dp).clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = .64f)).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (state.video.availability == VideoAvailability.Mock) "DEMO" else "VIDEO", color = Color.White, fontWeight = FontWeight.Bold); Text(if (state.video.availability == VideoAvailability.Mock) "  MOCK PREVIEW" else "  PHASE 3", color = TelloTextMuted, fontSize = 12.sp) }
        Text(if (state.video.measuredFps != null) "MOCK ${state.video.measuredFps.roundToInt()} fps" else "NO VIDEO", modifier = Modifier.align(Alignment.TopEnd).padding(14.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .62f)).padding(8.dp), fontSize = 12.sp)
        state.target?.let { target ->
            val boxWidth = maxWidth * (target.boundingBox.right - target.boundingBox.left)
            val boxHeight = maxHeight * (target.boundingBox.bottom - target.boundingBox.top)
            Column(Modifier.offset(maxWidth * target.boundingBox.left, maxHeight * target.boundingBox.top).size(boxWidth, boxHeight).border(2.dp, if (target.locked) TelloGreen else Color(0xFFFFC857), RoundedCornerShape(3.dp))) { Text(if (target.locked) "TARGET LOCK" else "PERSON • MOCK", color = TelloInk, modifier = Modifier.background(if (target.locked) TelloGreen else Color(0xFFFFC857)).padding(horizontal = 6.dp, vertical = 3.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { state.target?.estimatedDistanceMeters?.let { Text("EST. DISTANCE %.1f m".format(it), Modifier.clip(RoundedCornerShape(7.dp)).background(Color.Black.copy(alpha = .65f)).padding(8.dp), fontSize = 12.sp) }; Text("H: ${telemetryValue(state) { it.heightMeters?.let { value -> "%.1f m".format(value) } }}", Modifier.clip(RoundedCornerShape(7.dp)).background(Color.Black.copy(alpha = .65f)).padding(8.dp), fontSize = 12.sp) }
        Text(if (state.video.availability == VideoAvailability.Mock) "Mock preview • no physical video" else "Video intentionally unavailable in Phase 2", Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .30f)).padding(8.dp), color = TelloTextMuted, fontSize = 12.sp)
    }
}

@Composable private fun RightControls(state: DroneSessionState, vm: DroneViewModel, modifier: Modifier) = LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) { item { CriticalFlightControls(state, vm) }; item { TrackingControls(state, vm) }; item { MediaControls() }; item { StatusPanel(state) } }
@Composable private fun ControlCard(title: String, content: @Composable ColumnScope.() -> Unit) = Card(colors = CardDefaults.cardColors(containerColor = TelloPanel), shape = panelShape) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, color = TelloTextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium); content() } }

@Composable private fun CriticalFlightControls(state: DroneSessionState, vm: DroneViewModel) = ControlCard("FLIGHT CONTROLS") { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ActionButton("TAKE OFF", Icons.Default.ArrowUpward, state.connection == DroneConnectionState.Connected && state.flight == FlightState.Grounded && state.telemetry.isFresh, vm::takeOff, Modifier.weight(1f)); OutlineAction("LAND", Icons.Default.ArrowDownward, state.connection == DroneConnectionState.Connected && state.flight in setOf(FlightState.Flying, FlightState.Unknown), vm::land, Modifier.weight(1f)) }; OutlineAction("STOP / HOVER", Icons.Default.PauseCircle, state.connection == DroneConnectionState.Connected && state.flight == FlightState.Flying, vm::stopAndHover, Modifier.fillMaxWidth().testTag("stop_hover")) }

@Composable private fun TrackingControls(state: DroneSessionState, vm: DroneViewModel) = ControlCard("TRACKING • PHASE 3+") { val mock = state.controllerMode == ControllerMode.Mock; ActionButton("DETECT PERSON", Icons.Default.PersonSearch, mock && state.connection == DroneConnectionState.Connected, { vm.setTrackingMode(TrackingMode.DetectOnly) }, Modifier.fillMaxWidth(), active = state.tracking == TrackingMode.DetectOnly); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlineAction("TARGET LOCK", Icons.Default.Lock, mock && state.flight == FlightState.Flying && state.target != null, { vm.setTargetLock(state.target?.locked != true) }, Modifier.weight(1f), active = state.target?.locked == true); ActionButton("FOLLOW", Icons.Default.PlayArrow, mock && state.flight == FlightState.Flying && state.target?.locked == true, { vm.setTrackingMode(TrackingMode.Follow) }, Modifier.weight(1f), active = state.tracking == TrackingMode.Follow) }; if (!mock) Text("Unavailable for real flight in Phase 2; authority remains Manual", color = TelloTextMuted, fontSize = 11.sp) }
@Composable private fun MediaControls() = ControlCard("MEDIA") { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlineAction("RECORD", Icons.Default.Videocam, false, {}, Modifier.weight(1f)); OutlineAction("TAKE PHOTO", Icons.Default.CameraAlt, false, {}, Modifier.weight(1f)) }; Text("Available in a future media phase", color = TelloTextMuted, fontSize = 11.sp) }

@Composable private fun StatusPanel(state: DroneSessionState) = ControlCard("STATUS") { StatusLine("Battery", telemetryValue(state) { it.batteryPercent?.let { value -> "$value%" } }, if (state.telemetry.isFresh) TelloGreen else TelloTextMuted); StatusLine("Temperature", telemetryValue(state) { it.temperatureCelsius?.let { value -> "%.0f°C".format(value) } }); StatusLine("Velocity X/Y/Z", telemetryValue(state) { telemetry -> listOf(telemetry.velocityXCentimetersPerSecond, telemetry.velocityYCentimetersPerSecond, telemetry.velocityZCentimetersPerSecond).takeIf { values -> values.all { it != null } }?.joinToString(" / ") { "${it}cm/s" } }); StatusLine("Network", state.networkSelection.name); StatusLine("Connection", connectionLabel(state.connection)); StatusLine("Flight", state.flight.name); state.lastMessage?.let { Text(it, color = if (state.connection == DroneConnectionState.Error) TelloRed else TelloTextMuted, fontSize = 11.sp) } }
@Composable private fun StatusLine(label: String, value: String, color: Color = Color.White) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = TelloTextMuted, fontSize = 13.sp); Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium) }

@Composable
private fun BottomControls(state: DroneSessionState, vm: DroneViewModel) = ControlCard("MANUAL CONTROL") {
    val enabled = state.connection == DroneConnectionState.Connected &&
        state.flight == FlightState.Flying &&
        (state.controllerMode == ControllerMode.Mock || state.telemetry.isFresh)
    var desired by remember { mutableStateOf(ManualControlVector()) }
    fun publish(next: ManualControlVector) {
        desired = next
        vm.setManualVector(next)
    }
    LaunchedEffect(enabled) { if (!enabled) publish(ManualControlVector()) }
    DisposableEffect(Unit) { onDispose { vm.setManualVector(ManualControlVector()) } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Joystick(
            enabled,
            onLateral = { publish(desired.copy(lateral = it)) },
            onForward = { publish(desired.copy(forward = it)) },
            heartbeat = { vm.setManualVector(desired) },
            modifier = Modifier.weight(1f),
        )
        AxisControls(enabled, { publish(desired.copy(vertical = it)) }, { vm.setManualVector(desired) }, Modifier.weight(1f))
        YawControls(enabled, { publish(desired.copy(yaw = it)) }, { vm.setManualVector(desired) }, Modifier.weight(1f))
        SpeedControl(state, vm, Modifier.weight(1f))
    }
}

@Composable private fun Joystick(enabled: Boolean, onLateral: (Float) -> Unit, onForward: (Float) -> Unit, heartbeat: () -> Unit, modifier: Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { Text("DIRECTION", fontSize = 11.sp, color = TelloTextMuted); Row(verticalAlignment = Alignment.CenterVertically) { HoldCircleIcon(Icons.AutoMirrored.Filled.ArrowBack, enabled, -1f, onLateral, heartbeat); Column(horizontalAlignment = Alignment.CenterHorizontally) { HoldCircleIcon(Icons.Default.KeyboardArrowUp, enabled, 1f, onForward, heartbeat); HoldCircleIcon(Icons.Default.KeyboardArrowDown, enabled, -1f, onForward, heartbeat) }; HoldCircleIcon(Icons.AutoMirrored.Filled.ArrowForward, enabled, 1f, onLateral, heartbeat) } }
@Composable private fun AxisControls(enabled: Boolean, onAxis: (Float) -> Unit, heartbeat: () -> Unit, modifier: Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { Text("ALTITUDE", fontSize = 11.sp, color = TelloTextMuted); Row { HoldCircleIcon(Icons.Default.ArrowUpward, enabled, 1f, onAxis, heartbeat); HoldCircleIcon(Icons.Default.ArrowDownward, enabled, -1f, onAxis, heartbeat) } }
@Composable private fun YawControls(enabled: Boolean, onAxis: (Float) -> Unit, heartbeat: () -> Unit, modifier: Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { Text("ROTATE / YAW", fontSize = 11.sp, color = TelloTextMuted); Row { HoldCircleIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, enabled, -1f, onAxis, heartbeat); HoldCircleIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, enabled, 1f, onAxis, heartbeat) } }
@Composable private fun SpeedControl(state: DroneSessionState, vm: DroneViewModel, modifier: Modifier) = Column(modifier) { Text("SPEED  ${state.speedPercent}%", color = TelloGreen, fontWeight = FontWeight.Medium); Slider(value = state.speedPercent.toFloat(), onValueChange = { vm.setSpeed(it.roundToInt()) }, valueRange = 10f..40f); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("10%", fontSize = 11.sp, color = TelloTextMuted); Text("40% max", fontSize = 11.sp, color = TelloTextMuted) } }

@Composable
private fun HoldCircleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, activeValue: Float, onAxis: (Float) -> Unit, heartbeat: () -> Unit) {
    val currentOnAxis by rememberUpdatedState(onAxis)
    val currentHeartbeat by rememberUpdatedState(heartbeat)
    Box(
        Modifier
            .size(46.dp)
            .border(1.dp, TelloLine, CircleShape)
            .pointerInput(enabled) {
                detectTapGestures(onPress = {
                    if (enabled) coroutineScope {
                        currentOnAxis(activeValue)
                        val heartbeatJob = launch {
                            while (true) {
                                delay(MANUAL_HEARTBEAT_MILLIS)
                                currentHeartbeat()
                            }
                        }
                        try { tryAwaitRelease() } finally {
                            heartbeatJob.cancel()
                            currentOnAxis(0f)
                        }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) Color.White else TelloTextMuted) }
}

@Composable private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) = Button(onClick = onClick, enabled = enabled, modifier = modifier.defaultMinSize(minHeight = 48.dp).testTag(label.lowercase().replace(' ', '_')), colors = ButtonDefaults.buttonColors(containerColor = if (active) TelloGreenDark else TelloGreen, disabledContainerColor = TelloLine.copy(alpha = .55f), disabledContentColor = TelloTextMuted)) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label, fontSize = 12.sp) }
@Composable private fun OutlineAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) = OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.defaultMinSize(minHeight = 48.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (active) TelloGreen else TelloLine)) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label, fontSize = 12.sp, textAlign = TextAlign.Center) }

@Composable
private fun EmergencyHoldButton(enabled: Boolean, onTriggered: () -> Unit, modifier: Modifier = Modifier) {
    var pressing by remember { mutableStateOf(false) }; var triggered by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(if (pressing) 1f else 0f, label = "emergency hold")
    LaunchedEffect(pressing) { if (pressing) { delay(900); if (pressing && !triggered) { triggered = true; onTriggered() } } else triggered = false }
    Surface(modifier = modifier.heightIn(min = 64.dp).clip(panelShape).background(if (enabled) TelloRed else TelloLine).pointerInput(enabled) { detectTapGestures(onPress = { if (enabled) { pressing = true; tryAwaitRelease(); pressing = false } }) }.testTag("emergency_motor_kill"), color = Color.Transparent, shape = panelShape) {
        Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Emergency, null); Spacer(Modifier.width(8.dp)); Text("EMERGENCY MOTOR KILL", fontWeight = FontWeight.Bold, fontSize = 13.sp) }; Text(if (enabled) "Hold for 0.9 seconds" else "Available while flying", fontSize = 11.sp); if (pressing) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp), color = Color.White, trackColor = Color.Black.copy(alpha = .35f)) }
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

@Preview(name = "Expanded – connected grounded", widthDp = 1280, heightDp = 800)
@Composable private fun ExpandedGroundedPreview() = PreviewDashboard(DroneSessionState(connection = DroneConnectionState.Connected))
@Preview(name = "Portrait – disconnected", widthDp = 420, heightDp = 900)
@Composable private fun PortraitDisconnectedPreview() = PreviewDashboard(DroneSessionState())
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
