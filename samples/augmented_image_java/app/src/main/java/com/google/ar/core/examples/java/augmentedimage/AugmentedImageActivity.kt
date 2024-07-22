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

import android.graphics.ImageFormat
import android.graphics.Point
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.SharedCamera
import com.google.ar.core.TrackingState
import com.google.ar.core.examples.java.augmentedimage.rendering.AugmentedImageRenderer
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
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
class AugmentedImageActivity : AppCompatActivity(), GLSurfaceView.Renderer, OnImageAvailableListener {
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

    private var detectedObjectAnchor: DetectedObjectAnchor? = null
    private var job: Job? = null
    private val coroutineScope = MainScope()

    // Whether the app has just entered non-AR mode.
    private val isFirstFrameWithoutArcore = AtomicBoolean(true)
    // Camera capture session. Used by both non-AR and AR modes.
    private var captureSession: CameraCaptureSession? = null
    // Reference to the camera system service.
    private var cameraManager: CameraManager? = null
    // Camera device. Used by both non-AR and AR modes.
    private var cameraDevice: CameraDevice? = null
    // Looper handler thread.
    private var backgroundThread: HandlerThread? = null
    // Looper handler.
    private var backgroundHandler: Handler? = null
    // ARCore shared camera instance, obtained from ARCore session that supports sharing.
    private var sharedCamera: SharedCamera? = null
    // Camera ID for the camera used by ARCore.
    private var cameraId: String? = null
    // Ensure GL surface draws only occur when new frames are available.
    private val shouldUpdateSurfaceTexture = AtomicBoolean(false)
    // Whether the GL surface has been created.
    private var surfaceCreated = false
    // Whether an error was thrown during session creation.
    private var errorCreatingSession = false
    // Camera preview capture request builder
    private var previewCaptureRequestBuilder: CaptureRequest.Builder? = null
    // Image reader that continuously processes CPU images.
    private var cpuImageReader: ImageReader? = null
    // Prevent any changes to camera capture session after CameraManager.openCamera() is called, but
    // before camera device becomes active.
    private var captureSessionChangesPossible = Mutex()
    // A check mechanism to ensure that the camera closed properly so that the app can safely exit.
    private val safeToExitApp = ConditionVariable()

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
        }

        waitUntilCameraCaptureSessionIsActive()
        startBackgroundThread()
        surfaceView.onResume()

        // When the activity starts and resumes for the first time, openCamera() will be called
        // from onSurfaceCreated(). In subsequent resumes we call openCamera() here.
        if (surfaceCreated) {
            openCamera()
        }
    }

    public override fun onPause() {
        shouldUpdateSurfaceTexture.set(false)
        surfaceView.onPause()
        waitUntilCameraCaptureSessionIsActive()
        displayRotationHelper!!.onPause()
        pauseARCore()
        closeCamera()
        stopBackgroundThread()
        super.onPause()
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
        surfaceCreated = true

        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread( /*context=*/this)
            augmentedImageRenderer.createOnGlThread( /*context=*/this)

            openCamera()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read an asset file", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        displayRotationHelper!!.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (!shouldUpdateSurfaceTexture.get()) {
            // Not ready to draw.
            return
        }

        if (session == null) {
            return
        }

        if (errorCreatingSession) {
            // Session not created, so nothing to draw.
            return
        }

        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper!!.updateSessionIfNeeded(session)

        try {
            if (job?.isActive != true) {
                Log.d("carloss", "onDrawFrameARCore")
                onDrawFrameARCore()
            } else {
                Log.d("carloss", "onDrawFrameCamera2")
                onDrawFrameCamera2()
            }
        } catch (t: Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    private fun onDrawFrameARCore() {
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
    }

    private fun configureSession() {
        val config = Config(session)
        config.setFocusMode(Config.FocusMode.AUTO)
//        config.setDepthMode(Config.DepthMode.AUTOMATIC)
        config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL)
//        config.setInstantPlacementMode(Config.InstantPlacementMode.LOCAL_Y_UP)
        config.setUpdateMode(Config.UpdateMode.BLOCKING)
        session!!.configure(config)
    }

    private fun drawAugmentedImages(
        frame: Frame, projmtx: FloatArray, viewmtx: FloatArray, colorCorrectionRgba: FloatArray
    ) {
        val cameraId = session!!.cameraConfig.cameraId
        val imageRotation = displayRotationHelper!!.getCameraSensorToDisplayRotation(cameraId)
//            Log.d("carlos", "detecting objects...")

        detectedObjectAnchor?.let { anchors ->
            if (anchors.anchor.trackingState != TrackingState.TRACKING) {
                Log.d("carlos", "lost tracking $anchors, removing anchor")
                anchors.detach()
                detectedObjectAnchor = null
            }
        }

        if (detectedObjectAnchor == null && frame.camera.trackingState == TrackingState.TRACKING) {
            job = coroutineScope.launch {
                Log.d("carloss", "no existing anchor && no lock")

                frame.tryAcquireCameraImage()?.let { image ->
                    val convertYuv = objectDetector.convertYuv(image)
                    image.close()

                    val screenCenterPoint = Point(convertYuv.width / 2, convertYuv.height / 2)
                    Log.d("carlos", "looking for anchor")
                    createAnchor(
                        screenCenterPoint.x.toFloat(),
                        screenCenterPoint.y.toFloat(),
                        frame,
                        frame.camera
                    )?.let { centerAnchor ->
                        val plane = centerAnchor.trackable as Plane
                        Log.d("carlos", "found anchor ${centerAnchor.hitPose}")

                        // Check if the current view has a document
                        objectDetector.analyze(convertYuv, imageRotation)?.let { cornerPoints ->
                            Log.d("carlos", "found 4 corners $cornerPoints")

                            val centerPoint = intersectionPoint(
                                cornerPoints[0] to cornerPoints[2],
                                cornerPoints[1] to cornerPoints[3]
                            )

                            if (cornerPoints.isInside(
                                    centerPoint.x.toFloat(),
                                    centerPoint.y.toFloat()
                                )
                            ) {
                                DetectedObject(
                                    cornerPoints = cornerPoints,
                                    centerPoint = centerPoint,
                                    plane = plane
                                )
                            } else {
                                null
                            }
                        }
                    }?.let {
                        createAnchor(
                            it.centerPoint.x.toFloat(),
                            it.centerPoint.y.toFloat(),
                            frame,
                            it.plane
                        )?.let { centerAnchor ->
                            val anchor = centerAnchor.createAnchor()

                            Log.d(
                                "carlos",
                                "camera (${frame.camera.pose.tx()}, ${frame.camera.pose.ty()}, ${frame.camera.pose.tz()})"
                            )

                            val cornerAnchors = it.cornerPoints.mapIndexed { index, corner ->
                                createAnchor(
                                    corner.x.toFloat(),
                                    corner.y.toFloat(),
                                    frame,
                                    it.plane
                                )?.hitPose?.let { pose ->
                                    Log.d("carlos", "corner $index anchor=${pose}")
                                    val rotation = pose.rotationQuaternion
                                    it.plane.createAnchor(
                                        pose.compose(
                                            Pose.makeRotation(0f, -rotation[1], 0f, rotation[3])
                                        )
                                    )
                                }
                            }.filterNotNull()

                            if (cornerAnchors.size == 4) {
                                detectedObjectAnchor = DetectedObjectAnchor(
                                    anchor = anchor,
                                    cornerAnchors = cornerAnchors
                                )
                            }
                        }
                    }
                } ?: run {
                    Log.d("carlos", "no anchor")
                }
            }
        }

        detectedObjectAnchor?.let { detectedObjectAnchor ->
            // Detected an object
            // Object is new
            Log.d("carloss", "drawing anchor")

            augmentedImageRenderer.draw(
                viewmtx, projmtx, detectedObjectAnchor.anchor, detectedObjectAnchor.cornerAnchors, colorCorrectionRgba
            )
        }
    }

    // Draw frame when in non-AR mode. Called on the GL thread.
    private fun onDrawFrameCamera2() {
        val texture = sharedCamera!!.surfaceTexture

        // ARCore may attach the SurfaceTexture to a different texture from the camera texture, so we
        // need to manually reattach it to our desired texture.
        if (isFirstFrameWithoutArcore.getAndSet(false)) {
            try {
                texture.detachFromGLContext()
            } catch (e: RuntimeException) {
                // Ignore if fails, it may not be attached yet.
            }
            texture.attachToGLContext(backgroundRenderer.textureId)
        }

        // Update the surface.
        texture.updateTexImage()

        // Account for any difference between camera sensor orientation and display orientation.
        val rotationDegrees = displayRotationHelper!!.getCameraSensorToDisplayRotation(cameraId)

        // Determine size of the camera preview image.
        val size = session!!.cameraConfig.textureSize

        // Determine aspect ratio of the output GL surface, accounting for the current display rotation
        // relative to the camera sensor orientation of the device.
        val displayAspectRatio =
            displayRotationHelper!!.getCameraSensorRelativeViewportAspectRatio(cameraId)

        // Render camera preview image to the GL surface.
        backgroundRenderer.draw(size.width, size.height, displayAspectRatio, rotationDegrees)
    }

    @Synchronized
    private fun waitUntilCameraCaptureSessionIsActive() {
        runBlocking {
            while (captureSessionChangesPossible.isLocked) {
                delay(100)
            }
        }
    }

    private fun resumeARCore() {
        // Ensure that session is valid before triggering ARCore resume. Handles the case where the user
        // manually uninstalls ARCore while the app is paused and then resumes.
        if (session == null) {
            return
        }

        try {
            // To avoid flicker when resuming ARCore mode inform the renderer to not suppress rendering
            // of the frames with zero timestamp.
            backgroundRenderer.suppressTimestampZeroRendering(false)
            // Resume ARCore.
            session!!.resume()

            // Set capture session callback while in AR mode.
            sharedCamera!!.setCaptureCallback(cameraCaptureCallback, backgroundHandler)
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Failed to resume ARCore session", e)
            return
        }
    }

    private fun pauseARCore() {
        // Pause ARCore.
        session?.pause()
        isFirstFrameWithoutArcore.set(true)
    }

    // Called when starting non-AR mode or switching to non-AR mode.
    // Also called when app starts in AR mode, or resumes in AR mode.
    private fun setRepeatingCaptureRequest() {
        try {
            captureSession!!.setRepeatingRequest(
                previewCaptureRequestBuilder!!.build(), cameraCaptureCallback, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to set repeating request", e)
        }
    }

    private fun createCameraPreviewSession() {
        try {
            session!!.setCameraTextureName(backgroundRenderer.textureId)

            // Create an ARCore compatible capture request using `TEMPLATE_RECORD`.
            previewCaptureRequestBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

            // Build surfaces list, starting with ARCore provided surfaces.
            val surfaceList = sharedCamera!!.arCoreSurfaces

            // Add a CPU image reader surface. On devices that don't support CPU image access, the image
            // may arrive significantly later, or not arrive at all.
            surfaceList.add(cpuImageReader!!.surface)

            // Surface list should now contain three surfaces:
            // 0. sharedCamera.getSurfaceTexture()
            // 1. …
            // 2. cpuImageReader.getSurface()

            // Add ARCore surfaces and CPU image surface targets.
            for (surface in surfaceList) {
                previewCaptureRequestBuilder!!.addTarget(surface)
            }

            // Wrap our callback in a shared camera callback.
            val wrappedCallback =
                sharedCamera!!.createARSessionStateCallback(
                    cameraSessionStateCallback,
                    backgroundHandler
                )

            // Create camera capture session for camera preview using ARCore wrapped callback.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cameraDevice!!.createCaptureSession(
                    SessionConfiguration(
                        SESSION_REGULAR,
                        surfaceList.map { OutputConfiguration(it) },
                        Executors.newSingleThreadExecutor(),
                        wrappedCallback
                    )
                )
            } else {
                cameraDevice!!.createCaptureSession(surfaceList, wrappedCallback, backgroundHandler)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "CameraAccessException", e)
        }
    }

    // Start background handler thread, used to run callbacks without blocking UI thread.
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("sharedCameraBackground")
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    // Stop background handler thread.
    private fun stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread!!.quitSafely()
            try {
                backgroundThread!!.join()
                backgroundThread = null
                backgroundHandler = null
            } catch (e: InterruptedException) {
                Log.e(TAG, "Interrupted while trying to join background handler thread", e)
            }
        }
    }

    // Perform various checks, then open camera device and create CPU image reader.
    private fun openCamera() {
        // Don't open camera if already opened.
        if (cameraDevice != null) {
            return
        }

        // Verify CAMERA_PERMISSION has been granted.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            CameraPermissionHelper.requestCameraPermission(this)
            return
        }

        // Make sure that ARCore is installed, up to date, and supported on this device.
        if (installRequested) {
            return
        }

        if (session == null) {
            try {
                // Create ARCore session that supports camera sharing.
                session = Session(this, EnumSet.of(Session.Feature.SHARED_CAMERA))
            } catch (e: Exception) {
                errorCreatingSession = true
                Log.e(TAG, "Failed to create ARCore session that supports camera sharing", e)
                return
            }

            errorCreatingSession = false

            // Enable auto focus mode while ARCore is running.
            configureSession()
        }

        // Store the ARCore shared camera reference.
        sharedCamera = session!!.sharedCamera

        // Store the ID of the camera used by ARCore.
        cameraId = session!!.cameraConfig.cameraId

        // Use the currently configured CPU image size.
        val desiredCpuImageSize = session!!.cameraConfig.imageSize
        cpuImageReader =
            ImageReader.newInstance(
                desiredCpuImageSize.width,
                desiredCpuImageSize.height,
                ImageFormat.YUV_420_888,
                2
            )
        cpuImageReader!!.setOnImageAvailableListener(this, backgroundHandler)

        // When ARCore is running, make sure it also updates our CPU image surface.
        sharedCamera!!.setAppSurfaces(
            this.cameraId, listOf(
                cpuImageReader!!.surface
            )
        )

        try {
            // Wrap our callback in a shared camera callback.

            val wrappedCallback =
                sharedCamera!!.createARDeviceStateCallback(cameraDeviceCallback, backgroundHandler)

            // Store a reference to the camera system service.
            cameraManager = this.getSystemService(CAMERA_SERVICE) as CameraManager

            // Get the characteristics for the ARCore camera.
//            val characteristics = cameraManager!!.getCameraCharacteristics(this.cameraId!!)

            // Prevent app crashes due to quick operations on camera open / close by waiting for the
            // capture session's onActive() callback to be triggered.
            captureSessionChangesPossible.tryLock()

            // Open the camera device using the ARCore wrapped callback.
            cameraManager!!.openCamera(cameraId!!, wrappedCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed to open camera", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    // Close the camera device.
    private fun closeCamera() {
        if (captureSession != null) {
            captureSession!!.close()
            captureSession = null
        }
        if (cameraDevice != null) {
            waitUntilCameraCaptureSessionIsActive()
            safeToExitApp.close()
            cameraDevice!!.close()
            safeToExitApp.block()
        }
        if (cpuImageReader != null) {
            cpuImageReader!!.close()
            cpuImageReader = null
        }
    }

    // CPU image reader callback.
    override fun onImageAvailable(imageReader: ImageReader) {
        val image = imageReader.acquireLatestImage()
        if (image == null) {
            Log.w(TAG, "onImageAvailable: Skipping null image.")
            return
        }

        image.close()
    }

    // Camera device state callback.
    private val cameraDeviceCallback: CameraDevice.StateCallback =
        object : CameraDevice.StateCallback() {
            override fun onOpened(cameraDevice: CameraDevice) {
                Log.d(TAG, "Camera device ID " + cameraDevice.id + " opened.")
                this@AugmentedImageActivity.cameraDevice = cameraDevice
                createCameraPreviewSession()
            }

            override fun onClosed(cameraDevice: CameraDevice) {
                Log.d(TAG, "Camera device ID " + cameraDevice.id + " closed.")
                this@AugmentedImageActivity.cameraDevice = null
                safeToExitApp.open()
            }

            override fun onDisconnected(cameraDevice: CameraDevice) {
                Log.w(TAG, "Camera device ID " + cameraDevice.id + " disconnected.")
                cameraDevice.close()
                this@AugmentedImageActivity.cameraDevice = null
            }

            override fun onError(cameraDevice: CameraDevice, error: Int) {
                Log.e(TAG, "Camera device ID " + cameraDevice.id + " error " + error)
                cameraDevice.close()
                this@AugmentedImageActivity.cameraDevice = null
                // Fatal error. Quit application.
                finish()
            }
        }

    // Repeating camera capture session state callback.
    private val cameraSessionStateCallback: CameraCaptureSession.StateCallback =
        object : CameraCaptureSession.StateCallback() {
            // Called when the camera capture session is first configured after the app
            // is initialized, and again each time the activity is resumed.
            override fun onConfigured(session: CameraCaptureSession) {
                Log.d(TAG, "Camera capture session configured.")
                captureSession = session
                setRepeatingCaptureRequest()
            }

            override fun onSurfacePrepared(
                session: CameraCaptureSession, surface: Surface
            ) {
                Log.d(TAG, "Camera capture surface prepared.")
            }

            override fun onReady(session: CameraCaptureSession) {
                Log.d(TAG, "Camera capture session ready.")
            }

            override fun onActive(session: CameraCaptureSession) {
                Log.d(TAG, "Camera capture session active.")
                resumeARCore()

                captureSessionChangesPossible.unlock()
            }

            override fun onCaptureQueueEmpty(session: CameraCaptureSession) {
                Log.w(TAG, "Camera capture queue empty.")
            }

            override fun onClosed(session: CameraCaptureSession) {
                Log.d(TAG, "Camera capture session closed.")
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Failed to configure camera capture session.")
            }
        }

    // Repeating camera capture session capture callback.
    private val cameraCaptureCallback: CaptureCallback = object : CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            shouldUpdateSurfaceTexture.set(true)
        }

        override fun onCaptureBufferLost(
            session: CameraCaptureSession,
            request: CaptureRequest,
            target: Surface,
            frameNumber: Long
        ) {
            Log.e(TAG, "onCaptureBufferLost: $frameNumber")
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            Log.e(TAG, "onCaptureFailed: " + failure.frameNumber + " " + failure.reason)
        }

        override fun onCaptureSequenceAborted(
            session: CameraCaptureSession, sequenceId: Int
        ) {
            Log.e(TAG, "onCaptureSequenceAborted: $sequenceId $session")
        }
    }

    companion object {
        private val TAG: String = AugmentedImageActivity::class.java.simpleName
    }
}

data class DetectedObjectAnchor(
    val anchor: Anchor,
    val cornerAnchors: List<Anchor>
) {
    fun detach() {
        anchor.detach()
        cornerAnchors.forEach { it.detach() }
    }
}

data class DetectedObject(
    val cornerPoints: List<Point>,
    val centerPoint: Point,
    val plane: Plane,
)
