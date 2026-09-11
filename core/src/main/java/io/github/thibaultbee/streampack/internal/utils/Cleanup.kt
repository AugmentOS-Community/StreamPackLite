package io.github.thibaultbee.streampack.internal.utils

/** Finish independent resource cleanup before propagating the first failure. */
internal class Cleanup {
    private var failure: Exception? = null

    fun run(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            record(error)
        }
    }

    suspend fun runSuspending(action: suspend () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            record(error)
        }
    }

    private fun record(error: Exception) {
        val first = failure
        if (first == null) failure = error
        else if (first !== error) first.addSuppressed(error)
    }

    fun throwIfFailed() {
        failure?.let { throw it }
    }
}
