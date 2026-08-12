package com.alonibh.tellodrone.vision

import android.graphics.Bitmap
import com.alonibh.tellodrone.domain.DetectorBackend
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.tello.AnalysisFrameMetadata
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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
        val normalized = rawDetections.asSequence()
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
            .toList()
        return PersonDetectionDeduplicator.suppressSameFrameDuplicates(normalized).take(MAX_RESULTS)
    }
}

/** Conservative app-owned duplicate suppression for one completed detector frame. */
object PersonDetectionDeduplicator {
    /** Keeps distinct people when any one of these same-object checks is inconclusive. */
    const val MAX_CENTER_DISTANCE = .08f
    const val MIN_INTERSECTION_OVER_SMALLER = .75f
    const val MIN_AREA_RATIO = .60f
    const val MAX_AREA_RATIO = 1.67f

    fun suppressSameFrameDuplicates(detections: List<PersonDetection>): List<PersonDetection> {
        val ordered = detections.sortedWith(
            compareByDescending<PersonDetection> { it.confidence }
                .thenBy { it.boundingBox.left }
                .thenBy { it.boundingBox.top }
                .thenBy { it.boundingBox.right }
                .thenBy { it.boundingBox.bottom },
        )
        val retained = mutableListOf<PersonDetection>()
        ordered.forEach { candidate ->
            if (retained.none { kept -> areSamePhysicalObject(kept.boundingBox, candidate.boundingBox) }) {
                retained += candidate
            }
        }
        return retained
    }

    fun areSamePhysicalObject(kept: NormalizedBoundingBox, candidate: NormalizedBoundingBox): Boolean =
        centerDistance(kept, candidate) <= MAX_CENTER_DISTANCE &&
            intersectionOverSmaller(kept, candidate) >= MIN_INTERSECTION_OVER_SMALLER &&
            areaRatio(candidate, kept) in MIN_AREA_RATIO..MAX_AREA_RATIO

    fun centerDistance(first: NormalizedBoundingBox, second: NormalizedBoundingBox): Float = hypot(
        centerX(first) - centerX(second), centerY(first) - centerY(second),
    )

    fun intersectionOverSmaller(first: NormalizedBoundingBox, second: NormalizedBoundingBox): Float {
        val smaller = min(area(first), area(second))
        return if (smaller > 0f) intersection(first, second) / smaller else 0f
    }

    /** Candidate area divided by retained area. */
    fun areaRatio(candidate: NormalizedBoundingBox, kept: NormalizedBoundingBox): Float {
        val keptArea = area(kept)
        return if (keptArea > 0f) area(candidate) / keptArea else 0f
    }

    private fun intersection(first: NormalizedBoundingBox, second: NormalizedBoundingBox): Float =
        max(0f, min(first.right, second.right) - max(first.left, second.left)) *
            max(0f, min(first.bottom, second.bottom) - max(first.top, second.top))

    private fun area(box: NormalizedBoundingBox): Float =
        max(0f, box.right - box.left) * max(0f, box.bottom - box.top)

    private fun centerX(box: NormalizedBoundingBox): Float = (box.left + box.right) / 2f
    private fun centerY(box: NormalizedBoundingBox): Float = (box.top + box.bottom) / 2f
}
