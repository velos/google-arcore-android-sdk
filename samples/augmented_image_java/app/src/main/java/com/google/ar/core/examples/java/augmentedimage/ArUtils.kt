package com.google.ar.core.examples.java.augmentedimage

import android.graphics.Point
import android.media.Image
import android.util.Log
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Trackable
import com.google.ar.core.examples.java.common.rendering.PlaneRenderer
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import javax.vecmath.Vector2f
import javax.vecmath.Vector3f

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
            is com.google.ar.core.Point -> false //trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            is InstantPlacementPoint -> false
            // DepthPoints are only returned if Config.DepthMode is set to AUTOMATIC.
            is DepthPoint -> false
            else -> false
        }
    } ?: return null

//    val result = hits.getOrNull(0) ?: return null
    return result
}

fun createAnchor(xImage: Float, yImage: Float, frame: Frame, trackable: Trackable): HitResult? {
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
        hit.trackable == trackable
    }

    return result
}

fun createAnchor(point: Vector3f, frame: Frame, camera: Camera): HitResult? {
    val cameraPosition = camera.pose.translation
    Log.d("carlos", "hitTest (${cameraPosition[0]}, ${cameraPosition[1]}, ${cameraPosition[2]}) => ${point}")

    val hits = frame.hitTest(
        cameraPosition,
        0,
        floatArrayOf(point.x, point.y, point.z),
        0
    )
    Log.d("carlos", "hits: ${hits.map { it.trackable.javaClass.simpleName }}")

    val result = hits.firstOrNull { hit ->
        when (val trackable = hit.trackable!!) {
            is Plane ->
                trackable.isPoseInPolygon(hit.hitPose) &&
                        PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0
            is com.google.ar.core.Point -> false //trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            is InstantPlacementPoint -> false
            // DepthPoints are only returned if Config.DepthMode is set to AUTOMATIC.
            is DepthPoint -> false
            else -> false
        }
    } ?: return null

    //    val result = hits.getOrNull(0) ?: return null
    return result
}

fun createCornerAnchor(xImage: Float, yImage: Float, distance: Float, frame: Frame): HitResult {
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
    return frame.hitTestInstantPlacement(convertFloatsOut[0], convertFloatsOut[1], distance).first()
}

fun getWorldCoordinates(point: Point, frame: Frame, projmtx: FloatArray, viewmtx: FloatArray, width: Int, height: Int, distance: Float): Vector3f {
    val convertFloats = FloatArray(4)
    val convertFloatsOut = FloatArray(4)

    convertFloats[0] = point.x.toFloat()
    convertFloats[1] = point.y.toFloat()
    frame.transformCoordinates2d(
        Coordinates2d.IMAGE_PIXELS,
        convertFloats,
        Coordinates2d.VIEW,
        convertFloatsOut
    )
    return LineUtils.GetWorldCoords(
        Vector2f(convertFloatsOut),
        width.toFloat(),
        height.toFloat(),
        projmtx,
        viewmtx,
        distance
    )
}

fun getDistance(point: Point, frame: Frame, depthImage: Image): Float {
    val depthIn = FloatArray(2)
    val depthOut = FloatArray(2)
    depthIn[0] = point.x.toFloat()
    depthIn[1] = point.y.toFloat()
    frame.transformCoordinates2d(
        Coordinates2d.IMAGE_PIXELS,
        depthIn,
        Coordinates2d.TEXTURE_NORMALIZED,
        depthOut
    )
    val depthCenterPoint = Point(
        (depthOut[0] * depthImage.width).toInt(),
        (depthOut[1] * depthImage.height).toInt()
    )
    Log.d(
        "carlos",
        "converting depth: (${depthIn[0]}, ${depthIn[1]}) => (${depthOut[0]}, ${depthOut[1]})"
    )
    Log.d(
        "carlos",
        "getting depth: ${depthImage.width} x ${depthImage.height} (${depthCenterPoint.x}, ${depthCenterPoint.y})"
    )
    return getMetersDepth(
        depthImage,
        depthCenterPoint.x,
        depthCenterPoint.y
    )
}

fun List<Point>.isInside(x: Float, y: Float): Boolean {
    return x > this[0].x
            && x < this[1].x
            && x > this[3].x
            && x < this[2].x
            && y > this[0].y
            && y < this[3].y
            && y > this[1].y
            && y < this[2].y
}

fun intersectionPoint(line1: Pair<Point, Point>, line2: Pair<Point, Point>): Point {
    val p0_x = line1.first.x
    val p0_y = line1.first.y
    val p1_x = line1.second.x
    val p1_y = line1.second.y

    val p2_x = line2.first.x
    val p2_y = line2.first.y
    val p3_x = line2.second.x
    val p3_y = line2.second.y

    val s1_x = p1_x - p0_x
    val s1_y = p1_y - p0_y
    val s2_x = p3_x - p2_x
    val s2_y = p3_y - p2_y

    val t = (s2_x * (p0_y - p2_y) - s2_y * (p0_x - p2_x)).toFloat() / (-s2_x * s1_y + s1_x * s2_y).toFloat()

    return Point((p0_x + (t * s1_x)).toInt(), (p0_y + (t * s1_y)).toInt())
}

/** Obtain the depth in millimeters for [depthImage] at coordinates ([x], [y]). */
fun getMetersDepth(depthImage: Image, x: Int, y: Int): Float {
    // The depth image has a single plane, which stores depth for each
    // pixel as 16-bit unsigned integers.
    val plane = depthImage.planes[0]
    val byteIndex = x * plane.pixelStride + y * plane.rowStride
    val buffer = plane.buffer.order(ByteOrder.nativeOrder())
    val depthSample = buffer.getShort(byteIndex)
    return (depthSample.toDouble() / 1000.0).toFloat()
}

fun Frame.tryAcquireCameraImage() =
    try {
        acquireCameraImage()
    } catch (e: NotYetAvailableException) {
        null
    } catch (e: Throwable) {
        throw e
    }

fun Frame.tryAcquireDepthImage() =
    try {
        acquireDepthImage16Bits()
    } catch (e: NotYetAvailableException) {
        null
    } catch (e: Throwable) {
        throw e
    }
