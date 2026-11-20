package com.example.devicewatch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.example.devicewatch.location.DistanceCalculator
import com.example.devicewatch.location.NativeGeocoder
import com.example.devicewatch.model.ParkingPhotoItem
import com.example.devicewatch.model.ParkingRecord
import com.example.devicewatch.storage.ParkingPhotoManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.utils.BitmapUtils
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.text.DateFormat
import java.util.Date

internal object ParkingUiHelper {

    const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    const val MAP_DEFAULT_ZOOM = 16.5
    private const val MARKER_SOURCE_ID = "parking-spot-source"
    private const val MARKER_LAYER_ID = "parking-spot-layer"
    private const val MARKER_ICON_ID = "parking-spot-pin"
    const val PREVIEW_REQ_SIZE = 1440

    fun formatCooldownLabel(context: Context, cooldownMs: Long): String {
        val resId = when (cooldownMs) {
            3_000L -> R.string.cooldown_3_sec
            5_000L -> R.string.cooldown_5_sec
            10_000L -> R.string.cooldown_10_sec
            20_000L -> R.string.cooldown_20_sec
            30_000L -> R.string.cooldown_30_sec
            60_000L -> R.string.cooldown_1_min
            120_000L -> R.string.cooldown_2_min
            300_000L -> R.string.cooldown_5_min
            else -> R.string.cooldown_10_sec
        }
        return context.getString(resId)
    }

    fun ensureMarkerLayer(context: Context, style: Style) {
        if (style.getImage(MARKER_ICON_ID) == null) {
            val drawable = ResourcesCompat.getDrawable(
                context.resources,
                R.drawable.ic_parking_pin,
                context.theme
            )
            val bitmap = drawable?.let { BitmapUtils.getBitmapFromDrawable(it) }
            if (bitmap != null) {
                style.addImage(MARKER_ICON_ID, bitmap)
            }
        }
        if (style.getSource(MARKER_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(MARKER_SOURCE_ID))
        }
        if (style.getLayer(MARKER_LAYER_ID) == null) {
            val layer = SymbolLayer(MARKER_LAYER_ID, MARKER_SOURCE_ID).withProperties(
                iconImage(MARKER_ICON_ID),
                iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                iconAllowOverlap(true),
                iconIgnorePlacement(true)
            )
            style.addLayer(layer)
        }
    }

    fun activateUserLocationPuck(
        context: Context,
        map: MapLibreMap,
        style: Style,
        initialLocation: Location?
    ) {
        try {
            val locationComponent = map.locationComponent
            if (!locationComponent.isLocationComponentActivated) {
                val options = LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(false)
                    .build()
                locationComponent.activateLocationComponent(options)
            }
            locationComponent.isLocationComponentEnabled = true
            locationComponent.cameraMode = CameraMode.NONE
            locationComponent.renderMode = RenderMode.COMPASS
            if (initialLocation != null) {
                locationComponent.forceLocationUpdate(initialLocation)
            }
        } catch (_: SecurityException) {
        }
    }

    fun updateUserLocationPuck(map: MapLibreMap?, location: Location?) {
        if (map == null || location == null) return
        try {
            val locationComponent = map.locationComponent
            if (locationComponent.isLocationComponentActivated && locationComponent.isLocationComponentEnabled) {
                locationComponent.forceLocationUpdate(location)
            }
        } catch (_: SecurityException) {
        }
    }

    fun showRecordOnMap(
        map: MapLibreMap,
        styleLoaded: Boolean,
        record: ParkingRecord,
        animateCamera: Boolean = true
    ) {
        showCoordinatesOnMap(map, styleLoaded, record.latitude, record.longitude, animateCamera)
    }

