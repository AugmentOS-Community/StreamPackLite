package io.github.thibaultbee.streampack.internal.gl

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FullFrameRectTest {
    @Test
    fun centerCropRectForRotation_960x720To854x480At270_cropsRotatedHeight() {
        val coords = FullFrameRect.centerCropRectForRotation(960, 720, 854, 480, 270)

        assertArrayEquals(
            floatArrayOf(0.125f, 0f, 0.875f, 0f, 0.125f, 1f, 0.875f, 1f),
            coords,
            0.0001f
        )
    }

    @Test
    fun centerCropRectForRotation_640x480To640x360At270_cropsRotatedHeight() {
        val coords = FullFrameRect.centerCropRectForRotation(640, 480, 640, 360, 270)

        assertArrayEquals(
            floatArrayOf(0.125f, 0f, 0.875f, 0f, 0.125f, 1f, 0.875f, 1f),
            coords,
            0.0001f
        )
    }

    @Test
    fun centerCropRectForRotation_960x720To854x480AtZero_cropsHeight() {
        val coords = FullFrameRect.centerCropRectForRotation(960, 720, 854, 480, 0)

        assertArrayEquals(
            floatArrayOf(0f, 0.125f, 1f, 0.125f, 0f, 0.875f, 1f, 0.875f),
            coords,
            0.0001f
        )
    }

    @Test
    fun centerCropRect_native16x9_usesFullTexture() {
        val coords = FullFrameRect.centerCropRect(1280, 720, 1280, 720)

        assertArrayEquals(
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
            coords,
            0.0001f
        )
    }
}
