package com.alonibh.tellodrone.vision

import android.graphics.Bitmap
import com.alonibh.tellodrone.domain.DetectorBackend
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.tello.AnalysisFrameMetadata

class PersonDetectorFrame(
    val metadata: AnalysisFrameMetadata,
    private val bitmapProvider: () -> Bitmap,
) {
    val bitmap: Bitmap get() = bitmapProvider()
}

data class PersonDetectorDescriptor(
    val modelName: String,
    val backend: DetectorBackend,
    val fellBackFromGpu: Boolean = false,
)

/** Synchronous detector boundary. Implementations may read a frame only during [detect]. */
interface PersonDetector : AutoCloseable {
    val descriptor: PersonDetectorDescriptor
    fun detect(frame: PersonDetectorFrame): List<PersonDetection>
}

fun interface PersonDetectorCreator {
    fun create(backend: DetectorBackend): PersonDetector
}

/** GPU-preferred factory whose initialization and runtime failures retry once on CPU. */
class FallbackPersonDetectorFactory(
    private val creator: PersonDetectorCreator,
) {
    fun create(preference: DetectorBackendPreference): PersonDetector = when (preference) {
        DetectorBackendPreference.Cpu -> creator.create(DetectorBackend.Cpu)
        DetectorBackendPreference.Accelerated -> GpuFallbackPersonDetector(creator)
    }

    private class GpuFallbackPersonDetector(
        private val creator: PersonDetectorCreator,
    ) : PersonDetector {
        private var active: PersonDetector
        private var fellBack = false

        init {
            active = try {
                creator.create(DetectorBackend.Gpu)
            } catch (_: Throwable) {
                fellBack = true
                creator.create(DetectorBackend.Cpu)
            }
        }

        override val descriptor: PersonDetectorDescriptor
            get() = active.descriptor.copy(fellBackFromGpu = fellBack)

        override fun detect(frame: PersonDetectorFrame): List<PersonDetection> {
            return try {
                active.detect(frame)
            } catch (gpuFailure: Throwable) {
                if (active.descriptor.backend != DetectorBackend.Gpu) throw gpuFailure
                runCatching { active.close() }
                fellBack = true
                active = creator.create(DetectorBackend.Cpu)
                active.detect(frame)
            }
        }

        override fun close() = active.close()
    }
}

/** Library-neutral intermediate result used to test app-owned filtering and normalization. */
data class RawObjectDetection(
    val categoryName: String,
    val confidence: Float,
    val leftPixels: Float,
    val topPixels: Float,
    val rightPixels: Float,
    val bottomPixels: Float,
)

object PersonDetectionMapper {
    const val PERSON_CATEGORY = "person"
    const val MIN_CONFIDENCE = 0.50f
    const val MAX_RESULTS = 5

    fun map(
        rawDetections: List<RawObjectDetection>,
        frame: AnalysisFrameMetadata,
    ): List<PersonDetection> {
        if (frame.width <= 0 || frame.height <= 0) return emptyList()
        val width = frame.width.toFloat()
        val height = frame.height.toFloat()
        return rawDetections.asSequence()
            .filter { it.categoryName == PERSON_CATEGORY }
            .filter { it.confidence.isFinite() && it.confidence >= MIN_CONFIDENCE }
            .mapNotNull { raw ->
                val values = listOf(
                    raw.leftPixels,
                    raw.topPixels,
                    raw.rightPixels,
                    raw.bottomPixels,
                )
                if (values.any { !it.isFinite() }) return@mapNotNull null
                val left = raw.leftPixels.coerceIn(0f, width)
                val top = raw.topPixels.coerceIn(0f, height)
                val right = raw.rightPixels.coerceIn(0f, width)
                val bottom = raw.bottomPixels.coerceIn(0f, height)
                if (right <= left || bottom <= top) return@mapNotNull null
                PersonDetection(
                    boundingBox = NormalizedBoundingBox(
                        left = left / width,
                        top = top / height,
                        right = right / width,
                        bottom = bottom / height,
                    ),
                    confidence = raw.confidence.coerceIn(0f, 1f),
                    frameSequence = frame.sequence,
                    sourceTimestampNanos = frame.captureTimestampNanos,
                )
            }
            .take(MAX_RESULTS)
            .toList()
    }
}
