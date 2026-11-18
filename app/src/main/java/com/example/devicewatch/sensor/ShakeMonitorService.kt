package com.example.devicewatch.sensor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.devicewatch.MainActivity
import com.example.devicewatch.R
import com.example.devicewatch.location.NativeGeocoder
import com.example.devicewatch.location.ParkingLocationManager
import com.example.devicewatch.model.ParkingRecord
import com.example.devicewatch.storage.ParkingStorage
import java.util.UUID

/**
 * Foreground location service that keeps 1.0s continuous shake detection active
 * while Parking Spot Saver is in the background, saving spots directly to History.
 */
class ShakeMonitorService : Service() {

    companion object {
        private const val ACTION_START = "com.example.devicewatch.action.START_SHAKE_MONITOR"
        private const val CHANNEL_SERVICE_ID = "shake_monitor_channel"
        private const val CHANNEL_ALERT_ID = "shake_saved_alert_channel"
        private const val NOTIFICATION_SERVICE_ID = 1001
        private const val NOTIFICATION_SAVED_ID = 1002

        @Volatile
        var isMainForeground: Boolean = false

        fun start(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, ShakeMonitorService::class.java).apply {
                action = ACTION_START
            }
            try {
                ContextCompat.startForegroundService(appCtx, intent)
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            try {
                appCtx.stopService(Intent(appCtx, ShakeMonitorService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private lateinit var parkingStorage: ParkingStorage
    private lateinit var locationManager: ParkingLocationManager
    private lateinit var nativeGeocoder: NativeGeocoder
    private lateinit var shakeDetector: ShakeDetector

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isSaveInFlight = false
    private var isForegroundStarted = false

    override fun onCreate() {
        super.onCreate()
        parkingStorage = ParkingStorage(this)
        locationManager = ParkingLocationManager(this)
        nativeGeocoder = NativeGeocoder(this)
        shakeDetector = ShakeDetector(this) { onBackgroundShakeDetected() }
        ensureNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!locationManager.hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!promoteToForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!parkingStorage.isShakeToSaveEnabled() || !shakeDetector.isAccelerometerAvailable()) {
            stopSelf()
            return START_NOT_STICKY
        }

        shakeDetector.setSensitivity(parkingStorage.getShakeSensitivity())
        shakeDetector.start()
        return START_STICKY
    }

    private fun promoteToForeground(): Boolean {
        if (isForegroundStarted) return true
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setSmallIcon(R.drawable.ic_pin_drop)
            .setContentTitle(getString(R.string.shake_notif_title))
            .setContentText(getString(R.string.shake_notif_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()

        return try {
            val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_SERVICE_ID, notification, fgType)
            isForegroundStarted = true
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun onBackgroundShakeDetected() {
        if (isMainForeground || isSaveInFlight) return
        if (!parkingStorage.isShakeToSaveEnabled() || !locationManager.hasLocationPermission()) return

        isSaveInFlight = true
        locationManager.getFastLocation(object : ParkingLocationManager.LocationResultCallback {
            override fun onLocationRetrieved(location: Location) {
                saveShakeSpot(location)
            }

            override fun onLocationError(message: String) {
                isSaveInFlight = false
                showToastOnMain(getString(R.string.shake_failed_notif_text))
                postSavedAlertNotification(
                    getString(R.string.shake_notif_title),
                    getString(R.string.shake_failed_notif_text)
                )
            }
        })
    }

    private fun saveShakeSpot(location: Location) {
        val record = ParkingRecord(
            UUID.randomUUID().toString(),
            location.latitude,
            location.longitude,
            null,
            null,
            System.currentTimeMillis(),
            null,
            emptyList(),
            false
        )
        parkingStorage.saveNewActiveRecord(record)
        isSaveInFlight = false

        val coords = NativeGeocoder.formatCoordinates(record.latitude, record.longitude)
        showToastOnMain(getString(R.string.shake_saved_confirmation))
        postSavedAlertNotification(
            getString(R.string.shake_saved_notif_title),
            getString(R.string.shake_saved_notif_text, coords)
        )

        nativeGeocoder.reverseGeocode(
            record.latitude,
            record.longitude,
            object : NativeGeocoder.GeocodeCallback {
                override fun onSuccess(formattedAddress: String) {
                    parkingStorage.updateRecordAddress(record.id, formattedAddress)
                    postSavedAlertNotification(
                        getString(R.string.shake_saved_notif_title),
                        getString(R.string.shake_saved_notif_text, formattedAddress)
                    )
                }

                override fun onFailure(coordinateFallback: String, reason: String) {
                    // Coordinate summary is already persisted and shown in the alert.
                }
            }
        )
    }

    private fun showToastOnMain(message: String) {
        mainHandler.post {
            try {
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
            }
        }
    }

    private fun postSavedAlertNotification(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OPEN_HOME, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(R.drawable.ic_pin_drop)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(NOTIFICATION_SAVED_ID, notification)
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val monitorChannel = NotificationChannel(
            CHANNEL_SERVICE_ID,
            getString(R.string.shake_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }

        val alertChannel = NotificationChannel(
            CHANNEL_ALERT_ID,
            getString(R.string.shake_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )

        manager.createNotificationChannel(monitorChannel)
        manager.createNotificationChannel(alertChannel)
    }

    override fun onDestroy() {
        isForegroundStarted = false
        if (::shakeDetector.isInitialized) {
            shakeDetector.stop()
        }
        if (::locationManager.isInitialized) {
            locationManager.cancelCurrentLocationRequest()
        }
        if (::nativeGeocoder.isInitialized) {
            nativeGeocoder.shutdown()
        }
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
