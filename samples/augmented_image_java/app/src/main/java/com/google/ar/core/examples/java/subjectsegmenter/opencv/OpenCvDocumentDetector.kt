package com.google.mlkit.vision.demo.kotlin.subjectsegmenter.opencv

import android.graphics.Point
import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class OpenCvDocumentDetector {
    companion object {
        private const val TAG = "SubjectSegmentationGfx"
    }

    // Temporary data structures to be re-used for performance purposes
    private lateinit var hierarchy: Mat

    private lateinit var contour: MatOfPoint
    private lateinit var kernel: Mat

    private val contours = mutableListOf<MatOfPoint>()

    private var currentContour: MatOfPoint? = null
    private val blurSize = Size(5.0, 5.0)

    private var isProcessing = false

    fun initialize() {
        hierarchy = Mat()
        contour = MatOfPoint()
        kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            blurSize
        )
    }

    fun release() {
        hierarchy.release()
        kernel.release()

        contour.release()
        currentContour = null

        contours.release()
    }

    fun detect(
        foregroundMask: IntArray,
        width: Int,
        height: Int
    ): List<Point> {
        if (isProcessing) {
            Log.d(TAG, "dropped frame")
            return currentContour?.toPoints() ?: emptyList()
        }

        isProcessing = true

        val maskedFrame = foregroundMask.toMat(width, height)

//        Imgproc.Canny(
//            maskedFrame,
//            maskedFrame,
//            75.0,
//            200.0
//        )

//        Log.d("carlos", maskedFrame.toString())
//        maskedFrame.print("carlos")

        // Find edge contours

        Imgproc.findContours(
            maskedFrame,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        val frameArea = maskedFrame.rows() * maskedFrame.cols()

        // Find the largest contour with 4 corners
        contours.map { contour ->
            val contourFloat = MatOfPoint2f()
            contour.toMatOfPoint2F(contourFloat)

            // Approximate the contour
            val peri = Imgproc.arcLength(
                contourFloat,
                true
            )

            Imgproc.approxPolyDP(
                contourFloat,
                contourFloat,
                0.02 * peri,
                true
            )

//            Log.d(TAG, "found contour ${contour.rows()} points, ${Imgproc.contourArea(contour)} / $frameArea area")
//            Log.d(TAG, "found contourFloat ${contourFloat.rows()} points, ${Imgproc.contourArea(contourFloat)} / $frameArea area")
//            contourFloat.print(TAG)
            contourFloat
        }
            .maxByOrNull { Imgproc.contourArea(it) }
//            .sortedByDescending { Imgproc.contourArea(it) }
//            .firstOrNull { it.rows() == 4 }
            ?.let { screenContour ->
                Log.d(TAG, "found screenContour ${screenContour.rows()} points, ${Imgproc.contourArea(screenContour)} / $frameArea area")
//                screenContour.print(TAG)
                screenContour.toMatOfPoint(contour)

                currentContour = contour
            }
            ?: run {
                currentContour = null
            }

        contours.release()
        maskedFrame.release()
        isProcessing = false
        return currentContour?.toPoints() ?: emptyList()
    }
}
