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
package com.google.ar.core.examples.java.sharedcamera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.opengl.GLES20
import android.opengl.GLSurfaceView
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
import com.google.ar.core.ArCoreApk.Availability
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.SharedCamera
import com.google.ar.core.TrackingState
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.helpers.TapHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer
import com.google.ar.core.examples.java.common.rendering.PlaneRenderer
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.SessionNotPausedException
import com.google.ar.core.exceptions.UnavailableException
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

/**
 * This is a simple example that demonstrates how to use the Camera2 API while sharing camera access
 * with ARCore. An on-screen switch can be used to pause and resume ARCore. The app utilizes a
 * trivial sepia camera effect while ARCore is paused, and seamlessly hands camera capture request
 * control over to ARCore when it is running.
 *
 *
 * This app demonstrates:
 *
 *
 *  * Starting in AR or non-AR mode by setting the initial value of `arMode`
 *  * Toggling between non-AR and AR mode using an on screen switch
 *  * Pausing and resuming the app while in AR or non-AR mode
 *  * Requesting CAMERA_PERMISSION when app starts, and each time the app is resumed
 *
 */
class SharedCameraActivity : AppCompatActivity(), GLSurfaceView.Renderer, OnImageAvailableListener {
    // Whether the app has just entered non-AR mode.
    private val isFirstFrameWithoutArcore = AtomicBoolean(true)

    // GL Surface used to draw camera preview image.
    private var surfaceView: GLSurfaceView? = null

    // ARCore session that supports camera sharing.
    private var sharedSession: Session? = null

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

    // Whether ARCore is currently active.
    // TODO is this needed?
    private var arcoreActive = false

    // Whether the GL surface has been created.
    private var surfaceCreated = false

    /**
     * Whether an error was thrown during session creation.
     */
    private var errorCreatingSession = false

    // Camera preview capture request builder
    private var previewCaptureRequestBuilder: CaptureRequest.Builder? = null

    // Image reader that continuously processes CPU images.
    private var cpuImageReader: ImageReader? = null

    // Various helper classes, see hello_ar_java sample to learn more.
    private var displayRotationHelper: DisplayRotationHelper? = null
    private val trackingStateHelper = TrackingStateHelper(this)
    private var tapHelper: TapHelper? = null

    // Renderers, see hello_ar_java sample to learn more.
    private val backgroundRenderer = BackgroundRenderer()
    private val virtualObject = ObjectRenderer()
    private val planeRenderer = PlaneRenderer()

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private val anchorMatrix = FloatArray(16)

    // Anchors created from taps, see hello_ar_java sample to learn more.
    private var anchor: ColoredAnchor? = null

    // Prevent any changes to camera capture session after CameraManager.openCamera() is called, but
    // before camera device becomes active.
    private var captureSessionChangesPossible = Mutex()

    // A check mechanism to ensure that the camera closed properly so that the app can safely exit.
    private val safeToExitApp = ConditionVariable()

    private class ColoredAnchor(val anchor: Anchor, val color: FloatArray)

    private var job: Job? = null
    private val coroutineScope = MainScope()

    // Camera device state callback.
    private val cameraDeviceCallback: CameraDevice.StateCallback =
        object : CameraDevice.StateCallback() {
            override fun onOpened(cameraDevice: CameraDevice) {
                Log.d(TAG, "Camera device ID " + cameraDevice.id + " opened.")
                this@SharedCameraActivity.cameraDevice = cameraDevice
                createCameraPreviewSession()
            }

            override fun onClosed(cameraDevice: CameraDevice) {
                Log.d(TAG, "Camera device ID " + cameraDevice.id + " closed.")
                this@SharedCameraActivity.cameraDevice = null
                safeToExitApp.open()
            }

            override fun onDisconnected(cameraDevice: CameraDevice) {
                Log.w(TAG, "Camera device ID " + cameraDevice.id + " disconnected.")
                cameraDevice.close()
                this@SharedCameraActivity.cameraDevice = null
            }

