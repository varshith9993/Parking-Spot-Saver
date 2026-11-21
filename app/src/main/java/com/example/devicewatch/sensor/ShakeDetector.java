package com.example.devicewatch.sensor;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Deliberate-shake detector using gravity-removed 3-axis linear acceleration with
 * low-pass vibration rejection and alternating direction-reversal verification.
 * Rejects motorbike engine vibration and repeated one-way road bumps in both Normal and High modes.
 */
public class ShakeDetector implements SensorEventListener {

    public interface OnShakeListener {
        void onShakeDetected();
    }

    public static final int SENSITIVITY_NORMAL = 0;
    public static final int SENSITIVITY_HIGH = 1;
    public static final long DEFAULT_COOLDOWN_MS = 10_000L;

    // 0.35s high-pass time constant isolates motion from gravity (~0.45 Hz cutoff) without attenuating 2-4 Hz shakes.
    private static final double GRAVITY_TAU_SEC = 0.35;
    // 0.025s low-pass time constant attenuates high-frequency bike engine/handlebar vibration (~6.4 Hz cutoff).
    private static final double SMOOTH_TAU_SEC = 0.025;
    private static final long MAX_SAMPLE_GAP_MS = 500L;

    // Normal: firm deliberate back-and-forth hand shake, high immunity to travel vibration.
    private static final double NORMAL_LINEAR_G_THRESHOLD = 1.95;
    private static final int NORMAL_MIN_REVERSALS = 5;
    private static final long NORMAL_REQUIRED_DURATION_MS = 1000L;
    private static final double NORMAL_MAX_REVERSE_DOT = -0.55;
    private static final long NORMAL_MIN_HALF_CYCLE_MS = 95L;
    private static final long NORMAL_MAX_HALF_CYCLE_MS = 350L;

    // High: lighter deliberate wrist shake, still requires alternating direction reversals.
    private static final double HIGH_LINEAR_G_THRESHOLD = 1.30;
    private static final int HIGH_MIN_REVERSALS = 4;
    private static final long HIGH_REQUIRED_DURATION_MS = 750L;
    private static final double HIGH_MAX_REVERSE_DOT = -0.45;
    private static final long HIGH_MIN_HALF_CYCLE_MS = 85L;
    private static final long HIGH_MAX_HALF_CYCLE_MS = 380L;

    private static final double SAME_DIRECTION_DOT_THRESHOLD = 0.20;

    @Nullable
    private final SensorManager sensorManager;
    @Nullable
    private final Sensor activeSensor;
    private final boolean usesLinearAccelerationSensor;
    @NonNull
    private final OnShakeListener listener;

    private int sensitivity = SENSITIVITY_NORMAL;
    private long cooldownMs = DEFAULT_COOLDOWN_MS;
    private boolean isRegistered = false;

    private boolean gravityInitialized = false;
    private double gravityX = 0.0;
    private double gravityY = 0.0;
    private double gravityZ = 0.0;
    private long lastAccelTimeMs = 0L;

    private double smoothX = 0.0;
    private double smoothY = 0.0;
    private double smoothZ = 0.0;
    private long lastSampleTimeMs = 0L;

    private long shakeStartTimeMs = 0L;
    private long lastReversalTimeMs = 0L;
    private double lastPeakDirX = 0.0;
    private double lastPeakDirY = 0.0;
    private double lastPeakDirZ = 0.0;
    private double lastPeakMag = 0.0;
    private int reversalCount = 0;

    private long lastTriggeredTimeMs = 0L;
    private long cooldownUntilMs = 0L;

