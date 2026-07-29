/*
 * Copyright (C) 2021 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.internal.encoders

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Size
import android.view.Surface
import io.github.thibaultbee.streampack.data.Config
import io.github.thibaultbee.streampack.data.VideoConfig
import io.github.thibaultbee.streampack.internal.gl.EglWindowSurface
import io.github.thibaultbee.streampack.internal.gl.FullFrameRect
import io.github.thibaultbee.streampack.internal.gl.Texture2DProgram
import io.github.thibaultbee.streampack.internal.orientation.ISourceOrientationListener
import io.github.thibaultbee.streampack.internal.orientation.ISourceOrientationProvider
import io.github.thibaultbee.streampack.internal.utils.av.video.DynamicRangeProfile
import io.github.thibaultbee.streampack.listeners.OnErrorListener
import java.util.concurrent.Executors

/**
 * Encoder for video using MediaCodec.
 *
 * @param useSurfaceMode to get video frames, if [Boolean.true],the encoder will use Surface mode, else Buffer mode with [IEncoderListener.onInputFrame].
 * @param orientationProvider to get the orientation of the source. If null, the source will keep its original dimensions.
 */
class VideoMediaCodecEncoder(
    encoderListener: IEncoderListener,
    override val onInternalErrorListener: OnErrorListener,
    private val useSurfaceMode: Boolean,
    private val orientationProvider: ISourceOrientationProvider?
) :
    MediaCodecEncoder<VideoConfig>(encoderListener) {
    val codecSurface = if (useSurfaceMode) {
        CodecSurface(orientationProvider)
    } else {
        null
    }

    private var _bitrate: Int? = null
    override var bitrate: Int = 0
        get() = _bitrate ?: super.bitrate
        set(value) {
            val bundle = Bundle()
            bundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, value)
            mediaCodec?.setParameters(bundle)
            field = value
            _bitrate = value
        }

    override fun onNewMediaCodec(mediaCodec: MediaCodec) {
        try {
            val mimeType = mediaCodec.outputFormat.getString(MediaFormat.KEY_MIME)!!
            val profile = mediaCodec.outputFormat.getInteger(MediaFormat.KEY_PROFILE)
            codecSurface?.useHighBitDepth =
                DynamicRangeProfile.fromProfile(mimeType, profile).isHdr
        } catch (_: Exception) {
            codecSurface?.useHighBitDepth = false
        }

        codecSurface?.outputSurface = mediaCodec.createInputSurface()
    }

    override fun createMediaFormat(config: Config, withProfileLevel: Boolean): MediaFormat {
        val videoFormat = super.createMediaFormat(config, withProfileLevel)

        if (useSurfaceMode) {
            videoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
        } else {
            val colorFormat = if ((config as VideoConfig).dynamicRangeProfile.isHdr) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
            } else {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            }
            videoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                colorFormat
            )
        }
        return videoFormat
    }

    override fun extendMediaFormat(config: Config, format: MediaFormat) {
        val videoConfig = config as VideoConfig
        codecSurface?.captureResolution = videoConfig.captureResolution
        codecSurface?.setTargetFps(videoConfig.fps)
        orientationProvider?.let {
            it.getOrientedSize(videoConfig.resolution).apply {
                // Override previous format
                format.setInteger(MediaFormat.KEY_WIDTH, width)
                format.setInteger(MediaFormat.KEY_HEIGHT, height)
            }
        }
    }

    override fun startStream() {
        codecSurface?.startStream()
        super.startStream()
    }

    override fun stopStream() {
        codecSurface?.stopStream()
        super.stopStream()
    }

    val inputSurface: Surface?
        get() = codecSurface?.inputSurface

    /**
     * Measured encode fps from MediaCodec output when available; otherwise surface throttle rate.
     */
    val measuredFps: Double
        get() {
            val encodeFps = measuredOutputFps
            if (encodeFps.isFinite() && encodeFps > 0) return encodeFps
            return codecSurface?.measuredFps ?: Double.NaN
        }

    class CodecSurface(
        private val orientationProvider: ISourceOrientationProvider?
    ) :
        SurfaceTexture.OnFrameAvailableListener, ISourceOrientationListener {
        private var eglSurface: EglWindowSurface? = null
        private var fullFrameRect: FullFrameRect? = null
        private var textureId = -1
        // Single thread with minimal priority executor for power savings
        private val executor = Executors.newSingleThreadExecutor { r -> 
            Thread(r).apply { 
                priority = Thread.MIN_PRIORITY 
                name = "encoder-power-save-thread"
            } 
        }
        private var isRunning = false
        private var surfaceTexture: SurfaceTexture? = null
        private val stMatrix = FloatArray(16)
        
        // Pace encode to VideoConfig.fps with a next-deadline schedule (not
        // min-interval-since-last-accept). Min-interval phase-locks against a
        // higher camera cadence when the target is not a divisor — e.g. 30fps
        // capture targeting 20fps locks to every other frame (~15fps).
        private var nextFrameDueMs = 0L
        @Volatile
        private var targetFrameIntervalMs = 66L // default ~15fps until setTargetFps()
        private var acceptedFrameCount = 0
        private var measuredWindowStartMs = 0L
        @Volatile
        var measuredFps: Double = Double.NaN
            private set

        fun setTargetFps(fps: Int) {
            val clamped = fps.coerceAtLeast(1)
            targetFrameIntervalMs = (1000L / clamped).coerceAtLeast(1L)
            nextFrameDueMs = 0L
        }

        /**
         * Returns true when this camera frame should be encoded. Advances the
         * deadline by one target interval on accept so average rate matches
         * [targetFrameIntervalMs] even when capture fps is not a multiple of
         * the encode fps (30→20 yields ~20, not ~15).
         */
        private fun shouldAcceptFrame(nowMs: Long): Boolean {
            if (nextFrameDueMs == 0L) {
                nextFrameDueMs = nowMs + targetFrameIntervalMs
                return true
            }
            if (nowMs < nextFrameDueMs) {
                return false
            }
            nextFrameDueMs += targetFrameIntervalMs
            // If we fell more than one interval behind, resync so we don't
            // accept a burst of queued frames back-to-back.
            if (nextFrameDueMs <= nowMs) {
                nextFrameDueMs = nowMs + targetFrameIntervalMs
            }
            return true
        }

        private fun recordAcceptedFrame(nowMs: Long) {
            if (measuredWindowStartMs == 0L) {
                measuredWindowStartMs = nowMs
                acceptedFrameCount = 0
            }
            acceptedFrameCount++
            val elapsedMs = nowMs - measuredWindowStartMs
            if (elapsedMs >= 1000L) {
                measuredFps = acceptedFrameCount * 1000.0 / elapsedMs
                acceptedFrameCount = 0
                measuredWindowStartMs = nowMs
            }
        }

        /** Consume a dropped camera frame so SurfaceTexture buffers don't stall. */
        private fun drainDroppedFrame(surfaceTexture: SurfaceTexture) {
            executor.execute {
                synchronized(this) {
                    eglSurface?.let {
                        it.makeCurrent()
                        surfaceTexture.updateTexImage()
                        surfaceTexture.releaseTexImage()
                    }
                }
            }
        }

        private var _inputSurface: Surface? = null
        val inputSurface: Surface?
            get() = _inputSurface

        /**
         * If true, the encoder will use high bit depth (10 bits) for encoding.
         */
        var useHighBitDepth = false

        /**
         * When non-null, [SurfaceTexture.setDefaultBufferSize] uses this size and
         * [FullFrameRect] center-crops to the encoder viewport.
         */
        var captureResolution: Size? = null

        var outputSurface: Surface? = null
            set(value) {
                /**
                 * When surface is called twice without the stopStream(). When configure() is
                 * called twice for example,
                 */
                executor.submit {
                    if (eglSurface != null) {
                        detachSurfaceTexture()
                    }
                    synchronized(this) {
                        value?.let {
                            initOrUpdateSurfaceTexture(it)
                        }
                    }

                }.get() // Wait till executor returns
                field = value
            }

        init {
            orientationProvider?.addListener(this)
        }

        private fun initOrUpdateSurfaceTexture(surface: Surface) {
            eglSurface = ensureGlContext(EglWindowSurface(surface, useHighBitDepth)) {
                val width = it.getWidth()
                val height = it.getHeight()
                val encoderSize =
                    orientationProvider?.getOrientedSize(Size(width, height)) ?: Size(width, height)
                val captureOriented = captureResolution?.let { cr ->
                    orientationProvider?.getOrientedSize(cr) ?: cr
                }
                val defaultBufferSize = captureOriented
                    ?: (orientationProvider?.getDefaultBufferSize(encoderSize) ?: Size(width, height))
                val orientation = orientationProvider?.orientation ?: 0
                fullFrameRect = FullFrameRect(Texture2DProgram()).apply {
                    textureId = createTextureObject()
                    setMVPMatrixViewPortAndCrop(
                        orientation.toFloat(),
                        encoderSize,
                        captureOriented ?: encoderSize,
                        orientationProvider?.mirroredVertically ?: false
                    )
                }

                surfaceTexture = attachOrBuildSurfaceTexture(surfaceTexture).apply {
                    setDefaultBufferSize(defaultBufferSize.width, defaultBufferSize.height)
                    setOnFrameAvailableListener(this@CodecSurface)
                }
            }
        }

        @SuppressLint("Recycle")
        private fun attachOrBuildSurfaceTexture(surfaceTexture: SurfaceTexture?): SurfaceTexture {
            return if (surfaceTexture == null) {
                SurfaceTexture(textureId).apply {
                    _inputSurface = Surface(this)
                }
            } else {
                surfaceTexture.attachToGLContext(textureId)
                surfaceTexture
            }
        }

        private fun ensureGlContext(
            surface: EglWindowSurface?,
            action: (EglWindowSurface) -> Unit
        ): EglWindowSurface? {
            surface?.let {
                it.makeCurrent()
                action(it)
                it.makeUnCurrent()
            }
            return surface
        }

        override fun onOrientationChanged() {
            executor.execute {
                synchronized(this) {
                    ensureGlContext(eglSurface) {
                        val width = it.getWidth()
                        val height = it.getHeight()

                        val encoderSize =
                            orientationProvider?.getOrientedSize(Size(width, height))
                                ?: Size(width, height)
                        val captureOriented = captureResolution?.let { cr ->
                            orientationProvider?.getOrientedSize(cr) ?: cr
                        }
                        fullFrameRect?.setMVPMatrixViewPortAndCrop(
                            (orientationProvider?.orientation ?: 0).toFloat(),
                            encoderSize,
                            captureOriented ?: encoderSize,
                            orientationProvider?.mirroredVertically ?: false
                        )

                        /**
                         * Flushing spurious latest camera frames that block SurfaceTexture buffer
                         * to avoid having a misoriented frame.
                         */
                        surfaceTexture?.updateTexImage()
                        surfaceTexture?.releaseTexImage()
                    }
                }
            }
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
            if (!isRunning) {
                return
            }

            // Pace to VideoConfig.fps once the camera has produced a real frame.
            val currentTimeMs = System.currentTimeMillis()
            if (surfaceTexture.timestamp != 0L) {
                if (!shouldAcceptFrame(currentTimeMs)) {
                    drainDroppedFrame(surfaceTexture)
                    return
                }
                recordAcceptedFrame(currentTimeMs)
            }

            executor.execute {
                synchronized(this) {
                    eglSurface?.let {
                        it.makeCurrent()
                        surfaceTexture.updateTexImage()
                        surfaceTexture.getTransformMatrix(stMatrix)

                        // Use the identity matrix for MVP so our 2x2 FULL_RECTANGLE covers the viewport.
                        fullFrameRect?.drawFrame(textureId, stMatrix)
                        it.setPresentationTime(surfaceTexture.timestamp)
                        it.swapBuffers()
                        surfaceTexture.releaseTexImage()
                    }
                }
            }
        }

        fun startStream() {
            // Flushing spurious latest camera frames that block SurfaceTexture buffer.
            ensureGlContext(eglSurface) {
                surfaceTexture?.updateTexImage()
            }
            nextFrameDueMs = 0L
            acceptedFrameCount = 0
            measuredWindowStartMs = 0L
            measuredFps = Double.NaN
            isRunning = true
        }

        private fun detachSurfaceTexture() {
            ensureGlContext(eglSurface) {
                surfaceTexture?.detachFromGLContext()
                fullFrameRect?.release(true)
            }
            eglSurface?.release()
            eglSurface = null
            fullFrameRect = null
        }

        fun stopStream() {
            executor.submit {
                synchronized(this) {
                    isRunning = false
                    detachSurfaceTexture()
                }
            }.get()
        }

        fun release() {
            orientationProvider?.removeListener(this)
            stopStream()
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
            surfaceTexture = null
        }
    }
}
