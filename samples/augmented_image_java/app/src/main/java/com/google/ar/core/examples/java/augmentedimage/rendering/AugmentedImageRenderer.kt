/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.java.augmentedimage.rendering

import android.content.Context
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Pose
import com.google.ar.core.dependencies.f
import com.google.ar.core.dependencies.i
import com.google.ar.core.examples.java.augmentedimage.DetectedObjectResult
import com.google.ar.core.examples.java.augmentedimage.LineUtils
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer
import java.io.IOException
import javax.vecmath.Vector2f

/** Renders an augmented image.  */
class AugmentedImageRenderer {
    private val imageFrameUpperLeft = ObjectRenderer()
    private val imageFrameUpperRight = ObjectRenderer()
    private val imageFrameLowerLeft = ObjectRenderer()
    private val imageFrameLowerRight = ObjectRenderer()

    @Throws(IOException::class)
    fun createOnGlThread(context: Context?) {
        imageFrameUpperLeft.createOnGlThread(
            context, "models/frame_upper_left.obj", "models/frame_base.png"
        )
        imageFrameUpperLeft.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f)
        imageFrameUpperLeft.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending)

        imageFrameUpperRight.createOnGlThread(
            context, "models/frame_upper_right.obj", "models/frame_base.png"
        )
        imageFrameUpperRight.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f)
        imageFrameUpperRight.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending)

        imageFrameLowerLeft.createOnGlThread(
            context, "models/frame_lower_left.obj", "models/frame_base.png"
        )
        imageFrameLowerLeft.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f)
        imageFrameLowerLeft.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending)

        imageFrameLowerRight.createOnGlThread(
            context, "models/frame_lower_right.obj", "models/frame_base.png"
        )
        imageFrameLowerRight.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f)
        imageFrameLowerRight.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending)
    }

    fun draw(
        viewMatrix: FloatArray?,
        projectionMatrix: FloatArray?,
        augmentedImage: DetectedObjectResult,
        centerAnchor: Anchor,
        colorCorrectionRgba: FloatArray?,
    ) {
        Log.d(TAG, "drawing $centerAnchor")
        val tintColor =
            convertHexToColor(TINT_COLORS_HEX[0])

        val coordinates = listOf(
            floatArrayOf(augmentedImage.boundingBox.left.toFloat(), augmentedImage.boundingBox.top.toFloat()),
            floatArrayOf(augmentedImage.boundingBox.right.toFloat(), augmentedImage.boundingBox.top.toFloat()),
            floatArrayOf(augmentedImage.boundingBox.right.toFloat(), augmentedImage.boundingBox.bottom.toFloat()),
            floatArrayOf(augmentedImage.boundingBox.left.toFloat(), augmentedImage.boundingBox.bottom.toFloat()),
        )

        val worldCoords = coordinates.mapIndexed { index, point ->
            LineUtils.GetWorldCoords(
                Vector2f(point),
                480f,
                640f,
                projectionMatrix,
                viewMatrix
            ).also {
                Log.d("carloss", "$index: (${coordinates[index][0]}, ${coordinates[index][1]}) => (${it.x}, ${it.y}, ${it.z})")
            }
        }


        val xRadius = 0.10795f // TODO calculate radius dynamically
        val yRadius = 0.1397f

        val localBoundaryPoses = arrayOf(
            Pose.makeTranslation(
                -xRadius,
                0.0f,
                -yRadius
            ),  // upper left
            Pose.makeTranslation(
                xRadius,
                0.0f,
                -yRadius
            ),  // upper right
            Pose.makeTranslation(
                xRadius,
                0.0f,
                yRadius
            ),  // lower right
            Pose.makeTranslation(
                -xRadius,
                0.0f,
                yRadius
            ) // lower left
        )

        val anchorPose = centerAnchor.pose
        val worldBoundaryPoses = arrayOfNulls<Pose>(4)
        for (i in 0..3) {
            worldBoundaryPoses[i] = anchorPose.compose(localBoundaryPoses[i])
        }

        val scaleFactor = 1.0f
        val modelMatrix = FloatArray(16)

        worldBoundaryPoses[0]!!.toMatrix(modelMatrix, 0)
//        Matrix.translateM(modelMatrix, 0, -0.5f * augmentedImage.boundingBox.width(), 0f, -0.5f * augmentedImage.boundingBox.height())
        imageFrameUpperLeft.updateModelMatrix(modelMatrix, scaleFactor)
        imageFrameUpperLeft.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, tintColor)

        worldBoundaryPoses[1]!!.toMatrix(modelMatrix, 0)
        imageFrameUpperRight.updateModelMatrix(modelMatrix, scaleFactor)
        imageFrameUpperRight.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, tintColor)

        worldBoundaryPoses[2]!!.toMatrix(modelMatrix, 0)
        imageFrameLowerRight.updateModelMatrix(modelMatrix, scaleFactor)
        imageFrameLowerRight.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, tintColor)

        worldBoundaryPoses[3]!!.toMatrix(modelMatrix, 0)
        imageFrameLowerLeft.updateModelMatrix(modelMatrix, scaleFactor)
        imageFrameLowerLeft.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, tintColor)
    }

    companion object {
        private const val TAG = "AugmentedImageRenderer"

        private const val TINT_INTENSITY = 0.1f
        private const val TINT_ALPHA = 1.0f
        private val TINT_COLORS_HEX = intArrayOf(
            0x000000,
            0xF44336,
            0xE91E63,
            0x9C27B0,
            0x673AB7,
            0x3F51B5,
            0x2196F3,
            0x03A9F4,
            0x00BCD4,
            0x009688,
            0x4CAF50,
            0x8BC34A,
            0xCDDC39,
            0xFFEB3B,
            0xFFC107,
            0xFF9800,
        )

        private fun convertHexToColor(colorHex: Int): FloatArray {
            // colorHex is in 0xRRGGBB format
            val red = ((colorHex and 0xFF0000) shr 16) / 255.0f * TINT_INTENSITY
            val green = ((colorHex and 0x00FF00) shr 8) / 255.0f * TINT_INTENSITY
            val blue = (colorHex and 0x0000FF) / 255.0f * TINT_INTENSITY
            return floatArrayOf(red, green, blue, TINT_ALPHA)
        }
    }
}
