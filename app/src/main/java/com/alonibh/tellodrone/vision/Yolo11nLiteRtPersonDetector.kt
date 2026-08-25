package com.alonibh.tellodrone.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.alonibh.tellodrone.domain.DetectorBackend
import com.alonibh.tellodrone.domain.DetectorModel
import com.alonibh.tellodrone.domain.HsvAppearanceHistogram
import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** App-owned YOLO11n preprocessing, decoding, NMS, and person filtering on LiteRT CPU. */
class Yolo11nLiteRtPersonDetector(
    context: Context,
    model: DetectorModel = DetectorModel.Default,
    backend: DetectorBackend = DetectorBackend.Cpu,
) : PersonDetector {
    init {
        require(model == DetectorModel.Yolo11nLiteRtFloat32)
        require(backend == DetectorBackend.Cpu) { "The validated YOLO11n runtime is CPU-only" }
    }

    private val compiledModel = CompiledModel.create(
        context.assets,
        model.assetFileName,
        CompiledModel.Options(Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(numThreads = CPU_THREADS)
        },
    )
    private val inputBuffers = compiledModel.createInputBuffers()
    private val outputBuffers = compiledModel.createOutputBuffers()
    private val input = FloatArray(INPUT_SIZE * INPUT_SIZE * CHANNELS)
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val letterboxed = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(letterboxed)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override val descriptor = PersonDetectorDescriptor(model.displayName, DetectorBackend.Cpu)

    override fun detect(frame: PersonDetectorFrame): List<PersonDetection> {
        val source = frame.bitmap
        val geometry = preprocess(source)
        inputBuffers[0].writeFloat(input)
        compiledModel.run(inputBuffers, outputBuffers)
        val output = outputBuffers[0].readFloat()
        require(output.size == OUTPUT_CHANNELS * OUTPUT_CANDIDATES) {
            "Unexpected YOLO11n output size ${output.size}"
        }
        return decode(output, source, frame, geometry)
    }

    private fun preprocess(source: Bitmap): LetterboxGeometry {
        val scale = min(INPUT_SIZE.toFloat() / source.width, INPUT_SIZE.toFloat() / source.height)
        val resizedWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val resizedHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        val padLeft = ((INPUT_SIZE - resizedWidth) / 2f - 0.1f).roundToInt()
        val padTop = ((INPUT_SIZE - resizedHeight) / 2f - 0.1f).roundToInt()
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(
            source,
            null,
            Rect(padLeft, padTop, padLeft + resizedWidth, padTop + resizedHeight),
            paint,
        )
        letterboxed.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        var outputIndex = 0
        pixels.forEach { pixel ->
            input[outputIndex++] = Color.red(pixel) / 255f
            input[outputIndex++] = Color.green(pixel) / 255f
            input[outputIndex++] = Color.blue(pixel) / 255f
        }
        return LetterboxGeometry(scale, padLeft.toFloat(), padTop.toFloat())
    }

    private fun decode(
        output: FloatArray,
        source: Bitmap,
        frame: PersonDetectorFrame,
        geometry: LetterboxGeometry,
    ): List<PersonDetection> {
        val raw = ArrayList<DecodedBox>()
        for (candidateIndex in 0 until OUTPUT_CANDIDATES) {
            var bestClass = 0
            var confidence = output[BOX_CHANNELS * OUTPUT_CANDIDATES + candidateIndex]
            for (classIndex in 1 until CLASS_COUNT) {
                val score = output[(BOX_CHANNELS + classIndex) * OUTPUT_CANDIDATES + candidateIndex]
                if (score > confidence) {
                    confidence = score
                    bestClass = classIndex
                }
            }
            if (bestClass != PERSON_CLASS || !confidence.isFinite() || confidence < RAW_CONFIDENCE_FLOOR) continue
            val centerX = output[candidateIndex] * INPUT_SIZE
            val centerY = output[OUTPUT_CANDIDATES + candidateIndex] * INPUT_SIZE
            val width = output[2 * OUTPUT_CANDIDATES + candidateIndex] * INPUT_SIZE
            val height = output[3 * OUTPUT_CANDIDATES + candidateIndex] * INPUT_SIZE
            val left = ((centerX - width / 2f - geometry.padLeft) / geometry.scale).coerceIn(0f, source.width.toFloat())
            val top = ((centerY - height / 2f - geometry.padTop) / geometry.scale).coerceIn(0f, source.height.toFloat())
            val right = ((centerX + width / 2f - geometry.padLeft) / geometry.scale).coerceIn(0f, source.width.toFloat())
            val bottom = ((centerY + height / 2f - geometry.padTop) / geometry.scale).coerceIn(0f, source.height.toFloat())
            if (right > left && bottom > top) raw += DecodedBox(left, top, right, bottom, confidence)
        }
        val retained = ArrayList<DecodedBox>()
        raw.sortedByDescending { it.confidence }.forEach { candidate ->
            if (retained.size < MAX_RESULTS && retained.none { intersectionOverUnion(it, candidate) > NMS_IOU }) {
                retained += candidate
            }
        }
        return retained.map { box ->
            val normalized = NormalizedBoundingBox(
                box.left / source.width,
                box.top / source.height,
                box.right / source.width,
                box.bottom / source.height,
            )
            PersonDetection(
                boundingBox = normalized,
                confidence = box.confidence.coerceIn(0f, 1f),
                frameSequence = frame.metadata.sequence,
                sourceTimestampNanos = frame.metadata.captureTimestampNanos,
                appearance = extractAppearance(source, normalized),
            )
        }
    }

    override fun close() {
        inputBuffers.forEach { it.close() }
        outputBuffers.forEach { it.close() }
        compiledModel.close()
        letterboxed.recycle()
    }

    private data class LetterboxGeometry(val scale: Float, val padLeft: Float, val padTop: Float)
    private data class DecodedBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val confidence: Float,
    )

    companion object {
        const val INPUT_SIZE = 320
        const val CPU_THREADS = 4
        const val RAW_CONFIDENCE_FLOOR = 0.02f
        const val NMS_IOU = 0.60f
        const val MAX_RESULTS = 300
        private const val CHANNELS = 3
        private const val BOX_CHANNELS = 4
        private const val CLASS_COUNT = 80
        private const val OUTPUT_CHANNELS = BOX_CHANNELS + CLASS_COUNT
        private const val OUTPUT_CANDIDATES = 2100
        private const val PERSON_CLASS = 0

        private fun intersectionOverUnion(first: DecodedBox, second: DecodedBox): Float {
            val intersection = max(0f, min(first.right, second.right) - max(first.left, second.left)) *
                max(0f, min(first.bottom, second.bottom) - max(first.top, second.top))
            val firstArea = (first.right - first.left) * (first.bottom - first.top)
            val secondArea = (second.right - second.left) * (second.bottom - second.top)
            val union = firstArea + secondArea - intersection
            return if (union > 0f) intersection / union else 0f
        }

        internal fun extractAppearance(bitmap: Bitmap, box: NormalizedBoundingBox): HsvAppearanceHistogram? {
            val boxWidth = (box.right - box.left) * bitmap.width
            val boxHeight = (box.bottom - box.top) * bitmap.height
            val left = ((box.left * bitmap.width) + boxWidth * 0.14f).roundToInt().coerceIn(0, bitmap.width)
            val right = ((box.right * bitmap.width) - boxWidth * 0.14f).roundToInt().coerceIn(0, bitmap.width)
            val top = ((box.top * bitmap.height) + boxHeight * 0.08f).roundToInt().coerceIn(0, bitmap.height)
            val bottom = ((box.bottom * bitmap.height) - boxHeight * 0.05f).roundToInt().coerceIn(0, bitmap.height)
            if (right <= left || bottom <= top) return null
            val histogram = FloatArray(HsvAppearanceHistogram.BIN_COUNT)
            val hsv = FloatArray(3)
            var samples = 0
            for (y in top until bottom) {
                for (x in left until right) {
                    Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                    val hueBin = (hsv[0] / 360f * HsvAppearanceHistogram.HUE_BINS).toInt()
                        .coerceIn(0, HsvAppearanceHistogram.HUE_BINS - 1)
                    val saturationBin = (hsv[1] * HsvAppearanceHistogram.SATURATION_BINS).toInt()
                        .coerceIn(0, HsvAppearanceHistogram.SATURATION_BINS - 1)
                    histogram[hueBin * HsvAppearanceHistogram.SATURATION_BINS + saturationBin]++
                    samples++
                }
            }
            if (samples == 0) return null
            if (samples * 3 < 100) return null
            val minimum = histogram.minOrNull() ?: return null
            val maximum = histogram.maxOrNull() ?: return null
            val range = maximum - minimum
            if (range <= 0f) return null
            return HsvAppearanceHistogram(histogram.map { (it - minimum) / range })
        }
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
