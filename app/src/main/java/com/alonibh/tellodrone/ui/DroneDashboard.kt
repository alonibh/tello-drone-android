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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.alonibh.tellodrone.TelloGreen
import com.alonibh.tellodrone.TelloGreenDark
import com.alonibh.tellodrone.TelloInk
import com.alonibh.tellodrone.TelloLine
import com.alonibh.tellodrone.TelloPanel
import com.alonibh.tellodrone.TelloPanelRaised
import com.alonibh.tellodrone.TelloRed
import com.alonibh.tellodrone.TelloTextMuted
import com.alonibh.tellodrone.domain.DetectorBackend
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
import com.alonibh.tellodrone.vision.VisionSessionControls
import com.alonibh.tellodrone.vision.VisionTraceFeature
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

internal enum class OperationalTab(val label: String) {
    Flight("FLIGHT"),
    Tracking("TRACKING"),
    Status("STATUS");

    companion object {
        fun from(destination: String): OperationalTab = when (destination.uppercase()) {
            "TRACKING" -> Tracking
            "STATUS" -> Status
            else -> Flight
        }
    }
}

@Composable
fun DroneDashboard(
    state: DroneSessionState,
    viewModel: DroneDashboardActions,
    modifier: Modifier = Modifier,
    initialDestination: String = "FLIGHT",
) {
    var destination by remember { mutableStateOf(initialDestination) }
    val videoSurface = remember(viewModel) {
        movableContentOf { TelloVideoSurface(viewModel) }
    }
    BoxWithConstraints(
        modifier.fillMaxSize().background(TelloInk)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(12.dp),
    ) {
        if (isPortraitOperationalWindow(maxWidth, maxHeight)) {
            PortraitSafetyFallback(state, viewModel)
        } else {
            when (windowLayout(maxWidth, maxHeight)) {
                WindowLayout.Expanded -> ExpandedDashboard(state, viewModel, destination, videoSurface) { destination = it }
                WindowLayout.Medium -> MediumDashboard(state, viewModel, destination, videoSurface) { destination = it }
                WindowLayout.CompactHeight -> LandscapeDashboard(state, viewModel, destination, videoSurface, { destination = it })
                WindowLayout.Compact -> CompactDashboard(state, viewModel, destination, videoSurface) { destination = it }
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
    "Tracking", "TRACKING" -> CompactHeightContent.Tracking
    "Media" -> CompactHeightContent.Media
    "Status", "STATUS" -> CompactHeightContent.Status
    else -> CompactHeightContent.Dashboard
}

@Composable
private fun PortraitSafetyFallback(state: DroneSessionState, vm: DroneDashboardActions) = Surface(
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
private fun AirborneBatteryWarningBanner(state: DroneSessionState, modifier: Modifier = Modifier) {
    val isAirborne = state.flight in setOf(FlightState.TakingOff, FlightState.Flying, FlightState.Landing)
    if (!isAirborne) return
    val battery = state.telemetry.batteryPercent ?: return
    when {
        battery <= 10 -> {
            Surface(
                color = TelloRed,
                shape = RoundedCornerShape(6.dp),
                modifier = modifier.testTag("battery_critical_warning"),
            ) {
                Text(
                    "CRITICAL BATTERY: $battery% • LAND IMMEDIATELY",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        battery <= 20 -> {
            Surface(
                color = Color(0xFFFF9800),
                shape = RoundedCornerShape(6.dp),
                modifier = modifier.testTag("battery_low_warning"),
            ) {
                Text(
                    "LOW BATTERY: $battery%",
                    color = Color.Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun OperationalNavTabs(
    selectedTab: OperationalTab,
    onTabSelected: (OperationalTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = TelloPanel,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.testTag("operational_navigation"),
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OperationalTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                Surface(
                    color = if (selected) TelloGreenDark.copy(alpha = 0.65f) else Color.Transparent,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .clickable { onTabSelected(tab) }
                        .testTag("nav_tab_${tab.name.lowercase()}"),
                ) {
                    Text(
                        tab.label,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) TelloGreen else TelloTextMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedHeader(
    state: DroneSessionState,
    vm: DroneDashboardActions,
    selectedTab: OperationalTab,
    onTabSelected: (OperationalTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = panelShape,
        color = TelloPanelRaised,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Brand(state, Modifier.widthIn(min = 140.dp))
            OperationalNavTabs(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HeaderMetric("BATTERY", telemetryValue(state) { it.batteryPercent?.let { value -> "$value%" } })
                AirborneBatteryWarningBanner(state)
                HeaderMetric("HEIGHT", telemetryValue(state) { it.heightMeters?.let { value -> "%.1f m".format(value) } })
                HeaderMetric("SPEED", telemetryValue(state) { it.speedMetersPerSecond?.let { value -> "%.1f m/s".format(value) } })
                HeaderMetric("TIME", telemetryValue(state) { it.flightTimeSeconds?.let(::formatTime) })
            }
            Spacer(Modifier.weight(1f))
            ConnectionPrimaryButton(state, vm)
            if (Build.VERSION.SDK_INT == 28 && state.connection != DroneConnectionState.Connected) {
                WifiSettingsButton()
            }
            EmergencyHoldButton(
                enabled = state.canEmergency(),
                onTriggered = vm::emergencyMotorKill,
                modifier = Modifier.width(170.dp).height(44.dp),
                compact = true,
            )
        }
    }
}

@Composable
private fun ExpandedDashboard(
    state: DroneSessionState,
    vm: DroneDashboardActions,
    destination: String,
    videoSurface: @Composable () -> Unit,
    onDestination: (String) -> Unit,
) {
    val currentTab = OperationalTab.from(destination)
    Column(
        Modifier.fillMaxSize().testTag("layout_expanded"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ExpandedHeader(
            state = state,
            vm = vm,
            selectedTab = currentTab,
            onTabSelected = { onDestination(it.label) },
        )
        VideoPanel(
            state = state,
            vm = vm,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("expanded_dashboard_video"),
            videoSurface = videoSurface,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(
                    when (currentTab) {
                        OperationalTab.Flight -> 250.dp
                        OperationalTab.Tracking -> 150.dp
                        OperationalTab.Status -> 190.dp
                    },
                ),
        ) {
            when (currentTab) {
                OperationalTab.Flight -> {
                    ExpandedTwoThumbFlightControls(
                        state = state,
                        vm = vm,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                OperationalTab.Tracking -> {
                    ExpandedTrackingControls(
                        state = state,
                        vm = vm,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                OperationalTab.Status -> {
                    ExpandedStatusControls(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedTwoThumbFlightControls(
    state: DroneSessionState,
    vm: DroneDashboardActions,
    modifier: Modifier = Modifier,
) {
    val enabled = state.connection == DroneConnectionState.Connected &&
        state.flight == FlightState.Flying &&
        state.telemetry.isFresh
    var leftStick by remember { mutableStateOf(JoystickVector()) }
    var rightStick by remember { mutableStateOf(JoystickVector()) }
    fun publish() {
        vm.setManualVector(manualVectorFromSticks(leftStick, rightStick))
    }
    DisposableEffect(enabled) {
        onDispose { if (enabled) vm.setManualVector(ManualControlVector()) }
    }

    val stickDiameter = 190.dp

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.testTag("left_joystick").padding(start = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            VirtualJoystick(
                label = "ALT / YAW",
                value = leftStick,
                enabled = enabled,
                diameter = stickDiameter,
                onVector = { leftStick = it; publish() },
                onHeartbeat = { publish() },
                onReleased = { leftStick = JoystickVector(); publish() },
            )
        }

        ExpandedTabletFlightCenter(
            state = state,
            vm = vm,
            modifier = Modifier.width(420.dp),
        )

        Box(
            modifier = Modifier.testTag("right_joystick").padding(end = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            VirtualJoystick(
                label = "ROLL / PITCH",
                value = rightStick,
                enabled = enabled,
                diameter = stickDiameter,
                onVector = { rightStick = it; publish() },
                onHeartbeat = { publish() },
                onReleased = { rightStick = JoystickVector(); publish() },
            )
        }
    }
}

@Composable
private fun ExpandedTabletFlightCenter(
    state: DroneSessionState,
    vm: DroneDashboardActions,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.testTag("center_flight_controls"),
        colors = CardDefaults.cardColors(containerColor = TelloPanel),
        shape = panelShape,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.flight == FlightState.Grounded -> {
                    Text(
                        if (state.telemetry.isFresh) "READY FOR TAKEOFF" else connectionLabel(state.connection).uppercase(),
                        color = if (state.telemetry.isFresh) TelloGreen else TelloTextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TakeoffAction(state, vm, Modifier.fillMaxWidth().height(48.dp))
                    FlightActionHint(state)
                }
                state.flight in setOf(FlightState.Flying, FlightState.TakingOff, FlightState.Landing) -> {
                    HoverAction(state, vm, Modifier.fillMaxWidth().height(48.dp).testTag("stop_hover"))
                    if (state.hoverActive) HoverActiveStatus()
                    LandAction(state, vm, Modifier.fillMaxWidth().height(40.dp), compact = true)
                    CompactCenterSpeedControl(state, vm, Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                    FlightActionHint(state)
                }
                else -> {
                    Text(connectionLabel(state.connection).uppercase(), color = connectionColor(state.connection), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    TakeoffAction(state, vm, Modifier.fillMaxWidth().height(48.dp))
                    FlightActionHint(state)
                }
            }
        }
    }
}

@Composable
private fun CompactCenterSpeedControl(
    state: DroneSessionState,
    vm: DroneDashboardActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("SPEED", fontSize = 11.sp, color = TelloTextMuted, fontWeight = FontWeight.Medium)
        Slider(
            value = state.speedPercent.toFloat(),
            onValueChange = { vm.setSpeed(it.roundToInt()) },
            valueRange = 10f..40f,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${state.speedPercent}%",
            color = TelloGreen,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ExpandedTrackingControls(
    state: DroneSessionState,
    vm: DroneDashboardActions,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TelloPanel),
        shape = panelShape,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1.2f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val presentation = state.trackingUiPresentation()
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusLine("Detection", presentation.detection.value, presentation.detection.color)
                    StatusLine("Target", presentation.target.value, presentation.target.color)
                    StatusLine("Yaw", presentation.yaw.value, presentation.yaw.color)
                    Surface(
                        color = TelloRed.copy(alpha = .10f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.testTag("yaw_only_badge"),
                    ) {
                        Text(
                            "YAW ONLY",
                            color = TelloRed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
                presentation.instruction?.let {
                    Text(it, color = TelloTextMuted, fontSize = 12.sp)
                }
            }

            Column(
                modifier = Modifier.weight(0.8f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val presentation = state.trackingUiPresentation()
                val canStart = state.connection == DroneConnectionState.Connected &&
                    state.video.availability == VideoAvailability.Streaming &&
                    state.video.analysisLatestSequence != null

                when (presentation.primaryAction) {
                    TrackingPrimaryAction.StartDetection -> ActionButton("START DETECTION", Icons.Default.PersonSearch, canStart, { vm.setTrackingMode(TrackingMode.DetectOnly) }, Modifier.fillMaxWidth())
                    TrackingPrimaryAction.ArmYawFollow -> ActionButton("ARM YAW FOLLOW", Icons.Default.CheckCircle, true, { vm.setYawFollowArmed(true) }, Modifier.fillMaxWidth().testTag("arm_yaw_follow"))
                    TrackingPrimaryAction.RearmYawFollow -> ActionButton("RE-ARM YAW FOLLOW", Icons.Default.CheckCircle, true, { vm.setYawFollowArmed(true) }, Modifier.fillMaxWidth().testTag("arm_yaw_follow"))
                    TrackingPrimaryAction.DisarmYawFollow -> ActionButton("DISARM YAW FOLLOW", Icons.Default.Close, true, { vm.setYawFollowArmed(false) }, Modifier.fillMaxWidth().testTag("disarm_yaw_follow"), active = true)
                    TrackingPrimaryAction.None -> Unit
                }
                if (presentation.showStopDetection) {
                    OutlineAction("STOP DETECTION", Icons.Default.Close, true, { vm.setTrackingMode(TrackingMode.Off) }, Modifier.fillMaxWidth())
                }
                if (state.flight == FlightState.Flying) {
                    HoverAction(state, vm, Modifier.fillMaxWidth().testTag("stop_hover"), compact = true)
                }
            }
        }
    }
}

@Composable
private fun ExpandedStatusControls(
    state: DroneSessionState,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TelloPanel),
        shape = panelShape,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item {
                StatusPanel(state)
            }
        }
    }
}

@Composable
private fun CompactDashboard(
    state: DroneSessionState,
    vm: DroneDashboardActions,
    destination: String,
    videoSurface: @Composable () -> Unit,
    onDestination: (String) -> Unit,
) {
    val currentTab = OperationalTab.from(destination)
    LazyColumn(
        modifier = Modifier.testTag("layout_compact"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item { Header(state, vm, expanded = false) }
        item { CompactNav(destination, onDestination) }
        item { VideoPanel(state, vm, Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 460.dp), videoSurface) }
        when (currentTab) {
            OperationalTab.Flight -> {
                item { CriticalFlightControls(state, vm) }
                item { CompactFutureControlsNotice() }
                item { BottomControls(state, vm) }
                item { StatusPanel(state) }
                item { EmergencyHoldButton(state.canEmergency(), vm::emergencyMotorKill, Modifier.fillMaxWidth()) }
            }
            OperationalTab.Tracking -> {
                item { TrackingControls(state, vm) }
            }
            OperationalTab.Status -> {
                item { StatusPanel(state, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun MediumDashboard(
    state: DroneSessionState,
    vm: DroneDashboardActions,
    destination: String,
    videoSurface: @Composable () -> Unit,
    onDestination: (String) -> Unit,
) {
    val currentTab = OperationalTab.from(destination)
    Column(Modifier.testTag("layout_medium"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header(state, vm, expanded = false)
        CompactNav(destination, onDestination)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VideoPanel(state, vm, Modifier.weight(1.25f).fillMaxHeight(), videoSurface)
            Box(Modifier.weight(0.75f).fillMaxHeight()) {
                when (currentTab) {
                    OperationalTab.Flight -> {
                        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            item { CriticalFlightControls(state, vm) }
                            item { EmergencyHoldButton(state.canEmergency(), vm::emergencyMotorKill, Modifier.fillMaxWidth()) }
                            item { TrackingControls(state, vm) }
                            item { StatusPanel(state) }
                        }
                    }
                    OperationalTab.Tracking -> {
                        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            item { TrackingControls(state, vm) }
                        }
                    }
                    OperationalTab.Status -> StatusPanel(state, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun LandscapeDashboard(
    state: DroneSessionState,
    vm: DroneDashboardActions,
    destination: String,
    videoSurface: @Composable () -> Unit,
    onDestination: (String) -> Unit,
    showApi28WifiAction: Boolean = needsCompactWifiAction(state),
) {
    Column(Modifier.testTag("layout_compact_height"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactHeader(state, vm, showApi28WifiAction)
        CompactNav(destination, onDestination, Modifier.testTag("compact_height_navigation"))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val videoWeight = when (compactHeightContent(destination)) {
                CompactHeightContent.Tracking -> 1.15f
                else -> 1.65f
            }
            val videoTag = when (compactHeightContent(destination)) {
                CompactHeightContent.Tracking -> "compact_height_tracking_video"
                else -> "compact_height_dashboard_video"
            }
            VideoPanel(
                state,
                vm,
                Modifier.weight(videoWeight).fillMaxHeight().testTag(videoTag),
                videoSurface,
            )
            val rightWeight = when (compactHeightContent(destination)) {
                CompactHeightContent.Tracking -> 0.85f
                else -> 0.7f
            }
            Box(Modifier.weight(rightWeight).fillMaxHeight()) {
                when (compactHeightContent(destination)) {
                    CompactHeightContent.Dashboard -> {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LandscapeFlightControls(state, vm)
                            EmergencyHoldButton(state.canEmergency(), vm::emergencyMotorKill, Modifier.fillMaxWidth().weight(1f), compact = true)
                        }
                    }
                    CompactHeightContent.Controls -> CompactHeightControlsDestination(state, vm, Modifier.fillMaxSize())
                    CompactHeightContent.Tracking -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().testTag("compact_height_tracking_scroll"),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 8.dp),
                        ) {
                            item { TrackingControls(state, vm) }
                        }
                    }
                    CompactHeightContent.Status -> CompactHeightScrollableDestination(Modifier.fillMaxSize()) { StatusPanel(state) }
                    CompactHeightContent.Media -> CompactHeightScrollableDestination(Modifier.fillMaxSize()) { MediaControls() }
                }
            }
        }
    }
}

@Composable
private fun Header(state: DroneSessionState, vm: DroneDashboardActions, expanded: Boolean) {
    Surface(shape = panelShape, color = TelloPanelRaised, modifier = Modifier.fillMaxWidth()) {
        if (expanded) Row(Modifier.heightIn(min = 96.dp).padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Brand(state, Modifier.width(190.dp)); HeaderMetric("BATTERY", telemetryValue(state) { it.batteryPercent?.let { value -> "$value%" } }); HeaderMetric("HEIGHT", telemetryValue(state) { it.heightMeters?.let { value -> "%.1f m".format(value) } }); HeaderMetric("SPEED", telemetryValue(state) { it.speedMetersPerSecond?.let { value -> "%.1f m/s".format(value) } }); HeaderMetric("FLIGHT TIME", telemetryValue(state) { it.flightTimeSeconds?.let(::formatTime) }); AirborneBatteryWarningBanner(state, Modifier.padding(horizontal = 8.dp)); Spacer(Modifier.weight(1f)); HeaderActions(state, vm)
        } else Column(Modifier.padding(standardCardPadding), verticalArrangement = Arrangement.spacedBy(sectionSpacing)) {
            Brand(state); AirborneBatteryWarningBanner(state); FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HeaderMetric("BATTERY", telemetryValue(state) { it.batteryPercent?.let { value -> "$value%" } }); HeaderMetric("HEIGHT", telemetryValue(state) { it.heightMeters?.let { value -> "%.1f m".format(value) } }); HeaderMetric("SPEED", telemetryValue(state) { it.speedMetersPerSecond?.let { value -> "%.1f m/s".format(value) } })
            }
            ConnectionButton(state, vm)
        }
    }
}

@Composable
private fun CompactHeader(state: DroneSessionState, vm: DroneDashboardActions, showApi28WifiAction: Boolean = needsCompactWifiAction(state)) = Surface(
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
        AirborneBatteryWarningBanner(state)
        HeaderMetric("BATTERY", telemetryValue(state) { it.batteryPercent?.let { value -> "$value%" } })
        CompactConnectionActions(state, vm, showApi28WifiAction)
    }
}

private fun needsCompactWifiAction(state: DroneSessionState) = Build.VERSION.SDK_INT == 28 &&
    state.connection != DroneConnectionState.Connected


@Composable private fun CompactConnectionActions(state: DroneSessionState, vm: DroneDashboardActions, showApi28WifiAction: Boolean) = Row(
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

@Composable private fun HeaderActions(state: DroneSessionState, vm: DroneDashboardActions) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    ConnectionPrimaryButton(state, vm)
    if (Build.VERSION.SDK_INT == 28 && state.connection != DroneConnectionState.Connected) WifiSettingsButton()
    OutlineAction("SETTINGS", Icons.Default.Settings, false, {}, compact = true)
}

@Composable private fun ConnectionPrimaryButton(state: DroneSessionState, vm: DroneDashboardActions) {
    val active = state.connection == DroneConnectionState.Connected
    val transition = state.connection in setOf(DroneConnectionState.Connecting, DroneConnectionState.AwaitingPermission)
    val unsafeDisconnect = active && state.flight in setOf(FlightState.TakingOff, FlightState.Flying, FlightState.Landing, FlightState.Unknown)
    OutlinedButton(onClick = if (active) vm::disconnect else vm::connect, enabled = !transition && !unsafeDisconnect) {
        Text(when { transition -> "CONNECTING..."; active -> "DISCONNECT"; else -> "CONNECT TELLO" }, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable private fun WifiSettingsButton() {
    val context = LocalContext.current
    OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, modifier = Modifier.testTag("open_wifi_settings")) { Text("WI-FI SETTINGS", fontSize = 10.sp, maxLines = 1) }
}

@Composable private fun ConnectionButton(state: DroneSessionState, vm: DroneDashboardActions) {
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
                    else -> "CONNECT TELLO"
                },
                fontSize = 11.sp,
            )
        }
        if (Build.VERSION.SDK_INT == 28 &&
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

@Composable private fun Brand(state: DroneSessionState, modifier: Modifier = Modifier) = Column(modifier) {
    Text("TELLO DRONE", fontWeight = FontWeight.Bold, fontSize = 20.sp)
    Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(9.dp).clip(CircleShape).background(connectionColor(state.connection))); Spacer(Modifier.width(7.dp)); Text(connectionLabel(state.connection).uppercase(), color = connectionColor(state.connection), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}
@Composable private fun HeaderMetric(label: String, value: String) = Column(Modifier.padding(horizontal = 10.dp)) { Text(label, fontSize = 11.sp, color = TelloTextMuted); Text(value, fontSize = 19.sp, fontWeight = FontWeight.Medium) }

@Composable private fun CompactNav(destination: String, onDestination: (String) -> Unit, modifier: Modifier = Modifier) = Surface(
    color = TelloPanel,
    shape = panelShape,
    modifier = modifier.fillMaxWidth().testTag("compact_navigation"),
) {
    val currentTab = OperationalTab.from(destination)
    Row(
        Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        OperationalTab.entries.forEach { tab ->
            val selected = tab == currentTab
            Text(
                tab.label,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onDestination(tab.label) }
                    .padding(vertical = 5.dp)
                    .testTag("compact_nav_${tab.name.lowercase()}"),
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                color = if (selected) TelloGreen else TelloTextMuted,
            )
        }
    }
}

@Composable private fun DestinationPlaceholder(destination: String, modifier: Modifier = Modifier) = Surface(modifier, shape = panelShape, color = TelloPanel) { Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Settings, null, tint = TelloGreen, modifier = Modifier.size(38.dp)); Spacer(Modifier.height(12.dp)); Text(destination, fontWeight = FontWeight.Bold, fontSize = 22.sp); Text("This Phase 1 destination is a lightweight placeholder.", color = TelloTextMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) } }

@Composable
private fun VideoPanel(
    state: DroneSessionState,
    vm: DroneDashboardActions,
    modifier: Modifier = Modifier,
    videoSurface: @Composable () -> Unit,
) = Surface(modifier.clip(panelShape), color = Color(0xFF252A2C)) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF42403B), Color(0xFF171B1D))))) {
        videoSurface()
        Row(Modifier.align(Alignment.TopStart).padding(14.dp).clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = .64f)).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("VIDEO", color = Color.White, fontWeight = FontWeight.Bold)
            Text("  LIVE PREVIEW", color = TelloTextMuted, fontSize = 12.sp)
        }
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
        state.targetOverlayPresentation()?.let { presentation ->
            val target = presentation.target
            val targetColor = when (presentation.kind) {
                TargetOverlayKind.Current -> TelloGreen
                TargetOverlayKind.LastSeenMissing -> Color(0xFFFFC857)
                TargetOverlayKind.IdentityUncertain -> TelloRed
            }
            val mapped = VideoOverlayCoordinateMapper.mapFillBounds(target.boundingBox, maxWidth.value, maxHeight.value)
            if (mapped != null) {
                val boxWidth = (mapped.right - mapped.left).dp
                val boxHeight = (mapped.bottom - mapped.top).dp
                Box(Modifier.offset(mapped.left.dp, mapped.top.dp)) {
                    Box(
                        Modifier
                            .size(boxWidth, boxHeight)
                            .border(2.dp, targetColor, RoundedCornerShape(3.dp)),
                    )
                    Text(
                        presentation.label,
                        color = TelloInk,
                        modifier = Modifier
                            .background(targetColor)
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        state.personDetections.filterNot { detection -> state.isCurrentTargetDetection(detection) }.forEach { detection ->
            val mapped = VideoOverlayCoordinateMapper.mapFillBounds(
                detection.boundingBox,
                maxWidth.value,
                maxHeight.value,
            ) ?: return@forEach
            val selectable = state.connection == DroneConnectionState.Connected &&
                    state.video.availability == VideoAvailability.Streaming &&
                    state.video.personDetectionState == PersonDetectionState.Detecting &&
                    state.video.processedDetectorFrameSequence == detection.frameSequence &&
                    state.video.processedDetectorSourceTimestampNanos == detection.sourceTimestampNanos
            val boxWidth = (mapped.right - mapped.left).dp
            val boxHeight = (mapped.bottom - mapped.top).dp
            Box(
                Modifier.offset(mapped.left.dp, mapped.top.dp),
            ) {
                Box(
                    Modifier
                        .size(boxWidth, boxHeight)
                        .border(2.dp, Color(0xFFFFC857), RoundedCornerShape(3.dp))
                        .clickable(enabled = selectable) { vm.selectTarget(detection) },
                )
                Text(
                    "PERSON ${(detection.confidence * 100f).roundToInt()}%",
                    color = TelloInk,
                    modifier = Modifier
                        .background(Color(0xFFFFC857))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text("H: ${telemetryValue(state) { it.heightMeters?.let { value -> "%.1f m".format(value) } }}", Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .82f)).padding(horizontal = 12.dp, vertical = 9.dp), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        val centerMessage = when {
            state.video.availability == VideoAvailability.Error -> "VIDEO UNAVAILABLE\n${state.video.errorReason ?: "Video pipeline error"}"
            state.connection in setOf(DroneConnectionState.Connecting, DroneConnectionState.Connected) &&
                state.video.availability == VideoAvailability.Unavailable -> "STARTING VIDEO…"
            state.video.availability == VideoAvailability.Streaming -> null
            else -> "NO VIDEO / WAITING"
        }
        centerMessage?.let { Text(it, Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .52f)).padding(10.dp), color = if (state.video.availability == VideoAvailability.Error) TelloRed else TelloTextMuted, fontSize = 12.sp, textAlign = TextAlign.Center) }
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

private class TelloVideoSurfaceView(
    context: Context,
    private val viewModel: DroneDashboardActions,
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

@Composable private fun TabletFlightControls(state: DroneSessionState, vm: DroneDashboardActions, modifier: Modifier) = ControlCard("FLIGHT CONTROLS", modifier = modifier) {
    Text(if (state.flight == FlightState.Grounded && state.telemetry.isFresh) "READY TO FLY" else connectionLabel(state.connection).uppercase(), color = if (state.flight == FlightState.Grounded && state.telemetry.isFresh) TelloGreen else TelloTextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    TakeoffAction(state, vm, Modifier.fillMaxWidth())
    LandAction(state, vm, Modifier.fillMaxWidth())
    if (state.hoverActive) HoverActiveStatus()
    FlightActionHint(state)
}

@Composable private fun CriticalFlightControls(state: DroneSessionState, vm: DroneDashboardActions) = ControlCard("FLIGHT CONTROLS") {
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
    state.flight == FlightState.Grounded && (state.telemetry.batteryPercent ?: 0) < MINIMUM_TAKEOFF_BATTERY_PERCENT ->
        "Takeoff blocked: battery (${state.telemetry.batteryPercent ?: "--"}%) below ${MINIMUM_TAKEOFF_BATTERY_PERCENT}% minimum."
    state.flight == FlightState.Grounded -> "Takeoff is available when telemetry is fresh."
    state.flight == FlightState.Flying -> "Land and STOP/HOVER are available while flying."
    else -> "Aircraft state is uncertain; land before normal flight commands."
}, color = TelloTextMuted, fontSize = 11.sp)

@Composable private fun FlightReadinessHint(state: DroneSessionState) = Surface(color = TelloPanel, shape = panelShape, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(standardCardPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("FLIGHT STATUS", color = TelloTextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium); Text(connectionLabel(state.connection), color = connectionColor(state.connection), fontWeight = FontWeight.SemiBold); FlightActionHint(state) } }
@Composable private fun HoverActiveStatus() = Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = TelloGreen, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("HOVER ACTIVE", color = TelloGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }

@Composable private fun LandscapeFlightControls(state: DroneSessionState, vm: DroneDashboardActions) = ControlCard("FLIGHT", compact = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TakeoffAction(state, vm, Modifier.weight(1f), compact = true)
        LandAction(state, vm, Modifier.weight(1f), compact = true)
    }
    HoverAction(state, vm, Modifier.fillMaxWidth().testTag("stop_hover"), compact = true)
}

@Composable
private fun TakeoffAction(state: DroneSessionState, vm: DroneDashboardActions, modifier: Modifier, compact: Boolean = false) {
    val gate = remember { TakeoffConfirmationGate() }
    var dialogVisible by remember { mutableStateOf(false) }
    val eligible = state.isTakeoffEligible()
    LaunchedEffect(eligible, state.flight) {
        if (!gate.dismissIfIneligible(state)) dialogVisible = false
    }
    ActionButton(
        if (state.flight == FlightState.TakingOff) "TAKING OFF…" else "TAKE OFF",
        Icons.Default.ArrowUpward,
        eligible,
        onClick = {
            dialogVisible = gate.request(state)
        },
        modifier = modifier,
        active = state.flight == FlightState.TakingOff,
        compact = compact,
    )
    if (dialogVisible && eligible) {
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
@Composable private fun LandAction(state: DroneSessionState, vm: DroneDashboardActions, modifier: Modifier, compact: Boolean = false) {
    val landing = state.flight == FlightState.Landing
    if (landing) ActionButton("LANDING…", Icons.Default.ArrowDownward, false, {}, modifier, active = true, compact = compact)
    else OutlineAction("LAND", Icons.Default.ArrowDownward, state.connection == DroneConnectionState.Connected && state.flight in setOf(FlightState.Flying, FlightState.Unknown), vm::land, modifier, compact = compact)
}
@Composable private fun HoverAction(state: DroneSessionState, vm: DroneDashboardActions, modifier: Modifier, compact: Boolean = false) {
    val enabled = state.connection == DroneConnectionState.Connected && state.flight == FlightState.Flying
    if (state.hoverActive) ActionButton("HOVER ACTIVE", Icons.Default.CheckCircle, enabled, vm::stopAndHover, modifier, active = true, compact = compact)
    else OutlineAction("STOP / HOVER", Icons.Default.PauseCircle, enabled, vm::stopAndHover, modifier, compact = compact)
}

@Composable private fun TrackingControls(state: DroneSessionState, vm: DroneDashboardActions) = ControlCard("TRACKING") {
    val presentation = state.trackingUiPresentation()
    val canStart = state.connection == DroneConnectionState.Connected && state.video.availability == VideoAvailability.Streaming && state.video.analysisLatestSequence != null
    listOf(presentation.detection, presentation.target, presentation.yaw).forEach { StatusLine(it.label, it.value, it.color) }
    Surface(color = TelloRed.copy(alpha = .10f), shape = RoundedCornerShape(6.dp), modifier = Modifier.testTag("yaw_only_badge")) {
        Text("YAW ONLY", color = TelloRed, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
    }
    presentation.instruction?.let { Text(it, color = TelloTextMuted, fontSize = 12.sp) }
    when (presentation.primaryAction) {
        TrackingPrimaryAction.StartDetection -> ActionButton("START DETECTION", Icons.Default.PersonSearch, canStart, { vm.setTrackingMode(TrackingMode.DetectOnly) }, Modifier.fillMaxWidth())
        TrackingPrimaryAction.ArmYawFollow -> ActionButton("ARM YAW FOLLOW", Icons.Default.CheckCircle, true, { vm.setYawFollowArmed(true) }, Modifier.fillMaxWidth().testTag("arm_yaw_follow"))
        TrackingPrimaryAction.RearmYawFollow -> ActionButton("RE-ARM YAW FOLLOW", Icons.Default.CheckCircle, true, { vm.setYawFollowArmed(true) }, Modifier.fillMaxWidth().testTag("arm_yaw_follow"))
        TrackingPrimaryAction.DisarmYawFollow -> ActionButton("DISARM YAW FOLLOW", Icons.Default.Close, true, { vm.setYawFollowArmed(false) }, Modifier.fillMaxWidth().testTag("disarm_yaw_follow"), active = true)
        TrackingPrimaryAction.None -> Unit
    }
    if (presentation.showStopDetection) OutlineAction("STOP DETECTION", Icons.Default.Close, true, { vm.setTrackingMode(TrackingMode.Off) }, Modifier.fillMaxWidth())
}

internal fun DroneSessionState.isCurrentTargetDetection(detection: com.alonibh.tellodrone.domain.PersonDetection): Boolean = target?.let {
    detection.frameSequence == it.lastSeenFrameSequence && detection.sourceTimestampNanos == it.lastSeenSourceTimestampNanos && detection.boundingBox == it.boundingBox
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
        TargetAssociationState.Selected, TargetAssociationState.Matched ->
            TargetOverlayPresentation(currentTarget, TargetOverlayKind.Current, "TARGET SELECTED")
        TargetAssociationState.TemporarilyMissing ->
            TargetOverlayPresentation(currentTarget, TargetOverlayKind.LastSeenMissing, "LAST SEEN • MISSING")
        TargetAssociationState.Ambiguous ->
            TargetOverlayPresentation(currentTarget, TargetOverlayKind.IdentityUncertain, "IDENTITY UNCERTAIN")
        TargetAssociationState.None, TargetAssociationState.Lost -> null
    }
}

@Composable private fun TrackingDestination(state: DroneSessionState, vm: DroneDashboardActions, modifier: Modifier = Modifier, videoSurface: @Composable () -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VideoPanel(state, vm, Modifier.weight(1.4f).fillMaxHeight(), videoSurface)
        LazyColumn(
            modifier = Modifier.weight(.8f).fillMaxHeight().testTag("expanded_tracking_scroll"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item { TrackingControls(state, vm) }
        }
    }
}

/** Short landscape keeps the camera visible while its independently scrollable pane exposes every detector action. */
@Composable private fun CompactHeightTrackingDestination(state: DroneSessionState, vm: DroneDashboardActions, modifier: Modifier = Modifier, videoSurface: @Composable () -> Unit) = Row(
    modifier,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    VideoPanel(state, vm, Modifier.weight(1.15f).fillMaxHeight().testTag("compact_height_tracking_video"), videoSurface)
    LazyColumn(
        modifier = Modifier.weight(.85f).fillMaxHeight().testTag("compact_height_tracking_scroll"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        item { TrackingControls(state, vm) }
    }
}

@Composable private fun CompactHeightControlsDestination(state: DroneSessionState, vm: DroneDashboardActions, modifier: Modifier = Modifier) = ControlCard(
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
    if (VisionTraceFeature.isAvailable) {
        HorizontalDivider(color = TelloLine)
        Text("DEBUG DIAGNOSTICS", color = TelloTextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        VisionSessionControls(state)
    }
}
@Composable private fun StatusLine(label: String, value: String, color: Color = Color.White) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = TelloTextMuted, fontSize = 13.sp); Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium) }

@Composable
private fun BottomControls(state: DroneSessionState, vm: DroneDashboardActions, modifier: Modifier = Modifier, tablet: Boolean = false) = ControlCard("MANUAL CONTROL", modifier = modifier) { ManualControlPanel(state, vm, tablet = tablet) }

@Composable
private fun ManualControlPanel(state: DroneSessionState, vm: DroneDashboardActions, compact: Boolean = false, tablet: Boolean = false) {
    val enabled = state.connection == DroneConnectionState.Connected &&
        state.flight == FlightState.Flying &&
        state.telemetry.isFresh
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
private fun ManualFlightCenter(state: DroneSessionState, vm: DroneDashboardActions, modifier: Modifier = Modifier, tablet: Boolean = false) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("FLIGHT", color = TelloTextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    HoverAction(state, vm, Modifier.fillMaxWidth().testTag("manual_stop_hover"), compact = true)
    if (state.hoverActive) HoverActiveStatus()
    SpeedControl(state, vm, Modifier.fillMaxWidth(), carded = tablet)
}

@Composable private fun SpeedControl(state: DroneSessionState, vm: DroneDashboardActions, modifier: Modifier, carded: Boolean = false) {
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
        ) {
            Canvas(Modifier.size(diameter)) {
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
    var pressing by remember { mutableStateOf(false) }
    var triggered by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { pressing = false } }
    val progress by animateFloatAsState(if (pressing) 1f else 0f, label = "emergency hold")
    LaunchedEffect(pressing) {
        if (pressing) {
            delay(900)
            if (pressing && !triggered) {
                triggered = true
                onTriggered()
            }
        } else triggered = false
    }
    Surface(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(panelShape)
            .background(if (enabled) TelloRed else TelloLine)
            .pointerInput(enabled) {
                detectTapGestures(onPress = {
                    if (enabled) {
                        pressing = true
                        tryAwaitRelease()
                        pressing = false
                    }
                })
            }
            .testTag("emergency_motor_kill"),
        color = Color.Transparent,
        shape = panelShape,
    ) {
        Column(
            Modifier.fillMaxSize().padding(if (compact) 6.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.Emergency, null, modifier = Modifier.size(if (compact) 18.dp else 22.dp))
            Spacer(Modifier.height(2.dp))
            Text(
                if (compact) "EMERGENCY MOTOR KILL" else "EMERGENCY MOTOR KILL",
                fontWeight = FontWeight.Bold,
                fontSize = if (compact) 10.sp else 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (pressing) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    color = Color.White,
                    trackColor = Color.Black.copy(alpha = .35f),
                )
            }
        }
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

@Preview(name = "1280x800 – Grounded Flight", widthDp = 1280, heightDp = 800)
@Composable private fun ExpandedGroundedFlightPreview() = PreviewDashboard(tabletPreviewState(FlightState.Grounded))

@Preview(name = "1280x800 – Flying Flight", widthDp = 1280, heightDp = 800)
@Composable private fun ExpandedFlyingFlightPreview() = PreviewDashboard(tabletPreviewState(FlightState.Flying))

@Preview(name = "1280x800 – Flying Low Battery", widthDp = 1280, heightDp = 800)
@Composable private fun ExpandedFlyingLowBatteryPreview() = PreviewDashboard(
    tabletPreviewState(FlightState.Flying).copy(
        telemetry = com.alonibh.tellodrone.domain.TelemetrySnapshot(isFresh = true, batteryPercent = 15, heightMeters = 1.2f),
    ),
)

@Preview(name = "1280x800 – Tracking Detection Off", widthDp = 1280, heightDp = 800)
@Composable private fun ExpandedTrackingDetectionOffPreview() = PreviewDashboardDestination(
    tabletPreviewState(FlightState.Flying),
    "TRACKING",
)

@Preview(name = "1280x800 – Tracking Selected Armed", widthDp = 1280, heightDp = 800)
@Composable private fun ExpandedTrackingSelectedArmedPreview() = PreviewDashboardDestination(
    tabletPreviewState(FlightState.Flying).copy(
        video = VideoState(availability = VideoAvailability.Streaming, personDetectionState = PersonDetectionState.Detecting),
        targetAssociationState = TargetAssociationState.Matched,
        yawFollowDecision = com.alonibh.tellodrone.domain.YawFollowDecision(state = com.alonibh.tellodrone.domain.YawFollowState.ACTIVE),
    ),
    "TRACKING",
)

@Preview(name = "1280x800 – Status", widthDp = 1280, heightDp = 800)
@Composable private fun ExpandedStatusPreview() = PreviewDashboardDestination(
    tabletPreviewState(FlightState.Flying),
    "STATUS",
)

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

@Preview(name = "Medium window", widthDp = 700, heightDp = 600)
@Composable private fun MediumPreview() = PreviewDashboard(DroneSessionState(connection = DroneConnectionState.Connected, flight = FlightState.Flying))

@Composable private fun PreviewDashboard(state: DroneSessionState) {
    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = TelloGreen, background = TelloInk, surface = TelloPanel, surfaceVariant = TelloPanelRaised, error = TelloRed)) { DroneDashboard(state, NoOpDroneDashboardActions) }
}

@Composable private fun PreviewDashboardDestination(state: DroneSessionState, destination: String) {
    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = TelloGreen, background = TelloInk, surface = TelloPanel, surfaceVariant = TelloPanelRaised, error = TelloRed)) {
        DroneDashboard(state, NoOpDroneDashboardActions, initialDestination = destination)
    }
}

@Composable private fun PreviewCompactHeightDestination(state: DroneSessionState, destination: String, showApi28WifiAction: Boolean = false) {
    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = TelloGreen, background = TelloInk, surface = TelloPanel, surfaceVariant = TelloPanelRaised, error = TelloRed)) {
        val videoSurface = remember { movableContentOf { TelloVideoSurface(NoOpDroneDashboardActions) } }
        LandscapeDashboard(state, NoOpDroneDashboardActions, destination, videoSurface, {}, showApi28WifiAction)
    }
}

private fun tabletPreviewState(flight: FlightState, hoverActive: Boolean = false) = DroneSessionState(connection = DroneConnectionState.Connected, flight = flight, telemetry = com.alonibh.tellodrone.domain.TelemetrySnapshot(isFresh = true), hoverActive = hoverActive)
// SPDX-License-Identifier: AGPL-3.0-only
