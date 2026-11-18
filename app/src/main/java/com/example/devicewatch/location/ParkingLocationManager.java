package com.example.devicewatch.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.Priority;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.CancellationTokenSource;

/**
 * GPS retrieval, settings verification, and live updates via FusedLocationProviderClient.
 */
public class ParkingLocationManager {

    public interface LocationResultCallback {
        void onLocationRetrieved(@NonNull Location location);
        void onLocationError(@NonNull String message);
    }

    public interface SettingsCheckCallback {
        void onSettingsSatisfied();
        void onSettingsResolvable(@NonNull ResolvableApiException exception);
        void onSettingsUnavailable(@NonNull String message);
    }

    private static final long UPDATE_INTERVAL_MS = 4000L;
    private static final long MIN_UPDATE_INTERVAL_MS = 2000L;

    private final Context appContext;
    private final FusedLocationProviderClient fusedClient;
    private final SettingsClient settingsClient;

    @Nullable
    private CancellationTokenSource activeCancellationToken;
    @Nullable
    private LocationCallback liveLocationCallback;

    public ParkingLocationManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.fusedClient = LocationServices.getFusedLocationProviderClient(appContext);
        this.settingsClient = LocationServices.getSettingsClient(appContext);
    }

    public boolean hasLocationPermission() {
        boolean fine = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return fine || coarse;
    }

    public void checkLocationSettings(@NonNull SettingsCheckCallback callback) {
        LocationRequest request = buildHighAccuracyRequest();
        LocationSettingsRequest settingsRequest = new LocationSettingsRequest.Builder()
                .addLocationRequest(request)
                .setAlwaysShow(true)
                .build();

        settingsClient.checkLocationSettings(settingsRequest)
                .addOnSuccessListener(response -> callback.onSettingsSatisfied())
                .addOnFailureListener(exception -> {
                    if (exception instanceof ResolvableApiException) {
                        callback.onSettingsResolvable((ResolvableApiException) exception);
                    } else {
                        String msg = exception.getMessage() != null
                                ? exception.getMessage()
                                : "Location settings are unavailable.";
                        callback.onSettingsUnavailable(msg);
                    }
                });
    }

    public void getCurrentLocation(@NonNull LocationResultCallback callback) {
        if (!hasLocationPermission()) {
            callback.onLocationError("Location permission is not granted.");
            return;
        }

        cancelPendingCurrentLocation();
        activeCancellationToken = new CancellationTokenSource();

        try {
            fusedClient.getCurrentLocation(
                            Priority.PRIORITY_HIGH_ACCURACY,
                            activeCancellationToken.getToken()
                    )
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            callback.onLocationRetrieved(location);
                        } else {
                            fallbackToLastKnownLocation(callback);
                        }
                    })
                    .addOnFailureListener(e -> fallbackToLastKnownLocation(callback));
        } catch (SecurityException e) {
            callback.onLocationError("Location permission denied.");
        }
    }

    public void startLocationUpdates(@NonNull LocationResultCallback callback) {
        if (!hasLocationPermission()) {
            callback.onLocationError("Location permission is not granted.");
            return;
        }
        stopLocationUpdates();

        liveLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location latest = result.getLastLocation();
                if (latest != null) {
                    callback.onLocationRetrieved(latest);
                }
            }
        };

        try {
            fusedClient.requestLocationUpdates(
                    buildHighAccuracyRequest(),
                    liveLocationCallback,
                    Looper.getMainLooper()
            ).addOnFailureListener(e -> callback.onLocationError(
                    e.getMessage() != null ? e.getMessage() : "Unable to start location updates."
            ));
        } catch (SecurityException e) {
            callback.onLocationError("Location permission denied.");
        }
    }

    public void stopLocationUpdates() {
        if (liveLocationCallback != null) {
            fusedClient.removeLocationUpdates(liveLocationCallback);
            liveLocationCallback = null;
        }
    }

    public void cancelCurrentLocationRequest() {
        cancelPendingCurrentLocation();
    }

    private void fallbackToLastKnownLocation(@NonNull LocationResultCallback callback) {
        if (!hasLocationPermission()) {
            callback.onLocationError("Location permission is not granted.");
            return;
        }
        try {
            fusedClient.getLastLocation()
                    .addOnSuccessListener(lastLocation -> {
                        if (lastLocation != null) {
                            callback.onLocationRetrieved(lastLocation);
                        } else {
                            callback.onLocationError("GPS location is currently unavailable.");
                        }
                    })
                    .addOnFailureListener(e -> callback.onLocationError(
                            e.getMessage() != null ? e.getMessage() : "Failed to retrieve location."
                    ));
        } catch (SecurityException e) {
            callback.onLocationError("Location permission denied.");
        }
    }

    private void cancelPendingCurrentLocation() {
        if (activeCancellationToken != null) {
            activeCancellationToken.cancel();
            activeCancellationToken = null;
        }
    }

    private static LocationRequest buildHighAccuracyRequest() {
        return new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
                .build();
    }
}
