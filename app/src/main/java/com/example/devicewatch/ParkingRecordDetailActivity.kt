package com.example.devicewatch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.devicewatch.databinding.ActivityRecordDetailBinding
import com.example.devicewatch.location.NativeGeocoder
import com.example.devicewatch.location.ParkingLocationManager
import com.example.devicewatch.model.ParkingPhotoItem
import com.example.devicewatch.model.ParkingRecord
import com.example.devicewatch.model.RelativeTimeFormatter
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
        private const val RELATIVE_TIME_REFRESH_MS = 60_000L
    }

    private lateinit var binding: ActivityRecordDetailBinding
    private lateinit var parkingStorage: ParkingStorage
    private lateinit var photoManager: ParkingPhotoManager
    private lateinit var locationManager: ParkingLocationManager
    private lateinit var distanceController: OnDemandDistanceController
    private lateinit var photoAdapter: ParkingPhotoAdapter

    private var currentRecord: ParkingRecord? = null
    private var sourceMode: String = MODE_SAVED
    private var mapLibreMap: MapLibreMap? = null
    private var mapInitialized = false
    private var isPhotoBusy = false

    private val relativeTimeHandler = Handler(Looper.getMainLooper())
    private val relativeTimeTickRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                currentRecord?.let {
                    binding.tvDetailRelativeTime.text = RelativeTimeFormatter.format(it.savedTimestamp)
                }
                relativeTimeHandler.postDelayed(this, RELATIVE_TIME_REFRESH_MS)
            }
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (isFinishing || isDestroyed) return@registerForActivityResult
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val allowRetry = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
        distanceController.onPermissionResult(granted, allowRetry)
    }

    private val gpsResolutionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (isFinishing || isDestroyed) return@registerForActivityResult
        distanceController.onGpsResolutionResult(result.resultCode == Activity.RESULT_OK)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (isFinishing || isDestroyed) return@registerForActivityResult
        if (granted) {
            openCameraCaptureActivity()
        } else {
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val cameraCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (isFinishing || isDestroyed) return@registerForActivityResult
        if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult

        val data = result.data ?: return@registerForActivityResult
        val photoPath = data.getStringExtra(CameraCaptureActivity.EXTRA_PHOTO_PATH) ?: return@registerForActivityResult
        val capturedAt = data.getLongExtra(CameraCaptureActivity.EXTRA_CAPTURED_AT, 0L)
        val photoLat = data.getDoubleExtra(CameraCaptureActivity.EXTRA_PHOTO_LAT, Double.NaN)
        val photoLng = data.getDoubleExtra(CameraCaptureActivity.EXTRA_PHOTO_LNG, Double.NaN)

        val item = ParkingPhotoItem(photoPath, capturedAt, photoLat, photoLng, 0L)
        attachPhotoToCurrentRecord(item, showAddedToast = true)
    }

    private val pickSingleMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (isFinishing || isDestroyed || uri == null) return@registerForActivityResult
        importSelectedGalleryUris(listOf(uri))
    }

    private val pickMultipleMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ParkingPhotoItem.MAX_PHOTOS_PER_RECORD)
    ) { uris ->
        if (isFinishing || isDestroyed || uris.isEmpty()) return@registerForActivityResult
        importSelectedGalleryUris(uris)
    }

    private val fallbackGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (isFinishing || isDestroyed || result.resultCode != RESULT_OK) {
            return@registerForActivityResult
        }
        val uris = ParkingUiHelper.extractPickedImageUris(result.data)
        if (uris.isNotEmpty()) {
            importSelectedGalleryUris(uris)
        }
    }

    private val photoViewerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (isFinishing || isDestroyed) return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            reloadRecordAndBind()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        mapInitialized = initMapLibre()
        binding = ActivityRecordDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        parkingStorage = ParkingStorage(this)
        photoManager = ParkingPhotoManager(this)
        locationManager = ParkingLocationManager(this)
        distanceController = OnDemandDistanceController(
            context = this,
            locationManager = locationManager,
            isActive = { !isFinishing && !isDestroyed },
            onRequestLocationPermission = {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            },
            onLaunchGpsResolution = { request -> gpsResolutionLauncher.launch(request) },
            onShowPermissionDialog = { allowRetry ->
                if (!isFinishing && !isDestroyed) {
                    ParkingUiHelper.showDistancePermissionDialog(
                        this,
                        allowRetry,
                        onRetryPermission = { distanceController.retryAfterPermissionGrantRequest() },
                        onOpenSettings = { ParkingUiHelper.openAppPermissionSettings(this) }
                    )
                }
            },
            onShowGpsDisabledDialog = {
                if (!isFinishing && !isDestroyed) {
                    ParkingUiHelper.showDistanceGpsDisabledDialog(this) {
                        ParkingUiHelper.openDeviceLocationSettings(this)
                    }
                }
            },
            onStatesChanged = {
                if (!isFinishing && !isDestroyed) {
                    currentRecord?.let { bindDistanceUi(it) }
                }
            }
        )

        val recordId = intent.getStringExtra(EXTRA_RECORD_ID)
        sourceMode = intent.getStringExtra(EXTRA_SOURCE_MODE) ?: MODE_SAVED
        val record = if (sourceMode == MODE_SAVED) {
            parkingStorage.getSavedRecordById(recordId) ?: parkingStorage.getRecordById(recordId)
        } else {
            parkingStorage.getRecordById(recordId)
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
        binding.btnDetailTopShare.setOnClickListener { shareCurrentRecord() }
        binding.btnDetailBookmark.setOnClickListener { toggleBookmark() }
        binding.btnDetailZoomIn.setOnClickListener { ParkingUiHelper.zoomIn(mapLibreMap) }
        binding.btnDetailZoomOut.setOnClickListener { ParkingUiHelper.zoomOut(mapLibreMap) }
        binding.btnDetailNavigate.setOnClickListener {
            val active = currentRecord ?: return@setOnClickListener
            val opened = NavigationManager.openDirections(
                this,
                active.toParkingSpot(),
                parkingStorage.getNavigationTravelMode()
            )
            if (!opened) {
                Toast.makeText(this, R.string.no_navigation_app, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnDetailShare.setOnClickListener { shareCurrentRecord() }
        binding.btnDetailDelete.setOnClickListener { confirmDeleteOrUnsave() }

        if (sourceMode == MODE_SAVED) {
            binding.btnDetailEditPlaceLabel.visibility = View.VISIBLE
            binding.btnDetailEditPlaceLabel.setOnClickListener { editPlaceLabel() }
            binding.btnDetailEditNote.visibility = View.VISIBLE
            binding.btnDetailEditNote.setOnClickListener { editNote() }
            binding.btnDetailAddPhoto.visibility = View.VISIBLE
            binding.btnDetailAddPhoto.setOnClickListener { showPhotoSourceDialog() }
            binding.btnDetailSetActive.visibility = View.VISIBLE
            binding.btnDetailSetActive.setOnClickListener { setRecordAsActive() }
            binding.btnDetailDelete.setText(R.string.unsave)
        } else {
            binding.btnDetailEditPlaceLabel.visibility = View.GONE
            binding.btnDetailEditNote.visibility = View.GONE
            binding.btnDetailAddPhoto.visibility = View.GONE
            binding.btnDetailSetActive.visibility = View.GONE
            binding.btnDetailDelete.setText(R.string.delete_record)
        }

        setupPhotoAdapter()
        bindRecord(record)
        setupMapView(savedInstanceState, record)
        if (locationManager.hasLocationPermission()) {
            distanceController.requestDistance(record)
        }
    }

    private fun setupPhotoAdapter() {
        val removeHandler: ((Int, ParkingPhotoItem) -> Unit)? = if (sourceMode == MODE_SAVED) {
            { index, _ -> confirmRemovePhotoAt(index) }
        } else {
            null
        }
        photoAdapter = ParkingPhotoAdapter(
            photoManager = photoManager,
            onPhotoClick = { index, _ -> openPhotoViewer(index) },
            onRemoveClick = removeHandler
        )
        binding.rvDetailPhotos.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvDetailPhotos.adapter = photoAdapter
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
        ParkingUiHelper.applyBookmarkIcon(binding.btnDetailBookmark, record.isSaved)

        binding.tvDetailAddress.text = if (!record.address.isNullOrBlank()) {
            record.address
        } else {
            getString(R.string.address_unavailable_fallback)
        }
        binding.tvDetailCoordinates.text =
            NativeGeocoder.formatCoordinates(record.latitude, record.longitude)

        val placeLabel = record.placeLabel
        if (sourceMode == MODE_HISTORY && placeLabel.isNullOrBlank()) {
            binding.layoutDetailPlaceLabel.visibility = View.GONE
            binding.dividerDetailPlaceLabel.visibility = View.GONE
        } else {
            binding.layoutDetailPlaceLabel.visibility = View.VISIBLE
            binding.dividerDetailPlaceLabel.visibility = View.VISIBLE
            if (!placeLabel.isNullOrBlank()) {
                binding.tvDetailPlaceLabel.text = placeLabel
                binding.btnDetailEditPlaceLabel.setText(R.string.edit_place_label)
            } else {
                binding.tvDetailPlaceLabel.setText(R.string.no_note_placeholder)
                binding.btnDetailEditPlaceLabel.setText(R.string.add_place_label)
            }
        }

        binding.tvDetailRelativeTime.text = RelativeTimeFormatter.format(record.savedTimestamp)
        if (record.savedTimestamp > 0L) {
            val date = Date(record.savedTimestamp)
            binding.tvDetailDate.text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
            binding.tvDetailTime.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
        } else {
            binding.tvDetailDate.text = "--"
            binding.tvDetailTime.text = "--"
        }

        bindDistanceUi(record)

        val note = record.note
        if (!note.isNullOrBlank()) {
            binding.tvDetailNote.text = note
            binding.btnDetailEditNote.setText(R.string.edit_note)
        } else {
            binding.tvDetailNote.setText(R.string.no_note_placeholder)
            binding.btnDetailEditNote.setText(R.string.add_note)
        }

        val photos = record.photos
        if (sourceMode == MODE_HISTORY && photos.isEmpty()) {
            binding.cardDetailPhoto.visibility = View.GONE
            return
        }

        binding.cardDetailPhoto.visibility = View.VISIBLE
        binding.tvDetailPhotoHeader.text = getString(
            R.string.parking_photos_count_format,
            photos.size,
            ParkingPhotoItem.MAX_PHOTOS_PER_RECORD
        )

        if (sourceMode == MODE_SAVED) {
            val canAdd = photos.size < ParkingPhotoItem.MAX_PHOTOS_PER_RECORD && !isPhotoBusy
            binding.btnDetailAddPhoto.visibility = View.VISIBLE
            binding.btnDetailAddPhoto.isEnabled = canAdd
            binding.btnDetailAddPhoto.alpha = if (canAdd) 1.0f else 0.5f
        } else {
            binding.btnDetailAddPhoto.visibility = View.GONE
        }

        if (photos.isEmpty()) {
            binding.rvDetailPhotos.visibility = View.GONE
            binding.layoutDetailPhotoMetadata.visibility = View.GONE
            binding.tvDetailPhotoHint.setText(R.string.no_photos_attached)
            binding.tvDetailPhotoHint.setTextColor(ContextCompat.getColor(this, R.color.ps_text_secondary))
            binding.tvDetailPhotoHint.visibility = View.VISIBLE
            photoAdapter.submitList(emptyList())
            return
        }

        binding.rvDetailPhotos.visibility = View.VISIBLE
        photoAdapter.submitList(photos)

        if (sourceMode == MODE_SAVED && photos.size >= ParkingPhotoItem.MAX_PHOTOS_PER_RECORD) {
            binding.tvDetailPhotoHint.setText(R.string.photo_limit_reached)
            binding.tvDetailPhotoHint.setTextColor(ContextCompat.getColor(this, R.color.ps_primary))
            binding.tvDetailPhotoHint.visibility = View.VISIBLE
        } else {
            binding.tvDetailPhotoHint.visibility = View.GONE
        }

        val firstPhoto = photos.first()
        binding.layoutDetailPhotoMetadata.visibility = View.VISIBLE
        binding.tvDetailPhotoTime.text = ParkingUiHelper.formatPhotoTimeText(this, firstPhoto)
        binding.tvDetailPhotoGps.text = ParkingUiHelper.formatPhotoLocationText(this, firstPhoto)
    }

    private fun reloadRecordAndBind() {
        val record = currentRecord ?: return
        val refreshed = if (sourceMode == MODE_SAVED) {
            parkingStorage.getSavedRecordById(record.id) ?: parkingStorage.getRecordById(record.id)
        } else {
            parkingStorage.getRecordById(record.id)
        }
        if (refreshed != null) {
            currentRecord = refreshed
            bindRecord(refreshed)
        }
    }

    private fun openPhotoViewer(index: Int) {
        val record = currentRecord ?: return
        if (record.photos.isEmpty()) return
        val intent = Intent(this, PhotoViewerActivity::class.java).apply {
            putExtra(PhotoViewerActivity.EXTRA_RECORD_ID, record.id)
            putExtra(PhotoViewerActivity.EXTRA_INITIAL_INDEX, index)
            putExtra(PhotoViewerActivity.EXTRA_EDITABLE, sourceMode == MODE_SAVED)
        }
        photoViewerLauncher.launch(intent)
    }

    private fun showPhotoSourceDialog() {
        val record = currentRecord ?: return
        if (sourceMode != MODE_SAVED) return
        val remaining = (ParkingPhotoItem.MAX_PHOTOS_PER_RECORD - record.photoCount).coerceAtLeast(0)
        if (remaining <= 0) {
            Toast.makeText(this, R.string.photo_limit_reached, Toast.LENGTH_SHORT).show()
            return
        }
        ParkingUiHelper.showPhotoSourceBottomSheet(
            context = this,
            titleRes = R.string.choose_photo_source,
            onTakePhoto = { launchCameraCaptureFlow() },
            onChooseGallery = { launchGalleryPicker(remaining) }
        )
    }

    private fun launchCameraCaptureFlow() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            openCameraCaptureActivity()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCameraCaptureActivity() {
        cameraCaptureLauncher.launch(Intent(this, CameraCaptureActivity::class.java))
    }

    private fun launchGalleryPicker(remainingSlots: Int) {
        if (remainingSlots <= 0) {
            Toast.makeText(this, R.string.photo_limit_reached, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            if (remainingSlots == 1) {
                pickSingleMediaLauncher.launch(request)
            } else {
                pickMultipleMediaLauncher.launch(request)
            }
        } catch (_: Exception) {
            try {
                fallbackGalleryLauncher.launch(
                    ParkingUiHelper.buildFallbackGalleryIntent(allowMultiple = remainingSlots > 1)
                )
            } catch (_: Exception) {
                Toast.makeText(this, R.string.photo_picker_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importSelectedGalleryUris(uris: List<Uri>) {
        val record = currentRecord ?: return
        val remaining = (ParkingPhotoItem.MAX_PHOTOS_PER_RECORD - record.photoCount).coerceAtLeast(0)
        if (remaining <= 0) {
            Toast.makeText(this, R.string.photo_limit_reached, Toast.LENGTH_SHORT).show()
            return
        }
        val clipped = if (uris.size > remaining) uris.take(remaining) else uris
        val wasTrimmed = uris.size > remaining
        setPhotoBusy(true)
        importNextGalleryUri(clipped, 0, 0, wasTrimmed)
    }

    private fun importNextGalleryUri(
        uris: List<Uri>,
        index: Int,
        addedCount: Int,
        wasTrimmed: Boolean
    ) {
        if (isFinishing || isDestroyed) return
        if (index >= uris.size) {
            setPhotoBusy(false)
            if (addedCount > 0) {
                val count = currentRecord?.photoCount ?: 0
                if (wasTrimmed || count >= ParkingPhotoItem.MAX_PHOTOS_PER_RECORD) {
                    val msg = if (wasTrimmed) {
                        getString(R.string.photo_limit_partial_added, addedCount)
                    } else {
                        getString(R.string.photo_limit_reached)
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        photoManager.importGalleryPhoto(
            uris[index],
            null,
            object : ParkingPhotoManager.PhotoMetadataCallback {
                override fun onPhotoReady(metadata: ParkingPhotoManager.PhotoMetadata) {
                    if (isFinishing || isDestroyed) return
                    val importedAt = if (metadata.capturedAtMillis > 0L) 0L else metadata.importedAtMillis
                    val item = ParkingPhotoItem(
                        metadata.internalFilePath,
                        metadata.capturedAtMillis,
                        metadata.latitude,
                        metadata.longitude,
                        importedAt
                    )
                    val appended = attachPhotoToCurrentRecord(item, showAddedToast = false)
                    val nextAdded = if (appended) addedCount + 1 else addedCount
                    importNextGalleryUri(uris, index + 1, nextAdded, wasTrimmed)
                }

                override fun onPhotoError(message: String) {
                    if (isFinishing || isDestroyed) return
                    Toast.makeText(this@ParkingRecordDetailActivity, message, Toast.LENGTH_SHORT).show()
                    importNextGalleryUri(uris, index + 1, addedCount, wasTrimmed)
                }
            }
        )
    }

    private fun attachPhotoToCurrentRecord(item: ParkingPhotoItem, showAddedToast: Boolean): Boolean {
        val record = currentRecord ?: run {
            deleteIfUnreferenced(item.filePath)
            return false
        }
        val added = parkingStorage.addPhotoToRecord(record.id, item)
        if (!added) {
            deleteIfUnreferenced(item.filePath)
            Toast.makeText(this, R.string.photo_limit_reached, Toast.LENGTH_SHORT).show()
            return false
        }
        reloadRecordAndBind()
        if (showAddedToast) {
            val count = currentRecord?.photoCount ?: 0
            val msg = if (count >= ParkingPhotoItem.MAX_PHOTOS_PER_RECORD) {
                getString(R.string.photo_limit_reached)
            } else {
                getString(R.string.photo_added_confirmation, count)
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun confirmRemovePhotoAt(index: Int) {
        val record = currentRecord ?: return
        if (sourceMode != MODE_SAVED) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_single_photo_title)
            .setMessage(R.string.remove_single_photo_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ ->
                val removed = parkingStorage.removePhotoFromRecord(record.id, index)
                if (removed != null) {
                    deleteIfUnreferenced(removed.filePath)
                }
                reloadRecordAndBind()
                Toast.makeText(this, R.string.photo_removed_confirmation, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun setPhotoBusy(busy: Boolean) {
        isPhotoBusy = busy
        currentRecord?.let { bindRecord(it) }
    }

    private fun deleteIfUnreferenced(path: String?) {
        if (!path.isNullOrBlank() && !parkingStorage.isPhotoReferenced(path)) {
            photoManager.deletePhotoFile(path)
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

    private fun toggleBookmark() {
        val record = currentRecord ?: return
        val nextSaved = !record.isSaved
        parkingStorage.setRecordSaved(record.id, nextSaved)
        val refreshed = parkingStorage.getRecordById(record.id)
        if (refreshed != null) {
            currentRecord = refreshed
            ParkingUiHelper.applyBookmarkIcon(binding.btnDetailBookmark, refreshed.isSaved)
        }
        Toast.makeText(
            this,
            if (nextSaved) R.string.bookmark_saved else R.string.bookmark_unsaved,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun editPlaceLabel() {
        val record = currentRecord ?: return
        ParkingUiHelper.showPlaceLabelDialog(
            context = this,
            titleRes = R.string.place_label_title,
            hintRes = R.string.place_label_hint,
            currentLabel = record.placeLabel,
            negativeRes = R.string.cancel,
            allowRemove = true
        ) { updated ->
            parkingStorage.updateRecordPlaceLabel(record.id, updated)
            val refreshed = parkingStorage.getRecordById(record.id)
            if (refreshed != null) {
                currentRecord = refreshed
                bindRecord(refreshed)
            }
        }
    }

    private fun editNote() {
        val record = currentRecord ?: return
        ParkingUiHelper.showNoteEditDialog(this, record.note) { updatedNote ->
            parkingStorage.updateRecordNote(record.id, updatedNote)
            val refreshed = parkingStorage.getRecordById(record.id)
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

    private fun shareCurrentRecord() {
        val active = currentRecord ?: return
        val shared = NavigationManager.shareParkingRecord(
            this,
            active,
            getString(R.string.share_chooser_title)
        )
        if (!shared) {
            Toast.makeText(this, R.string.share_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteOrUnsave() {
        val record = currentRecord ?: return
        if (sourceMode == MODE_SAVED) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_saved_title)
                .setMessage(R.string.delete_saved_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.unsave) { _, _ ->
                    parkingStorage.unsaveRecord(record.id)
                    Toast.makeText(this, R.string.bookmark_unsaved, Toast.LENGTH_SHORT).show()
                    finish()
                }
                .show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_history_item_title)
            .setMessage(R.string.delete_history_item_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val removed = parkingStorage.deleteHistoryRecord(record.id)
                removed?.photos?.forEach { photo ->
                    deleteIfUnreferenced(photo.filePath)
                }
                finish()
            }
            .show()
    }

    private fun bindDistanceUi(record: ParkingRecord) {
        ParkingUiHelper.bindOnDemandDistanceRow(
            distanceController.stateFor(record.id),
            binding.pbDetailDistance,
            binding.tvDetailDistance,
            binding.btnDetailDistance
        )
        binding.btnDetailDistance.setOnClickListener {
            distanceController.requestDistance(record)
        }
    }

    private fun startRelativeTimeTicker() {
        stopRelativeTimeTicker()
        relativeTimeHandler.postDelayed(relativeTimeTickRunnable, RELATIVE_TIME_REFRESH_MS)
    }

    private fun stopRelativeTimeTicker() {
        relativeTimeHandler.removeCallbacks(relativeTimeTickRunnable)
    }

    override fun onStart() {
        super.onStart()
        if (mapInitialized) binding.detailMapView.onStart()
        startRelativeTimeTicker()
    }

    override fun onResume() {
        super.onResume()
        if (mapInitialized) binding.detailMapView.onResume()
        currentRecord?.let {
            binding.tvDetailRelativeTime.text = RelativeTimeFormatter.format(it.savedTimestamp)
            if (distanceController.stateFor(it.id) is ParkingUiHelper.RecordDistanceState.Idle &&
                locationManager.hasLocationPermission()
            ) {
                distanceController.requestDistance(it)
            } else {
                bindDistanceUi(it)
            }
        }
        startRelativeTimeTicker()
    }

    override fun onPause() {
        if (mapInitialized) binding.detailMapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        stopRelativeTimeTicker()
        distanceController.cancelAndClear(resetStates = true)
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
        stopRelativeTimeTicker()
        distanceController.cancelAndClear(resetStates = false)
        photoManager.shutdown()
        if (mapInitialized) binding.detailMapView.onDestroy()
        super.onDestroy()
    }
}
