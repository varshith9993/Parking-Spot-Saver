package com.example.devicewatch

import com.example.devicewatch.location.ParkingLocationManager
import com.example.devicewatch.location.ParkingLocationManager.FixSample
import com.example.devicewatch.location.ParkingLocationManager.LiveLocationFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationStabilizerTest {

    private fun sample(
        lat: Double,
        lng: Double,
        acc: Float,
        timeMs: Long,
        provider: String = "gps",
        hasAccuracy: Boolean = true
    ): FixSample = FixSample(lat, lng, acc, hasAccuracy, timeMs, provider)

    @Test
    fun isBetterSampleRejectsNewerCoarseCellJumpOverRecentAccurateGps() {
        val gps = sample(17.385000, 78.486000, acc = 7f, timeMs = 100_000L, provider = "gps")
        val coarseNetwork = sample(17.392000, 78.494000, acc = 650f, timeMs = 103_000L, provider = "network")

        assertFalse(ParkingLocationManager.isBetterSample(coarseNetwork, gps))
        assertTrue(ParkingLocationManager.isBetterSample(gps, coarseNetwork))
    }

    @Test
    fun liveFilterRejectsCoarseWiFiSpikeAndMultipathTeleportThatBouncesBack() {
        val filter = LiveLocationFilter()
        val initial = sample(17.385000, 78.486000, acc = 8f, timeMs = 10_000L)
        assertTrue(filter.shouldAccept(initial))

        // Tiny stationary drift (< 4m) is suppressed cleanly without jittering UI.
        assertFalse(filter.shouldAccept(sample(17.385012, 78.486010, acc = 9f, timeMs = 13_000L)))

        // Coarse fallback spike (> 65m) is rejected immediately.
        assertFalse(filter.shouldAccept(sample(17.392000, 78.495000, acc = 220f, timeMs = 16_000L)))

        // Single-frame teleport glitch claiming 18m accuracy ~500m away is held as unconfirmed.
        assertFalse(filter.shouldAccept(sample(17.389500, 78.486000, acc = 18f, timeMs = 19_000L)))

        // Glitch snaps right back near the original stationary spot -> rejected cleanly without ever moving anchor.
        assertFalse(filter.shouldAccept(sample(17.385008, 78.486005, acc = 8f, timeMs = 22_000L)))
        assertTrue(filter.getAnchor()?.latitude == initial.latitude)
    }

    @Test
    fun liveFilterConfirmsRealRelocationOnConsistentSecondFixAndAllowsSmoothWalk() {
        val filter = LiveLocationFilter()
        val start = sample(17.385000, 78.486000, acc = 8f, timeMs = 10_000L)
        assertTrue(filter.shouldAccept(start))

        // Real slow walk (~12m over 6s = 2 m/s) is accepted on first fix.
        val walked = sample(17.385110, 78.486000, acc = 8f, timeMs = 16_000L)
        assertTrue(filter.shouldAccept(walked))

        // Larger sudden relocation (> 200m) requires two consistent consecutive fixes before moving UI.
        val exitTunnel1 = sample(17.387500, 78.486000, acc = 10f, timeMs = 20_000L)
        val exitTunnel2 = sample(17.387520, 78.486010, acc = 9f, timeMs = 23_000L)
        assertFalse(filter.shouldAccept(exitTunnel1))
        assertTrue(filter.shouldAccept(exitTunnel2))
        assertTrue(filter.getAnchor()?.latitude == exitTunnel2.latitude)
    }

    @Test
    fun liveFilterUpgradesImmediatelyWhenAccuracyImprovesSharply() {
        val filter = LiveLocationFilter()
        val initialCoarse = sample(17.385500, 78.486500, acc = 85f, timeMs = 10_000L, provider = "network")
        assertTrue(filter.shouldAccept(initialCoarse))

        val sharpGps = sample(17.385000, 78.486000, acc = 6f, timeMs = 13_000L, provider = "gps")
        assertTrue(filter.shouldAccept(sharpGps))
        assertTrue(filter.getAnchor()?.accuracyMeters == 6f)
    }

    @Test
    fun widgetSampleGuardRejectsCoarseOrTeleportSpikesWhileAllowingWalk() {
        val baseLat = 17.385000
        val baseLng = 78.486000
        val t0 = 100_000L

        // Coarse cell spike (>65m) rejected
        assertFalse(
            ParkingLocationManager.shouldAcceptWidgetSample(
                baseLat,
                baseLng,
                8f,
                t0,
                sample(17.390000, 78.490000, acc = 120f, timeMs = t0 + 4_000L)
            )
        )
        // Micro jitter (<6m) ignored
        assertFalse(
            ParkingLocationManager.shouldAcceptWidgetSample(
                baseLat,
                baseLng,
                8f,
                t0,
                sample(17.385020, 78.486010, acc = 8f, timeMs = t0 + 4_000L)
            )
        )
        // Fast impossible teleport in 4s rejected
        assertFalse(
            ParkingLocationManager.shouldAcceptWidgetSample(
                baseLat,
                baseLng,
                8f,
                t0,
                sample(17.388000, 78.486000, acc = 20f, timeMs = t0 + 4_000L)
            )
        )
        // Normal walk ~12m over 5s accepted
        assertTrue(
            ParkingLocationManager.shouldAcceptWidgetSample(
                baseLat,
                baseLng,
                8f,
                t0,
                sample(17.385110, 78.486000, acc = 9f, timeMs = t0 + 5_000L)
            )
        )
    }
}
