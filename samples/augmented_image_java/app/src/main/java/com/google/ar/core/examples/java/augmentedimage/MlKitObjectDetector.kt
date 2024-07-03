package com.google.ar.core.examples.java.augmentedimage

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.media.Image
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.examples.kotlin.ml.classification.utils.ImageUtils
import com.google.ar.core.examples.kotlin.ml.classification.utils.VertexUtils.rotateCoordinates
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Analyzes an image using ML Kit. */
class MLKitObjectDetector(context: Activity) {
    val yuvConverter = YuvToRgbConverter(context)
    val builder = ObjectDetectorOptions.Builder()

    private val options =
        builder
            .setDetectorMode(CustomObjectDetectorOptions.STREAM_MODE)
//      .enableClassification()
//      .enableMultipleObjects()
            .build()
    private val detector = ObjectDetection.getClient(options)

    suspend fun analyze(frame: Frame, imageRotation: Int): DetectedObjectResult? {
        val image = frame.tryAcquireCameraImage() ?: return null

            // `image` is in YUV
            // (https://developers.google.com/ar/reference/java/com/google/ar/core/Frame#acquireCameraImage()),
            val convertYuv = convertYuv(image)

            image.close()

            val targetWidth = 640f
            val scaleFactor = targetWidth / convertYuv.width.toFloat()
            val targetHeight = convertYuv.height.toFloat() * scaleFactor

//    val scaledBitmap = Bitmap.createScaledBitmap(
//      convertYuv,
//      targetWidth.toInt(),
//      targetHeight.toInt(),
//      true
//    )

            // The model performs best on upright images, so rotate it.
            val rotatedImage = ImageUtils.rotateBitmap(convertYuv, imageRotation)

            val inputImage = InputImage.fromBitmap(rotatedImage, 0)

            val mlKitDetectedObjects = detector.process(inputImage).await()
//            Log.d("carlos", "detected $mlKitDetectedObjects")
            return mlKitDetectedObjects.firstOrNull()?.let { obj ->
//      val bestLabel = obj.labels.maxByOrNull { label -> label.confidence } ?: return@mapNotNull null

                val rotatedTopLeft = Point(obj.boundingBox.left, obj.boundingBox.top)
                    .rotateCoordinates(rotatedImage.width, rotatedImage.height, imageRotation)
                val rotatedBottomRight = Point(obj.boundingBox.right, obj.boundingBox.bottom)
                    .rotateCoordinates(rotatedImage.width, rotatedImage.height, imageRotation)
                val rotatedBounds = Rect(
                    rotatedTopLeft.x,
                    rotatedTopLeft.y,
                    rotatedBottomRight.x,
                    rotatedBottomRight.y
                )

//                Log.d(
//                    "carlos",
//                    "(${obj.boundingBox.left}, ${obj.boundingBox.top}) => (${rotatedTopLeft.x}, ${rotatedTopLeft.y})"
//                )

                DetectedObjectResult(obj.trackingId!!, rotatedBounds)
            }
    }

    fun convertYuv(image: Image): Bitmap {
        return Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).apply {
            yuvConverter.yuvToRgb(image, this)
        }
    }

    data class DetectedObjectResult(
        val id: Int,
        val boundingBox: Rect,
    )
}
