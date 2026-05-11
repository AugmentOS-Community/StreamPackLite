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


/**
 * This class essentially represents a viewport-sized sprite that will be rendered with
 * a texture, usually from an external source like the camera or video decoder.
 *
 * (Contains mostly code borrowed from graphika)
 */
class FullFrameRect(var program: Texture2DProgram) {
    private val mvpMatrix = FloatArray(16)
    private val cropMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)
    private var hasCrop = false

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
            val fb: FloatBuffer = bb.asFloatBuffer()
            fb.put(coords)
            fb.position(0)
            return fb
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

    /**
     * Sets up a crop matrix to center-crop the source aspect ratio into the target
     * aspect ratio. Applied after the SurfaceTexture transform so both compose correctly.
     * Fills the output completely with no black bars.
     *
     * For example, 4:3 source → 16:9 target crops the top and bottom of the frame.
     *
     * @param sourceAspect width/height of the camera source
     * @param targetAspect width/height of the encoder output
     */
    fun setCropForAspectRatio(sourceAspect: Float, targetAspect: Float) {
        if (sourceAspect <= 0f || targetAspect <= 0f) {
            hasCrop = false
            return
        }

        val ratio = sourceAspect / targetAspect
        val scaleX: Float
        val scaleY: Float

        if (ratio > 1.001f) {
            // Source is wider than target — zoom into center horizontally
            scaleX = 1f / ratio
            scaleY = 1f
        } else if (ratio < 0.999f) {
            // Source is taller than target — zoom into center vertically
            scaleX = 1f
            scaleY = ratio
        } else {
            hasCrop = false
            return
        }

        // Build a matrix that scales around the center (0.5, 0.5) in texture space:
        // translate to origin, scale, translate back
        Matrix.setIdentityM(cropMatrix, 0)
        Matrix.translateM(cropMatrix, 0, (1f - scaleX) / 2f, (1f - scaleY) / 2f, 0f)
        Matrix.scaleM(cropMatrix, 0, scaleX, scaleY, 1f)
        hasCrop = true
    }

    fun setMVPMatrixAndViewPort(rotation: Float, resolution: Size, mirroredVertically: Boolean) {
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.scaleM(mvpMatrix, 0, if (mirroredVertically) -1f else 1f, 1f, 0f)
        Matrix.rotateM(
            mvpMatrix, 0,
            rotation, 0f, 0f, -1f
        )
        GLES20.glViewport(0, 0, resolution.width, resolution.height)
    }

    /**
     * Draws a viewport-filling rect, texturing it with the specified texture object.
     */
    fun drawFrame(textureId: Int, texMatrix: FloatArray) {
        // Compose crop matrix with SurfaceTexture transform: crop * texMatrix
        // This applies the SurfaceTexture transform first, then crops the result
        val finalTexMatrix = if (hasCrop) {
            Matrix.multiplyMM(tempMatrix, 0, cropMatrix, 0, texMatrix, 0)
            tempMatrix
        } else {
            texMatrix
        }
        program.draw(
            mvpMatrix, FULL_RECTANGLE_BUF, 0,
            4, 2, 2 * Float.SIZE_BYTES,
            finalTexMatrix, FULL_RECTANGLE_TEX_BUF, textureId, 2 * Float.SIZE_BYTES
        )
    }
}