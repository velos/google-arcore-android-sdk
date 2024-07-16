package com.google.ar.core.examples.java.augmentedimage

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.media.Image
import android.util.Log
import com.google.android.gms.tasks.Tasks.await
import com.google.ar.core.Frame
import com.google.ar.core.examples.kotlin.ml.classification.utils.ImageUtils
import com.google.ar.core.examples.kotlin.ml.classification.utils.VertexUtils.rotateCoordinates
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.demo.kotlin.subjectsegmenter.EdgeDetector
import com.google.mlkit.vision.demo.kotlin.subjectsegmenter.opencv.OpenCvDocumentDetector
import com.google.mlkit.vision.demo.kotlin.subjectsegmenter.opencv.ordered
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Analyzes an image using ML Kit. */
class OpenCvObjectDetector(context: Activity) {
    val yuvConverter = YuvToRgbConverter(context)
//    private val subjectSegmenter: SubjectSegmenter
    private val imageSegmenter: ImageSegmenter
    private val openCvDocumentDetector: OpenCvDocumentDetector
    private val edgeDetector: EdgeDetector

    init {
//        subjectSegmenter =
//            SubjectSegmentation.getClient(
//                SubjectSegmenterOptions.Builder()
//                    .enableForegroundConfidenceMask()
//                    .build()
//            )

        imageSegmenter = ImageSegmenter.createFromOptions(
            context,
            ImageSegmenter.ImageSegmenterOptions.builder()
                .setRunningMode(RunningMode.IMAGE)
                .setBaseOptions(
                    BaseOptions.builder()
                        .setDelegate(Delegate.CPU)
                        .setModelAssetPath("mobile_bg_removal_v8.f16.tflite")
                        .build()
                )
                .setOutputCategoryMask(false)
                .setOutputConfidenceMasks(true)
                .build()
        )

        openCvDocumentDetector = OpenCvDocumentDetector()
        openCvDocumentDetector.initialize()
        edgeDetector = EdgeDetector()
    }

    fun stop() {
        openCvDocumentDetector.release()
    }

    suspend fun analyze(convertYuv: Bitmap, imageRotation: Int): List<Point>? {
        // The model performs best on upright images, so rotate it.
        val rotatedImage = ImageUtils.rotateBitmap(convertYuv, imageRotation)

        val segmentationResult = withContext(Dispatchers.IO) {
            runCatching {
                val mpImage = BitmapImageBuilder(rotatedImage).build()
                val segmentationResult = imageSegmenter.segment(mpImage)
                ByteBufferExtractor.extract(
                    segmentationResult.confidenceMasks().get().first()
                )
                    .asFloatBuffer()

//                val inputImage = InputImage.fromBitmap(rotatedImage, 0)
//                subjectSegmenter.process(inputImage).await()
            }
                .onFailure {
                    Log.e("OpenCvObjectDetector", "Error with subject segmenter", it)
                }
                .getOrNull()
        } ?: return null

        val edgeMask = edgeDetector.detect(
            rotatedImage.width,
            rotatedImage.height,
            rotatedImage.width,
            segmentationResult
        )

        val contourPoints = openCvDocumentDetector.detect(
            edgeMask,
            rotatedImage.width,
            rotatedImage.height
        ).map {
            it.rotateCoordinates(rotatedImage.width, rotatedImage.height, imageRotation)
        }

//        contourPoints.forEachIndexed { index, point ->
//            Log.d("carloss", "contourPoint $index: (${point.x}, ${point.y})")
//        }

        if (contourPoints.size == 4) {
            val orderedPoints = contourPoints.ordered()
//            val extentX = abs(orderedPoints[0].x - orderedPoints[2].x)
//            val extentY = abs(orderedPoints[0].y - orderedPoints[2].y)
//            val center = floatArrayOf(
//                orderedPoints[0].x + extentX / 2f,
//                orderedPoints[0].y + extentY / 2f
//            )

            return orderedPoints
        } else {
            return null
        }
//
//            val mlKitDetectedObjects = detector.process(inputImage).await()
////            Log.d("carlos", "detected $mlKitDetectedObjects")
//            return mlKitDetectedObjects.firstOrNull()?.let { obj ->
////      val bestLabel = obj.labels.maxByOrNull { label -> label.confidence } ?: return@mapNotNull null
//
//                val rotatedTopLeft = Point(obj.boundingBox.left, obj.boundingBox.top)
//                    .rotateCoordinates(rotatedImage.width, rotatedImage.height, imageRotation)
//                val rotatedBottomRight = Point(obj.boundingBox.right, obj.boundingBox.bottom)
//                    .rotateCoordinates(rotatedImage.width, rotatedImage.height, imageRotation)
//                val rotatedBounds = Rect(
//                    rotatedTopLeft.x,
//                    rotatedTopLeft.y,
//                    rotatedBottomRight.x,
//                    rotatedBottomRight.y
//                )
//
////                Log.d(
////                    "carlos",
////                    "(${obj.boundingBox.left}, ${obj.boundingBox.top}) => (${rotatedTopLeft.x}, ${rotatedTopLeft.y})"
////                )
//
//                DetectedObjectResult(obj.trackingId!!, rotatedBounds)
//            }
    }

    fun convertYuv(image: Image): Bitmap {
        return Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).apply {
            yuvConverter.yuvToRgb(image, this)
        }
    }

}