            override fun onError(cameraDevice: CameraDevice, error: Int) {
                Log.e(TAG, "Camera device ID " + cameraDevice.id + " error " + error)
                cameraDevice.close()
                this@SharedCameraActivity.cameraDevice = null
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
                if (!arcoreActive) {
                    resumeARCore()
                }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // GL surface view that renders camera preview image.
        surfaceView = findViewById(R.id.glsurfaceview)
        surfaceView!!.setPreserveEGLContextOnPause(true)
        surfaceView!!.setEGLContextClientVersion(2)
        surfaceView!!.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView!!.setRenderer(this)
        surfaceView!!.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY)

        // Helpers, see hello_ar_java sample to learn more.
        displayRotationHelper = DisplayRotationHelper(this)
        tapHelper = TapHelper(this)
        surfaceView!!.setOnTouchListener(tapHelper)
    }

    override fun onDestroy() {
        if (sharedSession != null) {
            // Explicitly close ARCore Session to release native resources.
            // Review the API reference for important considerations before calling close() in apps with
            // more complicated lifecycle requirements:
            // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
            sharedSession!!.close()
            sharedSession = null
        }

        super.onDestroy()
    }

    @Synchronized
    private fun waitUntilCameraCaptureSessionIsActive() {
        runBlocking {
            while (captureSessionChangesPossible.isLocked) {
                delay(100)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        waitUntilCameraCaptureSessionIsActive()
        startBackgroundThread()
        surfaceView!!.onResume()

        // When the activity starts and resumes for the first time, openCamera() will be called
        // from onSurfaceCreated(). In subsequent resumes we call openCamera() here.
        if (surfaceCreated) {
            openCamera()
        }

        displayRotationHelper!!.onResume()
    }

    public override fun onPause() {
        shouldUpdateSurfaceTexture.set(false)
        surfaceView!!.onPause()
        waitUntilCameraCaptureSessionIsActive()
        displayRotationHelper!!.onPause()
        pauseARCore()
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun resumeARCore() {
        // Ensure that session is valid before triggering ARCore resume. Handles the case where the user
        // manually uninstalls ARCore while the app is paused and then resumes.
        if (sharedSession == null) {
            return
        }

        if (!arcoreActive) {
            try {
                // To avoid flicker when resuming ARCore mode inform the renderer to not suppress rendering
                // of the frames with zero timestamp.
                backgroundRenderer.suppressTimestampZeroRendering(false)
                // Resume ARCore.
                try {
                    sharedSession!!.resume()
                } catch (_: SessionNotPausedException) { }
                arcoreActive = true

                // Set capture session callback while in AR mode.
                sharedCamera!!.setCaptureCallback(cameraCaptureCallback, backgroundHandler)
            } catch (e: CameraNotAvailableException) {
                Log.e(TAG, "Failed to resume ARCore session", e)
                return
            }
        }
    }

    private fun pauseARCore() {
        if (arcoreActive) {
            // Pause ARCore.
            sharedSession!!.pause()
            isFirstFrameWithoutArcore.set(true)
            arcoreActive = false
        }
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
            sharedSession!!.setCameraTextureName(backgroundRenderer.textureId)

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
            cameraDevice!!.createCaptureSession(surfaceList, wrappedCallback, backgroundHandler)
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
        if (!isARCoreSupportedAndUpToDate) {
            return
        }

        if (sharedSession == null) {
            try {
                // Create ARCore session that supports camera sharing.
                sharedSession = Session(this, EnumSet.of(Session.Feature.SHARED_CAMERA))
            } catch (e: Exception) {
                errorCreatingSession = true
                Log.e(TAG, "Failed to create ARCore session that supports camera sharing", e)
                return
            }

            errorCreatingSession = false

            // Enable auto focus mode while ARCore is running.
            val config = sharedSession!!.config
            config.setFocusMode(Config.FocusMode.AUTO)
            sharedSession!!.configure(config)
        }

        // Store the ARCore shared camera reference.
        sharedCamera = sharedSession!!.sharedCamera

        // Store the ID of the camera used by ARCore.
        cameraId = sharedSession!!.cameraConfig.cameraId

        // Use the currently configured CPU image size.
        val desiredCpuImageSize = sharedSession!!.cameraConfig.imageSize
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

    // Android permission request callback.
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(
                applicationContext,
                "Camera permission is needed to run this application",
                Toast.LENGTH_LONG
            )
                .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    // Android focus change callback.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    // GL surface created callback. Will be called on the GL thread.
    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        surfaceCreated = true

        // Set GL clear color to black.
        GLES20.glClearColor(0f, 0f, 0f, 1.0f)

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the camera preview image texture. Used in non-AR and AR mode.
            backgroundRenderer.createOnGlThread(this)
            planeRenderer.createOnGlThread(this, "models/trigrid.png")

            virtualObject.createOnGlThread(this, "models/andy.obj", "models/andy.png")
            virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f)

            openCamera()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read an asset file", e)
        }
    }

    // GL surface changed callback. Will be called on the GL thread.
    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        displayRotationHelper!!.onSurfaceChanged(width, height)
    }

