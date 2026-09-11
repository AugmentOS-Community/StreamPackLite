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
package io.github.thibaultbee.streampack.internal.sources.camera

import android.Manifest
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.CameraDevice.AUDIO_RESTRICTION_NONE
import android.hardware.camera2.CameraDevice.AUDIO_RESTRICTION_VIBRATION_SOUND
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.params.OutputConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.view.Surface
import androidx.annotation.RequiresPermission
import io.github.thibaultbee.streampack.error.CameraError
import io.github.thibaultbee.streampack.listeners.OnErrorListener
import io.github.thibaultbee.streampack.logger.Logger
import io.github.thibaultbee.streampack.utils.getCameraFpsList
import kotlinx.coroutines.*
import java.security.InvalidParameterException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraController(
    private val context: Context,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    /** Delivers active-camera failures on the main thread, scoped to the current camera open. */
    var onErrorListener: OnErrorListener? = null
    private val lifecycleLock = Any()
    private var cameraGeneration = 0L
    private val errorHandler = Handler(Looper.getMainLooper())
    private var camera: CameraDevice? = null
    val cameraId: String?
        get() = camera?.id

    /** Rolling camera capture fps from CaptureCallback (NaN until first 1s window). */
    @Volatile
    var measuredCaptureFps: Double = Double.NaN
        private set

    private var captureSession: CameraCaptureSession? = null
    private var captureRequest: CaptureRequest.Builder? = null

    private val threadManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        CameraExecutorManager()
    } else {
        CameraHandlerManager()
    }

    private fun getClosestFpsRange(cameraId: String, fps: Int): Range<Int> {
        val fpsRangeList = context.getCameraFpsList(cameraId)
        Logger.i(TAG, "Supported FPS range list: $fpsRangeList (requested=$fps)")

        // Prefer an advertised fixed range at the exact target.
        fpsRangeList.find { it.lower == fps && it.upper == fps }?.let {
            Logger.d(TAG, "Using exact fixed fps range: $it")
            return it
        }

        val containing = fpsRangeList.filter { it.contains(fps) }
        if (containing.isNotEmpty()) {
            // Mentra Live / K900 only: invent [fps,fps] so AE does not ride the top of a
            // wider band (e.g. [5,30]). Standards-compliant HALs may reject synthetic
            // fixed ranges, so this stays opt-in via forceFixedFpsInsideSupportedBand.
            if (forceFixedFpsInsideSupportedBand) {
                val fixed = Range(fps, fps)
                Logger.d(TAG, "Using forced fixed fps range inside supported band: $fixed")
                return fixed
            }

            // Otherwise stay on an advertised range that actually contains the target —
            // prefer the narrowest span, then the upper bound closest to the request.
            val selected = containing.minWith(
                compareBy<Range<Int>> { it.upper - it.lower }
                    .thenBy { kotlin.math.abs(it.upper - fps) }
                    .thenBy { kotlin.math.abs(it.lower - fps) }
            )
            Logger.d(TAG, "Using advertised containing fps range: $selected")
            return selected
        }

        // Fallback: closest advertised range by lower/upper distance to target.
        val selectedFpsRange = fpsRangeList.minWith(
            compareBy<Range<Int>> { kotlin.math.abs(it.lower - fps) }
                .thenBy { kotlin.math.abs(it.upper - fps) }
        )
        Logger.d(TAG, "Fallback fps range: $selectedFpsRange")
        return selectedFpsRange
    }

    internal class CameraDeviceCallback(
        private val cont: CancellableContinuation<CameraDevice>,
        private val onActiveCameraFailure: (CameraError) -> Unit,
    ) : CameraDevice.StateCallback() {
        private val failureReported = AtomicBoolean(false)

        @OptIn(ExperimentalCoroutinesApi::class)
        override fun onOpened(device: CameraDevice) {
            // Covers cancellation after resume but before the caller receives ownership.
            cont.resume(device) { device.close() }
        }

        override fun onDisconnected(camera: CameraDevice) {
            Logger.w(TAG, "Camera ${camera.id} has been disconnected")
            fail(camera, CameraError("Camera has been disconnected"))
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Logger.e(TAG, "Camera ${camera.id} is in error $error")

            val exc = when (error) {
                ERROR_CAMERA_IN_USE -> CameraError("Camera already in use")
                ERROR_MAX_CAMERAS_IN_USE -> CameraError("Max cameras in use")
                ERROR_CAMERA_DISABLED -> CameraError("Camera has been disabled")
                ERROR_CAMERA_DEVICE -> CameraError("Camera device has crashed")
                ERROR_CAMERA_SERVICE -> CameraError("Camera service has crashed")
                else -> CameraError("Unknown error")
            }
            fail(camera, exc)
        }

        private fun fail(camera: CameraDevice, error: CameraError) {
            if (!failureReported.compareAndSet(false, true)) return
            try { camera.close() } catch (e: Exception) { error.addSuppressed(e) }
            if (cont.isActive) {
                cont.resumeWithException(error)
            } else if (!cont.isCancelled) {
                // The open continuation is already complete during capture. A
                // device loss must still reach the owner and stop microphone use.
                onActiveCameraFailure(error)
            }
        }
    }

    private class CameraCaptureSessionCallback(
        private val cont: CancellableContinuation<CameraCaptureSession>,
    ) : CameraCaptureSession.StateCallback() {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun onConfigured(session: CameraCaptureSession) {
            cont.resume(session) { session.close() }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            Logger.e(TAG, "Camera Session configuration failed")
            val error = CameraError("Camera: failed to configure the capture session")
            try { session.close() } catch (e: Exception) { error.addSuppressed(e) }
            if (cont.isActive) cont.resumeWithException(error)
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        private var frameCount = 0
        private var lastLogTime = 0L
        private var samplingActive = false

        fun resetMetrics() {
            frameCount = 0
            lastLogTime = 0L
            samplingActive = false
            measuredCaptureFps = Double.NaN
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)

            val currentTime = System.currentTimeMillis()
            // Start the sampling window on the first completed capture so idle time
            // before the session (or between sessions) cannot poison the first FPS.
            if (!samplingActive) {
                samplingActive = true
                frameCount = 0
                lastLogTime = currentTime
                return
            }

            frameCount++
            val elapsedMs = currentTime - lastLogTime
            if (elapsedMs >= 1000) {
                val fps = frameCount * 1000.0 / elapsedMs
                measuredCaptureFps = fps
                Logger.i(TAG, "Camera capture framerate (measured): ${"%.1f".format(fps)} fps")
                frameCount = 0
                lastLogTime = currentTime
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure
        ) {
            super.onCaptureFailed(session, request, failure)
            Logger.e(TAG, "Capture failed with code ${failure.reason}")
        }

        override fun onCaptureSequenceCompleted(
            session: CameraCaptureSession,
            sequenceId: Int,
            frameNumber: Long
        ) {
            super.onCaptureSequenceCompleted(session, sequenceId, frameNumber)
            Logger.d(TAG, "Capture sequence $sequenceId completed at frame $frameNumber")
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun openCamera(
        manager: CameraManager, cameraId: String
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        val generation = synchronized(lifecycleLock) { ++cameraGeneration }
        threadManager.openCamera(
            manager, cameraId, CameraDeviceCallback(cont) { error ->
                // Never tear down from Camera2's callback thread: closing camera
                // resources may need that same thread. Invalidate queued failures
                // on close/reopen, and serialize the check with invalidation.
                errorHandler.post {
                    synchronized(lifecycleLock) {
                        if (generation == cameraGeneration) onErrorListener?.onError(error)
                    }
                }
            }
        )
    }

    private suspend fun createCaptureSession(
        camera: CameraDevice,
        targets: List<Surface>,
        dynamicRange: Long,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val outputConfigurations = targets.map {
                OutputConfiguration(it).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        dynamicRangeProfile = dynamicRange
                    }
                }
            }

            threadManager.createCaptureSessionByOutputConfiguration(
                camera, outputConfigurations, CameraCaptureSessionCallback(cont)
            )
        } else {
            threadManager.createCaptureSession(
                camera, targets, CameraCaptureSessionCallback(cont)
            )
        }
    }

    private fun createRequestSession(
        camera: CameraDevice,
        captureSession: CameraCaptureSession,
        fpsRange: Range<Int>,
        surfaces: List<Surface>
    ): CaptureRequest.Builder {
        if (surfaces.isEmpty()) {
            throw RuntimeException("No target surface")
        }

        // Use PREVIEW template for most camera types
        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        
        try {
            // Add all surfaces
            surfaces.forEach { captureBuilder.addTarget(it) }
            
            // Basic settings - balance power and functionality
            captureBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            
            // Save power by disabling features that are CPU intensive
            captureBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) // Auto-focus but continuous video mode uses less CPU than picture mode
            captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO) // Keep auto white balance for usable image
            captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            captureBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
            captureBuilder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST)
            
            // Start the repeating request right away to ensure continuous capture
            threadManager.setRepeatingSingleRequest(captureSession, captureBuilder.build(), captureCallback)
            
            return captureBuilder
        } catch (e: Exception) {
            Logger.e(TAG, "Error creating camera request session", e)
            throw e
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun startCamera(
        cameraId: String,
        targets: List<Surface>,
        dynamicRange: Long,
    ) {
        require(targets.isNotEmpty()) { " At least one target is required" }

        withContext(coroutineDispatcher) {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            try {
                // Claim the device before the next suspension so a failed session cannot lose it.
                camera = openCamera(manager, cameraId)
                captureSession = createCaptureSession(
                    camera!!, targets, dynamicRange
                )
            } catch (e: Exception) {
                try { stopCamera() } catch (cleanupError: Exception) { e.addSuppressed(cleanupError) }
                throw e
            }
        }
    }

    fun startRequestSession(fps: Int, targets: List<Surface>) {
        require(camera != null) { "Camera must not be null" }
        require(captureSession != null) { "Capture session must not be null" }
        require(targets.isNotEmpty()) { " At least one target is required" }

        captureCallback.resetMetrics()
        captureRequest = createRequestSession(
            camera!!, captureSession!!, getClosestFpsRange(camera!!.id, fps), targets
        )

        if (enablePixsmartEisOnRequest) {
            applyPixsmartEis()
        } else {
            Logger.i(TAG, "EIS stage=streampack-request applied=false enablePixsmartEisOnRequest=false")
        }
    }

    private fun applyPixsmartEis() {
        val builder = captureRequest ?: return
        // Scene mode is ignored unless CONTROL_MODE is USE_SCENE_MODE. The vendor
        // key is registered as int[] (Pixsmart); a boxed Int is dropped by the HAL.
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
        builder.set(
            CaptureRequest.CONTROL_SCENE_MODE,
            CaptureRequest.CONTROL_SCENE_MODE_SPORTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pixsmartEisKey = CaptureRequest.Key(
                "com.pixsmart.eisfeature.eisEnable", IntArray::class.java
            )
            builder.set(pixsmartEisKey, intArrayOf(1))
        }
        updateRepeatingSession()
        Logger.i(TAG, "Applied Pixsmart EIS (SPORTS + vendor key) to capture request")
    }

    fun stopCamera() {
        synchronized(lifecycleLock) { ++cameraGeneration }
        captureRequest = null
        val sessionToClose = captureSession
        val cameraToClose = camera
        captureSession = null
        camera = null
        val cleanup = io.github.thibaultbee.streampack.internal.utils.Cleanup()
        cleanup.run { sessionToClose?.close() }
        cleanup.run { cameraToClose?.close() }
        cleanup.run { captureCallback.resetMetrics() }
        cleanup.throwIfFailed()
    }

    fun addTargets(targets: List<Surface>) {
        require(captureRequest != null) { "capture request must not be null" }
        require(targets.isNotEmpty()) { " At least one target is required" }

        targets.forEach {
            captureRequest!!.addTarget(it)
        }
        updateRepeatingSession()
    }

    fun addTarget(target: Surface) {
        require(captureRequest != null) { "capture request must not be null" }

        captureRequest!!.addTarget(target)

        updateRepeatingSession()
    }

    fun removeTarget(target: Surface) {
        require(captureRequest != null) { "capture request must not be null" }

        captureRequest!!.removeTarget(target)
        updateRepeatingSession()
    }

    fun release() {
        val cleanup = io.github.thibaultbee.streampack.internal.utils.Cleanup()
        cleanup.run { stopCamera() }
        cleanup.run { threadManager.release() }
        cleanup.throwIfFailed()
    }


    fun muteVibrationAndSound() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            camera?.cameraAudioRestriction = AUDIO_RESTRICTION_VIBRATION_SOUND
        }
    }

    fun unmuteVibrationAndSound() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            camera?.cameraAudioRestriction = AUDIO_RESTRICTION_NONE
        }
    }

    fun updateRepeatingSession() {
        try {
            if (captureSession == null) {
                Logger.e(TAG, "Cannot update repeating session: capture session is null")
                return
            }
            if (captureRequest == null) {
                Logger.e(TAG, "Cannot update repeating session: capture request is null")
                return
            }

            // Build the request and set it as a repeating request to ensure continuous capture
            val request = captureRequest!!.build()
            threadManager.setRepeatingSingleRequest(captureSession!!, request, captureCallback)
            Logger.d(TAG, "Updated repeating request")
        } catch (e: Exception) {
            Logger.e(TAG, "Error updating repeating session", e)
        }
    }

    private fun updateBurstSession() {
        try {
            if (captureSession == null) {
                Logger.e(TAG, "Cannot update burst session: capture session is null")
                return
            }
            if (captureRequest == null) {
                Logger.e(TAG, "Cannot update burst session: capture request is null")
                return
            }

            // Build the request and capture it in burst mode
            val request = captureRequest!!.build()
            threadManager.captureBurstRequests(captureSession!!, listOf(request), captureCallback)
            Logger.d(TAG, "Updated burst request")
        } catch (e: Exception) {
            Logger.e(TAG, "Error updating burst session", e)
        }
    }

    fun <T> getSetting(key: CaptureRequest.Key<T>?): T? {
        return captureRequest?.get(key)
    }

    fun <T> setRepeatingSetting(key: CaptureRequest.Key<T>, value: T) {
        captureRequest?.let {
            it.set(key, value)
            updateRepeatingSession()
        }
    }

    fun setRepeatingSettings(settingsMap: Map<CaptureRequest.Key<Any>, Any>) {
        captureRequest?.let {
            for (item in settingsMap) {
                it.set(item.key, item.value)
            }
            updateRepeatingSession()
        }
    }

    fun setBurstSettings(settingsMap: Map<CaptureRequest.Key<Any>, Any>) {
        captureRequest?.let {
            for (item in settingsMap) {
                it.set(item.key, item.value)
            }
            updateBurstSession()
        }
    }

    companion object {
        private const val TAG = "CameraController"

        /**
         * Opt-in Mentra Live hook: when set to true, [startRequestSession] applies
         * [CaptureRequest.CONTROL_SCENE_MODE_SPORTS] and the Pixsmart vendor key
         * `com.pixsmart.eisfeature.eisEnable=1` to the active capture request.
         *
         * Defaults to false to keep this fork generic. asg_client toggles it from
         * StreamCommandHandler when starting/stopping a livestream.
         */
        @JvmField
        var enablePixsmartEisOnRequest: Boolean = false

        /**
         * Opt-in Mentra Live / K900 hook: when true, [getClosestFpsRange] may request a
         * synthetic fixed `[fps,fps]` range that is only covered by a wider advertised
         * band (e.g. requesting 10 fps when the HAL lists `[5,30]`). Mentra Live honors
         * that; standards-compliant HALs may reject it — keep false for generic devices.
         */
        @JvmField
        var forceFixedFpsInsideSupportedBand: Boolean = false
    }
}
