package com.example.devicewatch.sensor;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Accelerometer shake detector requiring sustained continuous shaking (>= 1.0s)
 * before triggering, followed by a strict 10-second cooldown that ignores ongoing shakes.
 */
public class ShakeDetector implements SensorEventListener {

    public interface OnShakeListener {
        void onShakeDetected();
    }

    public static final int SENSITIVITY_NORMAL = 0;
    public static final int SENSITIVITY_HIGH = 1;

    private static final float NORMAL_G_THRESHOLD = 2.0f;
    private static final float HIGH_G_THRESHOLD = 1.6f;
    private static final long REQUIRED_SHAKE_DURATION_MS = 1000L;
    private static final int NORMAL_MIN_IMPULSES = 4;
    private static final int HIGH_MIN_IMPULSES = 3;
    private static final long MIN_IMPULSE_GAP_MS = 100L;
    private static final long MAX_PAUSE_BETWEEN_IMPULSES_MS = 450L;
    private static final long COOLDOWN_MS = 10_000L;

    @Nullable
    private final SensorManager sensorManager;
    @Nullable
    private final Sensor accelerometer;
    @NonNull
    private final OnShakeListener listener;

    private int sensitivity = SENSITIVITY_NORMAL;
    private boolean isRegistered = false;
    private long shakeStartTimeMs = 0L;
    private long lastImpulseTimeMs = 0L;
    private long lastCountedImpulseTimeMs = 0L;
    private int impulseCount = 0;
    private long cooldownUntilMs = 0L;

    public ShakeDetector(@NonNull Context context, @NonNull OnShakeListener listener) {
        Context appContext = context.getApplicationContext();
        this.sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        this.accelerometer = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                : null;
        this.listener = listener;
    }

    /**
     * Sensor-less constructor for deterministic JVM unit testing of duration and cooldown logic.
     */
    public ShakeDetector(@NonNull OnShakeListener listener) {
        this.sensorManager = null;
        this.accelerometer = null;
        this.listener = listener;
    }

    public boolean isAccelerometerAvailable() {
        return sensorManager != null && accelerometer != null;
    }

    public void setSensitivity(int sensitivity) {
        this.sensitivity = sensitivity == SENSITIVITY_HIGH ? SENSITIVITY_HIGH : SENSITIVITY_NORMAL;
        resetShakeState();
    }

    public void start() {
        if (isRegistered || sensorManager == null || accelerometer == null) {
            return;
        }
        resetShakeState();
        isRegistered = sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
    }

    public void stop() {
        if (!isRegistered || sensorManager == null) {
            return;
        }
        sensorManager.unregisterListener(this);
        isRegistered = false;
        resetShakeState();
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

        processSample(gForce, System.currentTimeMillis());
    }

    /**
     * Evaluates a single normalized G-force sample at {@code nowMs}.
     * Returns true when a continuous 1.0s shake triggers outside the 10s cooldown.
     */
    public boolean processSample(double gForce, long nowMs) {
        // Ignore all accelerometer spikes while 10s cooldown is active.
        if (nowMs < cooldownUntilMs) {
            resetShakeState();
            return false;
        }

        float threshold = sensitivity == SENSITIVITY_HIGH ? HIGH_G_THRESHOLD : NORMAL_G_THRESHOLD;
        if (gForce < threshold) {
            if (shakeStartTimeMs != 0L && (nowMs - lastImpulseTimeMs) > MAX_PAUSE_BETWEEN_IMPULSES_MS) {
                resetShakeState();
            }
            return false;
        }

        if (shakeStartTimeMs == 0L || (nowMs - lastImpulseTimeMs) > MAX_PAUSE_BETWEEN_IMPULSES_MS) {
            shakeStartTimeMs = nowMs;
            lastImpulseTimeMs = nowMs;
            lastCountedImpulseTimeMs = nowMs;
            impulseCount = 1;
            return false;
        }

        lastImpulseTimeMs = nowMs;
        if ((nowMs - lastCountedImpulseTimeMs) >= MIN_IMPULSE_GAP_MS) {
            impulseCount++;
            lastCountedImpulseTimeMs = nowMs;
        }

        int minImpulses = sensitivity == SENSITIVITY_HIGH ? HIGH_MIN_IMPULSES : NORMAL_MIN_IMPULSES;
        if ((nowMs - shakeStartTimeMs) >= REQUIRED_SHAKE_DURATION_MS && impulseCount >= minImpulses) {
            cooldownUntilMs = nowMs + COOLDOWN_MS;
            resetShakeState();
            listener.onShakeDetected();
            return true;
        }
        return false;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed for accelerometer threshold detection.
    }

    private void resetShakeState() {
        shakeStartTimeMs = 0L;
        lastImpulseTimeMs = 0L;
        lastCountedImpulseTimeMs = 0L;
        impulseCount = 0;
    }
}
