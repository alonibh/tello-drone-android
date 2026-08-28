package com.alonibh.tellodrone.vision

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alonibh.tellodrone.domain.DroneSessionState
import java.util.Locale

@Composable
fun VisionSessionControls(state: DroneSessionState) {
    val context = LocalContext.current
    val replay = remember { DebugVisionReplayManager(context.applicationContext) }
    var status by remember {
        mutableStateOf("Capture active. Select a target to start tracking.")
    }
    var sessionSelected by remember { mutableStateOf(false) }
    var reportReady by remember { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    fun update(message: String) { mainHandler.post { status = message } }

    val exportSession = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        uri?.let {
            status = "Exporting vision session…"
            VisionTraceFeature.exportSession(context, it.toString()) { result ->
                update(result.fold(
                    onSuccess = { exported ->
                        "Exported ${exported.capturedFrameCount} frames " +
                            "(${exported.droppedFrameCount} dropped, " +
                            "${exported.excludedAfterLimitFrameCount} after limit)."
                    },
                    onFailure = { error -> "Session export failed: ${error.message ?: error.javaClass.simpleName}" },
                ))
            }
        }
    }
    val importSession = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            status = "Validating vision session…"
            replay.importSession(it.toString()) { result ->
                result.onSuccess { selected ->
                    sessionSelected = true
                    reportReady = false
                    status = "Selected ${selected.frameCount} frames (${selected.droppedFrameCount} capture drops). " +
                        if (selected.associationEvaluationValid) "Association replay is complete." else {
                            "Association replay will be flagged incomplete."
                        }
                }.onFailure { error ->
                    sessionSelected = false
                    reportReady = false
                    status = "Session import failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }
    val exportReport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let {
            replay.exportReport(it.toString()) { result ->
                status = result.fold(
                    onSuccess = { "Comparison report copied and exported." },
                    onFailure = { error -> "Report export failed: ${error.message ?: error.javaClass.simpleName}" },
                )
            }
        }
    }
    val exportFlightDiagnostics = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let {
            status = "Exporting flight diagnostics…"
            VisionTraceFeature.exportFlightDiagnostics(context, it.toString()) { result ->
                update(result.fold(
                    onSuccess = { exported ->
                        "Exported flight diagnostics (${exported.commandsCount} commands, " +
                            "${exported.transitionsCount} transitions, " +
                            "${exported.rcCount} RC packets, " +
                            "max cmd gap: ${exported.maxAirborneOutboundGapMillis ?: "--"} ms, " +
                            "max RC gap: ${exported.maxAirborneRcGapMillis ?: "--"} ms)."
                    },
                    onFailure = { error -> "Flight diagnostics export failed: ${error.message ?: error.javaClass.simpleName}" },
                ))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DiagnosticButton("COPY BENCHMARK", modifier = Modifier.weight(1f).height(48.dp)) {
                val report = deviceBenchmarkReport(state)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Tello grounded detector benchmark", report))
                status = "Grounded device benchmark copied (${state.video.detectorAnalyzedFrames} analyzed frames)."
            }
            DiagnosticButton("EXPORT FLIGHT DIAGNOSTICS", modifier = Modifier.weight(1f).height(48.dp)) {
                exportFlightDiagnostics.launch("tello-flight-diagnostics.json")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DiagnosticButton("EXPORT VISION SESSION", modifier = Modifier.weight(1f).height(48.dp)) {
                exportSession.launch("tello-vision-session.zip")
            }
            DiagnosticButton("IMPORT / SELECT SESSION", modifier = Modifier.weight(1f).height(48.dp)) {
                importSession.launch(arrayOf("application/zip", "application/octet-stream"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DiagnosticButton("RUN MODEL COMPARISON", enabled = sessionSelected, modifier = Modifier.weight(1f).height(48.dp)) {
                status = "Running YOLO11n LiteRT on the stored frames…"
                reportReady = false
                replay.runComparison { result ->
                    result.onSuccess { report ->
                        reportReady = true
                        status = "Comparison complete: ${report.sessionFrameCount} frames × ${report.models.size} models."
                    }.onFailure { error ->
                        status = "Comparison failed: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
            }
            DiagnosticButton("COPY / EXPORT REPORT", enabled = reportReady, modifier = Modifier.weight(1f).height(48.dp)) {
                replay.reportText()?.let { report ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val copied = runCatching {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Tello vision comparison", report))
                    }.isSuccess
                    status = if (copied) {
                        "Report copied; choose where to export it."
                    } else {
                        "Report is too large for the clipboard; choose where to export it."
                    }
                    exportReport.launch("tello-vision-comparison.json")
                }
            }
        }
        Text(status, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
    }
}

private fun deviceBenchmarkReport(state: DroneSessionState): String = buildString {
    fun Float?.metric(): String = this?.let { String.format(Locale.US, "%.3f", it) } ?: "null"
    appendLine("model=${state.video.detectorModelName ?: "unknown"}")
    appendLine("model_initialization_ms=${state.video.detectorInitializationMillis ?: "null"}")
    appendLine("detector_inference_p50_ms=${state.video.detectorInferenceP50Millis.metric()}")
    appendLine("detector_inference_p95_ms=${state.video.detectorInferenceP95Millis.metric()}")
    appendLine("detector_fps=${state.video.detectorMeasuredFps.metric()}")
    appendLine("analysis_fps=${state.video.analysisMeasuredFps.metric()}")
    appendLine("preview_fps=${state.video.measuredFps.metric()}")
    appendLine("captured_frames=${state.video.analysisCapturedFrames}")
    appendLine("analyzed_frames=${state.video.detectorAnalyzedFrames}")
    appendLine("dropped_frames=${state.video.analysisDroppedFrames}")
    appendLine("tracking_transitions=${state.trackingStateTransitions.size}")
    state.trackingStateTransitions.forEach { transition ->
        appendLine("${transition.frameSequence ?: -1},${transition.sourceTimestampNanos ?: -1},${transition.from}->${transition.to}")
    }
}

@Composable
private fun DiagnosticButton(
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
