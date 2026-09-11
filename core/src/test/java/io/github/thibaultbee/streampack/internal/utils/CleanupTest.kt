package io.github.thibaultbee.streampack.internal.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class CleanupTest {
    @Test
    fun `camera failure does not skip microphone and endpoint cleanup`() = runBlocking {
        val first = IllegalStateException("camera HAL died")
        val second = IllegalStateException("encoder failed")
        val released = mutableListOf<String>()
        val cleanup = Cleanup()
        cleanup.run { throw first }
        cleanup.run { throw second }
        cleanup.run { released.add("microphone") }
        cleanup.runSuspending { released.add("endpoint") }
        assertEquals(listOf("microphone", "endpoint"), released)
        try {
            cleanup.throwIfFailed()
            fail("Original cleanup failure must remain observable")
        } catch (error: IllegalStateException) {
            assertSame(first, error)
            assertEquals(listOf(second), error.suppressed.toList())
        }
    }

    @Test
    fun `same exception from multiple resources does not abort cleanup`() {
        val failure = IllegalStateException("shared failure")
        val cleanup = Cleanup()
        cleanup.run { throw failure }
        cleanup.run { throw failure }
        var finished = false
        cleanup.run { finished = true }
        assertEquals(true, finished)
    }
}
