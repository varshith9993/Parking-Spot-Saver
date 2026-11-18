package com.example.devicewatch

import android.content.Context
import android.location.Location
import androidx.activity.result.IntentSenderRequest
import com.example.devicewatch.location.DistanceCalculator
import com.example.devicewatch.location.ParkingLocationManager
import com.example.devicewatch.model.ParkingRecord
import com.google.android.gms.common.api.ResolvableApiException

/**
 * Coordinates strictly on-demand distance calculation for History, Saved Locations,
 * and Record Detail using one shared ParkingLocationManager without continuous GPS polling.
 */
internal class OnDemandDistanceController(
    private val context: Context,
    private val locationManager: ParkingLocationManager,
    private val isActive: () -> Boolean,
    private val onRequestLocationPermission: () -> Unit,
    private val onLaunchGpsResolution: (IntentSenderRequest) -> Unit,
    private val onShowPermissionDialog: (allowRetry: Boolean) -> Unit,
    private val onShowGpsDisabledDialog: () -> Unit,
    private val onStatesChanged: () -> Unit
) {

    private val states = mutableMapOf<String, ParkingUiHelper.RecordDistanceState>()
    private val pendingRecords = linkedMapOf<String, ParkingRecord>()
    private var gpsRetryRecords: List<ParkingRecord> = emptyList()
    private var permissionPendingRecord: ParkingRecord? = null
    private var isFetchInFlight = false

    fun stateFor(recordId: String): ParkingUiHelper.RecordDistanceState {
        return states[recordId] ?: ParkingUiHelper.RecordDistanceState.Idle
    }

    fun requestDistance(record: ParkingRecord) {
        if (!isActive()) return
        if (!DistanceCalculator.isValidCoordinate(record.latitude, record.longitude)) {
            states[record.id] = ParkingUiHelper.RecordDistanceState.Error(
                context.getString(R.string.distance_invalid_coords)
            )
            onStatesChanged()
            return
        }

        if (!locationManager.hasLocationPermission()) {
            permissionPendingRecord = record
            onRequestLocationPermission()
            return
        }

        enqueueAndFetch(record)
    }

    fun retryAfterPermissionGrantRequest() {
        permissionPendingRecord?.let {
            if (isActive()) {
                onRequestLocationPermission()
            }
        }
    }

    fun onPermissionResult(granted: Boolean, shouldShowRationale: Boolean) {
        if (!isActive()) {
            permissionPendingRecord = null
            return
        }
        val target = permissionPendingRecord
        if (granted) {
            permissionPendingRecord = null
            if (target != null) {
                enqueueAndFetch(target)
            }
        } else {
            if (target != null) {
                states[target.id] = ParkingUiHelper.RecordDistanceState.Error(
                    context.getString(R.string.distance_permission_required_title)
                )
                onStatesChanged()
            }
            onShowPermissionDialog(shouldShowRationale)
        }
    }

    fun onGpsResolutionResult(resolved: Boolean) {
        if (!isActive()) {
            gpsRetryRecords = emptyList()
            return
        }
        val retryList = gpsRetryRecords
        gpsRetryRecords = emptyList()
        if (resolved) {
            retryList.forEach { rec ->
                if (DistanceCalculator.isValidCoordinate(rec.latitude, rec.longitude)) {
                    pendingRecords[rec.id] = rec
                    states[rec.id] = ParkingUiHelper.RecordDistanceState.Loading
                }
            }
            if (pendingRecords.isNotEmpty() && !isFetchInFlight) {
                onStatesChanged()
                checkGpsAndFetchCurrentLocation()
            }
        } else {
            retryList.forEach { rec ->
                states[rec.id] = ParkingUiHelper.RecordDistanceState.Error(
                    context.getString(R.string.distance_gps_disabled_title)
                )
            }
            onStatesChanged()
            onShowGpsDisabledDialog()
        }
    }

    fun cancelAndClear(resetStates: Boolean = true) {
        isFetchInFlight = false
        pendingRecords.clear()
        gpsRetryRecords = emptyList()
        permissionPendingRecord = null
        locationManager.cancelCurrentLocationRequest()
        if (resetStates) {
            states.clear()
            onStatesChanged()
        }
    }

    private fun enqueueAndFetch(record: ParkingRecord) {
        pendingRecords[record.id] = record
        states[record.id] = ParkingUiHelper.RecordDistanceState.Loading
        onStatesChanged()

        if (!isFetchInFlight) {
            checkGpsAndFetchCurrentLocation()
        }
    }

    private fun checkGpsAndFetchCurrentLocation() {
        isFetchInFlight = true
        locationManager.checkLocationSettings(object : ParkingLocationManager.SettingsCheckCallback {
            override fun onSettingsSatisfied() {
                if (!isActive()) {
                    cancelAndClear(resetStates = false)
                    return
                }
                fetchCurrentLocationOnce()
            }

            override fun onSettingsResolvable(exception: ResolvableApiException) {
                if (!isActive()) {
                    cancelAndClear(resetStates = false)
                    return
                }
                isFetchInFlight = false
                val waiting = pendingRecords.values.toList()
                pendingRecords.clear()
                waiting.forEach { states.remove(it.id) }
                onStatesChanged()

                gpsRetryRecords = waiting
                try {
                    val request = IntentSenderRequest.Builder(exception.resolution).build()
                    onLaunchGpsResolution(request)
                } catch (_: Throwable) {
                    waiting.forEach { rec ->
                        states[rec.id] = ParkingUiHelper.RecordDistanceState.Error(
                            context.getString(R.string.distance_gps_disabled_title)
                        )
                    }
                    gpsRetryRecords = emptyList()
                    onStatesChanged()
                    onShowGpsDisabledDialog()
                }
            }

            override fun onSettingsUnavailable(message: String) {
                if (!isActive()) {
                    cancelAndClear(resetStates = false)
                    return
                }
                isFetchInFlight = false
                val waiting = pendingRecords.values.toList()
                pendingRecords.clear()
                waiting.forEach { rec ->
                    states[rec.id] = ParkingUiHelper.RecordDistanceState.Error(
                        context.getString(R.string.distance_gps_disabled_title)
                    )
                }
                onStatesChanged()
                onShowGpsDisabledDialog()
            }
        })
    }

    private fun fetchCurrentLocationOnce() {
        locationManager.getCurrentLocation(object : ParkingLocationManager.LocationResultCallback {
            override fun onLocationRetrieved(location: Location) {
                isFetchInFlight = false
                if (!isActive()) {
                    pendingRecords.clear()
                    return
                }
                val batch = pendingRecords.values.toList()
                pendingRecords.clear()
                batch.forEach { rec ->
                    val meters = DistanceCalculator.calculateDistanceMeters(location, rec.toParkingSpot())
                    val formatted = ParkingUiHelper.formatDistanceAwayText(context, meters)
                    states[rec.id] = if (formatted != null) {
                        ParkingUiHelper.RecordDistanceState.Loaded(formatted)
                    } else {
                        ParkingUiHelper.RecordDistanceState.Error(
                            context.getString(R.string.distance_invalid_coords)
                        )
                    }
                }
                onStatesChanged()
            }

            override fun onLocationError(message: String) {
                isFetchInFlight = false
                if (!isActive()) {
                    pendingRecords.clear()
                    return
                }
                val batch = pendingRecords.values.toList()
                pendingRecords.clear()
                val errorText = message.ifBlank { context.getString(R.string.distance_failed_retry) }
                batch.forEach { rec ->
                    states[rec.id] = ParkingUiHelper.RecordDistanceState.Error(errorText)
                }
                onStatesChanged()
            }
        })
    }
}