    fun showCoordinatesOnMap(
        map: MapLibreMap,
        styleLoaded: Boolean,
        latitude: Double,
        longitude: Double,
        animateCamera: Boolean = true
    ) {
        val style = map.style
        if (styleLoaded && style != null && style.isFullyLoaded) {
            val point = Point.fromLngLat(longitude, latitude)
            style.getSourceAs<GeoJsonSource>(MARKER_SOURCE_ID)
                ?.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(point)))
        }
        if (animateCamera) {
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(latitude, longitude),
                    MAP_DEFAULT_ZOOM
                )
            )
        }
    }

    fun clearMapMarker(map: MapLibreMap?) {
        val style = map?.style ?: return
        if (style.isFullyLoaded) {
            style.getSourceAs<GeoJsonSource>(MARKER_SOURCE_ID)
                ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        }
    }

    fun zoomIn(map: MapLibreMap?) {
        map?.animateCamera(CameraUpdateFactory.zoomIn())
    }

    fun zoomOut(map: MapLibreMap?) {
        map?.animateCamera(CameraUpdateFactory.zoomOut())
    }

    fun applyBookmarkIcon(button: ImageButton, isSaved: Boolean) {
        val ctx = button.context
        if (isSaved) {
            button.setImageResource(R.drawable.ic_bookmark_filled)
            button.setColorFilter(ContextCompat.getColor(ctx, R.color.ps_primary))
        } else {
            button.setImageResource(R.drawable.ic_bookmark_outline)
            button.setColorFilter(ContextCompat.getColor(ctx, R.color.ps_text_secondary))
        }
    }

    fun formatPhotoTimeText(context: Context, photo: ParkingPhotoItem): String {
        val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        return when {
            photo.hasOriginalCaptureTimestamp() -> context.getString(
                R.string.photo_captured_at,
                formatter.format(Date(photo.originalCaptureTimestamp))
            )
            photo.hasImportTimestamp() -> context.getString(
                R.string.photo_imported_at,
                formatter.format(Date(photo.importTimestamp))
            )
            else -> context.getString(R.string.photo_time_unavailable)
        }
    }

    fun formatPhotoLocationText(context: Context, photo: ParkingPhotoItem): String {
        return if (photo.hasPhotoLocation()) {
            context.getString(
                R.string.photo_gps_available,
                NativeGeocoder.formatCoordinates(photo.photoLatitude, photo.photoLongitude)
            )
        } else {
            context.getString(R.string.photo_gps_unavailable)
        }
    }

    fun formatPhotoTimeText(context: Context, record: ParkingRecord): String {
        val first = record.photos.firstOrNull()
        return if (first != null) {
            formatPhotoTimeText(context, first)
        } else {
            context.getString(R.string.photo_time_unavailable)
        }
    }

    fun formatPhotoLocationText(context: Context, record: ParkingRecord): String {
        val first = record.photos.firstOrNull()
        return if (first != null) {
            formatPhotoLocationText(context, first)
        } else {
            context.getString(R.string.photo_gps_unavailable)
        }
    }

    fun formatElapsedDuration(timestampMillis: Long): String {
        if (timestampMillis <= 0L) return "--"
        val diffMillis = (System.currentTimeMillis() - timestampMillis).coerceAtLeast(0L)
        val totalMinutes = diffMillis / 60_000L
        if (totalMinutes < 1L) return "Just now"
        if (totalMinutes < 60L) return "$totalMinutes min ago"
        val hours = totalMinutes / 60L
        val mins = totalMinutes % 60L
        if (hours < 24L) {
            return if (mins == 0L) "${hours}h ago" else "${hours}h ${mins}m ago"
        }
        val days = hours / 24L
        val remHours = hours % 24L
        return if (remHours == 0L) "${days}d ago" else "${days}d ${remHours}h ago"
    }

    fun showNoteEditDialog(
        context: Context,
        currentNote: String?,
        onSave: (String?) -> Unit
    ) {
        showTextInputDialog(
            context = context,
            titleRes = R.string.edit_note,
            hintRes = R.string.note_hint,
            initialText = currentNote,
            positiveRes = R.string.save,
            negativeRes = R.string.cancel,
            allowRemoveWhenNonEmpty = true,
            onSave = onSave
        )
    }

    fun showPlaceLabelDialog(
        context: Context,
        @StringRes titleRes: Int,
        @StringRes hintRes: Int,
        currentLabel: String?,
        @StringRes negativeRes: Int,
        allowRemove: Boolean,
        onSave: (String?) -> Unit
    ) {
        showTextInputDialog(
            context = context,
            titleRes = titleRes,
            hintRes = hintRes,
            initialText = currentLabel,
            positiveRes = R.string.save,
            negativeRes = negativeRes,
            allowRemoveWhenNonEmpty = allowRemove,
            onSave = onSave
        )
    }

    private fun showTextInputDialog(
        context: Context,
        @StringRes titleRes: Int,
        @StringRes hintRes: Int,
        initialText: String?,
        @StringRes positiveRes: Int,
        @StringRes negativeRes: Int,
        allowRemoveWhenNonEmpty: Boolean,
        onSave: (String?) -> Unit
    ) {
        val existing = initialText.orEmpty()
        val input = EditText(context).apply {
            setHint(hintRes)
            setText(existing)
            setTextColor(ContextCompat.getColor(context, R.color.ps_text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.ps_text_secondary))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelection(text?.length ?: 0)
        }
        val container = FrameLayout(context).apply {
            setPadding(48, 20, 48, 8)
            addView(input)
        }
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(container)
            .setNegativeButton(negativeRes, null)
            .setPositiveButton(positiveRes) { _, _ ->
                val value = input.text?.toString()?.trim().orEmpty()
                onSave(value.ifEmpty { null })
            }
        if (allowRemoveWhenNonEmpty && existing.isNotBlank()) {
            builder.setNeutralButton(R.string.remove) { _, _ ->
                onSave(null)
            }
        }
        builder.show()
    }

    fun showPhotoPreviewDialog(
        activity: Activity,
        photoManager: ParkingPhotoManager,
        photoPath: String,
        onMissing: () -> Unit
    ) {
        photoManager.loadSampledBitmap(photoPath, PREVIEW_REQ_SIZE, PREVIEW_REQ_SIZE) { bitmap ->
            if (activity.isFinishing || activity.isDestroyed) return@loadSampledBitmap
            if (bitmap == null) {
                onMissing()
                return@loadSampledBitmap
            }
            val previewImage = ImageView(activity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bitmap)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.photo_preview_title)
                .setView(previewImage)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    sealed class RecordDistanceState {
        object Idle : RecordDistanceState()
        object Loading : RecordDistanceState()
        data class Loaded(val awayText: String) : RecordDistanceState()
        data class Error(val message: String) : RecordDistanceState()
    }

    fun formatDistanceAwayText(context: Context, distanceMeters: Float): String? {
        if (distanceMeters < 0f || distanceMeters.isNaN() || distanceMeters.isInfinite()) {
            return null
        }
        return context.getString(
            R.string.distance_away_format,
            DistanceCalculator.formatDistance(distanceMeters)
        )
    }

    fun bindOnDemandDistanceRow(
        state: RecordDistanceState,
        progressBar: ProgressBar,
        statusTextView: TextView,
        actionButton: MaterialButton
    ) {
        val ctx = actionButton.context
        when (state) {
            is RecordDistanceState.Idle -> {
                progressBar.visibility = View.GONE
                statusTextView.visibility = View.GONE
                statusTextView.text = ""
                actionButton.isEnabled = true
                actionButton.setText(R.string.show_distance)
            }
            is RecordDistanceState.Loading -> {
                progressBar.visibility = View.VISIBLE
                statusTextView.visibility = View.VISIBLE
                statusTextView.setText(R.string.calculating_distance)
                statusTextView.setTextColor(ContextCompat.getColor(ctx, R.color.ps_text_secondary))
                actionButton.isEnabled = false
                actionButton.setText(R.string.show_distance)
            }
            is RecordDistanceState.Loaded -> {
                progressBar.visibility = View.GONE
                statusTextView.visibility = View.VISIBLE
                statusTextView.text = state.awayText
                statusTextView.setTextColor(ContextCompat.getColor(ctx, R.color.ps_primary))
                actionButton.isEnabled = true
                actionButton.setText(R.string.refresh_distance)
            }
            is RecordDistanceState.Error -> {
                progressBar.visibility = View.GONE
                statusTextView.visibility = View.VISIBLE
                statusTextView.text = state.message
                statusTextView.setTextColor(ContextCompat.getColor(ctx, R.color.ps_error))
                actionButton.isEnabled = true
                actionButton.setText(R.string.show_distance)
            }
        }
    }

    fun showDistancePermissionDialog(
        context: Context,
        allowRetryRequest: Boolean,
        onRetryPermission: () -> Unit,
        onOpenSettings: () -> Unit
    ) {
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.distance_permission_required_title)
            .setMessage(R.string.distance_permission_required_message)
            .setNegativeButton(R.string.cancel, null)
        if (allowRetryRequest) {
            builder.setPositiveButton(R.string.grant_permission) { _, _ -> onRetryPermission() }
            builder.setNeutralButton(R.string.open_settings) { _, _ -> onOpenSettings() }
        } else {
            builder.setPositiveButton(R.string.open_settings) { _, _ -> onOpenSettings() }
        }
        builder.show()
    }

    fun showDistanceGpsDisabledDialog(
        context: Context,
        onOpenLocationSettings: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.distance_gps_disabled_title)
            .setMessage(R.string.distance_gps_disabled_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.enable_gps) { _, _ -> onOpenLocationSettings() }
            .show()
    }

    fun openAppPermissionSettings(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
            )
        } catch (_: Exception) {
        }
    }

    fun openDeviceLocationSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (_: Exception) {
        }
    }

    fun showPhotoSourceBottomSheet(
        context: Context,
        @StringRes titleRes: Int,
        onTakePhoto: () -> Unit,
        onChooseGallery: () -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val sheetView = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_photo_source, null)
        sheetView.findViewById<TextView>(R.id.tvPhotoSheetTitle).setText(titleRes)
        sheetView.findViewById<View>(R.id.cardOptionCamera).setOnClickListener {
            dialog.dismiss()
            onTakePhoto()
        }
        sheetView.findViewById<View>(R.id.cardOptionGallery).setOnClickListener {
            dialog.dismiss()
            onChooseGallery()
        }
        sheetView.findViewById<View>(R.id.btnPhotoSourceCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.setContentView(sheetView)
        dialog.show()
    }

    fun showChangeLocationBottomSheet(
        context: Context,
        onUseCurrentGps: () -> Unit,
        onChooseOnMap: () -> Unit,
        onScanQr: (() -> Unit)? = null
    ) {
        val dialog = BottomSheetDialog(context)
        val sheetView = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_change_location, null)
        sheetView.findViewById<View>(R.id.cardOptionCurrentGps).setOnClickListener {
            dialog.dismiss()
            onUseCurrentGps()
        }
        sheetView.findViewById<View>(R.id.cardOptionChooseOnMap).setOnClickListener {
            dialog.dismiss()
            onChooseOnMap()
        }
        val qrCard = sheetView.findViewById<View>(R.id.cardOptionScanQr)
        if (onScanQr != null) {
            qrCard.visibility = View.VISIBLE
            qrCard.setOnClickListener {
                dialog.dismiss()
                onScanQr()
            }
        } else {
            qrCard.visibility = View.GONE
        }
        sheetView.findViewById<View>(R.id.btnChangeLocationCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.setContentView(sheetView)
        dialog.show()
    }

    fun buildFallbackGalleryIntent(allowMultiple: Boolean): Intent {
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun extractPickedImageUris(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val result = mutableListOf<Uri>()
        val clipData = data.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i)?.uri?.let { result.add(it) }
            }
        }
        if (result.isEmpty()) {
            data.data?.let { result.add(it) }
        }
        return result
    }
}
