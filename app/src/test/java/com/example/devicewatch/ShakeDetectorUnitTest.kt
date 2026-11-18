package com.example.devicewatch

import com.example.devicewatch.sensor.ShakeDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeDetectorUnitTest {

    @Test
    fun shortShakeUnderOneSecondDoesNotTrigger() {
        var triggers = 0
        val detector = ShakeDetector { triggers++ }

        var t = 1_000L
        repeat(5) {
            assertFalse(detector.processSample(2.5, t))
            t += 200L
        }
        assertEquals(0, triggers)
    }

    @Test
    fun continuousOneSecondShakeTriggersOnceAndEnforcesTenSecondCooldown() {
        var triggers = 0
        val detector = ShakeDetector { triggers++ }

        val start = 10_000L
        var t = start
        while (t < start + 1_000L) {
            assertFalse(detector.processSample(2.4, t))
            t += 200L
        }
        // Cross >= 1000ms with >= 4 impulses
        assertTrue(detector.processSample(2.4, start + 1_000L))
        assertEquals(1, triggers)

        // Ongoing violent shake during the 10-second cooldown must be ignored
        t = start + 1_400L
        while (t < start + 10_900L) {
            assertFalse(detector.processSample(3.0, t))
            t += 200L
        }
        assertEquals(1, triggers)

        // New continuous >= 1.0s shake AFTER cooldown expires triggers again
        val secondStart = start + 11_200L
        t = secondStart
        while (t < secondStart + 1_000L) {
            assertFalse(detector.processSample(2.4, t))
            t += 200L
        }
        assertTrue(detector.processSample(2.4, secondStart + 1_000L))
        assertEquals(2, triggers)
    }

    @Test
    fun gapOverPauseThresholdResetsContinuousWindow() {
        var triggers = 0
        val detector = ShakeDetector { triggers++ }

        assertFalse(detector.processSample(2.5, 1_000L))
        assertFalse(detector.processSample(2.5, 1_200L))
        // Gap > 450ms resets the shake window back to start at 1_800L
        assertFalse(detector.processSample(2.5, 1_800L))
        assertFalse(detector.processSample(2.5, 2_200L))
        assertFalse(detector.processSample(2.5, 2_500L))
        assertEquals(0, triggers)
    }
}
