/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.java.augmentedimage

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.examples.java.augmentedimage.rendering.AugmentedImageRenderer
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer
import com.google.ar.core.examples.java.common.rendering.PlaneRenderer
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.io.IOException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import javax.vecmath.Vector2f
import javax.vecmath.Vector3f
import kotlin.math.abs
import kotlin.math.atan2
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

/**
 * This app extends the HelloAR Java app to include image tracking functionality.
 *
 *
 * In this example, we assume all images are static or moving slowly with a large occupation of
 * the screen. If the target is actively moving, we recommend to check
 * AugmentedImage.getTrackingMethod() and render only when the tracking method equals to
 * FULL_TRACKING. See details in [Recognize and Augment
 * Images](https://developers.google.com/ar/develop/java/augmented-images/).
 */
class AugmentedImageActivity : AppCompatActivity(), GLSurfaceView.Renderer {
    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private lateinit var surfaceView: GLSurfaceView

    private var installRequested = false

    private var session: Session? = null
    private val messageSnackbarHelper = SnackbarHelper()
    private var displayRotationHelper: DisplayRotationHelper? = null
    private val trackingStateHelper = TrackingStateHelper(this)
    private lateinit var objectDetector: OpenCvObjectDetector

    private val backgroundRenderer = BackgroundRenderer()
    private val augmentedImageRenderer = AugmentedImageRenderer()

