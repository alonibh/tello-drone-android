package com.alonibh.tellodrone.vision

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun VisionSessionControls() {
    val context = LocalContext.current
    val replay = remember { DebugVisionReplayManager(context.applicationContext) }
    var status by remember { mutableStateOf("Capture is active while person detection analyzes frames.") }
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
                        "Exported ${exported.capturedFrameCount} frames (${exported.droppedFrameCount} dropped)."
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
                    status = "Selected ${selected.frameCount} frames (${selected.droppedFrameCount} capture drops)."
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

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DiagnosticButton("EXPORT VISION SESSION") { exportSession.launch("tello-vision-session.zip") }
        DiagnosticButton("IMPORT / SELECT SESSION") {
            importSession.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        DiagnosticButton("RUN MODEL COMPARISON", enabled = sessionSelected) {
            status = "Running Lite0 and Lite2 on the stored frames…"
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
        DiagnosticButton("COPY / EXPORT REPORT", enabled = reportReady) {
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
        Text(status)
    }
}

@Composable
private fun DiagnosticButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(label)
    }
}
