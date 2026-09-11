package io.github.thibaultbee.streampack.internal.sources

import android.hardware.camera2.CameraDevice
import io.github.thibaultbee.streampack.internal.sources.camera.CameraController
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class CameraOwnershipTest {
    @Test
    fun `cancellation between resume and delivery closes unclaimed camera`() {
        val queue = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val camera = mockk<CameraDevice>(relaxed = true)
        lateinit var callback: CameraController.CameraDeviceCallback
        var claimed = false
        val job = CoroutineScope(Job() + dispatcher).launch {
            suspendCancellableCoroutine<CameraDevice> {
                callback = CameraController.CameraDeviceCallback(it) { error("Unexpected active failure") }
            }
            claimed = true
        }
        queue.removeFirst().run()
        callback.onOpened(camera)
        job.cancel()
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertFalse(claimed)
        verify(exactly = 1) { camera.close() }
    }
}
