package com.example.devicewatch

import android.app.Activity
import android.content.Context
import android.location.Location
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import androidx.core.content.res.ResourcesCompat
import com.example.devicewatch.location.NativeGeocoder
import com.example.devicewatch.model.ParkingRecord
import com.example.devicewatch.storage.ParkingPhotoManager
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
            // Permission may have been revoked; ignore safely.
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
        val style = map.style
        if (styleLoaded && style != null && style.isFullyLoaded) {
            val point = Point.fromLngLat(record.longitude, record.latitude)
            style.getSourceAs<GeoJsonSource>(MARKER_SOURCE_ID)
                ?.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(point)))
        }
        if (animateCamera) {
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(record.latitude, record.longitude),
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

    fun formatPhotoTimeText(context: Context, record: ParkingRecord): String {
        val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        return when {
            record.hasOriginalCaptureTimestamp() -> context.getString(
                R.string.photo_captured_at,
                formatter.format(Date(record.originalCaptureTimestamp))
            )
            record.hasImportTimestamp() -> context.getString(
                R.string.photo_imported_at,
                formatter.format(Date(record.importTimestamp))
            )
            else -> context.getString(R.string.photo_time_unavailable)
        }
    }

    fun formatPhotoLocationText(context: Context, record: ParkingRecord): String {
        return if (record.hasPhotoLocation()) {
            context.getString(
                R.string.photo_gps_available,
                NativeGeocoder.formatCoordinates(record.photoLatitude, record.photoLongitude)
            )
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
        val existingNote = currentNote.orEmpty()
        val input = EditText(context).apply {
            setHint(R.string.note_hint)
            setText(existingNote)
            setPadding(48, 32, 48, 32)
        }
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.edit_note)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val note = input.text?.toString()?.trim().orEmpty()
                onSave(note.ifEmpty { null })
            }
        if (existingNote.isNotBlank()) {
            builder.setNeutralButton(R.string.remove_note) { _, _ ->
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
}