    public ShakeDetector(@NonNull Context context, @NonNull OnShakeListener listener) {
        Context appContext = context.getApplicationContext();
        this.sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        Sensor chosenSensor = null;
        boolean useLinear = false;
        if (sensorManager != null) {
            chosenSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            if (chosenSensor != null) {
                useLinear = true;
            } else {
                chosenSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
        }
        this.activeSensor = chosenSensor;
        this.usesLinearAccelerationSensor = useLinear;
        this.listener = listener;
    }

    /**
     * Sensor-less constructor for deterministic JVM unit testing of filter, reversal, and cooldown logic.
     */
    public ShakeDetector(@NonNull OnShakeListener listener) {
        this.sensorManager = null;
        this.activeSensor = null;
        this.usesLinearAccelerationSensor = true;
        this.listener = listener;
    }

    public boolean isAccelerometerAvailable() {
        return sensorManager != null && activeSensor != null;
    }

    public void setSensitivity(int sensitivity) {
        this.sensitivity = sensitivity == SENSITIVITY_HIGH ? SENSITIVITY_HIGH : SENSITIVITY_NORMAL;
        resetShakeState();
    }

    public void setCooldownMs(long cooldownMs) {
        long normalized = Math.max(1000L, cooldownMs);
        this.cooldownMs = normalized;
        if (lastTriggeredTimeMs > 0L) {
            this.cooldownUntilMs = lastTriggeredTimeMs + normalized;
        }
    }

    /**
     * Restores the last trigger timestamp so cooldown stays accurate across UI/service switches.
     */
    public void setLastTriggeredTimeMs(long lastTriggeredTimeMs, long nowMs) {
        if (lastTriggeredTimeMs <= 0L || lastTriggeredTimeMs > nowMs) {
            this.lastTriggeredTimeMs = 0L;
            this.cooldownUntilMs = 0L;
            return;
        }
        this.lastTriggeredTimeMs = lastTriggeredTimeMs;
        this.cooldownUntilMs = lastTriggeredTimeMs + this.cooldownMs;
    }

    public void start() {
        if (isRegistered || sensorManager == null || activeSensor == null) {
            return;
        }
        resetFilterState();
        resetShakeState();
        isRegistered = sensorManager.registerListener(this, activeSensor, SensorManager.SENSOR_DELAY_GAME);
    }

    public void stop() {
        if (!isRegistered || sensorManager == null) {
            return;
        }
        sensorManager.unregisterListener(this);
        isRegistered = false;
        resetFilterState();
        resetShakeState();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null || event.values.length < 3 || event.sensor == null) {
            return;
        }
        int type = event.sensor.getType();
        if (usesLinearAccelerationSensor && type == Sensor.TYPE_LINEAR_ACCELERATION) {
            double axG = event.values[0] / SensorManager.GRAVITY_EARTH;
            double ayG = event.values[1] / SensorManager.GRAVITY_EARTH;
            double azG = event.values[2] / SensorManager.GRAVITY_EARTH;
            processLinearSample(axG, ayG, azG, System.currentTimeMillis());
        } else if (!usesLinearAccelerationSensor && type == Sensor.TYPE_ACCELEROMETER) {
            processAccelerometerSample(event.values[0], event.values[1], event.values[2], System.currentTimeMillis());
        }
    }

    /**
     * Removes gravity from raw accelerometer readings (m/s^2) with a time-constant high-pass
     * filter before passing gravity-free G-force vectors to {@link #processLinearSample}.
     */
    public boolean processAccelerometerSample(float axMs2, float ayMs2, float azMs2, long nowMs) {
        if (!Float.isFinite(axMs2) || !Float.isFinite(ayMs2) || !Float.isFinite(azMs2)) {
            return false;
        }
        if (!gravityInitialized || nowMs < lastAccelTimeMs || (nowMs - lastAccelTimeMs) > MAX_SAMPLE_GAP_MS) {
            gravityX = axMs2;
            gravityY = ayMs2;
            gravityZ = azMs2;
            gravityInitialized = true;
            lastAccelTimeMs = nowMs;
            return processLinearSample(0.0, 0.0, 0.0, nowMs);
        }

        double dtSec = (nowMs - lastAccelTimeMs) / 1000.0;
        if (dtSec > 0.0) {
            double alpha = GRAVITY_TAU_SEC / (GRAVITY_TAU_SEC + dtSec);
            gravityX = alpha * gravityX + (1.0 - alpha) * axMs2;
            gravityY = alpha * gravityY + (1.0 - alpha) * ayMs2;
            gravityZ = alpha * gravityZ + (1.0 - alpha) * azMs2;
            lastAccelTimeMs = nowMs;
        }

        double lxG = (axMs2 - gravityX) / SensorManager.GRAVITY_EARTH;
        double lyG = (ayMs2 - gravityY) / SensorManager.GRAVITY_EARTH;
        double lzG = (azMs2 - gravityZ) / SensorManager.GRAVITY_EARTH;
        return processLinearSample(lxG, lyG, lzG, nowMs);
    }

