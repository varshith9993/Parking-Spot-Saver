package com.example.devicewatch.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import com.example.devicewatch.MainActivity
import com.example.devicewatch.R
import com.example.devicewatch.location.DistanceCalculator
import com.example.devicewatch.location.NativeGeocoder
import com.example.devicewatch.location.ParkingLocationManager
import com.example.devicewatch.model.ParkingRecord
import com.example.devicewatch.model.RelativeTimeFormatter
import com.example.devicewatch.navigation.NavigationManager
import com.example.devicewatch.storage.ParkingStorage
import com.google.android.gms.location.LocationResult
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Interactive Home Screen AppWidget supporting top-left navigation to app,
 * instant GPS spot saving, live distance calculation, MapLibre OpenFreeMap Liberty mini-map
 * & expanded full-middle map with live movement tracing, dashed route, zoom badge (bottom-right),
 * distance badge (bottom-left), and 'x', '+', '-', 'o' controls.
 */
class ParkingSpotWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId, isSaving = false, isMeasuringDistance = false)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidget(context, appWidgetManager, appWidgetId, isSaving = false, isMeasuringDistance = false)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        stopWidgetLocationTracking(context.applicationContext)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val appCtx = context.applicationContext
        val storage = ParkingStorage(appCtx)
        when (intent.action) {
            ACTION_SAVE_SPOT -> handleSaveSpotInBackground(appCtx)
            ACTION_SHOW_DISTANCE -> handleShowDistanceInBackground(appCtx, centerExpandedMapOnUser = false)
            ACTION_TOGGLE_BOOKMARK -> handleToggleBookmarkInBackground(appCtx)
            ACTION_CLEAR_SPOT -> {
                stopWidgetLocationTracking(appCtx)
                handleClearSpotInBackground(appCtx)
            }
            ACTION_OPEN_WIDGET_MAP -> handleOpenOrZoomMap(appCtx, storage, openExpanded = true, zoomDelta = 0.0)
            ACTION_CLOSE_WIDGET_MAP -> {
                stopWidgetLocationTracking(appCtx)
                storage.setWidgetMapExpanded(false)
            }
            ACTION_WIDGET_MAP_ZOOM_IN -> handleOpenOrZoomMap(appCtx, storage, openExpanded = false, zoomDelta = 1.0)
            ACTION_WIDGET_MAP_ZOOM_OUT -> handleOpenOrZoomMap(appCtx, storage, openExpanded = false, zoomDelta = -1.0)
            ACTION_WIDGET_MAP_MY_LOCATION -> handleShowDistanceInBackground(appCtx, centerExpandedMapOnUser = true)
            ACTION_WIDGET_MAP_CENTER_PIN -> handleOpenOrZoomMap(appCtx, storage, openExpanded = true, zoomDelta = 0.0)
            ACTION_WIDGET_LOCATION_UPDATE -> handlePassiveWidgetMovementUpdate(appCtx, storage, intent)
        }
    }

    private fun handleOpenOrZoomMap(
        appCtx: Context,
        storage: ParkingStorage,
        openExpanded: Boolean,
        zoomDelta: Double
    ) {
        val record = storage.getActiveRecord() ?: return
        if (openExpanded) {
            storage.resetWidgetMapCenterToSpot()
            storage.setWidgetMapExpandedQuiet(true)
            startWidgetLocationTracking(appCtx)
        }
        if (zoomDelta != 0.0) {
            storage.setWidgetMapZoomQuiet(storage.getWidgetMapZoom() + zoomDelta)
        }

        val isDark = isDarkModeActive(appCtx, storage)
        val userLat = storage.getWidgetMapUserLat()
        val userLng = storage.getWidgetMapUserLng()
        val hasUser = DistanceCalculator.isValidCoordinate(userLat, userLng)
        val centerOnUser = storage.isWidgetMapCenteredOnUser() && hasUser
        val req = WidgetMapRenderer.MapRenderRequest(
            parkedLat = record.latitude,
            parkedLng = record.longitude,
            centerLat = if (centerOnUser) userLat else record.latitude,
            centerLng = if (centerOnUser) userLng else record.longitude,
            userLat = if (hasUser) userLat else Double.NaN,
            userLng = if (hasUser) userLng else Double.NaN,
            zoom = storage.getWidgetMapZoom(),
            isExpanded = true,
            isDark = isDark
        )

        val isCached = WidgetMapRenderer.getCachedBitmap(appCtx, req) != null
        updateAllWidgets(appCtx, isSaving = false, isMeasuringDistance = !isCached)
        if (isCached) return

        val pendingResult = goAsync()
        val done = AtomicBoolean(false)
        fun completeAsync() {
            if (done.compareAndSet(false, true)) {
                pendingResult.finish()
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!done.get()) {
                updateAllWidgets(appCtx, isSaving = false, isMeasuringDistance = false)
                completeAsync()
            }
        }, ASYNC_GUARD_TIMEOUT_MS)

        WidgetMapRenderer.renderMapAsync(appCtx, req) {
            updateAllWidgets(appCtx, isSaving = false, isMeasuringDistance = false)
            completeAsync()
        }
    }

    private fun handlePassiveWidgetMovementUpdate(
        appCtx: Context,
        storage: ParkingStorage,
        intent: Intent
    ) {
        val record = storage.getActiveRecord()
        if (record == null || !storage.isWidgetMapExpanded()) {
            stopWidgetLocationTracking(appCtx)
            return
        }
        val location = LocationResult.extractResult(intent)?.lastLocation ?: return
        if (!DistanceCalculator.isValidCoordinate(location.latitude, location.longitude)) return

        val prevLat = storage.getWidgetMapUserLat()
        val prevLng = storage.getWidgetMapUserLng()
        if (DistanceCalculator.isValidCoordinate(prevLat, prevLng)) {
            val movedMeters = DistanceCalculator.calculateDistanceMeters(
                prevLat, prevLng,
                location.latitude, location.longitude
            )
            if (movedMeters < 5.0f) return
        }

        val meters = DistanceCalculator.calculateDistanceMeters(location, record.toParkingSpot())
        val formatted = if (meters >= 0f) {
            appCtx.getString(R.string.widget_distance_away_format, DistanceCalculator.formatDistance(meters))
        } else {
            null
        }
        val keepCenteredOnUser = storage.isWidgetMapCenteredOnUser()
        storage.saveWidgetDistanceAndUserLocationQuiet(
            record.id,
            formatted,
            location.latitude,
            location.longitude,
            keepCenteredOnUser
        )

        val req = WidgetMapRenderer.MapRenderRequest(
            parkedLat = record.latitude,
            parkedLng = record.longitude,
            centerLat = if (keepCenteredOnUser) location.latitude else record.latitude,
            centerLng = if (keepCenteredOnUser) location.longitude else record.longitude,
            userLat = location.latitude,
            userLng = location.longitude,
            zoom = storage.getWidgetMapZoom(),
            isExpanded = true,
            isDark = isDarkModeActive(appCtx, storage)
        )

        if (WidgetMapRenderer.getCachedBitmap(appCtx, req) != null) {
            updateAllWidgets(appCtx, isSaving = false, isMeasuringDistance = false)
            return
        }

        val pendingResult = goAsync()
        val done = AtomicBoolean(false)
        fun completeAsync() {
            if (done.compareAndSet(false, true)) pendingResult.finish()
        }
        Handler(Looper.getMainLooper()).postDelayed({ completeAsync() }, ASYNC_GUARD_TIMEOUT_MS)
        WidgetMapRenderer.renderMapAsync(appCtx, req) {
            updateAllWidgets(appCtx, isSaving = false, isMeasuringDistance = false)
            completeAsync()
        }
    }

    private fun handleSaveSpotInBackground(appCtx: Context) {
        val locationManager = ParkingLocationManager(appCtx)
        if (!locationManager.hasLocationPermission()) {
            showToast(appCtx, R.string.widget_location_permission_needed)
            val permIntent = Intent(appCtx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_WIDGET_SAVE_SPOT, true)
            }
            appCtx.startActivity(permIntent)
            return
        }

        val storage = ParkingStorage(appCtx)
        updateAllWidgets(appCtx, isSaving = true, isMeasuringDistance = false)

        val pendingResult = goAsync()
        val done = AtomicBoolean(false)
        val geocoder = NativeGeocoder(appCtx)

        fun completeAsync() {
            if (done.compareAndSet(false, true)) {
                geocoder.shutdown()
                pendingResult.finish()
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!done.get()) {
                updateAllWidgets(appCtx, isSaving = false, isMeasuringDistance = false)
                completeAsync()
            }
        }, ASYNC_GUARD_TIMEOUT_MS)

        locationManager.getFastLocation(object : ParkingLocationManager.LocationResultCallback {
            override fun onLocationRetrieved(location: Location) {
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
                storage.saveNewActiveRecord(record)
                showToast(appCtx, R.string.widget_saved_toast)

                geocoder.reverseGeocode(
                    record.latitude,
                    record.longitude,
                    object : NativeGeocoder.GeocodeCallback {
                        override fun onSuccess(formattedAddress: String) {
                            storage.updateRecordAddress(record.id, formattedAddress)
                            completeAsync()
                        }

                        override fun onFailure(coordinateFallback: String, reason: String) {
                            completeAsync()
                        }
                    }
                )
            }

            override fun onLocationError(message: String) {
                updateAllWidgets(appCtx, isSaving = false, isMeasuringDistance = false)
                showToast(appCtx, R.string.widget_gps_failed_toast)
                completeAsync()
            }
        })
    }

    private fun handleShowDistanceInBackground(appCtx: Context, centerExpandedMapOnUser: Boolean) {
        val storage = ParkingStorage(appCtx)
        val active = storage.getActiveRecord() ?: return
        val locationManager = ParkingLocationManager(appCtx)
        if (!locationManager.hasLocationPermission()) {
            showToast(appCtx, R.string.widget_location_permission_needed)
            val permIntent = Intent(appCtx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_HOME, true)
            }
            appCtx.startActivity(permIntent)
            return
        }

        if (centerExpandedMapOnUser || storage.isWidgetMapExpanded()) {
            startWidgetLocationTracking(appCtx)
        }

        val syncCached = locationManager.getLastKnownLocationSync()
        if (syncCached != null) {
            applyResolvedDistanceLocation(appCtx, storage, active, syncCached, centerExpandedMapOnUser, null)
            return
        }

        updateAllWidgets(appCtx, isSaving = false, isMeasuringDistance = true)

        val pendingResult = goAsync()
        val done = AtomicBoolean(false)
        fun completeAsync() {
            if (done.compareAndSet(false, true)) {
                pendingResult.finish()
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!done.get()) {
                updateAllWidgets(appCtx, isSaving = false, isMeasuringDistance = false)
                completeAsync()
            }
        }, ASYNC_GUARD_TIMEOUT_MS)

        locationManager.getFastLocation(object : ParkingLocationManager.LocationResultCallback {
            override fun onLocationRetrieved(location: Location) {
                applyResolvedDistanceLocation(
                    appCtx,
                    storage,
                    active,
                    location,
                    centerExpandedMapOnUser,
                    ::completeAsync
                )
            }

            override fun onLocationError(message: String) {
                updateAllWidgets(appCtx, isSaving = false, isMeasuringDistance = false)
                showToast(appCtx, R.string.widget_gps_failed_toast)
                completeAsync()
            }
        })
    }

    private fun applyResolvedDistanceLocation(
        appCtx: Context,
        storage: ParkingStorage,
        active: ParkingRecord,
        location: Location,
        centerExpandedMapOnUser: Boolean,
        onCompleteAsync: (() -> Unit)?
    ) {
        val meters = DistanceCalculator.calculateDistanceMeters(location, active.toParkingSpot())
        val formatted = if (meters >= 0f) {
            appCtx.getString(R.string.widget_distance_away_format, DistanceCalculator.formatDistance(meters))
        } else {
            null
        }
        val nextCenterOnUser = if (centerExpandedMapOnUser) true else storage.isWidgetMapCenteredOnUser()

        storage.saveWidgetDistanceAndUserLocationQuiet(
            active.id,
            formatted,
            location.latitude,
            location.longitude,
            nextCenterOnUser
        )

        if (!centerExpandedMapOnUser && !storage.isWidgetMapExpanded()) {
            updateAllWidgets(appCtx, isSaving = false, isMeasuringDistance = false)
            onCompleteAsync?.invoke()
            return
        }

        val mapReq = WidgetMapRenderer.MapRenderRequest(
            parkedLat = active.latitude,
            parkedLng = active.longitude,
            centerLat = if (nextCenterOnUser) location.latitude else active.latitude,
            centerLng = if (nextCenterOnUser) location.longitude else active.longitude,
            userLat = location.latitude,
            userLng = location.longitude,
            zoom = storage.getWidgetMapZoom(),
            isExpanded = true,
            isDark = isDarkModeActive(appCtx, storage)
        )

        val cached = WidgetMapRenderer.getCachedBitmap(appCtx, mapReq) != null
        updateAllWidgets(appCtx, isSaving = false, isMeasuringDistance = !cached)
        if (cached) {
            onCompleteAsync?.invoke()
            return
        }

        if (onCompleteAsync == null) {
            val pendingResult = goAsync()
            val done = AtomicBoolean(false)
            fun finishOnce() {
                if (done.compareAndSet(false, true)) pendingResult.finish()
            }
            Handler(Looper.getMainLooper()).postDelayed({ finishOnce() }, ASYNC_GUARD_TIMEOUT_MS)
            WidgetMapRenderer.renderMapAsync(appCtx, mapReq) {
                updateAllWidgets(appCtx, isSaving = false, isMeasuringDistance = false)
                finishOnce()
            }
        } else {
            WidgetMapRenderer.renderMapAsync(appCtx, mapReq) {
                updateAllWidgets(appCtx, isSaving = false, isMeasuringDistance = false)
                onCompleteAsync()
            }
        }
    }

    private fun handleToggleBookmarkInBackground(appCtx: Context) {
        val storage = ParkingStorage(appCtx)
        val active = storage.getActiveRecord() ?: return
        val nextSaved = !active.isSaved
        storage.setRecordSaved(active.id, nextSaved)
        showToast(appCtx, if (nextSaved) R.string.bookmark_saved else R.string.bookmark_unsaved)
    }

    private fun handleClearSpotInBackground(appCtx: Context) {
        val storage = ParkingStorage(appCtx)
        if (!storage.hasSavedParkingSpot()) return
        storage.clearActiveSpot()
        showToast(appCtx, R.string.widget_cleared_toast)
    }

    private fun showToast(context: Context, resId: Int) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(context.applicationContext, resId, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val ACTION_SAVE_SPOT = "com.example.devicewatch.widget.ACTION_SAVE_SPOT"
        private const val ACTION_SHOW_DISTANCE = "com.example.devicewatch.widget.ACTION_SHOW_DISTANCE"
        private const val ACTION_TOGGLE_BOOKMARK = "com.example.devicewatch.widget.ACTION_TOGGLE_BOOKMARK"
        private const val ACTION_CLEAR_SPOT = "com.example.devicewatch.widget.ACTION_CLEAR_SPOT"
        private const val ACTION_OPEN_WIDGET_MAP = "com.example.devicewatch.widget.ACTION_OPEN_WIDGET_MAP"
        private const val ACTION_CLOSE_WIDGET_MAP = "com.example.devicewatch.widget.ACTION_CLOSE_WIDGET_MAP"
        private const val ACTION_WIDGET_MAP_ZOOM_IN = "com.example.devicewatch.widget.ACTION_WIDGET_MAP_ZOOM_IN"
        private const val ACTION_WIDGET_MAP_ZOOM_OUT = "com.example.devicewatch.widget.ACTION_WIDGET_MAP_ZOOM_OUT"
        private const val ACTION_WIDGET_MAP_MY_LOCATION = "com.example.devicewatch.widget.ACTION_WIDGET_MAP_MY_LOCATION"
        private const val ACTION_WIDGET_MAP_CENTER_PIN = "com.example.devicewatch.widget.ACTION_WIDGET_MAP_CENTER_PIN"
        private const val ACTION_WIDGET_LOCATION_UPDATE = "com.example.devicewatch.widget.ACTION_WIDGET_LOCATION_UPDATE"

        private const val ASYNC_GUARD_TIMEOUT_MS = 7_500L
        private const val TINY_HEIGHT_DP = 100
        private const val LARGE_HEIGHT_DP = 175
        private const val MIN_WIDTH_FOR_MAP_DP = 200
        private const val LOCATION_UPDATE_REQUEST_CODE = 9901

        @JvmStatic
        fun updateAllWidgets(context: Context) {
            updateAllWidgets(context, isSaving = false, isMeasuringDistance = false)
        }

        private fun updateAllWidgets(
            context: Context,
            isSaving: Boolean,
            isMeasuringDistance: Boolean
        ) {
            try {
                val appCtx = context.applicationContext
                val manager = AppWidgetManager.getInstance(appCtx) ?: return
                val ids = manager.getAppWidgetIds(
                    ComponentName(appCtx, ParkingSpotWidgetProvider::class.java)
                )
                for (id in ids) {
                    updateWidget(
                        appCtx,
                        manager,
                        id,
                        isSaving = isSaving,
                        isMeasuringDistance = isMeasuringDistance
                    )
                }
            } catch (_: Exception) {
            }
        }

        private fun startWidgetLocationTracking(appCtx: Context) {
            val locationManager = ParkingLocationManager(appCtx)
            if (locationManager.hasLocationPermission()) {
                locationManager.startWidgetLocationBroadcastUpdates(buildLocationUpdatePendingIntent(appCtx))
            }
        }

        private fun stopWidgetLocationTracking(appCtx: Context) {
            ParkingLocationManager(appCtx).stopWidgetLocationBroadcastUpdates(
                buildLocationUpdatePendingIntent(appCtx)
            )
        }

        private fun buildLocationUpdatePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, ParkingSpotWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_LOCATION_UPDATE
            }
            val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            return PendingIntent.getBroadcast(
                context,
                LOCATION_UPDATE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or mutability
            )
        }

        private fun isDarkModeActive(context: Context, storage: ParkingStorage): Boolean {
            return when (storage.getThemeMode()) {
                AppCompatDelegate.MODE_NIGHT_YES -> true
                AppCompatDelegate.MODE_NIGHT_NO -> false
                else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            }
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            isSaving: Boolean,
            isMeasuringDistance: Boolean
        ) {
            val storage = ParkingStorage(context)
            val record = storage.getActiveRecord()
            val isDark = isDarkModeActive(context, storage)
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 145)
            val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)

            val isTiny = minHeightDp in 1 until TINY_HEIGHT_DP
            val isSmall = isTiny || minWidthDp in 1 until MIN_WIDTH_FOR_MAP_DP
            val isLarge = minHeightDp >= LARGE_HEIGHT_DP
            val canShowMiniMap = !isSmall && minWidthDp >= MIN_WIDTH_FOR_MAP_DP
            val isMapExpanded = record != null && storage.isWidgetMapExpanded() && !isTiny

            val views = RemoteViews(context.packageName, R.layout.widget_parking_spot)
            applyWidgetThemePalette(views, isDark)

            val openHomePending = buildActivityPendingIntent(
                context = context,
                requestCode = appWidgetId * 20 + 1,
                intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_OPEN_HOME, true)
                }
            )
            // Clicking the top-left parked spot header or spot card navigates into the app
            views.setOnClickPendingIntent(R.id.badgePinActive, openHomePending)
            views.setOnClickPendingIntent(R.id.badgePinIdle, openHomePending)
            views.setOnClickPendingIntent(R.id.widgetStatusStack, openHomePending)
            views.setOnClickPendingIntent(R.id.widgetSpotCard, openHomePending)

            val saveSpotBroadcast = buildBroadcastPendingIntent(
                context,
                appWidgetId * 20 + 3,
                ACTION_SAVE_SPOT
            )

            val qrPending = buildActivityPendingIntent(
                context = context,
                requestCode = appWidgetId * 20 + 4,
                intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_WIDGET_OPEN_QR, true)
                }
            )
            views.setOnClickPendingIntent(R.id.btnWidgetQr, qrPending)

            if (isSaving) {
                bindSavingState(context, views, isTiny)
            } else if (record == null) {
                bindEmptyState(context, views, minHeightDp, saveSpotBroadcast, qrPending)
            } else {
                bindActiveSpotState(
                    context = context,
                    storage = storage,
                    record = record,
                    views = views,
                    appWidgetId = appWidgetId,
                    isDark = isDark,
                    isTiny = isTiny,
                    isSmall = isSmall,
                    isLarge = isLarge,
                    canShowMiniMap = canShowMiniMap,
                    isMapExpanded = isMapExpanded,
                    isMeasuringDistance = isMeasuringDistance,
                    saveSpotBroadcast = saveSpotBroadcast
                )
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun applyWidgetThemePalette(views: RemoteViews, isDark: Boolean) {
            val primary = Color.parseColor(if (isDark) "#6DD58C" else "#146C2E")
            val onPrimary = Color.parseColor(if (isDark) "#072E12" else "#FFFFFF")
            val onPrimaryContainer = Color.parseColor(if (isDark) "#A8ECC0" else "#0D4A1F")
            val textPrimary = Color.parseColor(if (isDark) "#E8EAED" else "#1F1F1F")
            val textSecondary = Color.parseColor(if (isDark) "#9AA0A6" else "#5F6368")
            val accentBlue = Color.parseColor(if (isDark) "#8AB4F8" else "#1A73E8")

            views.setInt(
                R.id.widgetRoot,
                "setBackgroundResource",
                if (isDark) R.drawable.bg_widget_card_dark else R.drawable.bg_widget_card_light
            )
            views.setInt(
                R.id.widgetSpotCard,
                "setBackgroundResource",
                if (isDark) R.drawable.bg_widget_inner_card_dark else R.drawable.bg_widget_inner_card_light
            )

            val tonalBg = if (isDark) R.drawable.bg_widget_tonal_btn_dark else R.drawable.bg_widget_tonal_btn_light
            val chipBg = if (isDark) R.drawable.bg_widget_chip_dark else R.drawable.bg_widget_chip_light
            val timeChipBg = if (isDark) R.drawable.bg_widget_time_chip_dark else R.drawable.bg_widget_time_chip_light
            val mapCtrlBg = if (isDark) R.drawable.bg_widget_map_ctrl_dark else R.drawable.bg_widget_map_ctrl_light

            views.setInt(R.id.btnWidgetBookmark, "setBackgroundResource", chipBg)
            views.setInt(R.id.btnWidgetClear, "setBackgroundResource", chipBg)
            views.setInt(R.id.btnWidgetQr, "setBackgroundResource", tonalBg)
            views.setInt(R.id.tvWidgetCoordsChip, "setBackgroundResource", chipBg)
            views.setInt(R.id.btnWidgetDistance, "setBackgroundResource", tonalBg)
            views.setInt(R.id.tvWidgetDistanceActionHint, "setBackgroundResource", chipBg)
            views.setInt(R.id.layoutWidgetPhotoBadge, "setBackgroundResource", timeChipBg)
            views.setInt(R.id.btnWidgetUpdateSpot, "setBackgroundResource", tonalBg)
            views.setInt(R.id.btnWidgetShare, "setBackgroundResource", tonalBg)
            views.setInt(R.id.btnWidgetScanQrEmpty, "setBackgroundResource", tonalBg)
            views.setInt(R.id.badgePinIdle, "setBackgroundResource", timeChipBg)
            views.setInt(R.id.badgePinActive, "setBackgroundResource", R.drawable.bg_widget_badge_circle_active)

            // Expanded map controls & bottom Distance (left) + Zoom (right) badges
            views.setInt(R.id.btnWidgetMapClose, "setBackgroundResource", mapCtrlBg)
            views.setInt(R.id.btnWidgetMapZoomIn, "setBackgroundResource", mapCtrlBg)
            views.setInt(R.id.btnWidgetMapZoomOut, "setBackgroundResource", mapCtrlBg)
            views.setInt(R.id.btnWidgetMapMyLocation, "setBackgroundResource", mapCtrlBg)
            views.setInt(R.id.btnWidgetMapPin, "setBackgroundResource", mapCtrlBg)
            views.setInt(R.id.btnWidgetMapDistance, "setBackgroundResource", chipBg)
            views.setInt(R.id.tvWidgetMapZoom, "setBackgroundResource", chipBg)
            views.setTextColor(R.id.btnWidgetMapZoomIn, textPrimary)
            views.setTextColor(R.id.btnWidgetMapZoomOut, textPrimary)
            views.setTextColor(R.id.tvWidgetMapDistance, textPrimary)
            views.setTextColor(R.id.tvWidgetMapZoom, textPrimary)
            views.setInt(R.id.ivWidgetMapCloseIcon, "setColorFilter", textPrimary)
            views.setInt(R.id.ivWidgetMapDistanceIcon, "setColorFilter", primary)
            views.setInt(R.id.ivWidgetMapMyLocationIcon, "setColorFilter", accentBlue)
            views.setInt(R.id.ivWidgetMapPinIcon, "setColorFilter", primary)

            views.setTextColor(R.id.tvWidgetStatus, primary)
            views.setTextColor(R.id.tvWidgetHeaderSub, textSecondary)
            views.setTextColor(R.id.tvWidgetTitle, textPrimary)
            views.setTextColor(R.id.tvWidgetSubtitle, textSecondary)
            views.setTextColor(R.id.tvWidgetCoordsChip, textSecondary)
            views.setTextColor(R.id.tvWidgetPhotoCount, onPrimaryContainer)
            views.setTextColor(R.id.tvWidgetDistance, primary)
            views.setTextColor(R.id.tvWidgetDistanceActionHint, textSecondary)
            views.setTextColor(R.id.tvWidgetNavigateLabel, onPrimary)
            views.setTextColor(R.id.tvWidgetUpdateLabel, textPrimary)
            views.setTextColor(R.id.tvWidgetSaveSpotLabel, onPrimary)
            views.setTextColor(R.id.tvWidgetScanQrEmptyLabel, textPrimary)

            views.setInt(R.id.ivWidgetPinIdle, "setColorFilter", primary)
            views.setInt(R.id.ivWidgetPinActive, "setColorFilter", onPrimary)
            views.setInt(R.id.ivWidgetBookmark, "setColorFilter", primary)
            views.setInt(R.id.ivWidgetClear, "setColorFilter", textSecondary)
            views.setInt(R.id.ivWidgetQr, "setColorFilter", primary)
            views.setInt(R.id.ivWidgetPhotoIcon, "setColorFilter", onPrimaryContainer)
            views.setInt(R.id.ivWidgetDistanceIcon, "setColorFilter", primary)
            views.setInt(R.id.ivWidgetNavigateIcon, "setColorFilter", onPrimary)
            views.setInt(R.id.ivWidgetUpdateIcon, "setColorFilter", primary)
            views.setInt(R.id.ivWidgetShareIcon, "setColorFilter", textPrimary)
            views.setInt(R.id.ivWidgetSaveSpotIcon, "setColorFilter", onPrimary)
            views.setInt(R.id.ivWidgetScanQrEmptyIcon, "setColorFilter", primary)
        }

        private fun bindSavingState(
            context: Context,
            views: RemoteViews,
            isTiny: Boolean
        ) {
            views.setViewVisibility(R.id.widgetExpandedMapContainer, View.GONE)
            views.setViewVisibility(R.id.widgetNormalMiddle, View.VISIBLE)
            views.setViewVisibility(R.id.badgePinActive, View.VISIBLE)
            views.setViewVisibility(R.id.badgePinIdle, View.GONE)
            views.setTextViewText(R.id.tvWidgetStatus, context.getString(R.string.widget_status_saving))
            views.setTextViewText(R.id.tvWidgetHeaderSub, context.getString(R.string.loading_gps))
            views.setViewVisibility(R.id.btnWidgetBookmark, View.GONE)
            views.setViewVisibility(R.id.btnWidgetClear, View.GONE)

            views.setTextViewText(R.id.tvWidgetTitle, context.getString(R.string.widget_saving_title))
            views.setTextViewText(R.id.tvWidgetSubtitle, context.getString(R.string.widget_saving_subtitle))
            views.setViewVisibility(R.id.tvWidgetSubtitle, if (isTiny) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.layoutWidgetMetaRow, View.GONE)
            views.setViewVisibility(R.id.btnWidgetDistance, View.GONE)
            views.setViewVisibility(R.id.layoutWidgetMapFrame, View.GONE)
        }

        private fun bindEmptyState(
            context: Context,
            views: RemoteViews,
            minHeightDp: Int,
            saveSpotBroadcast: PendingIntent,
            qrPending: PendingIntent
        ) {
            views.setViewVisibility(R.id.widgetExpandedMapContainer, View.GONE)
            views.setViewVisibility(R.id.widgetNormalMiddle, View.VISIBLE)
            views.setViewVisibility(R.id.badgePinActive, View.GONE)
            views.setViewVisibility(R.id.badgePinIdle, View.VISIBLE)
            views.setTextViewText(R.id.tvWidgetStatus, context.getString(R.string.widget_status_ready))
            views.setTextViewText(R.id.tvWidgetHeaderSub, context.getString(R.string.widget_header_idle_sub))
            views.setViewVisibility(R.id.btnWidgetBookmark, View.GONE)
            views.setViewVisibility(R.id.btnWidgetClear, View.GONE)

            views.setTextViewText(R.id.tvWidgetTitle, context.getString(R.string.widget_no_spot_title))
            views.setTextViewText(R.id.tvWidgetSubtitle, context.getString(R.string.widget_no_spot_subtitle))
            views.setViewVisibility(
                R.id.tvWidgetSubtitle,
                if (minHeightDp in 1..84) View.GONE else View.VISIBLE
            )
            views.setInt(R.id.tvWidgetSubtitle, "setMaxLines", 2)
            views.setViewVisibility(R.id.layoutWidgetMetaRow, View.GONE)
            views.setViewVisibility(R.id.btnWidgetDistance, View.GONE)
            views.setViewVisibility(R.id.layoutWidgetMapFrame, View.GONE)

            views.setViewVisibility(R.id.layoutWidgetActionsActive, View.GONE)
            views.setViewVisibility(R.id.layoutWidgetActionsEmpty, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.btnWidgetSaveSpot, saveSpotBroadcast)
            views.setOnClickPendingIntent(R.id.btnWidgetScanQrEmpty, qrPending)
        }

        private fun bindActiveSpotState(
            context: Context,
            storage: ParkingStorage,
            record: ParkingRecord,
            views: RemoteViews,
            appWidgetId: Int,
            isDark: Boolean,
            isTiny: Boolean,
            isSmall: Boolean,
            isLarge: Boolean,
            canShowMiniMap: Boolean,
            isMapExpanded: Boolean,
            isMeasuringDistance: Boolean,
            saveSpotBroadcast: PendingIntent
        ) {
            views.setViewVisibility(R.id.badgePinActive, View.VISIBLE)
            views.setViewVisibility(R.id.badgePinIdle, View.GONE)
            views.setTextViewText(R.id.tvWidgetStatus, context.getString(R.string.widget_status_active))

            val cachedDistance = storage.getWidgetDistanceText(record.id)
            if (isTiny && !cachedDistance.isNullOrBlank()) {
                views.setTextViewText(R.id.tvWidgetHeaderSub, cachedDistance)
            } else if (record.savedTimestamp > 0L) {
                val timeClock = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(record.savedTimestamp))
                val rel = RelativeTimeFormatter.format(record.savedTimestamp)
                views.setTextViewText(R.id.tvWidgetHeaderSub, "$rel · $timeClock")
            } else {
                views.setTextViewText(R.id.tvWidgetHeaderSub, context.getString(R.string.saved_spot_badge))
            }

            views.setViewVisibility(R.id.btnWidgetBookmark, View.VISIBLE)
            views.setImageViewResource(
                R.id.ivWidgetBookmark,
                if (record.isSaved) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline
            )
            views.setOnClickPendingIntent(
                R.id.btnWidgetBookmark,
                buildBroadcastPendingIntent(context, appWidgetId * 20 + 5, ACTION_TOGGLE_BOOKMARK)
            )

            views.setViewVisibility(R.id.btnWidgetClear, View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.btnWidgetClear,
                buildBroadcastPendingIntent(context, appWidgetId * 20 + 6, ACTION_CLEAR_SPOT)
            )

            if (isMapExpanded) {
                bindExpandedWidgetMap(
                    context = context,
                    storage = storage,
                    record = record,
                    views = views,
                    appWidgetId = appWidgetId,
                    isDark = isDark,
                    isMeasuringDistance = isMeasuringDistance,
                    cachedDistance = cachedDistance
                )
            } else {
                bindNormalActiveMiddle(
                    context = context,
                    record = record,
                    views = views,
                    appWidgetId = appWidgetId,
                    isDark = isDark,
                    isTiny = isTiny,
                    isSmall = isSmall,
                    isLarge = isLarge,
                    canShowMiniMap = canShowMiniMap,
                    isMeasuringDistance = isMeasuringDistance,
                    cachedDistance = cachedDistance
                )
            }

            views.setViewVisibility(R.id.layoutWidgetActionsEmpty, View.GONE)
            views.setViewVisibility(R.id.layoutWidgetActionsActive, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.btnWidgetUpdateSpot, saveSpotBroadcast)

            val navIntent = NavigationManager.buildBestDirectionsIntent(
                context,
                record.toParkingSpot(),
                storage.getNavigationTravelMode()
            )
            if (navIntent != null) {
                navIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                views.setViewVisibility(R.id.btnWidgetNavigate, View.VISIBLE)
                views.setOnClickPendingIntent(
                    R.id.btnWidgetNavigate,
                    buildActivityPendingIntent(context, appWidgetId * 20 + 7, navIntent)
                )
            } else {
                views.setViewVisibility(R.id.btnWidgetNavigate, View.GONE)
            }

            val shareIntent = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name))
                    putExtra(Intent.EXTRA_TEXT, NavigationManager.buildShareText(record))
                },
                context.getString(R.string.share_chooser_title)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            views.setOnClickPendingIntent(
                R.id.btnWidgetShare,
                buildActivityPendingIntent(context, appWidgetId * 20 + 8, shareIntent)
            )
        }

        private fun bindNormalActiveMiddle(
            context: Context,
            record: ParkingRecord,
            views: RemoteViews,
            appWidgetId: Int,
            isDark: Boolean,
            isTiny: Boolean,
            isSmall: Boolean,
            isLarge: Boolean,
            canShowMiniMap: Boolean,
            isMeasuringDistance: Boolean,
            cachedDistance: String?
        ) {
            views.setViewVisibility(R.id.widgetExpandedMapContainer, View.GONE)
            views.setViewVisibility(R.id.widgetNormalMiddle, View.VISIBLE)

            val label = record.placeLabel?.trim()?.takeIf { it.isNotEmpty() }
            val address = record.address?.trim()?.takeIf { it.isNotEmpty() }
            val coords = NativeGeocoder.formatCoordinates(record.latitude, record.longitude)

            val title = label ?: address ?: context.getString(R.string.saved_spot_badge)
            val subtitle = when {
                label != null && address != null -> address
                else -> coords
            }

            views.setTextViewText(R.id.tvWidgetTitle, title)
            views.setTextViewText(R.id.tvWidgetSubtitle, subtitle)
            views.setViewVisibility(R.id.tvWidgetSubtitle, if (isTiny) View.GONE else View.VISIBLE)
            views.setInt(R.id.tvWidgetSubtitle, "setMaxLines", if (isLarge) 2 else 1)

            if (isSmall || !isLarge) {
                views.setViewVisibility(R.id.layoutWidgetMetaRow, View.GONE)
            } else {
                views.setViewVisibility(R.id.layoutWidgetMetaRow, View.VISIBLE)
                views.setTextViewText(R.id.tvWidgetCoordsChip, coords)
                val photoCount = record.photos.size
                if (photoCount > 0) {
                    views.setTextViewText(R.id.tvWidgetPhotoCount, photoCount.toString())
                    views.setViewVisibility(R.id.layoutWidgetPhotoBadge, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.layoutWidgetPhotoBadge, View.GONE)
                }
            }

            if (isTiny) {
                views.setViewVisibility(R.id.btnWidgetDistance, View.GONE)
            } else {
                views.setViewVisibility(R.id.btnWidgetDistance, View.VISIBLE)
                val hasDistance = !cachedDistance.isNullOrBlank()
                val distanceLabel = when {
                    isMeasuringDistance -> context.getString(R.string.widget_getting_distance)
                    hasDistance -> cachedDistance!!
                    else -> context.getString(R.string.widget_show_distance)
                }
                views.setTextViewText(R.id.tvWidgetDistance, distanceLabel)
                views.setViewVisibility(
                    R.id.tvWidgetDistanceActionHint,
                    if (isMeasuringDistance) View.GONE else View.VISIBLE
                )
                views.setTextViewText(
                    R.id.tvWidgetDistanceActionHint,
                    context.getString(if (hasDistance) R.string.refresh_distance else R.string.widget_distance_check_hint)
                )
                views.setOnClickPendingIntent(
                    R.id.btnWidgetDistance,
                    buildBroadcastPendingIntent(context, appWidgetId * 20 + 9, ACTION_SHOW_DISTANCE)
                )
            }

            if (canShowMiniMap) {
                views.setViewVisibility(R.id.layoutWidgetMapFrame, View.VISIBLE)
                val miniReq = WidgetMapRenderer.MapRenderRequest(
                    parkedLat = record.latitude,
                    parkedLng = record.longitude,
                    centerLat = record.latitude,
                    centerLng = record.longitude,
                    userLat = Double.NaN,
                    userLng = Double.NaN,
                    zoom = 16.0,
                    isExpanded = false,
                    isDark = isDark
                )
                val bitmapToShow = WidgetMapRenderer.getCachedBitmap(context, miniReq)
                    ?: WidgetMapRenderer.getPreviousSpotBitmapIfAvailable(context, miniReq)
                    ?: WidgetMapRenderer.renderFallbackBitmap(context, miniReq)
                views.setImageViewBitmap(R.id.ivWidgetMiniMap, bitmapToShow)

                if (WidgetMapRenderer.getCachedBitmap(context, miniReq) == null) {
                    WidgetMapRenderer.renderMapAsync(context, miniReq) {
                        updateAllWidgets(context, isSaving = false, isMeasuringDistance = false)
                    }
                }
                views.setOnClickPendingIntent(
                    R.id.layoutWidgetMapFrame,
                    buildBroadcastPendingIntent(context, appWidgetId * 20 + 10, ACTION_OPEN_WIDGET_MAP)
                )
            } else {
                views.setViewVisibility(R.id.layoutWidgetMapFrame, View.GONE)
            }
        }

        private fun bindExpandedWidgetMap(
            context: Context,
            storage: ParkingStorage,
            record: ParkingRecord,
            views: RemoteViews,
            appWidgetId: Int,
            isDark: Boolean,
            isMeasuringDistance: Boolean,
            cachedDistance: String?
        ) {
            views.setViewVisibility(R.id.widgetNormalMiddle, View.GONE)
            views.setViewVisibility(R.id.widgetExpandedMapContainer, View.VISIBLE)

            val zoom = storage.getWidgetMapZoom()
            val userLat = storage.getWidgetMapUserLat()
            val userLng = storage.getWidgetMapUserLng()
            val hasUser = DistanceCalculator.isValidCoordinate(userLat, userLng)
            val centerOnUser = storage.isWidgetMapCenteredOnUser() && hasUser

            val centerLat = if (centerOnUser) userLat else record.latitude
            val centerLng = if (centerOnUser) userLng else record.longitude

            val mapReq = WidgetMapRenderer.MapRenderRequest(
                parkedLat = record.latitude,
                parkedLng = record.longitude,
                centerLat = centerLat,
                centerLng = centerLng,
                userLat = if (hasUser) userLat else Double.NaN,
                userLng = if (hasUser) userLng else Double.NaN,
                zoom = zoom,
                isExpanded = true,
                isDark = isDark
            )

            val displayBitmap = WidgetMapRenderer.getCachedBitmap(context, mapReq)
                ?: WidgetMapRenderer.getPreviousSpotBitmapIfAvailable(context, mapReq)
                ?: WidgetMapRenderer.renderFallbackBitmap(context, mapReq)
            views.setImageViewBitmap(R.id.ivWidgetExpandedMap, displayBitmap)

            // Bottom-left: Distance badge (tappable to refresh live distance)
            val distanceText = when {
                isMeasuringDistance -> context.getString(R.string.widget_map_locating)
                !cachedDistance.isNullOrBlank() -> cachedDistance
                else -> context.getString(R.string.widget_show_distance)
            }
            views.setTextViewText(R.id.tvWidgetMapDistance, distanceText)
            views.setOnClickPendingIntent(
                R.id.btnWidgetMapDistance,
                buildBroadcastPendingIntent(context, appWidgetId * 20 + 9, ACTION_SHOW_DISTANCE)
            )

            // Bottom-right: Zoom badge
            views.setTextViewText(
                R.id.tvWidgetMapZoom,
                context.getString(R.string.widget_map_zoom_badge, zoom)
            )

            views.setOnClickPendingIntent(
                R.id.btnWidgetMapClose,
                buildBroadcastPendingIntent(context, appWidgetId * 20 + 11, ACTION_CLOSE_WIDGET_MAP)
            )
            views.setOnClickPendingIntent(
                R.id.btnWidgetMapZoomIn,
                buildBroadcastPendingIntent(context, appWidgetId * 20 + 12, ACTION_WIDGET_MAP_ZOOM_IN)
            )
            views.setOnClickPendingIntent(
                R.id.btnWidgetMapZoomOut,
                buildBroadcastPendingIntent(context, appWidgetId * 20 + 13, ACTION_WIDGET_MAP_ZOOM_OUT)
            )
            views.setOnClickPendingIntent(
                R.id.btnWidgetMapMyLocation,
                buildBroadcastPendingIntent(context, appWidgetId * 20 + 14, ACTION_WIDGET_MAP_MY_LOCATION)
            )
            views.setOnClickPendingIntent(
                R.id.btnWidgetMapPin,
                buildBroadcastPendingIntent(context, appWidgetId * 20 + 15, ACTION_WIDGET_MAP_CENTER_PIN)
            )
        }

        private fun buildActivityPendingIntent(
            context: Context,
            requestCode: Int,
            intent: Intent
        ): PendingIntent {
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun buildBroadcastPendingIntent(
            context: Context,
            requestCode: Int,
            action: String
        ): PendingIntent {
            val intent = Intent(context, ParkingSpotWidgetProvider::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
