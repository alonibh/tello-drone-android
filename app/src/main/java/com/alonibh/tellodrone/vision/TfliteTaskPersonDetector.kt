package com.alonibh.tellodrone.vision

import android.content.Context
import com.alonibh.tellodrone.domain.DetectorBackend
import com.alonibh.tellodrone.domain.DetectorModel
import com.alonibh.tellodrone.domain.PersonDetection
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/** Official TensorFlow Lite Task ObjectDetector, confined to the analysis-consumer thread. */
class TfliteTaskPersonDetector(
    context: Context,
    val model: DetectorModel = DetectorModel.Default,
    backend: DetectorBackend,
) : PersonDetector {
    constructor(context: Context, backend: DetectorBackend) : this(context, DetectorModel.Default, backend)

    override val descriptor = PersonDetectorDescriptor(model.displayName, backend)

    private val detector = ObjectDetector.createFromFileAndOptions(
        context.applicationContext,
        model.assetFileName,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder().apply {
                    if (backend == DetectorBackend.Gpu) useGpu() else setNumThreads(CPU_THREADS)
                }.build(),
            )
            .setLabelAllowList(listOf(PersonDetectionMapper.PERSON_CATEGORY))
            .setScoreThreshold(PersonDetectionMapper.MIN_CONFIDENCE)
            .setMaxResults(PersonDetectionMapper.MAX_RESULTS)
            .build(),
    )

    override fun detect(frame: PersonDetectorFrame): List<PersonDetection> = detectDetailed(frame).candidates

    override fun detectDetailed(frame: PersonDetectorFrame): PersonDetectorOutput {
        val raw = detector.detect(TensorImage.fromBitmap(frame.bitmap)).mapNotNull { detection ->
            val category = detection.categories
                .filter { it.label == PersonDetectionMapper.PERSON_CATEGORY }
                .maxByOrNull { it.score }
                ?: return@mapNotNull null
            val box = detection.boundingBox
            RawObjectDetection(
                categoryName = category.label,
                confidence = category.score,
                leftPixels = box.left,
                topPixels = box.top,
                rightPixels = box.right,
                bottomPixels = box.bottom,
            )
        }
        return PersonDetectionMapper.mapDetailed(raw, frame.metadata)
    }

    override fun close() = detector.close()

    companion object {
        const val MODEL_ASSET = "ssd_mobilenet_v1_metadata_v2.tflite"
        const val MODEL_DISPLAY_NAME = "SSD MobileNet V1 COCO metadata v2"
        const val CPU_THREADS = 4
    }
}
