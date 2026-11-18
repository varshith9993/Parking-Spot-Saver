package com.example.devicewatch.location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.CurrentLocationRequest;
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
 * GPS retrieval, settings verification, and live updates via
 * FusedLocationProviderClient.
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

    private static final long UPDATE_INTERVAL_MS = 3000L;
    private static final long MIN_UPDATE_INTERVAL_MS = 1000L;
    private static final long CURRENT_LOCATION_MAX_AGE_MS = 60_000L;
    private static final long CURRENT_LOCATION_TIMEOUT_MS = 2_000L;
    private static final long FAST_LOCATION_MAX_AGE_MS = 300_000L;
    private static final long BALANCED_LOCATION_TIMEOUT_MS = 1_500L;

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
            CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(CURRENT_LOCATION_MAX_AGE_MS)
                    .setDurationMillis(CURRENT_LOCATION_TIMEOUT_MS)
                    .build();
            fusedClient.getCurrentLocation(request, activeCancellationToken.getToken())
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            callback.onLocationRetrieved(location);
                        } else {
                            requestBalancedLocationThenLastKnown(callback);
                        }
                    })
                    .addOnFailureListener(e -> requestBalancedLocationThenLastKnown(callback));
        } catch (SecurityException e) {
            callback.onLocationError("Location permission denied.");
        }
    }

    public void getFastLocation(@NonNull LocationResultCallback callback) {
        if (!hasLocationPermission()) {
            callback.onLocationError("Location permission is not granted.");
            return;
        }
        Location syncBest = getLastKnownLocationSync();
        if (syncBest != null) {
            callback.onLocationRetrieved(syncBest);
            return;
        }
        try {
            fusedClient.getLastLocation()
                    .addOnSuccessListener(lastLocation -> {
                        if (lastLocation != null) {
                            callback.onLocationRetrieved(lastLocation);
                        } else {
                            requestBalancedLocationThenLastKnown(callback);
                        }
                    })
                    .addOnFailureListener(e -> requestBalancedLocationThenLastKnown(callback));
        } catch (SecurityException e) {
            callback.onLocationError("Location permission denied.");
        }
    }

    private void requestBalancedLocationThenLastKnown(@NonNull LocationResultCallback callback) {
        if (!hasLocationPermission()) {
            callback.onLocationError("Location permission is not granted.");
            return;
        }
        try {
            CurrentLocationRequest balancedRequest = new CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    .setMaxUpdateAgeMillis(FAST_LOCATION_MAX_AGE_MS)
                    .setDurationMillis(BALANCED_LOCATION_TIMEOUT_MS)
                    .build();
            if (activeCancellationToken == null) {
                activeCancellationToken = new CancellationTokenSource();
            }
            CancellationTokenSource token = activeCancellationToken;
            fusedClient.getCurrentLocation(balancedRequest, token.getToken())
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            callback.onLocationRetrieved(location);
                        } else {
                            getLastKnownLocation(callback);
                        }
                    })
                    .addOnFailureListener(e -> getLastKnownLocation(callback));
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
                    Looper.getMainLooper()).addOnFailureListener(
                            e -> callback.onLocationError(
                                    e.getMessage() != null ? e.getMessage() : "Unable to start location updates."));
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

    @Nullable
    @SuppressLint("MissingPermission")
    public Location getLastKnownLocationSync() {
        if (!hasLocationPermission()) {
            return null;
        }
        LocationManager systemLocation = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
        if (systemLocation == null) {
            return null;
        }
        Location best = null;
        String[] providers = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new String[] {
                        LocationManager.FUSED_PROVIDER,
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.PASSIVE_PROVIDER
                }
                : new String[] {
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.PASSIVE_PROVIDER
                };
        for (String provider : providers) {
            try {
                if (!systemLocation.isProviderEnabled(provider)) {
                    continue;
                }
                Location candidate = systemLocation.getLastKnownLocation(provider);
                if (candidate != null
                        && DistanceCalculator.isValidCoordinate(
                                candidate.getLatitude(), candidate.getLongitude())
                        && (best == null || candidate.getTime() > best.getTime())) {
                    best = candidate;
                }
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    public void getLastKnownLocation(@NonNull LocationResultCallback callback) {
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
                            Location systemBest = getLastKnownLocationSync();
                            if (systemBest != null) {
                                callback.onLocationRetrieved(systemBest);
                            } else {
                                callback.onLocationError("GPS location is currently unavailable.");
                            }
                        }
                    })
                    .addOnFailureListener(e -> {
                        Location systemBest = getLastKnownLocationSync();
                        if (systemBest != null) {
                            callback.onLocationRetrieved(systemBest);
                        } else {
                            callback.onLocationError(
                                    e.getMessage() != null ? e.getMessage() : "Failed to retrieve location.");
                        }
                    });
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
                .setWaitForAccurateLocation(false)
                .build();
    }
}
