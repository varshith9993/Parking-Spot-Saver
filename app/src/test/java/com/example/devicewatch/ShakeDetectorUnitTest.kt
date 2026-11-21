package com.example.devicewatch

import android.hardware.SensorManager
import com.example.devicewatch.sensor.ShakeDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ShakeDetectorUnitTest {

    private fun runAlternatingShakeAt200ms(
        detector: ShakeDetector,
        startMs: Long,
        cyclesInclusive: Int,
        amplitudeG: Double
    ): Boolean {
        var triggered = false
        for (i in 0..cyclesInclusive) {
            val ax = if (i % 2 == 0) amplitudeG else -amplitudeG
            if (detector.processLinearSample(ax, 0.0, 0.0, startMs + i * 200L)) {
                triggered = true
            }
        }
        return triggered
    }

    private fun runSinusoidalOscillation(
        detector: ShakeDetector,
        startMs: Long,
        durationMs: Long,
        stepMs: Long,
        freqHz: Double,
        amplitudeG: Double
    ): Boolean {
        var triggered = false
        var elapsed = 0L
        while (elapsed <= durationMs) {
            val ax = amplitudeG * sin(2.0 * PI * freqHz * (elapsed / 1000.0))
            if (detector.processLinearSample(ax, 0.0, 0.0, startMs + elapsed)) {
                triggered = true
            }
            elapsed += stepMs
        }
        return triggered
    }

    @Test
    fun shortShakeUnderOneSecondDoesNotTrigger() {
        var triggers = 0
        val detector = ShakeDetector { triggers++ }

        assertFalse(runAlternatingShakeAt200ms(detector, 1_000L, cyclesInclusive = 4, amplitudeG = 2.8))
        assertEquals(0, triggers)
    }

    @Test
    fun continuousDeliberateShakeTriggersOnceAndEnforcesTenSecondCooldown() {
        var triggers = 0
        val detector = ShakeDetector { triggers++ }

        val start = 10_000L
        assertFalse(runAlternatingShakeAt200ms(detector, start, cyclesInclusive = 4, amplitudeG = 2.8))
        // Step 5 at start + 1000ms reaches 5 reversals and 1000ms duration
        assertTrue(detector.processLinearSample(-2.8, 0.0, 0.0, start + 1_000L))
        assertEquals(1, triggers)

        // Ongoing violent oscillation during 10-second cooldown must be ignored
        assertFalse(
            runAlternatingShakeAt200ms(
                detector,
                startMs = start + 1_400L,
                cyclesInclusive = 40,
                amplitudeG = 3.2
            )
        )
        assertEquals(1, triggers)

        // New deliberate >= 1.0s shake AFTER cooldown expires triggers again
        val secondStart = start + 11_400L
        assertTrue(runAlternatingShakeAt200ms(detector, secondStart, cyclesInclusive = 5, amplitudeG = 2.8))
        assertEquals(2, triggers)
    }

    @Test
    fun gapOverPauseThresholdResetsContinuousWindow() {
        var triggers = 0
        val detector = ShakeDetector { triggers++ }

        assertFalse(detector.processLinearSample(2.8, 0.0, 0.0, 1_000L))
        assertFalse(detector.processLinearSample(-2.8, 0.0, 0.0, 1_200L))
        // Gap > 350ms resets the shake window back to start at 1_700L
        assertFalse(detector.processLinearSample(2.8, 0.0, 0.0, 1_700L))
        assertFalse(detector.processLinearSample(-2.8, 0.0, 0.0, 1_900L))
        assertFalse(detector.processLinearSample(2.8, 0.0, 0.0, 2_100L))
        assertFalse(detector.processLinearSample(-2.8, 0.0, 0.0, 2_300L))
        assertEquals(0, triggers)
    }

    @Test
    fun bikeOneWayPotholeBumpsDoNotTriggerInNormalOrHigh() {
        listOf(ShakeDetector.SENSITIVITY_NORMAL, ShakeDetector.SENSITIVITY_HIGH).forEach { mode ->
            var triggers = 0
            val detector = ShakeDetector { triggers++ }
            detector.setSensitivity(mode)

            var t = 1_000L
            repeat(12) {
                // Sharp upward bumps repeated on uneven roads without directional reversal
                assertFalse(detector.processLinearSample(0.0, 0.0, 3.0, t))
                assertFalse(detector.processLinearSample(0.0, 0.0, 0.2, t + 80L))
                t += 200L
            }
            assertEquals(0, triggers)
        }
    }

    @Test
    fun bikeHighFrequencyEngineVibrationDoesNotTriggerInNormalOrHigh() {
        listOf(ShakeDetector.SENSITIVITY_NORMAL, ShakeDetector.SENSITIVITY_HIGH).forEach { mode ->
            var triggers = 0
            val detector = ShakeDetector { triggers++ }
            detector.setSensitivity(mode)

            // 25 Hz vibration (alternating every 20ms) typical of bike handlebar / tank mount
            var t = 5_000L
            repeat(100) { idx ->
                val amp = if (idx % 2 == 0) 3.0 else -3.0
                assertFalse(detector.processLinearSample(amp, 0.0, 0.5, t))
                t += 20L
            }
            assertEquals(0, triggers)
        }
    }

    @Test
    fun bikeBumpWithWeakReboundDoesNotTriggerInNormalOrHigh() {
        listOf(ShakeDetector.SENSITIVITY_NORMAL, ShakeDetector.SENSITIVITY_HIGH).forEach { mode ->
            var triggers = 0
            val detector = ShakeDetector { triggers++ }
            detector.setSensitivity(mode)

            var t = 2_000L
            repeat(12) {
                assertFalse(detector.processLinearSample(0.0, 0.0, 2.8, t))
                assertFalse(detector.processLinearSample(0.0, 0.0, -0.9, t + 90L))
                t += 180L
            }
            assertEquals(0, triggers)
        }
    }

    @Test
    fun normalAndHighSensitivityBehaveDistinctly() {
        var normalTriggers = 0
        val normalDetector = ShakeDetector { normalTriggers++ }
        normalDetector.setSensitivity(ShakeDetector.SENSITIVITY_NORMAL)

        var highTriggers = 0
        val highDetector = ShakeDetector { highTriggers++ }
        highDetector.setSensitivity(ShakeDetector.SENSITIVITY_HIGH)

        // Light ~2.6 Hz deliberate wrist shake (~1.65g linear peak at 50 Hz):
        // High must trigger cleanly; Normal must stay quiet.
        assertFalse(runSinusoidalOscillation(normalDetector, 10_000L, 1_100L, 20L, 2.6, 1.65))
        assertTrue(runSinusoidalOscillation(highDetector, 10_000L, 1_100L, 20L, 2.6, 1.65))
        assertEquals(0, normalTriggers)
        assertEquals(1, highTriggers)

        // Firm ~2.6 Hz deliberate hand shake (~2.5g linear peak at 50 Hz):
        // Normal must trigger as well.
        assertTrue(runSinusoidalOscillation(normalDetector, 30_000L, 1_200L, 20L, 2.6, 2.5))
        assertEquals(1, normalTriggers)
    }

    @Test
    fun gravityFilterRejectsStationaryOrTiltedPhoneAndDetectsDeliberateShake() {
        var triggers = 0
        val detector = ShakeDetector { triggers++ }
        val g = SensorManager.GRAVITY_EARTH

        // Stationary upright then slowly tilted bike lean -> 0 triggers
        var t = 1_000L
        var lastTiltGx = 0f
        var lastTiltGy = g
        repeat(40) { step ->
            val tiltRatio = step / 40.0
            lastTiltGx = (g * 0.55 * tiltRatio).toFloat()
            lastTiltGy = (g * (1.0 - 0.16 * tiltRatio)).toFloat()
            assertFalse(detector.processAccelerometerSample(lastTiltGx, lastTiltGy, 0f, t))
            t += 40L
        }
        assertEquals(0, triggers)

        // Deliberate +/- 25 m/s^2 oscillation on top of the tilted gravity vector at 2.6 Hz (50 Hz stream) -> 1 trigger
        var triggered = false
        var elapsed = 0L
        while (elapsed <= 1_300L) {
            val ax = (25.0 * sin(2.0 * PI * 2.6 * (elapsed / 1000.0))).toFloat()
            if (detector.processAccelerometerSample(lastTiltGx + ax, lastTiltGy, 0f, t + elapsed)) {
                triggered = true
            }
            elapsed += 20L
        }
        assertTrue(triggered)
        assertEquals(1, triggers)
    }

    @Test
    fun customCooldownDurationOverridesDefaultTenSeconds() {
        var triggers = 0
        val detector = ShakeDetector { triggers++ }
        detector.setCooldownMs(3_000L)

        assertTrue(runAlternatingShakeAt200ms(detector, 1_000L, cyclesInclusive = 5, amplitudeG = 2.8))
        assertEquals(1, triggers)

        // Ignored at 1.2s after trigger (< 3s custom cooldown)
        assertFalse(runAlternatingShakeAt200ms(detector, 3_200L, cyclesInclusive = 5, amplitudeG = 2.8))
        assertEquals(1, triggers)

        // Triggers again after 3s custom cooldown elapses (2_000 + 3_200 = 5_200ms)
        assertTrue(runAlternatingShakeAt200ms(detector, 5_200L, cyclesInclusive = 5, amplitudeG = 2.8))
        assertEquals(2, triggers)
    }

    @Test
    fun setCooldownMsPreservesActiveCooldownAndExtendsWindow() {
        var triggers = 0
        val detector = ShakeDetector { triggers++ }
        detector.setCooldownMs(5_000L)

        assertTrue(runAlternatingShakeAt200ms(detector, 1_000L, cyclesInclusive = 5, amplitudeG = 2.8))
        assertEquals(1, triggers)

        // Simulates MainActivity.onResume() or user raising cooldown to 20s mid-cooldown
        detector.setCooldownMs(20_000L)

        // Even at 8s after trigger (past old 5s), the updated 20s cooldown blocks re-triggering
        assertFalse(runAlternatingShakeAt200ms(detector, 10_000L, cyclesInclusive = 5, amplitudeG = 2.8))
        assertEquals(1, triggers)

        // Triggers once >= 20s has passed since trigger at 2_000ms
        assertTrue(runAlternatingShakeAt200ms(detector, 22_200L, cyclesInclusive = 5, amplitudeG = 2.8))
        assertEquals(2, triggers)
    }

    @Test
    fun sharedLastTriggerTimestampEnforcesCooldownAcrossDetectors() {
        var bgTriggers = 0
        val bgDetector = ShakeDetector { bgTriggers++ }
        bgDetector.setCooldownMs(10_000L)
        // Simulate foreground detector having triggered at t=5_000ms
        bgDetector.setLastTriggeredTimeMs(5_000L, 6_000L)

        assertFalse(runAlternatingShakeAt200ms(bgDetector, 7_000L, cyclesInclusive = 6, amplitudeG = 2.8))
        assertEquals(0, bgTriggers)

        // After 5_000 + 10_000 = 15_000ms, background detector triggers normally
        assertTrue(runAlternatingShakeAt200ms(bgDetector, 15_100L, cyclesInclusive = 5, amplitudeG = 2.8))
        assertEquals(1, bgTriggers)
    }
}
