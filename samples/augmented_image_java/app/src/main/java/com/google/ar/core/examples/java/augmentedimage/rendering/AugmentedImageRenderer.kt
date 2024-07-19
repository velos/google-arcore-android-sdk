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
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer
import java.io.IOException

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
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        centerAnchor: Anchor,
        cornerAnchors: List<Anchor>,
        colorCorrectionRgba: FloatArray,
    ) {
        Log.d(TAG, "drawing $centerAnchor")
        val tintColor = convertHexToColor(TINT_COLORS_HEX[0])

        val anchorPose = centerAnchor.pose

        val scaleFactor = 1.0f
        val modelMatrix = FloatArray(16)

        anchorPose.toMatrix(modelMatrix, 0)
        imageFrameUpperLeft.updateModelMatrix(modelMatrix, scaleFactor)
        imageFrameUpperLeft.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, tintColor)

        val worldBoundaryPoses = arrayOfNulls<Pose>(4)
        for (i in 0..3) {
            worldBoundaryPoses[i] = cornerAnchors[i].pose
        }

        worldBoundaryPoses[0]!!.toMatrix(modelMatrix, 0)
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
