package com.alonibh.tellodrone.vision

import android.content.Context
import com.alonibh.tellodrone.domain.PersonDetection
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/** CPU-only MediaPipe Tasks Object Detector in synchronous IMAGE mode. */
class MediaPipePersonDetector(context: Context) : PersonDetector {
    private val detector = ObjectDetector.createFromOptions(
        context.applicationContext,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .setDelegate(Delegate.CPU)
                    .build(),
            )
            .setRunningMode(RunningMode.IMAGE)
            .setCategoryAllowlist(listOf(PersonDetectionMapper.PERSON_CATEGORY))
            .setScoreThreshold(PersonDetectionMapper.MIN_CONFIDENCE)
            .setMaxResults(PersonDetectionMapper.MAX_RESULTS)
            .build(),
    )

    override fun detect(frame: PersonDetectorFrame): List<PersonDetection> {
        val image = BitmapImageBuilder(frame.bitmap).build()
        val raw = detector.detect(image).detections().mapNotNull { detection ->
            val category = detection.categories()
                .filter { it.categoryName() == PersonDetectionMapper.PERSON_CATEGORY }
                .maxByOrNull { it.score() }
                ?: return@mapNotNull null
            val box = detection.boundingBox()
            RawObjectDetection(
                categoryName = category.categoryName(),
                confidence = category.score(),
                leftPixels = box.left,
                topPixels = box.top,
                rightPixels = box.right,
                bottomPixels = box.bottom,
            )
        }
        return PersonDetectionMapper.map(raw, frame.metadata)
    }

    override fun close() = detector.close()

    companion object {
        const val MODEL_ASSET = "efficientdet_lite0_int8.tflite"
    }
}
