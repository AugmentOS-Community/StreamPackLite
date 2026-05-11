/*
 * Copyright 2018 Google Inc. All rights reserved.
 * Copyright 2021 Thibault B.
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
package io.github.thibaultbee.streampack.internal.gl

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Size
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


/**
 * This class essentially represents a viewport-sized sprite that will be rendered with
 * a texture, usually from an external source like the camera or video decoder.
 *
 * (Contains mostly code borrowed from graphika)
 */
class FullFrameRect(var program: Texture2DProgram) {
    private val mvpMatrix = FloatArray(16)
    private var texCoordBuffer: FloatBuffer = duplicateTexCoords(FULL_RECTANGLE_TEX_COORDS)

    companion object {
        /**
         * A "full" square, extending from -1 to +1 in both dimensions. When the
         * model/view/projection matrix is identity, this will exactly cover the viewport.
         */
        private val FULL_RECTANGLE_COORDS = floatArrayOf(
            -1.0f, -1.0f,  // 0 bottom left
            1.0f, -1.0f,  // 1 bottom right
            -1.0f, 1.0f,  // 2 top left
            1.0f, 1.0f
        )

        private val FULL_RECTANGLE_BUF: FloatBuffer = createFloatBuffer(FULL_RECTANGLE_COORDS)

        private val FULL_RECTANGLE_TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,  // 0 bottom left
            1.0f, 0.0f,  // 1 bottom right
            0.0f, 1.0f,  // 2 top left
            1.0f, 1.0f // 3 top right
        )
        private val FULL_RECTANGLE_TEX_BUF: FloatBuffer =
            createFloatBuffer(FULL_RECTANGLE_TEX_COORDS)

        /**
         * Allocates a direct float buffer, and populates it with the float array data.
         */
        private fun createFloatBuffer(coords: FloatArray): FloatBuffer {
            // Allocate a direct ByteBuffer, using 4 bytes per float, and copy coords into it.
            val bb: ByteBuffer = ByteBuffer.allocateDirect(coords.size * Float.SIZE_BYTES)
            bb.order(ByteOrder.nativeOrder())
            val fb = bb.asFloatBuffer()
            fb.put(coords)
            fb.position(0)
            return fb
        }

        private fun duplicateTexCoords(coords: FloatArray): FloatBuffer {
            val bb: ByteBuffer = ByteBuffer.allocateDirect(coords.size * Float.SIZE_BYTES)
            bb.order(ByteOrder.nativeOrder())
            val fb = bb.asFloatBuffer()
            fb.put(coords)
            fb.position(0)
            return fb
        }

        /** Center-crop rectangle in pixel space (top-left origin) matching target aspect. */
        private fun centerCropRect(
            captureWidth: Int,
            captureHeight: Int,
            targetWidth: Int,
            targetHeight: Int
        ): FloatArray {
            if (captureWidth <= 0 || captureHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
                return floatArrayOf(0f, 0f, 1f, 1f)
            }
            val sourceAspect = captureWidth / captureHeight.toFloat()
            val targetAspect = targetWidth / targetHeight.toFloat()
            var cropW = captureWidth
            var cropH = captureHeight
            if (abs(sourceAspect - targetAspect) > 0.0001f) {
                if (sourceAspect > targetAspect) {
                    cropW = (captureHeight * targetAspect).roundToInt()
                } else {
                    cropH = (captureWidth / targetAspect).roundToInt()
                }
            }
            cropW = max(1, min(captureWidth, cropW))
            cropH = max(1, min(captureHeight, cropH))
            val cropX = max(0, (captureWidth - cropW) / 2)
            val cropY = max(0, (captureHeight - cropH) / 2)
            // GL texture coords with v=0 at bottom (SurfaceTexture / OES convention)
            val u0 = cropX / captureWidth.toFloat()
            val u1 = (cropX + cropW) / captureWidth.toFloat()
            val v0 = (captureHeight - (cropY + cropH)) / captureHeight.toFloat()
            val v1 = (captureHeight - cropY) / captureHeight.toFloat()
            return floatArrayOf(
                u0, v0,
                u1, v0,
                u0, v1,
                u1, v1
            )
        }
    }

    /**
     * Releases resources.
     *
     *
     * This must be called with the appropriate EGL context current (i.e. the one that was
     * current when the constructor was called).  If we're about to destroy the EGL context,
     * there's no value in having the caller make it current just to do this cleanup, so you
     * can pass a flag that will tell this function to skip any EGL-context-specific cleanup.
     */
    fun release(doEglCleanup: Boolean) {
        if (doEglCleanup) {
            program.release()
        }
    }

    /**
     * Changes the program.  The previous program will be released.
     *
     *
     * The appropriate EGL context must be current.
     */
    fun changeProgram(program: Texture2DProgram) {
        this.program.release()
        this.program = program
    }

    /**
     * Creates a texture object suitable for use with drawFrame().
     */
    fun createTextureObject(): Int {
        return program.createTextureObject()
    }

    fun setMVPMatrixAndViewPort(rotation: Float, resolution: Size, mirroredVertically: Boolean) {
        setMVPMatrixViewPortAndCrop(rotation, resolution, resolution, mirroredVertically)
    }

    /**
     * Sets MVP + viewport to [viewport] size, and texture coordinates to center-crop [capture]
     * to match the aspect ratio of [viewport] after accounting for [rotation] (swap width/height
     * for 90° / 270° when comparing aspects, matching how the MVP rotates the drawn quad).
     */
    fun setMVPMatrixViewPortAndCrop(
        rotation: Float,
        viewport: Size,
        capture: Size,
        mirroredVertically: Boolean
    ) {
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.scaleM(mvpMatrix, 0, if (mirroredVertically) -1f else 1f, 1f, 0f)
        Matrix.rotateM(
            mvpMatrix, 0,
            rotation, 0f, 0f, -1f
        )
        GLES20.glViewport(0, 0, viewport.width, viewport.height)

        val rotNorm = ((rotation.toInt() % 360) + 360) % 360
        val aspectW: Int
        val aspectH: Int
        when (rotNorm) {
            90, 270 -> {
                aspectW = viewport.height
                aspectH = viewport.width
            }
            else -> {
                aspectW = viewport.width
                aspectH = viewport.height
            }
        }

        if (capture.width == viewport.width && capture.height == viewport.height) {
            texCoordBuffer = duplicateTexCoords(FULL_RECTANGLE_TEX_COORDS)
            return
        }

        if (aspectW == capture.width && aspectH == capture.height) {
            texCoordBuffer = duplicateTexCoords(FULL_RECTANGLE_TEX_COORDS)
            return
        }

        val coords = centerCropRect(
            capture.width, capture.height,
            aspectW, aspectH
        )
        texCoordBuffer = duplicateTexCoords(coords)
    }

    /**
     * Draws a viewport-filling rect, texturing it with the specified texture object.
     */
    fun drawFrame(textureId: Int, texMatrix: FloatArray) {
        // Use the identity matrix for MVP so our 2x2 FULL_RECTANGLE covers the viewport.
        program.draw(
            mvpMatrix, FULL_RECTANGLE_BUF, 0,
            4, 2, 2 * Float.SIZE_BYTES,
            texMatrix, texCoordBuffer, textureId, 2 * Float.SIZE_BYTES
        )
    }
}