    private var shouldConfigureSession = false
    private var detectedObjectAnchor: DetectedObjectAnchor? = null
    private val coroutineScope = MainScope()
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully")
        } else {
            Log.e(TAG, "OpenCV initialization failed!")
            Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG)
                .show()
        }

        setContentView(R.layout.activity_main)
        surfaceView = findViewById(R.id.surfaceview)
        displayRotationHelper = DisplayRotationHelper( /*context=*/this)

        // Set up renderer.
        surfaceView.setPreserveEGLContextOnPause(true)
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.
        surfaceView.setRenderer(this)
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY)
        surfaceView.setWillNotDraw(false)

        objectDetector = OpenCvObjectDetector(this)

        installRequested = false
    }

    override fun onDestroy() {
        if (session != null) {
            // Explicitly close ARCore Session to release native resources.
            // Review the API reference for important considerations before calling close() in apps with
            // more complicated lifecycle requirements:
            // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
            session!!.close()
            session = null
        }

        objectDetector.stop()

        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        if (session == null) {
            var exception: Exception? = null
            var message: String? = null
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }

                    InstallStatus.INSTALLED -> {}
                }
                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this)
                    return
                }

                session = Session( /* context = */this)
            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableUserDeclinedInstallationException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                message = "Please update ARCore"
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                message = "Please update this app"
                exception = e
            } catch (e: Exception) {
                message = "This device does not support AR"
                exception = e
            }

            if (message != null) {
                messageSnackbarHelper.showError(this, message)
                Log.e(TAG, "Exception creating session", exception)
                return
            }

            shouldConfigureSession = true
        }

        if (shouldConfigureSession) {
            configureSession()
            shouldConfigureSession = false
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.")
            session = null
            return
        }
        surfaceView.onResume()
        displayRotationHelper!!.onResume()
    }

    public override fun onPause() {
        super.onPause()
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper!!.onPause()
            surfaceView.onPause()
            session!!.pause()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(
                this, "Camera permissions are needed to run this application", Toast.LENGTH_LONG
            )
                .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread( /*context=*/this)
            augmentedImageRenderer.createOnGlThread( /*context=*/this)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read an asset file", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        displayRotationHelper!!.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    private var currentFrame: Frame? = null

    override fun onDrawFrame(gl: GL10) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (session == null) {
            return
        }
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper!!.updateSessionIfNeeded(session)

        try {
            session!!.setCameraTextureName(backgroundRenderer.textureId)

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            val frame = session!!.update()
            val camera = frame.camera

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
            trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

            // If frame is ready, render camera preview image to the GL surface.
            backgroundRenderer.draw(frame)

            // Get projection matrix.
            val projmtx = FloatArray(16)
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)

            // Get camera matrix and draw.
            val viewmtx = FloatArray(16)
            camera.getViewMatrix(viewmtx, 0)

            // Compute lighting from average intensity of the image.
            val colorCorrectionRgba = FloatArray(4)
            frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)

            // Visualize augmented images.
            drawAugmentedImages(frame, projmtx, viewmtx, colorCorrectionRgba)
        } catch (t: Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    private fun configureSession() {
        val config = Config(session)
        config.setFocusMode(Config.FocusMode.AUTO)
        config.setDepthMode(Config.DepthMode.AUTOMATIC)
        config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL)
//        config.setInstantPlacementMode(Config.InstantPlacementMode.LOCAL_Y_UP)
        config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
        session!!.configure(config)
    }

    private fun drawAugmentedImages(
        frame: Frame, projmtx: FloatArray, viewmtx: FloatArray, colorCorrectionRgba: FloatArray
    ) {
        currentFrame = frame
        val cameraId = session!!.cameraConfig.cameraId
        val imageRotation = displayRotationHelper!!.getCameraSensorToDisplayRotation(cameraId)
//            Log.d("carlos", "detecting objects...")

        detectedObjectAnchor?.let { anchors ->
            if (anchors.anchor.trackingState != TrackingState.TRACKING) {
                Log.d("carlos", "lost tracking $anchors, removing anchor")
                anchors.detach()
                this@AugmentedImageActivity.detectedObjectAnchor = null
            }
        }

        if (detectedObjectAnchor == null && job?.isActive != true) {
            Log.d("carloss", "no existing anchor && no lock")

            frame.tryAcquireCameraImage()?.let { image ->
                val convertYuv = objectDetector.convertYuv(image)
                image.close()

                // Check if the center of the view center has a plane
                val centerPoint = floatArrayOf(convertYuv.width / 2f, convertYuv.height / 2f)
                createAnchor(centerPoint[0], centerPoint[1], frame, frame.camera)?.let { centerAnchor ->
                    val anchor = centerAnchor.createAnchor()
                    Log.d("carlos", "found center anchor (${centerPoint[0]}, ${centerPoint[1]}) ty=${anchor.pose.ty()}")

                    // Convert the center point to world coordinates
                    convertFloats[0] = centerPoint[0]
                    convertFloats[1] = centerPoint[1]
                    val ty = abs(anchor.pose.ty())
                    frame.transformCoordinates2d(
                        Coordinates2d.IMAGE_PIXELS,
                        convertFloats,
                        Coordinates2d.VIEW,
                        convertFloatsOut
                    )

                    val worldCenterPoint = LineUtils.GetWorldCoords(
                        Vector2f(convertFloatsOut),
                        surfaceView.width.toFloat(),
                        surfaceView.height.toFloat(),
                        projmtx,
                        viewmtx,
                        ty
                    )

                    Log.d("carlos", "surfaceView ${surfaceView.width} x ${surfaceView.height} rotate $imageRotation")
                    Log.d("carlos", "centerPoint (${centerPoint[0]}, ${centerPoint[1]}) => (${convertFloatsOut[0]}, ${convertFloatsOut[1]}) => (${worldCenterPoint.x}, ${worldCenterPoint.y}, ${worldCenterPoint.z})")

                    job = coroutineScope.launch {
                        // Check if the current view has a document
                        objectDetector.analyze(convertYuv, imageRotation)?.let { cornerPoints ->
                            Log.d("carlos", "found 4 corners $cornerPoints")

                            // check if centerAnchor is in cornerPoints
                            if (cornerPoints.isInside(centerPoint[0], centerPoint[1])) {
                                Log.d("carlos", "corners contains anchor")
                                val worldCornerPoints = cornerPoints.map {
                                    val convertFloats = FloatArray(4)
                                    val convertFloatsOut = FloatArray(4)

                                    convertFloats[0] = it.x.toFloat()
                                    convertFloats[1] = it.y.toFloat()
                                    currentFrame?.transformCoordinates2d(
                                        Coordinates2d.IMAGE_PIXELS,
                                        convertFloats,
                                        Coordinates2d.VIEW,
                                        convertFloatsOut
                                    )

                                    Log.d("carlos", "(${convertFloats[0]}, ${convertFloats[1]}) => (${convertFloatsOut[0]}, ${convertFloatsOut[1]})")

                                    LineUtils.GetWorldCoords(
                                        Vector2f(convertFloatsOut),
                                        surfaceView.width.toFloat(),
                                        surfaceView.height.toFloat(),
                                        projmtx,
                                        viewmtx,
                                        ty
                                    )
                                }

                                val transformedOrigin = anchor.pose.transformPoint(floatArrayOf(0f, 0f, 0f))

                                Log.d("carlos", "worldCenter = $worldCenterPoint anchor = (${transformedOrigin[0]}, ${transformedOrigin[1]}, ${transformedOrigin[2]})")
                                worldCornerPoints.forEachIndexed { index, vector3f ->
                                    Log.d("carlos", "worldCorner $index: ${vector3f}")
                                }

                                detectedObjectAnchor = DetectedObjectAnchor(
                                    anchor = anchor,
                                    centerPoint = worldCenterPoint,
                                    cornerPoints = worldCornerPoints
                                )
                            }
                        } ?: run {
                            Log.d("carlos", "no object detected")
                            anchor.detach()
                        }
                    }
                } ?: run {
                    Log.d("carlos", "no anchor detected")
                }
            }
        }

        detectedObjectAnchor?.let { detectedObjectAnchor ->
            // Detected an object
            // Object is new
            Log.d("carloss", "drawing anchor")

            augmentedImageRenderer.draw(
                viewmtx, projmtx, detectedObjectAnchor.anchor, detectedObjectAnchor.centerPoint, detectedObjectAnchor.cornerPoints, colorCorrectionRgba
            )
        }
    }

    /** Temporary arrays to prevent allocations in [createAnchor]. */
    private val convertFloats = FloatArray(4)
    private val convertFloatsOut = FloatArray(4)

    /**
     * Create an anchor using (x, y) coordinates in the [Coordinates2d.IMAGE_PIXELS] coordinate space.
     */
    fun createAnchor(xImage: Float, yImage: Float, frame: Frame, camera: Camera): HitResult? {
        // IMAGE_PIXELS -> VIEW
        convertFloats[0] = xImage
        convertFloats[1] = yImage
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS,
            convertFloats,
            Coordinates2d.VIEW,
            convertFloatsOut
        )

