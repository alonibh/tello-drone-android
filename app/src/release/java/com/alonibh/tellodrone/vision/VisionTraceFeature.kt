package com.alonibh.tellodrone.vision

import android.content.Context

object VisionTraceFeature {
    const val isAvailable = false
    fun recorder(context: Context): VisionTraceRecorder = NoOpVisionTraceRecorder
    fun export(context: Context, destinationUri: String, onComplete: (Result<VisionTraceExport>) -> Unit) {
        NoOpVisionTraceRecorder.export(destinationUri, onComplete)
    }
    fun exportSession(context: Context, destinationUri: String, onComplete: (Result<VisionSessionExport>) -> Unit) {
        onComplete(Result.failure(IllegalStateException("Vision session export is available only in debug builds")))
    }
    fun exportFlightDiagnostics(context: Context, destinationUri: String, onComplete: (Result<FlightDiagnosticsExport>) -> Unit) {
        onComplete(Result.failure(IllegalStateException("Flight diagnostics export is available only in debug builds")))
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
