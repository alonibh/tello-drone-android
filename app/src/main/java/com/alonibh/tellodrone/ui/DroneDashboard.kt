@file:OptIn(ExperimentalMaterial3Api::class)

package com.alonibh.tellodrone.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.alonibh.tellodrone.TelloGreen
import com.alonibh.tellodrone.TelloInk
import com.alonibh.tellodrone.TelloLine
import com.alonibh.tellodrone.TelloRed
import com.alonibh.tellodrone.TelloTextMuted
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.RcSpeedMode
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.domain.VideoAvailability
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val hudShape = RoundedCornerShape(12.dp)
private val hudBackground = Color(0xE6111417)
private val hudBorder = Color(0x523A4248)
private val activeBlue = Color(0xFF2864EE)

@Composable
fun DroneDashboard(
    state: DroneSessionState,
    viewModel: DroneDashboardActions,
    onExportTrace: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (isPortraitOperationalWindow(maxWidth, maxHeight)) {
            PortraitSafetyFallback(state, viewModel)
        } else {
            UnifiedFlightScreen(
                state = state,
                viewModel = viewModel,
                onExportTrace = onExportTrace,
                width = maxWidth,
                height = maxHeight,
                modifier = Modifier.fillMaxSize().testTag("unified_flight_screen"),
            )
        }
    }
}

internal fun isPortraitOperationalWindow(width: Dp, height: Dp): Boolean = height > width

@Composable
private fun UnifiedFlightScreen(
    state: DroneSessionState,
    viewModel: DroneDashboardActions,
    onExportTrace: () -> Unit,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val compact = height < 560.dp
    Box(modifier.background(Color.Black)) {
        VideoCanvas(state, viewModel, Modifier.fillMaxSize())
        TopFlightStatusBar(
            state,
            viewModel,
            compact,
            Modifier.align(Alignment.TopCenter).padding(horizontal = 10.dp, vertical = 8.dp),
        )
        TelemetryOverlay(
            state,
            compact,
            Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = if (compact) 68.dp else 86.dp)
                .width(if (compact) 190.dp else 238.dp),
        )
        TrackingOverlay(
            state,
            viewModel,
            onExportTrace,
            compact,
            Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = if (compact) 68.dp else 86.dp)
                .width(if (compact) 216.dp else 258.dp),
        )
        TwoThumbControls(
            state,
            viewModel,
            joystickDiameter(width, height),
            compact,
            Modifier.fillMaxSize(),
        )
        AirborneBatteryWarningBanner(
            state,
            Modifier.align(Alignment.TopCenter).padding(top = if (compact) 66.dp else 84.dp),
        )
    }
}

internal fun joystickDiameter(width: Dp, height: Dp): Dp =
    minOf(240.dp, maxOf(126.dp, minOf(width * 0.18f, height * 0.31f)))

@Composable
private fun TopFlightStatusBar(
    state: DroneSessionState,
    viewModel: DroneDashboardActions,
    compact: Boolean,
    modifier: Modifier = Modifier,
) = Surface(
    color = hudBackground,
    shape = hudShape,
    border = BorderStroke(1.dp, hudBorder),
    modifier = modifier.fillMaxWidth().height(if (compact) 52.dp else 64.dp).testTag("top_flight_status_bar"),
) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = if (compact) 8.dp else 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.widthIn(min = if (compact) 82.dp else 140.dp),
        ) {
            Box(Modifier.size(10.dp).background(connectionColor(state.connection), CircleShape))
            Text("TELLO", color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (compact) 13.sp else 16.sp)
            if (!compact && state.connection == DroneConnectionState.Connected) {
                Text("192.168.10.1", color = TelloTextMuted, fontSize = 12.sp)
            }
            Icon(Icons.Default.Wifi, null, tint = connectionColor(state.connection), modifier = Modifier.size(18.dp))
        }
        HeaderBattery(state, compact)
        if (!compact && state.video.availability == VideoAvailability.Streaming) {
            Icon(Icons.Default.Hd, "HD video", tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.weight(1f))
        TrackingHeaderChip(state, compact)
        SpeedModeSelector(state.speedPercent, viewModel::setSpeed, compact)
        EmergencyHoldButton(
            state.canEmergency(),
            viewModel::emergencyMotorKill,
            compact,
            Modifier.width(if (compact) 126.dp else 164.dp).fillMaxHeight(),
        )
        ConnectionStatusOrAction(state, viewModel, compact)
        if (Build.VERSION.SDK_INT == 28 && state.connection != DroneConnectionState.Connected) WifiSettingsButton()
    }
}

