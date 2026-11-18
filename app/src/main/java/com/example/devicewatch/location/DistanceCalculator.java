package com.example.devicewatch.location;

import android.location.Location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.devicewatch.model.ParkingSpot;

import java.util.Locale;

/**
 * Distance calculations and formatting using Android's native Location.distanceTo().
 */
public final class DistanceCalculator {

    public static final float INVALID_DISTANCE = -1f;
    private static final float METERS_PER_KILOMETER = 1000f;

    private DistanceCalculator() {
    }

    public static boolean isValidCoordinate(double latitude, double longitude) {
        return !Double.isNaN(latitude)
                && !Double.isInfinite(latitude)
                && !Double.isNaN(longitude)
                && !Double.isInfinite(longitude)
                && latitude >= -90.0 && latitude <= 90.0
                && longitude >= -180.0 && longitude <= 180.0;
    }

    public static float calculateDistanceMeters(
            double currentLat,
            double currentLng,
            double targetLat,
            double targetLng
    ) {
        if (!isValidCoordinate(currentLat, currentLng) || !isValidCoordinate(targetLat, targetLng)) {
            return INVALID_DISTANCE;
        }
        Location current = new Location("current");
        current.setLatitude(currentLat);
        current.setLongitude(currentLng);

        Location target = new Location("target");
        target.setLatitude(targetLat);
        target.setLongitude(targetLng);

        return current.distanceTo(target);
    }

    public static float calculateDistanceMeters(
            @Nullable Location currentLocation,
            @Nullable ParkingSpot spot
    ) {
        if (currentLocation == null || spot == null) {
            return INVALID_DISTANCE;
        }
        return calculateDistanceMeters(
                currentLocation.getLatitude(),
                currentLocation.getLongitude(),
                spot.getLatitude(),
                spot.getLongitude()
        );
    }

    @NonNull
    public static String formatDistance(float distanceMeters) {
        if (Float.isNaN(distanceMeters) || Float.isInfinite(distanceMeters) || distanceMeters < 0f) {
            return "--";
        }
        if (distanceMeters < METERS_PER_KILOMETER) {
            return String.format(Locale.US, "%d m", Math.round(distanceMeters));
        }
        return String.format(Locale.US, "%.2f km", distanceMeters / METERS_PER_KILOMETER);
    }
}