    // GL draw callback. Will be called each frame on the GL thread.
    override fun onDrawFrame(gl: GL10) {
        // Use the cGL clear color specified in onSurfaceCreated() to erase the GL surface.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (!shouldUpdateSurfaceTexture.get()) {
            // Not ready to draw.
            return
        }

        // Handle display rotations.
        displayRotationHelper!!.updateSessionIfNeeded(sharedSession)

        try {
            if (job?.isActive != true) {
                onDrawFrameARCore()
            } else {
                onDrawFrameCamera2()
            }
        } catch (t: Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t)
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
        val size = sharedSession!!.cameraConfig.textureSize

        // Determine aspect ratio of the output GL surface, accounting for the current display rotation
        // relative to the camera sensor orientation of the device.
        val displayAspectRatio =
            displayRotationHelper!!.getCameraSensorRelativeViewportAspectRatio(cameraId)

        // Render camera preview image to the GL surface.
        backgroundRenderer.draw(size.width, size.height, displayAspectRatio, rotationDegrees)
    }

    // Draw frame when in AR mode. Called on the GL thread.
    @Throws(CameraNotAvailableException::class)
    fun onDrawFrameARCore() {
        if (!arcoreActive) {
            // ARCore not yet active, so nothing to draw yet.
            return
        }

        if (errorCreatingSession) {
            // Session not created, so nothing to draw.
            return
        }

        // Perform ARCore per-frame update.
        val frame = sharedSession!!.update()
        val camera = frame.camera

        // Handle screen tap.
        handleTap(frame, camera)

        // If frame is ready, render camera preview image to the GL surface.
        backgroundRenderer.draw(frame)

        // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
        trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

        // If not tracking, don't draw 3D objects.
        if (camera.trackingState == TrackingState.PAUSED) {
            return
        }

        // Get projection matrix.
        val projmtx = FloatArray(16)
        camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)

        // Get camera matrix and draw.
        val viewmtx = FloatArray(16)
        camera.getViewMatrix(viewmtx, 0)

        // Compute lighting from average intensity of the image.
        // The first three components are color scaling factors.
        // The last one is the average pixel intensity in gamma space.
        val colorCorrectionRgba = FloatArray(4)
        frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)

        // Visualize planes.
        planeRenderer.drawPlanes(
            sharedSession!!.getAllTrackables(Plane::class.java), camera.displayOrientedPose, projmtx
        )

        // Visualize anchors created by touch.
        val scaleFactor = 1.0f
        anchor?.let { coloredAnchor ->
            if (coloredAnchor.anchor.trackingState != TrackingState.TRACKING) {
                return@let
            }
            // Get the current pose of an Anchor in world space. The Anchor pose is updated
            // during calls to sharedSession.update() as ARCore refines its estimate of the world.
            coloredAnchor.anchor.pose.toMatrix(anchorMatrix, 0)

            // Update and draw the model and its shadow.
            virtualObject.updateModelMatrix(anchorMatrix, scaleFactor)
            virtualObject.draw(viewmtx, projmtx, colorCorrectionRgba, coloredAnchor.color)
        }
    }

    // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
    private fun handleTap(frame: Frame, camera: Camera) {
        val tap = tapHelper!!.poll()
        if (job?.isActive != true && tap != null && camera.trackingState == TrackingState.TRACKING) {
            job = coroutineScope.launch {
                delay(500)

                for (hit in frame.hitTest(tap)) {
                    // Check if any plane was hit, and if it was hit inside the plane polygon
                    val trackable = hit.trackable
                    // Creates an anchor if a plane or an oriented point was hit.
                    if ((trackable is Plane
                                && trackable.isPoseInPolygon(hit.hitPose)
                                && (PlaneRenderer.calculateDistanceToPlane(
                            hit.hitPose,
                            camera.pose
                        ) > 0))
                        || (trackable is Point
                                && trackable.orientationMode
                                == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)
                    ) {
                        // Hits are sorted by depth. Consider only closest hit on a plane or oriented point.
                        // Cap the number of objects created. This avoids overloading both the
                        // rendering system and ARCore.
                        anchor?.anchor?.detach()
                        anchor = null

                        // Assign a color to the object for rendering based on the trackable type
                        // this anchor attached to. For AR_TRACKABLE_POINT, it's blue color, and
                        // for AR_TRACKABLE_PLANE, it's green color.
                        val objColor = if (trackable is Point) {
                            floatArrayOf(66.0f, 133.0f, 244.0f, 255.0f)
                        } else if (trackable is Plane) {
                            floatArrayOf(139.0f, 195.0f, 74.0f, 255.0f)
                        } else {
                            DEFAULT_COLOR
                        }

                        // Adding an Anchor tells ARCore that it should track this position in
                        // space. This anchor is created on the Plane to place the 3D model
                        // in the correct position relative both to the world and to the plane.
                        anchor = ColoredAnchor(hit.createAnchor(), objColor)
                        break
                    }
                }
            }
        }
    }

    private val isARCoreSupportedAndUpToDate: Boolean
        get() {
            // Make sure ARCore is installed and supported on this device.
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            when (availability) {
                Availability.SUPPORTED_INSTALLED -> {}
                Availability.SUPPORTED_APK_TOO_OLD, Availability.SUPPORTED_NOT_INSTALLED -> try {
                    // Request ARCore installation or update if needed.
                    val installStatus =
                        ArCoreApk.getInstance().requestInstall(this,  /*userRequestedInstall=*/true)
                    when (installStatus) {
                        InstallStatus.INSTALL_REQUESTED -> {
                            Log.e(TAG, "ARCore installation requested.")
                            return false
                        }

                        InstallStatus.INSTALLED -> {}
                    }
                } catch (e: UnavailableException) {
                    Log.e(TAG, "ARCore not installed", e)
                    runOnUiThread {
                        Toast.makeText(
                            applicationContext, "ARCore not installed\n$e", Toast.LENGTH_LONG
                        )
                            .show()
                    }
                    finish()
                    return false
                }

                Availability.UNKNOWN_ERROR, Availability.UNKNOWN_CHECKING, Availability.UNKNOWN_TIMED_OUT, Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                    Log.e(
                        TAG,
                        "ARCore is not supported on this device, ArCoreApk.checkAvailability() returned "
                                + availability
                    )
                    runOnUiThread {
                        Toast.makeText(
                            applicationContext,
                            "ARCore is not supported on this device, "
                                    + "ArCoreApk.checkAvailability() returned "
                                    + availability,
                            Toast.LENGTH_LONG
                        )
                            .show()
                    }
                    return false
                }
            }
            return true
        }

    companion object {
        private val TAG: String = SharedCameraActivity::class.java.simpleName

        private val DEFAULT_COLOR = floatArrayOf(0f, 0f, 0f, 0f)
    }
}