    /**
     * Evaluates a gravity-free linear acceleration vector (in g units) at {@code nowMs}.
     * Requires alternating opposing reversals over the configured duration to reject bike vibrations
     * and one-way road bumps.
     */
    public boolean processLinearSample(double rawAxG, double rawAyG, double rawAzG, long nowMs) {
        if (!Double.isFinite(rawAxG) || !Double.isFinite(rawAyG) || !Double.isFinite(rawAzG)) {
            return false;
        }

        if (lastSampleTimeMs == 0L || nowMs < lastSampleTimeMs || (nowMs - lastSampleTimeMs) > MAX_SAMPLE_GAP_MS) {
            smoothX = rawAxG;
            smoothY = rawAyG;
            smoothZ = rawAzG;
            lastSampleTimeMs = nowMs;
        } else {
            double dtSec = (nowMs - lastSampleTimeMs) / 1000.0;
            if (dtSec > 0.0) {
                double beta = dtSec / (SMOOTH_TAU_SEC + dtSec);
                smoothX += beta * (rawAxG - smoothX);
                smoothY += beta * (rawAyG - smoothY);
                smoothZ += beta * (rawAzG - smoothZ);
                lastSampleTimeMs = nowMs;
            }
        }

        if (nowMs < cooldownUntilMs) {
            resetShakeState();
            return false;
        }

        boolean isHigh = sensitivity == SENSITIVITY_HIGH;
        double activeThresholdG = isHigh ? HIGH_LINEAR_G_THRESHOLD : NORMAL_LINEAR_G_THRESHOLD;
        int activeMinReversals = isHigh ? HIGH_MIN_REVERSALS : NORMAL_MIN_REVERSALS;
        long activeRequiredDurationMs = isHigh ? HIGH_REQUIRED_DURATION_MS : NORMAL_REQUIRED_DURATION_MS;
        double activeMaxReverseDot = isHigh ? HIGH_MAX_REVERSE_DOT : NORMAL_MAX_REVERSE_DOT;
        long activeMinHalfCycleMs = isHigh ? HIGH_MIN_HALF_CYCLE_MS : NORMAL_MIN_HALF_CYCLE_MS;
        long activeMaxHalfCycleMs = isHigh ? HIGH_MAX_HALF_CYCLE_MS : NORMAL_MAX_HALF_CYCLE_MS;

        double mag = Math.sqrt(smoothX * smoothX + smoothY * smoothY + smoothZ * smoothZ);
        if (mag < activeThresholdG) {
            if (shakeStartTimeMs != 0L && (nowMs - lastReversalTimeMs) > activeMaxHalfCycleMs) {
                resetShakeState();
            }
            return false;
        }

        double ux = smoothX / mag;
        double uy = smoothY / mag;
        double uz = smoothZ / mag;

        if (shakeStartTimeMs == 0L || (nowMs - lastReversalTimeMs) > activeMaxHalfCycleMs) {
            shakeStartTimeMs = nowMs;
            lastReversalTimeMs = nowMs;
            lastPeakDirX = ux;
            lastPeakDirY = uy;
            lastPeakDirZ = uz;
            lastPeakMag = mag;
            reversalCount = 0;
            return false;
        }

        double dot = ux * lastPeakDirX + uy * lastPeakDirY + uz * lastPeakDirZ;
        if (dot > SAME_DIRECTION_DOT_THRESHOLD) {
            if (mag > lastPeakMag) {
                lastPeakDirX = ux;
                lastPeakDirY = uy;
                lastPeakDirZ = uz;
                lastPeakMag = mag;
            }
            return tryTriggerIfReady(nowMs, activeRequiredDurationMs, activeMinReversals);
        }

        if (dot <= activeMaxReverseDot) {
            long gapMs = nowMs - lastReversalTimeMs;
            if (gapMs < activeMinHalfCycleMs) {
                return false;
            }
            reversalCount++;
            lastReversalTimeMs = nowMs;
            lastPeakDirX = ux;
            lastPeakDirY = uy;
            lastPeakDirZ = uz;
            lastPeakMag = mag;

            return tryTriggerIfReady(nowMs, activeRequiredDurationMs, activeMinReversals);
        }
        return false;
    }

    private boolean tryTriggerIfReady(long nowMs, long requiredDurationMs, int minReversals) {
        if ((nowMs - shakeStartTimeMs) >= requiredDurationMs && reversalCount >= minReversals) {
            lastTriggeredTimeMs = nowMs;
            cooldownUntilMs = nowMs + cooldownMs;
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

    private void resetFilterState() {
        gravityInitialized = false;
        gravityX = 0.0;
        gravityY = 0.0;
        gravityZ = 0.0;
        lastAccelTimeMs = 0L;
        smoothX = 0.0;
        smoothY = 0.0;
        smoothZ = 0.0;
        lastSampleTimeMs = 0L;
    }

    private void resetShakeState() {
        shakeStartTimeMs = 0L;
        lastReversalTimeMs = 0L;
        lastPeakDirX = 0.0;
        lastPeakDirY = 0.0;
        lastPeakDirZ = 0.0;
        lastPeakMag = 0.0;
        reversalCount = 0;
    }
}
