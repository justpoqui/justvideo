package com.justvideo.app.analysis.visual

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.Closeable

// =====================================================================
// FaceAnalyzer.kt
// Counts faces and measures the biggest one with Google ML Kit, fully
// on-device (the model ships inside the app). Used for the "faces on
// screen" and "close-up" signals, and later for auto-reframe.
// =====================================================================

class FaceAnalyzer : Closeable {

    /** Result for one frame: how many faces, and how much of the frame the biggest covers (0..1). */
    data class Result(val count: Int, val biggestFraction: Float, val biggestCenterX: Float, val biggestCenterY: Float)

    // STEP 1: FAST mode, no landmarks/expressions: we only need boxes.
    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.08f)
            .build()
    )

    // STEP 2: Detect on a small greyscale frame. Blocking: call from a
    // background thread. ML Kit wants NV21, so we add neutral colour data.
    fun analyze(gray: ByteArray, width: Int, height: Int, rotationDegrees: Int): Result {
        val nv21 = ByteArray(width * height * 3 / 2)
        System.arraycopy(gray, 0, nv21, 0, width * height)
        nv21.fill(128.toByte(), width * height, nv21.size)
        val image = InputImage.fromByteArray(nv21, width, height, rotationDegrees, InputImage.IMAGE_FORMAT_NV21)
        val faces = Tasks.await(detector.process(image))
        if (faces.isEmpty()) return Result(0, 0f, 0.5f, 0.5f)
        // Box sizes are in the rotated (upright) image, whose area is the same.
        val (rw, rh) = if (rotationDegrees % 180 == 0) width to height else height to width
        val biggest = faces.maxBy { it.boundingBox.width() * it.boundingBox.height() }.boundingBox
        return Result(
            count = faces.size,
            biggestFraction = (biggest.width() * biggest.height()).toFloat() / (rw * rh),
            biggestCenterX = (biggest.exactCenterX() / rw).coerceIn(0f, 1f),
            biggestCenterY = (biggest.exactCenterY() / rh).coerceIn(0f, 1f),
        )
    }

    override fun close() = detector.close()
}
