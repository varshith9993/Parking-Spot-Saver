package com.example.devicewatch.location;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native reverse geocoding backed by android.location.Geocoder.
 * Never blocks the main thread and always provides a coordinate fallback on failure.
 */
public class NativeGeocoder {

    public interface GeocodeCallback {
        void onSuccess(@NonNull String formattedAddress);
        void onFailure(@NonNull String coordinateFallback, @NonNull String reason);
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean isShutDown = false;

    public NativeGeocoder(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void reverseGeocode(
            double latitude,
            double longitude,
            @NonNull GeocodeCallback callback
    ) {
        if (isShutDown) {
            return;
        }
        String fallback = formatCoordinates(latitude, longitude);
        if (!DistanceCalculator.isValidCoordinate(latitude, longitude)) {
            postFailure(callback, fallback, "Invalid coordinates.");
            return;
        }
        if (!Geocoder.isPresent()) {
            postFailure(callback, fallback, "Geocoder service is unavailable on this device.");
            return;
        }

        Geocoder geocoder = new Geocoder(appContext, Locale.getDefault());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(latitude, longitude, 1, new Geocoder.GeocodeListener() {
                @Override
                public void onGeocode(@NonNull List<Address> addresses) {
                    if (isShutDown) {
                        return;
                    }
                    String formatted = extractBestAddress(addresses);
                    if (formatted != null) {
                        postSuccess(callback, formatted);
                    } else {
                        postFailure(callback, fallback, "No address found for coordinates.");
                    }
                }

                @Override
                public void onError(@Nullable String errorMessage) {
                    if (isShutDown) {
                        return;
                    }
                    String reason = errorMessage != null && !errorMessage.isEmpty()
                            ? errorMessage
                            : "Reverse geocoding failed.";
                    postFailure(callback, fallback, reason);
                }
            });
        } else {
            if (executor.isShutdown()) {
                postFailure(callback, fallback, "Geocoder has been shut down.");
                return;
            }
            executor.execute(() -> {
                if (isShutDown) {
                    return;
                }
                try {
                    @SuppressWarnings("deprecation")
                    List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
                    String formatted = extractBestAddress(addresses);
                    if (formatted != null) {
                        postSuccess(callback, formatted);
                    } else {
                        postFailure(callback, fallback, "No address found for coordinates.");
                    }
                } catch (IOException | IllegalArgumentException e) {
                    String reason = e.getMessage() != null
                            ? e.getMessage()
                            : "Reverse geocoding failed.";
                    postFailure(callback, fallback, reason);
                }
            });
        }
    }

    public void shutdown() {
        isShutDown = true;
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdown();
    }

    @NonNull
    public static String formatCoordinates(double latitude, double longitude) {
        return String.format(Locale.US, "%.5f, %.5f", latitude, longitude);
    }

    @Nullable
    private static String extractBestAddress(@Nullable List<Address> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        Address first = addresses.get(0);
        if (first == null) {
            return null;
        }
        if (first.getMaxAddressLineIndex() >= 0) {
            String line = first.getAddressLine(0);
            if (line != null && !line.trim().isEmpty()) {
                return line.trim();
            }
        }
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, first.getFeatureName());
        addIfPresent(parts, first.getThoroughfare());
        addIfPresent(parts, first.getLocality());
        addIfPresent(parts, first.getAdminArea());
        addIfPresent(parts, first.getPostalCode());
        addIfPresent(parts, first.getCountryName());
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static void addIfPresent(@NonNull List<String> parts, @Nullable String value) {
        if (value != null) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty() && !parts.contains(trimmed)) {
                parts.add(trimmed);
            }
        }
    }

    private void postSuccess(@NonNull GeocodeCallback callback, @NonNull String address) {
        mainHandler.post(() -> callback.onSuccess(address));
    }

    private void postFailure(
            @NonNull GeocodeCallback callback,
            @NonNull String fallback,
            @NonNull String reason
    ) {
        mainHandler.post(() -> callback.onFailure(fallback, reason));
    }
}