//        Log.d("carlos", "hitTest (${convertFloatsOut[0]}, ${convertFloatsOut[1]})")

        // Conduct a hit test using the VIEW coordinates
        val hits = frame.hitTest(convertFloatsOut[0], convertFloatsOut[1])

        val result = hits.firstOrNull { hit ->
            when (val trackable = hit.trackable!!) {
                is Plane ->
                    trackable.isPoseInPolygon(hit.hitPose) &&
                            PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0
                is Point -> false //trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                is InstantPlacementPoint -> false
                // DepthPoints are only returned if Config.DepthMode is set to AUTOMATIC.
                is DepthPoint -> false
                else -> false
            }
        } ?: return null

//    val result = hits.getOrNull(0) ?: return null
        return result
    }

    fun createCornerAnchor(xImage: Float, yImage: Float, frame: Frame): HitResult? {
        // IMAGE_PIXELS -> VIEW
        convertFloats[0] = xImage
        convertFloats[1] = yImage
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS,
            convertFloats,
            Coordinates2d.VIEW,
            convertFloatsOut
        )

        // Conduct a hit test using the VIEW coordinates
        return frame.hitTest(convertFloatsOut[0], convertFloatsOut[1]).firstOrNull()
    }

    companion object {
        private val TAG: String = AugmentedImageActivity::class.java.simpleName
    }
}

fun List<android.graphics.Point>.isInside(x: Float, y: Float): Boolean {
    return x > this[0].x
            && x < this[1].x
            && x > this[3].x
            && x < this[2].x
            && y > this[0].y
            && y < this[3].y
            && y > this[1].y
            && y < this[2].y
}

fun Frame.tryAcquireCameraImage() =
    try {
        acquireCameraImage()
    } catch (e: NotYetAvailableException) {
        null
    } catch (e: Throwable) {
        throw e
    }

data class DetectedObjectAnchor(
    val anchor: Anchor,
    val centerPoint: Vector3f,
    val cornerPoints: List<Vector3f>
) {
    fun detach() {
        anchor.detach()
    }
}
