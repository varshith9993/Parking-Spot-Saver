package com.example.devicewatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.devicewatch.location.DistanceCalculator;
import com.example.devicewatch.model.ParkingSpot;

import org.junit.Test;

public class DistanceCalculatorTest {

    @Test
    public void testIsValidCoordinate() {
        assertTrue(DistanceCalculator.isValidCoordinate(0, 0));
        assertTrue(DistanceCalculator.isValidCoordinate(90, 180));
        assertTrue(DistanceCalculator.isValidCoordinate(-90, -180));
        assertFalse(DistanceCalculator.isValidCoordinate(91, 0));
        assertFalse(DistanceCalculator.isValidCoordinate(0, 181));
        assertFalse(DistanceCalculator.isValidCoordinate(Double.NaN, 0));
        assertFalse(DistanceCalculator.isValidCoordinate(0, Double.POSITIVE_INFINITY));
    }

    @Test
    public void testCalculateDistanceMeters_invalidCoordinates() {
        assertEquals(
                DistanceCalculator.INVALID_DISTANCE,
                DistanceCalculator.calculateDistanceMeters(Double.NaN, 0, 0, 0),
                0.001f
        );
        assertEquals(
                DistanceCalculator.INVALID_DISTANCE,
                DistanceCalculator.calculateDistanceMeters(0, 0, 91, 0),
                0.001f
        );
    }

    @Test
    public void testCalculateDistanceMeters_nullInputs() {
        ParkingSpot parkingSpot = new ParkingSpot(1.0, 1.0, 0L);

        assertEquals(
                DistanceCalculator.INVALID_DISTANCE,
                DistanceCalculator.calculateDistanceMeters(null, parkingSpot),
                0.001f
        );
        assertEquals(
                DistanceCalculator.INVALID_DISTANCE,
                DistanceCalculator.calculateDistanceMeters(null, null),
                0.001f
        );
    }

    @Test
    public void testFormatDistance() {
        assertEquals("0 m", DistanceCalculator.formatDistance(0f));
        assertEquals("999 m", DistanceCalculator.formatDistance(999f));
        assertEquals("1.00 km", DistanceCalculator.formatDistance(1000f));
        assertEquals("1.23 km", DistanceCalculator.formatDistance(1234.56f));
        assertEquals("10.00 km", DistanceCalculator.formatDistance(10000f));
        assertEquals("--", DistanceCalculator.formatDistance(-1f));
        assertEquals("--", DistanceCalculator.formatDistance(Float.NaN));
        assertEquals("--", DistanceCalculator.formatDistance(Float.POSITIVE_INFINITY));
    }
}
