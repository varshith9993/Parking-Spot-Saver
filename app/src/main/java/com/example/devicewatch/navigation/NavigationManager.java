package com.example.devicewatch.navigation;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.devicewatch.location.DistanceCalculator;
import com.example.devicewatch.model.ParkingSpot;

import java.util.Locale;

/**
 * Launches external walking navigation to a saved parking spot via Google Maps Intent
 * with generic geo/browser fallbacks if Google Maps is not installed.
 */
public final class NavigationManager {

    private static final String GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps";

    private NavigationManager() {
    }

    public static boolean openWalkingDirections(
            @NonNull Context context,
            @Nullable ParkingSpot spot
    ) {
        if (spot == null) {
            return false;
        }
        return openWalkingDirections(context, spot.getLatitude(), spot.getLongitude());
    }

    public static boolean openWalkingDirections(
            @NonNull Context context,
            double latitude,
            double longitude
    ) {
        if (!DistanceCalculator.isValidCoordinate(latitude, longitude)) {
            return false;
        }

        // Prefer turn-by-turn walking navigation in Google Maps when installed.
        String navUri = String.format(Locale.US, "google.navigation:q=%f,%f&mode=w", latitude, longitude);
        Intent googleMapsIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(navUri))
                .setPackage(GOOGLE_MAPS_PACKAGE);
        if (tryStartActivity(context, googleMapsIntent)) {
            return true;
        }

        // Fallback 1: any installed map application supporting geo: URIs.
        String geoUri = String.format(
                Locale.US,
                "geo:%f,%f?q=%f,%f(Parked+Car)",
                latitude, longitude, latitude, longitude
        );
        Intent geoIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(geoUri));
        if (tryStartActivity(context, geoIntent)) {
            return true;
        }

        // Fallback 2: Google Maps web directions in default browser.
        String webUri = String.format(
                Locale.US,
                "https://www.google.com/maps/dir/?api=1&destination=%f,%f&travelmode=walking",
                latitude, longitude
        );
        return tryStartActivity(context, new Intent(Intent.ACTION_VIEW, Uri.parse(webUri)));
    }

    private static boolean tryStartActivity(@NonNull Context context, @NonNull Intent intent) {
        if (intent.resolveActivity(context.getPackageManager()) == null) {
            return false;
        }
        try {
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }
}
