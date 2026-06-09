/*
 * Copyright 2026 Basheer
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.pixeldiet.compressor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the ported "gear" sizing math. No Android dependencies — these run on the
 * plain JVM via the standard `test` source set.
 */
class CompressionStrategyTest {

    private fun kb(n: Int) = n * 1024L

    @Test
    fun thirdGear_smallImageBelow150kb_isSkipped() {
        // 800x1000, scale 0.8 (in the (0.5625,1] band), height < 1664, < 150 KB -> pass through.
        val spec = CompressionStrategy.thirdGear(800, 1000, kb(100))
        assertTrue("small image should be skipped", spec.skip)
    }

    @Test
    fun thirdGear_tallImage_5625Band_producesExpectedSize() {
        // 1080x1920, scale exactly 0.5625 -> lands in the (0.5,0.5625] band.
        val spec = CompressionStrategy.thirdGear(1080, 1920, kb(4000))
        assertFalse(spec.skip)
        assertEquals(1080, spec.width)
        assertEquals(1920, spec.height)
        assertEquals(225L, spec.maxSizeKb)
    }

    @Test
    fun thirdGear_landscapeIsReturnedInOriginalOrientation() {
        // Landscape input: output width should remain the longer side.
        val spec = CompressionStrategy.thirdGear(1920, 1080, kb(4000))
        assertFalse(spec.skip)
        assertTrue("landscape stays landscape", spec.width >= spec.height)
    }

    @Test
    fun firstGear_tallImageIsAggressivelyDownscaled() {
        // 2000x4000, scale 0.5 -> long-side clamp to 720.
        val spec = CompressionStrategy.firstGear(2000, 4000, kb(5000))
        assertEquals(720, spec.height)
        assertEquals(360, spec.width)
    }

    @Test
    fun customGear_clampsToMaxWidthPreservingAspectRatio() {
        val spec = CompressionStrategy.customGear(
            srcWidth = 4000, srcHeight = 3000, fileLength = kb(3000),
            maxSize = 0, maxWidth = 1000, maxHeight = 0,
        )
        assertFalse(spec.skip)
        assertEquals(1000, spec.width)
        assertEquals(750, spec.height) // 3:4 aspect preserved
    }

    @Test
    fun customGear_targetLargerThanSource_isSkipped() {
        val spec = CompressionStrategy.customGear(
            srcWidth = 1200, srcHeight = 900, fileLength = kb(100),
            maxSize = 10_000, maxWidth = 0, maxHeight = 0,
        )
        assertTrue("no shrink needed -> skip", spec.skip)
    }

    @Test
    fun customGear_targetSizeBudgetIsRespected() {
        val spec = CompressionStrategy.customGear(
            srcWidth = 4000, srcHeight = 3000, fileLength = kb(3000),
            maxSize = 200, maxWidth = 0, maxHeight = 0,
        )
        assertFalse(spec.skip)
        assertEquals(200L, spec.maxSizeKb)
        assertTrue(spec.width < 4000)
    }

    // --- hardCap / target-size API surface tests ----------------------------------------------
    // (Encoder phase-2 resize-fallback is tested in EncoderResizeFallbackTest — needs Robolectric
    // since it uses Bitmap. The assertions below only cover the pure gear math side.)

    @Test
    fun customGear_veryTightTarget_geometryShrunkToMatch() {
        // 4000x3000 @ 4 MB compressed to 50 KB target: pixel count must be drastically reduced.
        val spec = CompressionStrategy.customGear(
            srcWidth = 4000, srcHeight = 3000, fileLength = kb(4000),
            maxSize = 50, maxWidth = 0, maxHeight = 0,
        )
        assertFalse(spec.skip)
        assertEquals(50L, spec.maxSizeKb)
        // sqrt(4000/50) ≈ 8.9× scale factor → roughly 450×337
        assertTrue("width should be well below 1000 for a 50KB target", spec.width < 1000)
        assertTrue("aspect ratio preserved", spec.width.toFloat() / spec.height > 1.2f)
    }

    @Test
    fun thirdGear_doesNotCareAboutMaxSize_hardCapIsEngineSideFence() {
        // The gear math itself does NOT know about hardCap — that's enforced in the encoder.
        // This test confirms third-gear spec is unaffected by a would-be hard cap.
        val spec = CompressionStrategy.thirdGear(1080, 1920, kb(4000))
        assertFalse(spec.skip)
        // Third-gear size budget for this input is 225 KB regardless of any hard cap.
        assertEquals(225L, spec.maxSizeKb)
    }
}
