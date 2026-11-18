package com.example.devicewatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.devicewatch.location.DistanceCalculator;
import com.example.devicewatch.model.ParkingSpot;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumented tests verifying Android Location.distanceTo() behavior on device/emulator.
 */
@RunWith(AndroidJUnit4.class)
public class DistanceCalculatorInstrumentedTest {

    @Test
    public void testCalculateDistanceMeters_coordinates() {
        assertEquals(0f, DistanceCalculator.calculateDistanceMeters(1.0, 1.0, 1.0, 1.0), 0.001f);

        float distance = DistanceCalculator.calculateDistanceMeters(0.0, 0.0, 0.001, 0.001);
        assertTrue(distance > 0f);
        assertNotEquals(DistanceCalculator.INVALID_DISTANCE, distance);
    }

    @Test
    public void testCalculateDistanceMeters_locationAndSpot() {
        Location currentLocation = new Location("test");
        currentLocation.setLatitude(1.0);
        currentLocation.setLongitude(1.0);

        ParkingSpot parkingSpot = new ParkingSpot(1.0, 1.0, 0L);

        assertEquals(0f, DistanceCalculator.calculateDistanceMeters(currentLocation, parkingSpot), 0.001f);
        assertEquals(
                DistanceCalculator.INVALID_DISTANCE,
                DistanceCalculator.calculateDistanceMeters(currentLocation, null),
                0.001f
        );

        Location differentLocation = new Location("test");
        differentLocation.setLatitude(1.001);
        differentLocation.setLongitude(1.001);
        float distance = DistanceCalculator.calculateDistanceMeters(differentLocation, parkingSpot);
        assertTrue(distance > 0f);
        assertNotEquals(DistanceCalculator.INVALID_DISTANCE, distance);
    }
}
