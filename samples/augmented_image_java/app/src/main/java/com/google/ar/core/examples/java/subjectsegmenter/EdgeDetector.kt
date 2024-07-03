package com.google.mlkit.vision.demo.kotlin.subjectsegmenter

import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.FloatBuffer

// From:
// https://github.com/google-ar/arcore-android-sdk/blob/master/samples/computervision_java/app/src/main/java/com/google/ar/core/examples/java/computervision/EdgeDetector.java
/** Detects edges from input YUV image.  */
class EdgeDetector {
    private var inputPixels = FloatArray(0) // Reuse java byte array to avoid multiple allocations.

    /**
     * Process a grayscale image using the Sobel edge detector.
     *
     * @param width image width.
     * @param height image height.
     * @param stride image stride (number of bytes per row, equals to width if no row padding).
     * @param input bytes of the image, assumed single channel grayscale of size [stride * height].
     * @return bytes of the processed image, where the byte value is the strength of the edge at that
     * pixel. Number of bytes is width * height, row padding (if any) is removed.
     */
    @Synchronized
    fun detect(width: Int, height: Int, stride: Int, input: FloatBuffer): IntArray {
        // Reallocate input byte array if its size is different from the required size.
        if (stride * height > inputPixels.size) {
            inputPixels = FloatArray(stride * height)
        }

        // Allocate a new output byte array.
        val outputPixels = IntArray(width * height)

        // Copy input buffer into a java array for ease of access. This is not the most optimal
        // way to process an image, but used here for simplicity.
        input.position(0)

        // Note: On certain devices with specific resolution where the stride is not equal to the width.
        // In such situation the memory allocated for the frame may not be exact multiple of stride x
        // height hence the capacity of the ByteBuffer could be less. To handle such situations it will
        // be better to transfer the exact amount of image bytes to the destination bytes.
        input[inputPixels, 0, input.capacity()]

        // Detect edges.
        for (j in 1 until height - 1) {
            for (i in 1 until width - 1) {
                // Offset of the pixel at [i, j] of the input image.
                val offset = (j * stride) + i

                // Neighbour pixels around the pixel at [i, j].
                val a00 = inputPixels[offset - stride - 1] * 255
                val a01 = inputPixels[offset - stride] * 255
                val a02 = inputPixels[offset - stride + 1] * 255
                val a10 = inputPixels[offset - 1] * 255
                val a12 = inputPixels[offset + 1] * 255
                val a20 = inputPixels[offset + stride - 1] * 255
                val a21 = inputPixels[offset + stride] * 255
                val a22 = inputPixels[offset + stride + 1] * 255

                // Sobel X filter:
                //   -1, 0, 1,
                //   -2, 0, 2,
                //   -1, 0, 1
                val xSum = -a00 - (2 * a10) - a20 + a02 + (2 * a12) + a22

                // Sobel Y filter:
                //    1, 2, 1,
                //    0, 0, 0,
                //   -1, -2, -1
                val ySum = a00 + (2 * a01) + a02 - a20 - (2 * a21) - a22

                if ((xSum * xSum) + (ySum * ySum) > SOBEL_EDGE_THRESHOLD) {
                    outputPixels[j * width + i] = 255//Color.argb(255, 255, 0, 0)//0xFF
                } else {
                    outputPixels[j * width + i] = 0//0x1F
                }
            }
        }

        return outputPixels
    }

    companion object {
        private const val SOBEL_EDGE_THRESHOLD = 128 * 128
    }
}