@Composable
private fun HeaderBattery(state: DroneSessionState, compact: Boolean) = Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(3.dp),
) {
    Icon(Icons.Default.BatteryStd, null, tint = if (state.telemetry.isFresh) TelloGreen else TelloTextMuted, modifier = Modifier.size(18.dp))
    Text(
        telemetryValue(state) { it.batteryPercent?.let { value -> "$value%" } },
        color = Color.White,
        fontSize = if (compact) 11.sp else 13.sp,
        maxLines = 1,
    )
}

@Composable
private fun TrackingHeaderChip(state: DroneSessionState, compact: Boolean) = Surface(
    color = Color.White.copy(alpha = .035f),
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, hudBorder),
) {
    Row(
        Modifier.padding(horizontal = if (compact) 7.dp else 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Icons.Default.MyLocation, null, tint = Color.White, modifier = Modifier.size(17.dp))
        Text(
            if (compact) trackingHudLabel(state) else "Tracking: ${trackingHudLabel(state)}",
            color = Color.White,
            fontSize = if (compact) 11.sp else 12.sp,
            maxLines = 1,
        )
    }
}

internal fun trackingHudLabel(state: DroneSessionState): String = state.trackingHudState().label

@Composable
private fun SpeedModeSelector(selectedPercent: Int, onSelected: (Int) -> Unit, compact: Boolean) = Surface(
    color = Color.Black.copy(alpha = .2f),
    shape = RoundedCornerShape(9.dp),
    border = BorderStroke(1.dp, hudBorder),
    modifier = Modifier.testTag("speed_mode_selector"),
) {
    Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        RcSpeedMode.entries.forEach { mode ->
            val selected = RcSpeedMode.fromPercent(selectedPercent) == mode
            Surface(
                color = if (selected) activeBlue else Color.Transparent,
                shape = RoundedCornerShape(7.dp),
                modifier = Modifier
                    .heightIn(min = if (compact) 40.dp else 44.dp)
                    .clickable { onSelected(mode.percent) }
                    .testTag("speed_${mode.name.lowercase()}"),
            ) {
                Text(
                    if (compact) "${mode.percent}%" else "${mode.name}  ${mode.percent}%",
                    color = Color.White,
                    fontSize = if (compact) 10.sp else 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = if (compact) 6.dp else 9.dp, vertical = 7.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusOrAction(state: DroneSessionState, vm: DroneDashboardActions, compact: Boolean) {
    val content: @Composable () -> Unit = {
        Box(Modifier.size(8.dp).background(connectionColor(state.connection), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            connectionLabel(state.connection),
            color = connectionColor(state.connection),
            fontSize = if (compact) 10.sp else 11.sp,
            maxLines = 1,
        )
    }
    if (state.connection in setOf(DroneConnectionState.Disconnected, DroneConnectionState.Error)) {
        OutlinedButton(
            onClick = vm::connect,
            border = BorderStroke(1.dp, connectionColor(state.connection).copy(alpha = .65f)),
            shape = RoundedCornerShape(9.dp),
            contentPadding = PaddingValues(horizontal = if (compact) 7.dp else 10.dp),
            modifier = Modifier.height(if (compact) 40.dp else 44.dp).testTag("connection_action"),
        ) { content() }
    } else {
        Surface(
            color = if (state.connection == DroneConnectionState.Connected) TelloGreen.copy(alpha = .08f) else Color.Transparent,
            shape = RoundedCornerShape(9.dp),
            border = BorderStroke(1.dp, connectionColor(state.connection).copy(alpha = .4f)),
            modifier = Modifier.height(if (compact) 40.dp else 44.dp).testTag("connection_status"),
        ) {
            Row(
                Modifier.padding(horizontal = if (compact) 7.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { content() }
        }
    }
}

@Composable
private fun WifiSettingsButton() {
    val context = LocalContext.current
    IconButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }, modifier = Modifier.size(38.dp)) {
        Icon(Icons.Default.Settings, "Wi-Fi settings", tint = TelloTextMuted)
    }
}

@Composable
private fun TelemetryOverlay(state: DroneSessionState, compact: Boolean, modifier: Modifier = Modifier) = HudPanel(modifier.testTag("telemetry_overlay")) {
    PanelTitle("STATUS")
    TelemetryLine("Battery", telemetryValue(state) { it.batteryPercent?.let { value -> "$value%" } }, if (state.telemetry.isFresh) TelloGreen else TelloTextMuted)
    TelemetryLine("Altitude", telemetryValue(state) { it.heightMeters?.let { value -> "%.1f m".format(value) } })
    TelemetryLine("Speed", telemetrySpeedValue(state.telemetry))
    if (!compact) {
        TelemetryLine("Flight Time", telemetryValue(state) { it.flightTimeSeconds?.let(::formatTime) })
        TelemetryLine("Temperature", telemetryValue(state) { it.temperatureCelsius?.let { value -> "%.0f °C".format(value) } })
    }
}

@Composable
private fun TrackingOverlay(
    state: DroneSessionState,
    viewModel: DroneDashboardActions,
    onExportTrace: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) = HudPanel(modifier.testTag("tracking_overlay")) {
    val presentation = state.trackingUiPresentation()
    PanelTitle("TRACKING")
    val hudLabel = trackingHudLabel(state)
    TelemetryLine("Status", hudLabel, when (hudLabel) {
        "ACTIVE", "TARGET READY" -> TelloGreen
        "LOST" -> TelloRed
        "SEARCHING" -> Color(0xFFFFC857)
        else -> TelloTextMuted
    })
    if (!compact) {
        TelemetryLine("Target", presentation.target.value, presentation.target.color)
        TelemetryLine("Yaw Follow", presentation.yaw.value, presentation.yaw.color)
    }
    when (presentation.primaryAction) {
        TrackingPrimaryAction.DetectPeople -> TrackingPrimaryButton("Detect People", state.canStartDetection()) { viewModel.setTrackingMode(TrackingMode.DetectOnly) }
        TrackingPrimaryAction.StartFollow -> TrackingPrimaryButton("Start Follow", state.canStartFollow()) { viewModel.setYawFollowArmed(true) }
        TrackingPrimaryAction.RearmFollow -> TrackingPrimaryButton("Re-arm Follow", state.canStartFollow()) { viewModel.setYawFollowArmed(true) }
        TrackingPrimaryAction.StopFollow -> TrackingPrimaryButton("Stop Follow", state.connection == DroneConnectionState.Connected && state.flight == FlightState.Flying) { viewModel.setYawFollowArmed(false) }
        TrackingPrimaryAction.None -> Unit
    }
    presentation.instruction?.let { instruction ->
        Text(
            instruction,
            color = TelloTextMuted,
            fontSize = if (compact) 9.sp else 10.sp,
            lineHeight = 13.sp,
            modifier = Modifier.testTag("tracking_instruction"),
        )
    }
    if (presentation.showStopDetection && presentation.primaryAction != TrackingPrimaryAction.StopFollow) {
        TextButton(
            onClick = { viewModel.setTrackingMode(TrackingMode.Off) },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("stop_detection"),
        ) {
            Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Stop detection", fontSize = 11.sp)
        }
    }
    if (com.alonibh.tellodrone.BuildConfig.DEBUG) {
        TextButton(
            onClick = onExportTrace,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).testTag("export_trace"),
        ) {
            Icon(Icons.Default.Share, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Export Trace", fontSize = 11.sp)
        }
    }
}

@Composable
private fun TrackingPrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) = Button(
    onClick = onClick,
    enabled = enabled,
    colors = ButtonDefaults.buttonColors(containerColor = activeBlue, disabledContainerColor = TelloLine.copy(alpha = .6f)),
    shape = RoundedCornerShape(9.dp),
    modifier = Modifier.fillMaxWidth().height(44.dp).testTag("tracking_primary_action"),
) {
    Icon(Icons.Default.PersonSearch, null, modifier = Modifier.size(17.dp))
    Spacer(Modifier.width(6.dp))
    Text(label, fontSize = 12.sp, maxLines = 1)
}

@Composable
private fun HudPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Surface(
    color = hudBackground,
    shape = hudShape,
    border = BorderStroke(1.dp, hudBorder),
    modifier = modifier,
) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun PanelTitle(title: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Icon(if (title == "TRACKING") Icons.Default.MyLocation else Icons.Default.Wifi, null, tint = Color.White, modifier = Modifier.size(19.dp))
    Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
}

@Composable
private fun TelemetryLine(label: String, value: String, color: Color = Color.White) = Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(label, color = Color.White.copy(alpha = .86f), fontSize = 11.sp)
    Text(value, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
}

@Composable
private fun VideoCanvas(
    state: DroneSessionState,
    viewModel: DroneDashboardActions,
    modifier: Modifier = Modifier,
) = BoxWithConstraints(modifier.background(Color.Black).testTag("video_canvas")) {
    val frame = VideoOverlayCoordinateMapper.aspectFitFrame(
        maxWidth.value,
        maxHeight.value,
        TELLO_VIDEO_WIDTH.toFloat(),
        TELLO_VIDEO_HEIGHT.toFloat(),
    )
    Box(
        Modifier.offset(frame.left.dp, frame.top.dp).size(frame.width.dp, frame.height.dp)
            .background(Color(0xFF171B1D)).testTag("aspect_fit_video"),
    ) {
        TelloVideoSurface(viewModel)
    }
    DetectionOverlay(state, viewModel, maxWidth.value, maxHeight.value)
    val message = when {
        state.video.availability == VideoAvailability.Error -> "VIDEO UNAVAILABLE\n${state.video.errorReason ?: "Video pipeline error"}"
        state.connection in setOf(DroneConnectionState.Connecting, DroneConnectionState.Connected) &&
            state.video.availability == VideoAvailability.Unavailable -> "STARTING VIDEO…"
        state.video.availability == VideoAvailability.Streaming -> null
        else -> "NO VIDEO / WAITING"
    }
    message?.let {
        Text(
            it,
            color = if (state.video.availability == VideoAvailability.Error) TelloRed else TelloTextMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .58f), RoundedCornerShape(8.dp)).padding(10.dp),
        )
    }
}

@Composable
private fun DetectionOverlay(state: DroneSessionState, vm: DroneDashboardActions, overlayWidth: Float, overlayHeight: Float) {
    Box(
        Modifier.fillMaxSize().pointerInput(state.video.processedDetectorFrameSequence) {
            detectTapGestures { offsetPx ->
                val point = VideoOverlayCoordinateMapper.mapPixelTapToNormalized(
                    tapX = offsetPx.x,
                    tapY = offsetPx.y,
                    containerWidth = size.width.toFloat(),
                    containerHeight = size.height.toFloat(),
                    sourceWidth = TELLO_VIDEO_WIDTH.toFloat(),
                    sourceHeight = TELLO_VIDEO_HEIGHT.toFloat(),
                )
                if (point != null) {
                    vm.selectTargetAt(point.normalizedX, point.normalizedY, state.video.processedDetectorFrameSequence)
                }
            }
        }
    ) {
        state.targetOverlayPresentation()?.let { presentation ->
            val color = when (presentation.kind) {
                TargetOverlayKind.Current -> TelloGreen
                TargetOverlayKind.LastSeenMissing -> Color(0xFFFFC857)
                TargetOverlayKind.IdentityUncertain -> TelloRed
            }
            DetectionBox(presentation.target.boundingBox, presentation.label, color, overlayWidth, overlayHeight)
        }
        state.personDetections.filterNot(state::isCurrentTargetDetection).forEachIndexed { index, detection ->
            val selectable = state.connection == DroneConnectionState.Connected &&
                state.video.availability == VideoAvailability.Streaming &&
                state.video.personDetectionState == PersonDetectionState.Detecting &&
                state.video.processedDetectorFrameSequence == detection.frameSequence &&
                state.video.processedDetectorSourceTimestampNanos == detection.sourceTimestampNanos
            val centerX = (detection.boundingBox.left + detection.boundingBox.right) / 2f
            val centerY = (detection.boundingBox.top + detection.boundingBox.bottom) / 2f
            DetectionBox(
                detection.boundingBox,
                "PERSON ${(detection.confidence * 100f).roundToInt()}%",
                Color(0xFFFFC857),
                overlayWidth,
                overlayHeight,
                Modifier.clickable(enabled = selectable) {
                    vm.selectTargetAt(centerX, centerY, detection.frameSequence)
                }.testTag("person_detection_$index"),
            )
        }
    }
}

@Composable
private fun DetectionBox(
    box: com.alonibh.tellodrone.domain.NormalizedBoundingBox,
    label: String,
    color: Color,
    overlayWidth: Float,
    overlayHeight: Float,
    modifier: Modifier = Modifier,
) {
    val mapped = VideoOverlayCoordinateMapper.mapAspectFit(
        box,
        overlayWidth,
        overlayHeight,
        TELLO_VIDEO_WIDTH.toFloat(),
        TELLO_VIDEO_HEIGHT.toFloat(),
    ) ?: return
    Box(Modifier.offset(mapped.left.dp, mapped.top.dp)) {
        Box(
            modifier.size((mapped.right - mapped.left).dp, (mapped.bottom - mapped.top).dp)
                .border(2.dp, color, RoundedCornerShape(3.dp)),
        )
        Text(
            label,
            color = TelloInk,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.background(color).padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun TelloVideoSurface(vm: DroneDashboardActions) {
    AndroidView(
        factory = { context -> TelloVideoSurfaceView(context, vm) },
        modifier = Modifier.fillMaxSize(),
        onRelease = { it.dispose() },
    )
}

@SuppressLint("ViewConstructor")
private class TelloVideoSurfaceView(context: Context, private val actions: DroneDashboardActions) : SurfaceView(context), SurfaceHolder.Callback {
    private var attached = false

    init {
        setZOrderOnTop(false)
        holder.setFixedSize(TELLO_VIDEO_WIDTH, TELLO_VIDEO_HEIGHT)
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attached = true
        actions.attachVideoSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (attached) actions.detachVideoSurface(holder.surface)
        attached = false
    }

    fun dispose() {
        if (attached) actions.detachVideoSurface(holder.surface)
        attached = false
        holder.removeCallback(this)
    }
}

@Composable
private fun TwoThumbControls(
    state: DroneSessionState,
    viewModel: DroneDashboardActions,
    diameter: Dp,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val enabled = state.connection == DroneConnectionState.Connected && state.flight == FlightState.Flying && state.telemetry.isFresh
    var leftStick by remember { mutableStateOf(JoystickVector()) }
    var rightStick by remember { mutableStateOf(JoystickVector()) }
    fun publish() = viewModel.setManualVector(manualVectorFromSticks(leftStick, rightStick))
    DisposableEffect(enabled) { onDispose { if (enabled) viewModel.setManualVector(ManualControlVector()) } }
    Box(modifier) {
        VirtualJoystick(
            leftStick,
            enabled,
            diameter,
            { leftStick = it; publish() },
            ::publish,
            { leftStick = JoystickVector(); publish() },
            Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp).testTag("left_joystick"),
        )
        VirtualJoystick(
            rightStick,
            enabled,
            diameter,
            { rightStick = it; publish() },
            ::publish,
            { rightStick = JoystickVector(); publish() },
            Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 4.dp).testTag("right_joystick"),
        )
        FlightActionControls(
            state,
            viewModel,
            compact,
            Modifier.align(Alignment.BottomCenter).padding(bottom = if (compact) 10.dp else 22.dp),
        )
    }
}

@Composable
private fun FlightActionControls(
    state: DroneSessionState,
    vm: DroneDashboardActions,
    compact: Boolean,
    modifier: Modifier = Modifier,
) = Column(
    modifier.width(if (compact) 300.dp else 440.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(7.dp),
) {
    StopHoverAction(state, vm, Modifier.width(if (compact) 176.dp else 218.dp).height(if (compact) 44.dp else 48.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TakeoffAction(state, vm, Modifier.weight(1f).height(if (compact) 44.dp else 58.dp))
        LandAction(state, vm, Modifier.weight(1f).height(if (compact) 44.dp else 58.dp))
    }
}

@Composable
private fun TakeoffAction(state: DroneSessionState, vm: DroneDashboardActions, modifier: Modifier) {
    val gate = remember { TakeoffConfirmationGate() }
    var dialogVisible by remember { mutableStateOf(false) }
    val eligible = state.isTakeoffEligible()
    LaunchedEffect(eligible, state.flight) { if (!gate.dismissIfIneligible(state)) dialogVisible = false }
    Button(
        onClick = { dialogVisible = gate.request(state) },
        enabled = eligible,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25A43B), disabledContainerColor = Color(0xFF1D4627)),
        modifier = modifier.testTag("take_off"),
    ) {
        Icon(Icons.Default.ArrowUpward, null)
        Spacer(Modifier.width(7.dp))
        Text(if (state.flight == FlightState.TakingOff) "Taking Off…" else "Take Off", maxLines = 1)
    }
    if (dialogVisible && eligible) {
        AlertDialog(
            onDismissRequest = { gate.cancel(); dialogVisible = false },
            title = { Text("Confirm takeoff") },
            text = { Text("Make sure the area above and around the drone is clear. The drone will take off and hover.") },
            dismissButton = { TextButton(onClick = { gate.cancel(); dialogVisible = false }) { Text("CANCEL") } },
            confirmButton = {
                TextButton(onClick = {
                    val confirmed = gate.confirm(state, vm::takeOff)
                    dialogVisible = false
                    if (!confirmed) gate.cancel()
                }) { Text("TAKE OFF") }
            },
        )
    }
}

@Composable
private fun LandAction(state: DroneSessionState, vm: DroneDashboardActions, modifier: Modifier) {
    val enabled = state.connection == DroneConnectionState.Connected && state.flight in setOf(FlightState.Flying, FlightState.Unknown)
    Button(
        onClick = vm::land,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE2261D), disabledContainerColor = Color(0xFF541F1D)),
        modifier = modifier.testTag("land"),
    ) {
        Icon(Icons.Default.ArrowDownward, null)
        Spacer(Modifier.width(7.dp))
        Text(if (state.flight == FlightState.Landing) "Landing…" else "Land", maxLines = 1)
    }
}

@Composable
private fun StopHoverAction(state: DroneSessionState, vm: DroneDashboardActions, modifier: Modifier) {
    val enabled = state.connection == DroneConnectionState.Connected && state.flight == FlightState.Flying
    OutlinedButton(
        onClick = vm::stopAndHover,
        enabled = enabled,
        border = BorderStroke(1.5.dp, if (state.hoverActive) TelloGreen else Color.White.copy(alpha = .68f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = .80f)),
        shape = RoundedCornerShape(9.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = modifier.testTag("stop_hover"),
    ) {
        Icon(if (state.hoverActive) Icons.Default.CheckCircle else Icons.Default.PauseCircle, null, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (state.hoverActive) "Hover Active" else "STOP / HOVER", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun VirtualJoystick(
    value: JoystickVector,
    enabled: Boolean,
    diameter: Dp,
    onVector: (JoystickVector) -> Unit,
    onHeartbeat: () -> Unit,
    onReleased: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentVector by rememberUpdatedState(onVector)
    val currentHeartbeat by rememberUpdatedState(onHeartbeat)
    val currentReleased by rememberUpdatedState(onReleased)
    val captureInset = if (diameter <= 150.dp) 12.dp else 20.dp
    DisposableEffect(Unit) { onDispose { currentReleased() } }
    Box(
        modifier.size(diameter + captureInset * 2).pointerInput(enabled) {
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
    ) {
        Canvas(Modifier.size(diameter)) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Color(0xCC15181B), radius, center)
            drawCircle(if (enabled) Color.White.copy(alpha = .34f) else TelloLine, radius, center, style = Stroke(width = 3.dp.toPx()))
            drawLine(Color.White.copy(alpha = .18f), Offset(center.x - radius * .62f, center.y), Offset(center.x + radius * .62f, center.y), strokeWidth = 1.dp.toPx())
            drawLine(Color.White.copy(alpha = .18f), Offset(center.x, center.y - radius * .62f), Offset(center.x, center.y + radius * .62f), strokeWidth = 1.dp.toPx())
            val thumb = Offset(center.x + value.horizontal * radius * .68f, center.y - value.vertical * radius * .68f)
            drawCircle(if (enabled) Color(0xFFB7BABC) else Color(0xFF777B7E), radius * .22f, thumb)
            drawCircle(Color.White.copy(alpha = .35f), radius * .22f, thumb, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

@Composable
private fun EmergencyHoldButton(
    enabled: Boolean,
    onTriggered: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(0f) }
    val completion = remember { EmergencyHoldCompletion() }
    val currentTriggered by rememberUpdatedState(onTriggered)
    Surface(
        color = if (enabled) TelloRed.copy(alpha = .13f) else Color.White.copy(alpha = .03f),
        contentColor = if (enabled) TelloRed else TelloTextMuted,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, if (enabled) TelloRed else hudBorder),
        modifier = modifier.pointerInput(enabled) {
            detectTapGestures(onPress = {
                if (enabled) {
                    completion.reset()
                    progress.snapTo(0f)
                    coroutineScope {
                        val hold = launch {
                            progress.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(
                                    durationMillis = EMERGENCY_HOLD_MILLIS.toInt(),
                                    easing = LinearEasing,
                                ),
                            )
                            if (completion.completeOnce()) currentTriggered()
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            hold.cancelAndJoin()
                            progress.snapTo(0f)
                            completion.reset()
                        }
                    }
                }
            })
        }.testTag("emergency_motor_kill"),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 7.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Default.Emergency, null, modifier = Modifier.size(if (compact) 16.dp else 18.dp))
                Text("Emergency Stop", fontSize = if (compact) 9.sp else 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            if (progress.value > 0f) {
                LinearProgressIndicator(
                    progress = { progress.value },
                    color = Color.White,
                    trackColor = Color.Black.copy(alpha = .35f),
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(2.dp),
                )
            }
        }
    }
}

@Composable
private fun AirborneBatteryWarningBanner(state: DroneSessionState, modifier: Modifier = Modifier) {
    if (state.flight !in setOf(FlightState.TakingOff, FlightState.Flying, FlightState.Landing)) return
    val battery = state.telemetry.batteryPercent ?: return
    if (battery > 20) return
    Surface(
        color = if (battery <= 10) TelloRed else Color(0xFFFF9800),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier.testTag(if (battery <= 10) "battery_critical_warning" else "battery_low_warning"),
    ) {
        Text(
            if (battery <= 10) "CRITICAL BATTERY: $battery% • LAND IMMEDIATELY" else "LOW BATTERY: $battery%",
            color = if (battery <= 10) Color.White else Color.Black,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PortraitSafetyFallback(state: DroneSessionState, vm: DroneDashboardActions) = Surface(
    color = TelloInk,
    modifier = Modifier.fillMaxSize().testTag("portrait_safety_fallback"),
) {
    val activeFlight = state.flight in setOf(FlightState.TakingOff, FlightState.Flying, FlightState.Landing, FlightState.Unknown)
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Rotate device to landscape", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            if (activeFlight) "Landscape controls are unavailable in this window. Safety controls remain available."
            else "The operational dashboard is landscape-first.",
            color = TelloTextMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
        )
        if (activeFlight) {
            StopHoverAction(state, vm, Modifier.fillMaxWidth().height(48.dp))
            Spacer(Modifier.height(10.dp))
            LandAction(state, vm, Modifier.fillMaxWidth().height(48.dp))
            Spacer(Modifier.height(10.dp))
            EmergencyHoldButton(state.canEmergency(), vm::emergencyMotorKill, false, Modifier.fillMaxWidth().height(76.dp))
        }
    }
}

internal fun DroneSessionState.isCurrentTargetDetection(detection: PersonDetection): Boolean = target?.let {
    detection.frameSequence == it.lastSeenFrameSequence && detection.sourceTimestampNanos == it.lastSeenSourceTimestampNanos &&
        detection.boundingBox == it.boundingBox
} == true

internal enum class TargetOverlayKind { Current, LastSeenMissing, IdentityUncertain }

internal data class TargetOverlayPresentation(
    val target: com.alonibh.tellodrone.domain.TrackedTarget,
    val kind: TargetOverlayKind,
    val label: String,
)

internal fun DroneSessionState.targetOverlayPresentation(): TargetOverlayPresentation? {
    val currentTarget = target ?: return null
    return when (targetAssociationState) {
        TargetAssociationState.Selected, TargetAssociationState.Matched -> TargetOverlayPresentation(currentTarget, TargetOverlayKind.Current, "TARGET SELECTED")
        TargetAssociationState.TemporarilyMissing -> TargetOverlayPresentation(currentTarget, TargetOverlayKind.LastSeenMissing, "LAST SEEN • MISSING")
        TargetAssociationState.Ambiguous -> TargetOverlayPresentation(currentTarget, TargetOverlayKind.IdentityUncertain, "IDENTITY UNCERTAIN")
        TargetAssociationState.None, TargetAssociationState.Lost -> null
    }
}

private fun connectionColor(state: DroneConnectionState) = when (state) {
    DroneConnectionState.Connected -> TelloGreen
    DroneConnectionState.Error -> TelloRed
    else -> TelloTextMuted
}

private fun connectionLabel(state: DroneConnectionState) = when (state) {
    DroneConnectionState.Connected -> "Connected"
    DroneConnectionState.Connecting -> "Connecting"
    DroneConnectionState.AwaitingPermission -> "Permission"
    DroneConnectionState.Disconnected -> "Connect"
    DroneConnectionState.Error -> "Retry"
}

private fun formatTime(totalSeconds: Int) = "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)

private fun telemetryValue(state: DroneSessionState, value: (com.alonibh.tellodrone.domain.TelemetrySnapshot) -> String?): String =
    if (!state.telemetry.isFresh) "—" else value(state.telemetry) ?: "—"

internal fun formatTelemetrySpeed(metersPerSecond: Float): String =
    if (metersPerSecond in 0f..<0.1f && metersPerSecond != 0f) "%.2f m/s".format(metersPerSecond)
    else "%.1f m/s".format(metersPerSecond)

internal fun telemetrySpeedValue(telemetry: com.alonibh.tellodrone.domain.TelemetrySnapshot): String =
    if (!telemetry.isFresh) "—" else telemetry.speedMetersPerSecond?.let(::formatTelemetrySpeed) ?: "—"

private fun DroneSessionState.canEmergency() = connection == DroneConnectionState.Connected &&
    flight in setOf(FlightState.TakingOff, FlightState.Flying, FlightState.Landing, FlightState.Unknown)

private const val TELLO_VIDEO_WIDTH = 960
private const val TELLO_VIDEO_HEIGHT = 720
private const val MANUAL_HEARTBEAT_MILLIS = 100L
internal const val EMERGENCY_HOLD_MILLIS = 900L

internal class EmergencyHoldCompletion {
    private var completed = false

    fun completeOnce(): Boolean {
        if (completed) return false
        completed = true
        return true
    }

    fun reset() {
        completed = false
    }
}
