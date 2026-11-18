package com.example.devicewatch

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.devicewatch.databinding.ActivityRecordDetailBinding
import com.example.devicewatch.location.NativeGeocoder
import com.example.devicewatch.model.ParkingRecord
import com.example.devicewatch.navigation.NavigationManager
import com.example.devicewatch.storage.ParkingPhotoManager
import com.example.devicewatch.storage.ParkingStorage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.text.DateFormat
import java.util.Date

class ParkingRecordDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECORD_ID = "extra_record_id"
        const val EXTRA_SOURCE_MODE = "extra_source_mode"
        const val MODE_SAVED = "mode_saved"
        const val MODE_HISTORY = "mode_history"
        private const val THUMB_REQ_SIZE = 720
    }

    private lateinit var binding: ActivityRecordDetailBinding
    private lateinit var parkingStorage: ParkingStorage
    private lateinit var photoManager: ParkingPhotoManager

    private var currentRecord: ParkingRecord? = null
    private var sourceMode: String = MODE_SAVED
    private var mapLibreMap: MapLibreMap? = null
    private var mapInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        mapInitialized = initMapLibre()
        binding = ActivityRecordDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        parkingStorage = ParkingStorage(this)
        photoManager = ParkingPhotoManager(this)

        val recordId = intent.getStringExtra(EXTRA_RECORD_ID)
        sourceMode = intent.getStringExtra(EXTRA_SOURCE_MODE) ?: MODE_SAVED
        val record = if (sourceMode == MODE_HISTORY) {
            parkingStorage.getHistoryRecordById(recordId)
        } else {
            parkingStorage.getSavedRecordById(recordId)
        }

        if (record == null) {
            Toast.makeText(this, R.string.record_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentRecord = record

        binding.tvDetailScreenTitle.setText(
            if (sourceMode == MODE_HISTORY) R.string.record_detail_history_title
            else R.string.record_detail_saved_title
        )

        binding.btnDetailBack.setOnClickListener { finish() }
        binding.btnDetailZoomIn.setOnClickListener { ParkingUiHelper.zoomIn(mapLibreMap) }
        binding.btnDetailZoomOut.setOnClickListener { ParkingUiHelper.zoomOut(mapLibreMap) }
        binding.btnDetailNavigate.setOnClickListener {
            val active = currentRecord ?: return@setOnClickListener
            val opened = NavigationManager.openWalkingDirections(this, active.toParkingSpot())
            if (!opened) {
                Toast.makeText(this, R.string.no_navigation_app, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnDetailDelete.setOnClickListener { confirmDelete() }

        if (sourceMode == MODE_SAVED) {
            binding.btnDetailEditNote.visibility = View.VISIBLE
            binding.btnDetailEditNote.setOnClickListener { editNote() }
            binding.btnDetailSetActive.visibility = View.VISIBLE
            binding.btnDetailSetActive.setOnClickListener { setRecordAsActive() }
        } else {
            binding.btnDetailEditNote.visibility = View.GONE
            binding.btnDetailSetActive.visibility = View.GONE
        }

        bindRecord(record)
        setupMapView(savedInstanceState, record)
    }

    private fun initMapLibre(): Boolean {
        return try {
            MapLibre.getInstance(this)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.recordDetailRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.detailTopBar.setPadding(8, 8 + bars.top, 8, 8)
            binding.detailContentContainer.setPadding(16, 14, 16, 24 + bars.bottom)
            insets
        }
    }

    private fun bindRecord(record: ParkingRecord) {
        binding.tvDetailAddress.text = if (!record.address.isNullOrBlank()) {
            record.address
        } else {
            getString(R.string.address_unavailable_fallback)
        }
        binding.tvDetailCoordinates.text =
            NativeGeocoder.formatCoordinates(record.latitude, record.longitude)

        if (record.savedTimestamp > 0L) {
            val date = Date(record.savedTimestamp)
            binding.tvDetailDate.text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
            binding.tvDetailTime.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
        } else {
            binding.tvDetailDate.text = "--"
            binding.tvDetailTime.text = "--"
        }

        val note = record.note
        if (!note.isNullOrBlank()) {
            binding.tvDetailNote.text = note
            binding.btnDetailEditNote.setText(R.string.edit_note)
        } else {
            binding.tvDetailNote.setText(R.string.no_note_placeholder)
            binding.btnDetailEditNote.setText(R.string.add_note)
        }

        val photoPath = record.photoPath
        if (photoPath.isNullOrBlank()) {
            binding.cardDetailPhoto.visibility = View.GONE
            return
        }

        binding.cardDetailPhoto.visibility = View.VISIBLE
        binding.layoutDetailPhotoMetadata.visibility = View.VISIBLE
        binding.tvDetailPhotoTime.text = ParkingUiHelper.formatPhotoTimeText(this, record)
        binding.tvDetailPhotoGps.text = ParkingUiHelper.formatPhotoLocationText(this, record)

        photoManager.loadSampledBitmap(photoPath, THUMB_REQ_SIZE, THUMB_REQ_SIZE) { bitmap ->
            if (isFinishing || isDestroyed) return@loadSampledBitmap
            if (bitmap != null) {
                binding.ivDetailPhoto.setImageBitmap(bitmap)
                binding.cardDetailPhotoContainer.visibility = View.VISIBLE
                binding.tvDetailPhotoMissing.visibility = View.GONE
                binding.ivDetailPhoto.setOnClickListener {
                    ParkingUiHelper.showPhotoPreviewDialog(this, photoManager, photoPath) {
                        binding.cardDetailPhotoContainer.visibility = View.GONE
                        binding.tvDetailPhotoMissing.visibility = View.VISIBLE
                    }
                }
            } else {
                binding.cardDetailPhotoContainer.visibility = View.GONE
                binding.tvDetailPhotoMissing.visibility = View.VISIBLE
            }
        }
    }

    private fun setupMapView(savedInstanceState: Bundle?, record: ParkingRecord) {
        if (!mapInitialized) {
            binding.tvDetailMapFallback.visibility = View.VISIBLE
            return
        }
        try {
            binding.detailMapView.onCreate(savedInstanceState)
            binding.detailMapView.addOnDidFailLoadingMapListener {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        binding.tvDetailMapFallback.visibility = View.VISIBLE
                    }
                }
            }
            binding.detailMapView.getMapAsync { map ->
                mapLibreMap = map
                map.setStyle(Style.Builder().fromUri(ParkingUiHelper.MAP_STYLE_URL)) { style ->
                    if (isFinishing || isDestroyed) return@setStyle
                    ParkingUiHelper.ensureMarkerLayer(this, style)
                    binding.tvDetailMapFallback.visibility = View.GONE
                    ParkingUiHelper.showRecordOnMap(map, true, record)
                }
            }
        } catch (_: Throwable) {
            mapInitialized = false
            binding.tvDetailMapFallback.visibility = View.VISIBLE
        }
    }

    private fun editNote() {
        val record = currentRecord ?: return
        ParkingUiHelper.showNoteEditDialog(this, record.note) { updatedNote ->
            parkingStorage.updateRecordNote(record.id, updatedNote)
            val refreshed = parkingStorage.getSavedRecordById(record.id)
            if (refreshed != null) {
                currentRecord = refreshed
                bindRecord(refreshed)
            }
        }
    }

    private fun setRecordAsActive() {
        val record = currentRecord ?: return
        parkingStorage.setExistingRecordAsActive(record)
        Toast.makeText(this, R.string.set_active_confirmation, Toast.LENGTH_SHORT).show()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_HOME, true)
        }
        startActivity(intent)
        finish()
    }

    private fun confirmDelete() {
        val record = currentRecord ?: return
        val titleRes = if (sourceMode == MODE_HISTORY) {
            R.string.delete_history_item_title
        } else {
            R.string.delete_saved_title
        }
        val msgRes = if (sourceMode == MODE_HISTORY) {
            R.string.delete_history_item_message
        } else {
            R.string.delete_saved_message
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(msgRes)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val removed = if (sourceMode == MODE_HISTORY) {
                    parkingStorage.deleteHistoryRecord(record.id)
                } else {
                    parkingStorage.deleteSavedRecord(record.id)
                }
                val photoPath = removed?.photoPath
                if (photoPath != null && !parkingStorage.isPhotoReferenced(photoPath)) {
                    photoManager.deletePhotoFile(photoPath)
                }
                finish()
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
        if (mapInitialized) binding.detailMapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (mapInitialized) binding.detailMapView.onResume()
    }

    override fun onPause() {
        if (mapInitialized) binding.detailMapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (mapInitialized) binding.detailMapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (mapInitialized) binding.detailMapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (mapInitialized) binding.detailMapView.onLowMemory()
    }

    override fun onDestroy() {
        photoManager.shutdown()
        if (mapInitialized) binding.detailMapView.onDestroy()
        super.onDestroy()
    }
}
