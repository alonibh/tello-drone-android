package com.alonibh.tellodrone.vision

import android.content.Context

object VisionTraceFeature {
    const val isAvailable = false
    fun recorder(context: Context): VisionTraceRecorder = NoOpVisionTraceRecorder
    fun export(context: Context, destinationUri: String, onComplete: (Result<VisionTraceExport>) -> Unit) {
        NoOpVisionTraceRecorder.export(destinationUri, onComplete)
    }
}
