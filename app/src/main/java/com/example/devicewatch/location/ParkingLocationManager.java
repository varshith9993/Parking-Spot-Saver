package com.example.devicewatch.location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
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
import com.google.android.gms.location.Granularity;
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

    private static final long UPDATE_INTERVAL_MS = 2000L;
    private static final long MIN_UPDATE_INTERVAL_MS = 1000L;
    private static final long MAX_LIVE_UPDATE_AGE_MS = 5_000L;
    private static final long CURRENT_LOCATION_MAX_AGE_MS = 5_000L;
    private static final long CURRENT_LOCATION_TIMEOUT_MS = 5_000L;
    private static final long FAST_LOCATION_MAX_AGE_MS = 12_000L;
    private static final long MAX_FRESH_CACHE_AGE_MS = 12_000L;
    private static final long FAST_CURRENT_LOCATION_TIMEOUT_MS = 3_000L;
    private static final long BALANCED_LOCATION_TIMEOUT_MS = 2_000L;
    private static final long SIGNIFICANT_TIME_DELTA_MS = 90_000L;
    private static final long MAX_SEED_ANCHOR_AGE_MS = 60_000L;
    private static final float MAX_PREFERRED_CACHE_ACCURACY_METERS = 20f;
    public static final float MAX_LIVE_ACCURACY_METERS = 65f;
    private static final float MAX_INITIAL_LIVE_ACCURACY_METERS = 120f;
    private static final float STATIONARY_JITTER_METERS = 2.5f;
    private static final float OUTLIER_JUMP_DISTANCE_METERS = 40.0f;
    private static final double MAX_REALISTIC_SPEED_MPS = 15.0;
    private static final double MAX_CONFIRMED_HIGHWAY_SPEED_MPS = 35.0;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    public static final class FixSample {
        public final double latitude;
        public final double longitude;
        public final float accuracyMeters;
        public final boolean hasAccuracy;
        public final long timeMs;
        @Nullable
        public final String provider;

        public FixSample(
                double latitude,
                double longitude,
                float accuracyMeters,
                boolean hasAccuracy,
                long timeMs,
                @Nullable String provider
        ) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracyMeters = accuracyMeters;
            this.hasAccuracy = hasAccuracy;
            this.timeMs = timeMs;
            this.provider = provider;
        }
    }

    public static final class LiveLocationFilter {
        @Nullable
        private FixSample anchor;
        @Nullable
        private FixSample pendingOutlier;

        public synchronized void reset(@Nullable FixSample seed) {
            if (seed != null && isValidSample(seed)
                    && seed.hasAccuracy
                    && seed.accuracyMeters > 0f
                    && seed.accuracyMeters <= MAX_LIVE_ACCURACY_METERS) {
                anchor = seed;
            } else {
                anchor = null;
            }
            pendingOutlier = null;
        }

        @Nullable
        public synchronized FixSample getAnchor() {
            return anchor;
        }

        public synchronized boolean shouldAccept(@Nullable FixSample candidate) {
            if (!isValidSample(candidate)) {
                return false;
            }
            if (!candidate.hasAccuracy || candidate.accuracyMeters <= 0f) {
                return false;
            }
            float candAcc = candidate.accuracyMeters;

            if (anchor == null) {
                if (candAcc <= MAX_INITIAL_LIVE_ACCURACY_METERS) {
                    anchor = candidate;
                    pendingOutlier = null;
                    return true;
                }
                return false;
            }

            float anchorAcc = anchor.hasAccuracy && anchor.accuracyMeters > 0f
                    ? anchor.accuracyMeters
                    : MAX_LIVE_ACCURACY_METERS;
            if (candidate.timeMs < anchor.timeMs - 5_000L) {
                return false;
            }

            double distMeters = haversineMeters(
                    anchor.latitude, anchor.longitude,
                    candidate.latitude, candidate.longitude
            );
            double dtSeconds = Math.max(0.5, (candidate.timeMs - anchor.timeMs) / 1000.0);

            // Snap immediately when accuracy sharpens meaningfully toward <25m GPS precision.
            if (candAcc <= 25f && candAcc < anchorAcc - 4f) {
                anchor = candidate;
                pendingOutlier = null;
                return true;
            }

            // Reject coarse Wi-Fi/cell fallback spikes once we already have an accurate lock.
            if (candAcc > MAX_LIVE_ACCURACY_METERS
                    || (anchorAcc <= 30f
                            && candAcc > Math.max(45f, anchorAcc * 2.5f)
                            && distMeters > anchorAcc + 15.0)) {
                pendingOutlier = null;
                return false;
            }

            // Suppress tiny stationary GPS drift unless accuracy meaningfully improves.
            if (distMeters < STATIONARY_JITTER_METERS && candAcc >= anchorAcc - 4.0f) {
                pendingOutlier = null;
                return false;
            }

            // Guard against single-frame multipath/network teleport glitches while stationary:
            // hold suspicious jumps until one consistent follow-up fix confirms real movement.
            double impliedSpeedMps = distMeters / dtSeconds;
            boolean shortBurstOutlier = dtSeconds <= 60.0
                    && distMeters > Math.max(OUTLIER_JUMP_DISTANCE_METERS, anchorAcc + candAcc)
                    && (impliedSpeedMps > MAX_REALISTIC_SPEED_MPS
                            || (distMeters > 100.0 && candAcc >= anchorAcc * 0.9f));
            boolean impossibleTeleport = distMeters > 150.0 && impliedSpeedMps > MAX_CONFIRMED_HIGHWAY_SPEED_MPS;
            if (shortBurstOutlier || impossibleTeleport) {
                if (pendingOutlier != null) {
                    long gapMs = candidate.timeMs - pendingOutlier.timeMs;
                    double gapMeters = haversineMeters(
                            pendingOutlier.latitude, pendingOutlier.longitude,
                            candidate.latitude, candidate.longitude
                    );
                    double confirmRadius = Math.max(45.0, candAcc * 2.0 + 20.0);
                    if (gapMs >= 0L && gapMs <= 20_000L && gapMeters <= confirmRadius) {
                        anchor = candidate;
                        pendingOutlier = null;
                        return true;
                    }
                }
                pendingOutlier = candidate;
                return false;
            }

            anchor = candidate;
            pendingOutlier = null;
            return true;
        }
    }

    private final Context appContext;
    private final FusedLocationProviderClient fusedClient;
    private final SettingsClient settingsClient;
    private final LiveLocationFilter liveFilter = new LiveLocationFilter();

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
        Location syncBaseline = getLastKnownLocationSync();

        try {
            CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setGranularity(Granularity.GRANULARITY_FINE)
                    .setMaxUpdateAgeMillis(CURRENT_LOCATION_MAX_AGE_MS)
                    .setDurationMillis(CURRENT_LOCATION_TIMEOUT_MS)
                    .build();
            fusedClient.getCurrentLocation(request, activeCancellationToken.getToken())
                    .addOnSuccessListener(location -> {
                        Location best = chooseBetterLocation(location, syncBaseline);
                        if (best != null) {
                            deliverTrustedOneShot(best, callback);
                        } else {
                            requestBalancedLocationThenLastKnown(callback, syncBaseline);
                        }
                    })
                    .addOnFailureListener(e -> requestBalancedLocationThenLastKnown(callback, syncBaseline));
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
        long nowMs = System.currentTimeMillis();
        if (isFreshAccurateLocation(syncBest, nowMs, MAX_FRESH_CACHE_AGE_MS, MAX_PREFERRED_CACHE_ACCURACY_METERS)) {
            deliverTrustedOneShot(syncBest, callback);
            return;
        }
        try {
            CancellationTokenSource fastToken = new CancellationTokenSource();
            CurrentLocationRequest fastRequest = new CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setGranularity(Granularity.GRANULARITY_FINE)
                    .setMaxUpdateAgeMillis(FAST_LOCATION_MAX_AGE_MS)
                    .setDurationMillis(FAST_CURRENT_LOCATION_TIMEOUT_MS)
                    .build();
            fusedClient.getCurrentLocation(fastRequest, fastToken.getToken())
                    .addOnSuccessListener(location -> {
                        Location preferred = chooseBetterLocation(location, syncBest);
                        if (preferred != null) {
                            deliverTrustedOneShot(preferred, callback);
                        } else {
                            fallbackToFusedLastOrBalanced(callback, syncBest);
                        }
                    })
                    .addOnFailureListener(e -> fallbackToFusedLastOrBalanced(callback, syncBest));
        } catch (SecurityException e) {
            callback.onLocationError("Location permission denied.");
        }
    }

    @SuppressLint("MissingPermission")
    private void fallbackToFusedLastOrBalanced(
            @NonNull LocationResultCallback callback,
            @Nullable Location syncBest
    ) {
        try {
            fusedClient.getLastLocation()
                    .addOnSuccessListener(lastLocation -> {
                        Location best = chooseBetterLocation(lastLocation, syncBest);
                        if (best != null) {
                            deliverTrustedOneShot(best, callback);
                        } else {
                            requestBalancedLocationThenLastKnown(callback, syncBest);
                        }
                    })
                    .addOnFailureListener(e -> requestBalancedLocationThenLastKnown(callback, syncBest));
        } catch (SecurityException e) {
            callback.onLocationError("Location permission denied.");
        }
    }

    private void requestBalancedLocationThenLastKnown(
            @NonNull LocationResultCallback callback,
            @Nullable Location syncBaseline
    ) {
        if (!hasLocationPermission()) {
            callback.onLocationError("Location permission is not granted.");
            return;
        }
        if (isFreshAccurateLocation(syncBaseline, System.currentTimeMillis(), 60_000L, 50f)) {
            deliverTrustedOneShot(syncBaseline, callback);
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
                        Location best = chooseBetterLocation(location, syncBaseline);
                        if (best != null) {
                            deliverTrustedOneShot(best, callback);
                        } else {
                            getLastKnownLocation(callback);
                        }
                    })
                    .addOnFailureListener(e -> getLastKnownLocation(callback));
        } catch (SecurityException e) {
            callback.onLocationError("Location permission denied.");
        }
    }

    private void deliverTrustedOneShot(@NonNull Location location, @NonNull LocationResultCallback callback) {
        if (Math.abs(System.currentTimeMillis() - location.getTime()) <= MAX_SEED_ANCHOR_AGE_MS) {
            liveFilter.reset(toSample(location));
        }
        callback.onLocationRetrieved(location);
    }

    public void startLocationUpdates(@NonNull LocationResultCallback callback) {
        if (!hasLocationPermission()) {
            callback.onLocationError("Location permission is not granted.");
            return;
        }
        stopLocationUpdates();
        Location seed = getLastKnownLocationSync();
        if (seed != null && Math.abs(System.currentTimeMillis() - seed.getTime()) <= MAX_SEED_ANCHOR_AGE_MS) {
            liveFilter.reset(toSample(seed));
        } else {
            liveFilter.reset(null);
        }

        liveLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                for (Location candidate : result.getLocations()) {
                    if (candidate != null && liveFilter.shouldAccept(toSample(candidate))) {
                        callback.onLocationRetrieved(candidate);
                    }
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

    public void startWidgetLocationBroadcastUpdates(@NonNull PendingIntent pendingIntent) {
        if (!hasLocationPermission()) {
            return;
        }
        try {
            LocationRequest widgetRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
                    .setGranularity(Granularity.GRANULARITY_FINE)
                    .setMinUpdateIntervalMillis(2500L)
                    .setMinUpdateDistanceMeters(6f)
                    .setMaxUpdateAgeMillis(MAX_LIVE_UPDATE_AGE_MS)
                    .setMaxUpdateDelayMillis(6000L)
                    .setWaitForAccurateLocation(false)
                    .build();
            fusedClient.requestLocationUpdates(widgetRequest, pendingIntent);
        } catch (SecurityException ignored) {
        }
    }

    public void stopWidgetLocationBroadcastUpdates(@NonNull PendingIntent pendingIntent) {
        try {
            fusedClient.removeLocationUpdates(pendingIntent);
        } catch (Exception ignored) {
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
                if (candidate != null && isBetterLocation(candidate, best)) {
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
        Location systemBest = getLastKnownLocationSync();
        try {
            fusedClient.getLastLocation()
                    .addOnSuccessListener(lastLocation -> {
                        Location best = chooseBetterLocation(lastLocation, systemBest);
                        if (best != null) {
                            callback.onLocationRetrieved(best);
                        } else {
                            callback.onLocationError("GPS location is currently unavailable.");
                        }
                    })
                    .addOnFailureListener(e -> {
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

    @Nullable
    public Location getFreshLastKnownLocationSync() {
        Location syncBest = getLastKnownLocationSync();
        if (isFreshAccurateLocation(
                syncBest,
                System.currentTimeMillis(),
                MAX_FRESH_CACHE_AGE_MS,
                MAX_PREFERRED_CACHE_ACCURACY_METERS)) {
            return syncBest;
        }
        return null;
    }

    public static boolean shouldAcceptWidgetLocation(
            double prevLat,
            double prevLng,
            float prevAccuracyMeters,
            long prevTimeMs,
            @Nullable Location candidate
    ) {
        return shouldAcceptWidgetSample(
                prevLat,
                prevLng,
                prevAccuracyMeters,
                prevTimeMs,
                toSample(candidate)
        );
    }

    public static boolean shouldAcceptWidgetSample(
            double prevLat,
            double prevLng,
            float prevAccuracyMeters,
            long prevTimeMs,
            @Nullable FixSample candSample
    ) {
        if (!isValidSample(candSample)
                || !candSample.hasAccuracy
                || candSample.accuracyMeters <= 0f
                || candSample.accuracyMeters > MAX_LIVE_ACCURACY_METERS) {
            return false;
        }
        if (!DistanceCalculator.isValidCoordinate(prevLat, prevLng) || prevTimeMs <= 0L) {
            return true;
        }
        double movedMeters = haversineMeters(prevLat, prevLng, candSample.latitude, candSample.longitude);
        if (movedMeters < 6.0) {
            return false;
        }
        double dtSeconds = Math.max(1.0, (candSample.timeMs - prevTimeMs) / 1000.0);
        double impliedSpeedMps = movedMeters / dtSeconds;
        float safePrevAcc = prevAccuracyMeters > 0f ? prevAccuracyMeters : MAX_LIVE_ACCURACY_METERS;
        if (dtSeconds <= 30.0 && impliedSpeedMps > 20.0) {
            return false;
        }
        if (safePrevAcc <= 30f
                && candSample.accuracyMeters > Math.max(40f, safePrevAcc * 2.5f)
                && movedMeters > 45.0) {
            return false;
        }
        return !(dtSeconds <= 30.0 && movedMeters > 100.0 && candSample.accuracyMeters > 25f);
    }

    @Nullable
    public static FixSample toSample(@Nullable Location location) {
        if (location == null) {
            return null;
        }
        return new FixSample(
                location.getLatitude(),
                location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : 9999f,
                location.hasAccuracy(),
                location.getTime(),
                location.getProvider()
        );
    }

    public static boolean isValidSample(@Nullable FixSample sample) {
        if (sample == null
                || !DistanceCalculator.isValidCoordinate(sample.latitude, sample.longitude)) {
            return false;
        }
        return Math.abs(sample.latitude) > 1e-6 || Math.abs(sample.longitude) > 1e-6;
    }

    public static boolean isBetterSample(
            @Nullable FixSample candidate,
            @Nullable FixSample currentBest
    ) {
        if (!isValidSample(candidate)) {
            return false;
        }
        if (!isValidSample(currentBest)) {
            return true;
        }
        long timeDelta = candidate.timeMs - currentBest.timeMs;
        boolean isSignificantlyNewer = timeDelta > SIGNIFICANT_TIME_DELTA_MS;
        boolean isSignificantlyOlder = timeDelta < -SIGNIFICANT_TIME_DELTA_MS;
        boolean isNewer = timeDelta > 0L;

        float candAcc = candidate.hasAccuracy && candidate.accuracyMeters > 0f
                ? candidate.accuracyMeters
                : 9999f;
        float bestAcc = currentBest.hasAccuracy && currentBest.accuracyMeters > 0f
                ? currentBest.accuracyMeters
                : 9999f;
        boolean candSeverelyCoarse = bestAcc <= 35f && candAcc > Math.max(45f, bestAcc * 2.0f);

        if (isSignificantlyNewer) {
            if (candSeverelyCoarse && timeDelta < 300_000L) {
                return false;
            }
            return candAcc <= 250f || bestAcc > 250f;
        }
        if (isSignificantlyOlder) {
            return false;
        }

        float accDelta = candAcc - bestAcc;
        boolean isMoreAccurate = accDelta < -2f;
        boolean isNotWorseAccuracy = accDelta <= 8f;
        boolean isSignificantlyLessAccurate = accDelta > 45f || candSeverelyCoarse;
        boolean sameProvider = isSameProvider(candidate.provider, currentBest.provider);

        if (isMoreAccurate && timeDelta >= -30_000L) {
            return true;
        }
        if (isNewer && isNotWorseAccuracy) {
            return true;
        }
        return isNewer && !isSignificantlyLessAccurate && sameProvider && candAcc <= MAX_LIVE_ACCURACY_METERS;
    }

    public static double haversineMeters(
            double lat1,
            double lng1,
            double lat2,
            double lng2
    ) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLng / 2.0) * Math.sin(dLng / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)));
        return EARTH_RADIUS_METERS * c;
    }

    private static boolean isBetterLocation(@Nullable Location candidate, @Nullable Location currentBest) {
        return isBetterSample(toSample(candidate), toSample(currentBest));
    }

    @Nullable
    private static Location chooseBetterLocation(@Nullable Location first, @Nullable Location second) {
        if (isBetterLocation(first, second)) {
            return first;
        }
        return isValidSample(toSample(second)) ? second : first;
    }

    private static boolean isFreshAccurateLocation(
            @Nullable Location location,
            long nowMs,
            long maxAgeMs,
            float maxAccuracyMeters
    ) {
        FixSample sample = toSample(location);
        if (!isValidSample(sample) || !sample.hasAccuracy || sample.accuracyMeters <= 0f) {
            return false;
        }
        long ageMs = nowMs - sample.timeMs;
        return ageMs >= -5_000L && ageMs <= maxAgeMs && sample.accuracyMeters <= maxAccuracyMeters;
    }

    private static boolean isSameProvider(@Nullable String first, @Nullable String second) {
        if (first == null) {
            return second == null;
        }
        return first.equals(second);
    }

    private void cancelPendingCurrentLocation() {
        if (activeCancellationToken != null) {
            activeCancellationToken.cancel();
            activeCancellationToken = null;
        }
    }

    private static LocationRequest buildHighAccuracyRequest() {
        return new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
                .setGranularity(Granularity.GRANULARITY_FINE)
                .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
                .setMaxUpdateAgeMillis(MAX_LIVE_UPDATE_AGE_MS)
                .setWaitForAccurateLocation(false)
                .build();
    }
}
