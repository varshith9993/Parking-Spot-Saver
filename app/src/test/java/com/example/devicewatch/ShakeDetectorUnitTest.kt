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

    @Test
    fun customCooldownDurationOverridesDefaultTenSeconds() {
        var triggers = 0
        val detector = ShakeDetector { triggers++ }
        detector.setCooldownMs(3_000L)

        var t = 1_000L
        while (t <= 2_000L) {
            detector.processSample(2.4, t)
            t += 200L
        }
        assertEquals(1, triggers)

        // Ignored at 2s after trigger (< 3s custom cooldown)
        t = 3_200L
        while (t <= 4_200L) {
            assertFalse(detector.processSample(2.6, t))
            t += 200L
        }
        assertEquals(1, triggers)

        // Triggers again after 3s custom cooldown elapses (2_000 + 3_200 = 5_200ms)
        t = 5_200L
        while (t < 6_200L) {
            assertFalse(detector.processSample(2.4, t))
            t += 200L
        }
        assertTrue(detector.processSample(2.4, 6_200L))
        assertEquals(2, triggers)
    }

    @Test
    fun setCooldownMsPreservesActiveCooldownAndExtendsWindow() {
        var triggers = 0
        val detector = ShakeDetector { triggers++ }
        detector.setCooldownMs(5_000L)

        var t = 1_000L
        while (t <= 2_000L) {
            detector.processSample(2.4, t)
            t += 200L
        }
        assertEquals(1, triggers)

        // Simulates MainActivity.onResume() or user raising cooldown to 20s mid-cooldown
        detector.setCooldownMs(20_000L)

        // Even at 8s after trigger (past old 5s), the updated 20s cooldown blocks re-triggering
        t = 10_000L
        while (t <= 12_000L) {
            assertFalse(detector.processSample(2.6, t))
            t += 200L
        }
        assertEquals(1, triggers)

        // Triggers once >= 20s has passed since trigger at 2_000ms
        t = 22_200L
        while (t < 23_200L) {
            assertFalse(detector.processSample(2.4, t))
            t += 200L
        }
        assertTrue(detector.processSample(2.4, 23_200L))
        assertEquals(2, triggers)
    }

    @Test
    fun sharedLastTriggerTimestampEnforcesCooldownAcrossDetectors() {
        var bgTriggers = 0
        val bgDetector = ShakeDetector { bgTriggers++ }
        bgDetector.setCooldownMs(10_000L)
        // Simulate foreground detector having triggered at t=5_000ms
        bgDetector.setLastTriggeredTimeMs(5_000L, 6_000L)

        var t = 7_000L
        while (t <= 9_000L) {
            assertFalse(bgDetector.processSample(2.8, t))
            t += 200L
        }
        assertEquals(0, bgTriggers)

        // After 5_000 + 10_000 = 15_000ms, background detector triggers normally
        t = 15_100L
        while (t < 16_100L) {
            assertFalse(bgDetector.processSample(2.4, t))
            t += 200L
        }
        assertTrue(bgDetector.processSample(2.4, 16_100L))
        assertEquals(1, bgTriggers)
    }
}
