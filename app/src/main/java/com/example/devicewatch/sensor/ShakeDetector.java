package com.example.devicewatch.sensor;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Foreground-only accelerometer shake detector with multi-peak filtering and cooldown
 * to avoid false triggers while walking or handling the phone normally.
 */
public class ShakeDetector implements SensorEventListener {

    public interface OnShakeListener {
        void onShakeDetected();
    }

    public static final int SENSITIVITY_NORMAL = 0;
    public static final int SENSITIVITY_HIGH = 1;

    private static final float NORMAL_G_THRESHOLD = 2.4f;
    private static final float HIGH_G_THRESHOLD = 1.85f;
    private static final int NORMAL_MIN_PEAKS = 3;
    private static final int HIGH_MIN_PEAKS = 2;
    private static final long MIN_PEAK_GAP_MS = 120L;
    private static final long SHAKE_WINDOW_MS = 700L;
    private static final long COOLDOWN_MS = 4000L;

    @Nullable
    private final SensorManager sensorManager;
    @Nullable
    private final Sensor accelerometer;
    @NonNull
    private final OnShakeListener listener;

    private int sensitivity = SENSITIVITY_NORMAL;
    private boolean isRegistered = false;
    private int peakCount = 0;
    private long firstPeakTimeMs = 0L;
    private long lastPeakTimeMs = 0L;
    private long lastTriggerTimeMs = 0L;

    public ShakeDetector(@NonNull Context context, @NonNull OnShakeListener listener) {
        Context appContext = context.getApplicationContext();
        this.sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        this.accelerometer = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                : null;
        this.listener = listener;
    }

    public boolean isAccelerometerAvailable() {
        return sensorManager != null && accelerometer != null;
    }

    public void setSensitivity(int sensitivity) {
        this.sensitivity = sensitivity == SENSITIVITY_HIGH ? SENSITIVITY_HIGH : SENSITIVITY_NORMAL;
        resetPeaks();
    }

    public void start() {
        if (isRegistered || sensorManager == null || accelerometer == null) {
            return;
        }
        resetPeaks();
        isRegistered = sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
    }

    public void stop() {
        if (!isRegistered || sensorManager == null) {
            return;
        }
        sensorManager.unregisterListener(this);
        isRegistered = false;
        resetPeaks();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER || event.values.length < 3) {
            return;
        }

        float gx = event.values[0] / SensorManager.GRAVITY_EARTH;
        float gy = event.values[1] / SensorManager.GRAVITY_EARTH;
        float gz = event.values[2] / SensorManager.GRAVITY_EARTH;
        double gForce = Math.sqrt(gx * gx + gy * gy + gz * gz);

        float threshold = sensitivity == SENSITIVITY_HIGH ? HIGH_G_THRESHOLD : NORMAL_G_THRESHOLD;
        if (gForce < threshold) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastTriggerTimeMs < COOLDOWN_MS) {
            return;
        }

        if (now - firstPeakTimeMs > SHAKE_WINDOW_MS) {
            peakCount = 0;
            firstPeakTimeMs = now;
        }

        if (peakCount == 0 || (now - lastPeakTimeMs) >= MIN_PEAK_GAP_MS) {
            if (peakCount == 0) {
                firstPeakTimeMs = now;
            }
            peakCount++;
            lastPeakTimeMs = now;
        }

        int requiredPeaks = sensitivity == SENSITIVITY_HIGH ? HIGH_MIN_PEAKS : NORMAL_MIN_PEAKS;
        if (peakCount >= requiredPeaks) {
            lastTriggerTimeMs = now;
            resetPeaks();
            listener.onShakeDetected();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed for accelerometer threshold detection.
    }

    private void resetPeaks() {
        peakCount = 0;
        firstPeakTimeMs = 0L;
        lastPeakTimeMs = 0L;
    }
}
