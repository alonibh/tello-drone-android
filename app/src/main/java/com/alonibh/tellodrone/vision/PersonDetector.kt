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

import com.alonibh.tellodrone.domain.DetectorModel

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
    fun create(model: DetectorModel, backend: DetectorBackend): PersonDetector
}

/** GPU-preferred factory whose initialization and runtime failures retry once on CPU. */
class FallbackPersonDetectorFactory(
    private val creator: PersonDetectorCreator,
) {
    fun create(model: DetectorModel, preference: DetectorBackendPreference): PersonDetector = when (preference) {
        DetectorBackendPreference.Cpu -> creator.create(model, DetectorBackend.Cpu)
        DetectorBackendPreference.Accelerated -> GpuFallbackPersonDetector(model, creator)
    }

    fun create(preference: DetectorBackendPreference): PersonDetector =
        create(DetectorModel.Default, preference)

    private class GpuFallbackPersonDetector(
        private val model: DetectorModel,
        private val creator: PersonDetectorCreator,
    ) : PersonDetector {
        private var active: PersonDetector
        private var fellBack = false

        init {
            active = try {
                creator.create(model, DetectorBackend.Gpu)
            } catch (_: Throwable) {
                fellBack = true
                creator.create(model, DetectorBackend.Cpu)
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
                active = creator.create(model, DetectorBackend.Cpu)
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

const val DEFAULT_PERSON_CONFIDENCE_THRESHOLD = 0.55f
const val MIN_PERSON_CONFIDENCE_THRESHOLD = 0.50f
const val MAX_PERSON_CONFIDENCE_THRESHOLD = 0.90f

fun normalizeConfidenceThreshold(value: Float): Float {
    if (!value.isFinite()) return DEFAULT_PERSON_CONFIDENCE_THRESHOLD
    val stepped = Math.round(value * 20f) / 20f
    return stepped.coerceIn(MIN_PERSON_CONFIDENCE_THRESHOLD, MAX_PERSON_CONFIDENCE_THRESHOLD)
}

internal object ProductionPersonDetectorConfiguration {
    val model = DetectorModel.EfficientDetLite0
    val backendPreference = DetectorBackendPreference.Cpu
    const val confidenceThreshold = DEFAULT_PERSON_CONFIDENCE_THRESHOLD
}

object PersonDetectionMapper {
    const val PERSON_CATEGORY = "person"
    const val MIN_CONFIDENCE = 0.50f
    const val MAX_RESULTS = 5

    fun map(
        rawDetections: List<RawObjectDetection>,
        frame: AnalysisFrameMetadata,
        minConfidence: Float = MIN_CONFIDENCE,
    ): List<PersonDetection> {
        if (frame.width <= 0 || frame.height <= 0) return emptyList()
        val width = frame.width.toFloat()
        val height = frame.height.toFloat()
        val effectiveThreshold = normalizeConfidenceThreshold(minConfidence)
        val normalized = rawDetections.asSequence()
            .filter { it.categoryName == PERSON_CATEGORY }
            .filter { it.confidence.isFinite() && it.confidence >= effectiveThreshold }
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
    const val MAX_CENTER_DISTANCE = .12f
    const val MIN_INTERSECTION_OVER_SMALLER = .75f
    const val MIN_AREA_RATIO = .50f
    const val MAX_AREA_RATIO = 2.00f
    const val MAX_STRONGLY_NESTED_CENTER_DISTANCE = .08f
    const val MIN_STRONGLY_NESTED_INTERSECTION_OVER_SMALLER = .90f

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

    fun areSamePhysicalObject(kept: NormalizedBoundingBox, candidate: NormalizedBoundingBox): Boolean {
        val centerDistance = centerDistance(kept, candidate)
        val intersectionOverSmaller = intersectionOverSmaller(kept, candidate)
        val hasSimilarArea = areaRatio(candidate, kept) in MIN_AREA_RATIO..MAX_AREA_RATIO
        val isStronglyNested = centerDistance <= MAX_STRONGLY_NESTED_CENTER_DISTANCE &&
            intersectionOverSmaller >= MIN_STRONGLY_NESTED_INTERSECTION_OVER_SMALLER
        return centerDistance <= MAX_CENTER_DISTANCE &&
            intersectionOverSmaller >= MIN_INTERSECTION_OVER_SMALLER &&
            (hasSimilarArea || isStronglyNested)
    }

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
