package com.example.devicewatch.navigation;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.devicewatch.location.DistanceCalculator;
import com.example.devicewatch.model.ParkingRecord;
import com.example.devicewatch.model.ParkingSpot;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Launches external Google Maps navigation and native Android sharing for parking records.
 * Uses Android Intents directly without bundling Google Maps SDK or embedding an API key.
 */
public final class NavigationManager {

    public static final String TRAVEL_WALKING = "walking";
    public static final String TRAVEL_DRIVING = "driving";

    private static final String GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps";

    private NavigationManager() {
    }

    public static boolean openDirections(
            @NonNull Context context,
            @Nullable ParkingSpot spot,
            @NonNull String travelMode
    ) {
        if (spot == null) {
            return false;
        }
        return openDirections(context, spot.getLatitude(), spot.getLongitude(), travelMode);
    }

    public static boolean openDirections(
            @NonNull Context context,
            double latitude,
            double longitude,
            @NonNull String travelMode
    ) {
        if (!DistanceCalculator.isValidCoordinate(latitude, longitude)) {
            return false;
        }

        String navModeFlag = TRAVEL_DRIVING.equals(travelMode) ? "d" : "w";
        String webTravelMode = TRAVEL_DRIVING.equals(travelMode) ? TRAVEL_DRIVING : TRAVEL_WALKING;

        // 1. Prefer Google Maps turn-by-turn navigation URI with travel mode.
        String navWithMode = String.format(
                Locale.US,
                "google.navigation:q=%.6f,%.6f&mode=%s",
                latitude, longitude, navModeFlag
        );
        if (tryStartActivity(context, new Intent(Intent.ACTION_VIEW, Uri.parse(navWithMode))
                .setPackage(GOOGLE_MAPS_PACKAGE))) {
            return true;
        }

        // 2. Fallback: standard google.navigation URI without mode parameter.
        String navSimple = String.format(
                Locale.US,
                "google.navigation:q=%.6f,%.6f",
                latitude, longitude
        );
        if (tryStartActivity(context, new Intent(Intent.ACTION_VIEW, Uri.parse(navSimple))
                .setPackage(GOOGLE_MAPS_PACKAGE))) {
            return true;
        }

        // 3. Fallback: Google Maps web directions URI routed to Google Maps app if installed.
        String webDirections = String.format(
                Locale.US,
                "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f&travelmode=%s",
                latitude, longitude, webTravelMode
        );
        if (tryStartActivity(context, new Intent(Intent.ACTION_VIEW, Uri.parse(webDirections))
                .setPackage(GOOGLE_MAPS_PACKAGE))) {
            return true;
        }

        // 4. Fallback: any installed map application supporting geo: URIs.
        String geoUri = String.format(
                Locale.US,
                "geo:%.6f,%.6f?q=%.6f,%.6f(%s)",
                latitude, longitude, latitude, longitude, Uri.encode("Parked Vehicle")
        );
        if (tryStartActivity(context, new Intent(Intent.ACTION_VIEW, Uri.parse(geoUri)))) {
            return true;
        }

        // 5. Final fallback: open Google Maps directions in the user's browser.
        return tryStartActivity(context, new Intent(Intent.ACTION_VIEW, Uri.parse(webDirections)));
    }

    public static boolean shareParkingRecord(
            @NonNull Context context,
            @Nullable ParkingRecord record,
            @NonNull String chooserTitle
    ) {
        if (record == null || !DistanceCalculator.isValidCoordinate(record.getLatitude(), record.getLongitude())) {
            return false;
        }

        String shareBody = buildShareText(record);
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("text/plain");
        sendIntent.putExtra(Intent.EXTRA_SUBJECT, "Parked Vehicle Location");
        sendIntent.putExtra(Intent.EXTRA_TEXT, shareBody);

        Intent chooser = Intent.createChooser(sendIntent, chooserTitle);
        return tryStartActivity(context, chooser);
    }

    @NonNull
    public static String buildShareText(@NonNull ParkingRecord record) {
        StringBuilder sb = new StringBuilder();

        if (record.getPlaceLabel() != null && !record.getPlaceLabel().trim().isEmpty()) {
            sb.append("Parking location: ").append(record.getPlaceLabel().trim()).append('\n');
        }
        if (record.getAddress() != null && !record.getAddress().trim().isEmpty()) {
            sb.append("Address: ").append(record.getAddress().trim()).append('\n');
        }

        sb.append(String.format(
                Locale.US,
                "Coordinates: %.6f, %.6f\n",
                record.getLatitude(),
                record.getLongitude()
        ));

        if (record.getSavedTimestamp() > 0L) {
            String formattedDateTime = DateFormat
                    .getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT)
                    .format(new Date(record.getSavedTimestamp()));
            sb.append("Parked at: ").append(formattedDateTime).append('\n');
        }

        if (record.getNote() != null && !record.getNote().trim().isEmpty()) {
            sb.append("Note: ").append(record.getNote().trim()).append('\n');
        }

        sb.append(String.format(
                Locale.US,
                "Open in Google Maps: https://www.google.com/maps/search/?api=1&query=%.6f,%.6f",
                record.getLatitude(),
                record.getLongitude()
        ));
        return sb.toString();
    }

    @Nullable
    public static Intent buildBestDirectionsIntent(
            @NonNull Context context,
            @Nullable ParkingSpot spot,
            @NonNull String travelMode
    ) {
        if (spot == null || !DistanceCalculator.isValidCoordinate(spot.getLatitude(), spot.getLongitude())) {
            return null;
        }
        String navModeFlag = TRAVEL_DRIVING.equals(travelMode) ? "d" : "w";
        String navWithMode = String.format(
                Locale.US,
                "google.navigation:q=%.6f,%.6f&mode=%s",
                spot.getLatitude(), spot.getLongitude(), navModeFlag
        );
        Intent gmapsNav = new Intent(Intent.ACTION_VIEW, Uri.parse(navWithMode))
                .setPackage(GOOGLE_MAPS_PACKAGE);
        if (gmapsNav.resolveActivity(context.getPackageManager()) != null) {
            return gmapsNav;
        }
        String webDirections = String.format(
                Locale.US,
                "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f&travelmode=%s",
                spot.getLatitude(), spot.getLongitude(),
                TRAVEL_DRIVING.equals(travelMode) ? TRAVEL_DRIVING : TRAVEL_WALKING
        );
        return new Intent(Intent.ACTION_VIEW, Uri.parse(webDirections));
    }

    private static boolean tryStartActivity(@NonNull Context context, @NonNull Intent intent) {
        try {
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
