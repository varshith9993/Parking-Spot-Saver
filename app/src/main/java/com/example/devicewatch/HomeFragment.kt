package com.example.devicewatch

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.example.devicewatch.databinding.FragmentHomeBinding
import com.example.devicewatch.location.DistanceCalculator
import com.example.devicewatch.location.NativeGeocoder
import com.example.devicewatch.location.ParkingLocationManager
import com.example.devicewatch.model.ParkingRecord
import com.example.devicewatch.navigation.NavigationManager
import com.example.devicewatch.storage.ParkingPhotoManager
import com.example.devicewatch.storage.ParkingStorage
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.text.DateFormat
import java.util.Date

class HomeFragment : Fragment() {

    private enum class PendingPermissionAction {
        NONE,
        SAVE_GPS_SPOT,
        CENTER_MY_LOCATION
    }

    companion object {
        private const val ELAPSED_REFRESH_MS = 30_000L
        private const val THUMB_REQ_SIZE = 720
        private const val FALLBACK_LAT = 0.0
        private const val FALLBACK_LNG = 0.0
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var parkingStorage: ParkingStorage
    private lateinit var photoManager: ParkingPhotoManager
    private lateinit var locationManager: ParkingLocationManager
    private lateinit var nativeGeocoder: NativeGeocoder
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<LinearLayout>

    private var mapLibreMap: MapLibreMap? = null
    private var mapInitialized = false
    private var isMapStyleLoaded = false
    private var activeRecord: ParkingRecord? = null
    private var latestUserLocation: Location? = null
    private var pendingAction = PendingPermissionAction.NONE
    private var pendingShakeConfirmation = false
    private var isSaveInProgress = false
    private var isLiveUpdatesActive = false
    private var isPinSelectionMode = false
    private var selectedManualLatLng: LatLng? = null
    private var renderedPhotoPath: String? = null

    private val elapsedHandler = Handler(Looper.getMainLooper())
    private val elapsedRefreshRunnable = object : Runnable {
        override fun run() {
            if (_binding != null && isAdded) {
                activeRecord?.let { updateTimestampViews(it.savedTimestamp) }
                elapsedHandler.postDelayed(this, ELAPSED_REFRESH_MS)
            }
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (!isAdded || _binding == null) return@registerForActivityResult
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val action = pendingAction
        pendingAction = PendingPermissionAction.NONE

        if (granted) {
            hideBanner()
            activateLocationPuckIfReady()
            when (action) {
                PendingPermissionAction.SAVE_GPS_SPOT -> verifyGpsAndSaveSpot()
                PendingPermissionAction.CENTER_MY_LOCATION -> centerMapOnUserLocation()
                PendingPermissionAction.NONE -> {
                    if (activeRecord != null) startLiveDistanceUpdates()
                }
            }
        } else {
            pendingShakeConfirmation = false
            setSaveLoading(false)
            showPermissionDeniedBanner()
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!isAdded || _binding == null) return@registerForActivityResult
        if (granted) {
            openCameraCaptureActivity()
        } else {
            Toast.makeText(requireContext(), R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val gpsResolutionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (!isAdded || _binding == null) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            hideBanner()
            fetchAndSaveCurrentSpot()
        } else {
            pendingShakeConfirmation = false
            setSaveLoading(false)
            showGpsDisabledBanner()
        }
    }

    private val cameraCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded || _binding == null) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK || result.data == null) return@registerForActivityResult

        val data = result.data ?: return@registerForActivityResult
        val photoPath = data.getStringExtra(CameraCaptureActivity.EXTRA_PHOTO_PATH) ?: return@registerForActivityResult
        val capturedAt = data.getLongExtra(CameraCaptureActivity.EXTRA_CAPTURED_AT, 0L)
        val photoLat = data.getDoubleExtra(CameraCaptureActivity.EXTRA_PHOTO_LAT, Double.NaN)
        val photoLng = data.getDoubleExtra(CameraCaptureActivity.EXTRA_PHOTO_LNG, Double.NaN)

        val record = activeRecord ?: run {
            if (!parkingStorage.isPhotoReferenced(photoPath)) {
                photoManager.deletePhotoFile(photoPath)
            }
            return@registerForActivityResult
        }

        val oldPhoto = record.photoPath
        parkingStorage.updateRecordPhotoWithMetadata(
            record.id,
            photoPath,
            capturedAt,
            0L,
            photoLat,
            photoLng
        )
        deleteUnreferencedPhoto(oldPhoto, photoPath)
        activeRecord = parkingStorage.getActiveRecord()
        renderedPhotoPath = null
        renderSheetState(focusMap = false)
    }

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (!isAdded || _binding == null || uri == null) return@registerForActivityResult
        val record = activeRecord ?: return@registerForActivityResult
        setPhotoSaving(true)
        photoManager.importGalleryPhoto(
            uri,
            null,
            object : ParkingPhotoManager.PhotoMetadataCallback {
                override fun onPhotoReady(metadata: ParkingPhotoManager.PhotoMetadata) {
                    if (!isAdded || _binding == null) return
                    setPhotoSaving(false)
                    val current = activeRecord
                    if (current == null || current.id != record.id) {
                        if (!parkingStorage.isPhotoReferenced(metadata.internalFilePath)) {
                            photoManager.deletePhotoFile(metadata.internalFilePath)
                        }
                        return
                    }
                    val oldPhoto = current.photoPath
                    parkingStorage.updateRecordPhotoWithMetadata(
                        current.id,
                        metadata.internalFilePath,
                        metadata.capturedAtMillis,
                        metadata.importTimestampOrCaptured(),
                        metadata.latitude,
                        metadata.longitude
                    )
                    deleteUnreferencedPhoto(oldPhoto, metadata.internalFilePath)
                    activeRecord = parkingStorage.getActiveRecord()
                    renderedPhotoPath = null
                    renderSheetState(focusMap = false)
                }

                override fun onPhotoError(message: String) {
                    if (!isAdded || _binding == null) return
                    setPhotoSaving(false)
                    showErrorBanner(message)
                }
            }
        )
    }

    private fun ParkingPhotoManager.PhotoMetadata.importTimestampOrCaptured(): Long {
        return if (capturedAtMillis > 0L) 0L else importedAtMillis
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mapInitialized = initMapLibre()
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        parkingStorage = ParkingStorage(requireContext())
        photoManager = ParkingPhotoManager(requireContext())
        locationManager = ParkingLocationManager(requireContext())
        nativeGeocoder = NativeGeocoder(requireContext())

        bottomSheetBehavior = BottomSheetBehavior.from(binding.homeBottomSheet)
        applyTopInsets()
        setupMapView(savedInstanceState)
        setupClickListeners()

        activeRecord = parkingStorage.getActiveRecord()
        renderSheetState(focusMap = true)
    }

    private fun initMapLibre(): Boolean {
        return try {
            MapLibre.getInstance(requireContext())
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun applyTopInsets() {
        val controlsTopMargin = (binding.mapControlsContainer.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.homeCoordinator) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            binding.topOverlayContainer.setPadding(
                binding.topOverlayContainer.paddingLeft,
                12 + bars.top,
                binding.topOverlayContainer.paddingRight,
                0
            )
            val params = binding.mapControlsContainer.layoutParams as ViewGroup.MarginLayoutParams
            params.topMargin = controlsTopMargin + bars.top
            binding.mapControlsContainer.layoutParams = params
            insets
        }
    }

    private fun setupMapView(savedInstanceState: Bundle?) {
        if (!mapInitialized) {
            binding.cardMapFallback.visibility = View.VISIBLE
            return
        }
        try {
            binding.mapView.onCreate(savedInstanceState)
            binding.mapView.addOnDidFailLoadingMapListener {
                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        binding.cardMapFallback.visibility = View.VISIBLE
                    }
                }
            }
            binding.mapView.getMapAsync { map ->
                if (!isAdded || _binding == null) return@getMapAsync
                mapLibreMap = map
                map.setStyle(Style.Builder().fromUri(ParkingUiHelper.MAP_STYLE_URL)) { style ->
                    if (!isAdded || _binding == null) return@setStyle
                    isMapStyleLoaded = true
                    binding.cardMapFallback.visibility = View.GONE
                    ParkingUiHelper.ensureMarkerLayer(requireContext(), style)
                    activateLocationPuckIfReady()

                    val record = activeRecord
                    if (record != null) {
                        ParkingUiHelper.showRecordOnMap(map, true, record, animateCamera = true)
                    } else if (locationManager.hasLocationPermission()) {
                        centerMapOnUserLocation(silent = true)
                    }
                }
                map.addOnCameraMoveListener {
                    if (isPinSelectionMode && _binding != null) {
                        val center = map.cameraPosition.target
                        if (center != null) {
                            selectedManualLatLng = center
                            val formatted = NativeGeocoder.formatCoordinates(center.latitude, center.longitude)
                            binding.tvTopSelectedCoords.text = formatted
                            binding.tvPinSelectCoords.text = formatted
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            mapInitialized = false
            binding.cardMapFallback.visibility = View.VISIBLE
        }
    }

    private fun activateLocationPuckIfReady() {
        val map = mapLibreMap ?: return
        val style = map.style ?: return
        if (isMapStyleLoaded && style.isFullyLoaded && locationManager.hasLocationPermission()) {
            ParkingUiHelper.activateUserLocationPuck(requireContext(), map, style, latestUserLocation)
        }
    }

    private fun setupClickListeners() {
        binding.btnZoomIn.setOnClickListener { ParkingUiHelper.zoomIn(mapLibreMap) }
        binding.btnZoomOut.setOnClickListener { ParkingUiHelper.zoomOut(mapLibreMap) }
        binding.btnMyLocation.setOnClickListener { onMyLocationClicked() }
        binding.btnManualPinMode.setOnClickListener {
            if (isPinSelectionMode) exitPinSelectionMode() else enterPinSelectionMode()
        }

        binding.btnSaveGpsSpot.setOnClickListener {
            pendingShakeConfirmation = false
            startGpsSaveFlow()
        }
        binding.btnChooseOnMapEmpty.setOnClickListener { enterPinSelectionMode() }

        binding.btnConfirmPinLocation.setOnClickListener { confirmManualPinLocation() }
        binding.btnCancelPinSelection.setOnClickListener { exitPinSelectionMode() }

        binding.btnHomeNavigate.setOnClickListener {
            val record = activeRecord ?: return@setOnClickListener
            val opened = NavigationManager.openWalkingDirections(requireContext(), record.toParkingSpot())
            if (!opened) {
                Toast.makeText(requireContext(), R.string.no_navigation_app, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnHomeChangeLocation.setOnClickListener { showChangeLocationDialog() }
        binding.btnHomeEditNote.setOnClickListener { editActiveNote() }
        binding.btnHomeAddPhoto.setOnClickListener { showPhotoSourceDialog() }
        binding.btnHomeRemovePhoto.setOnClickListener { removeActivePhoto() }
        binding.ivHomePhoto.setOnClickListener {
            val path = activeRecord?.photoPath ?: return@setOnClickListener
            val act = activity ?: return@setOnClickListener
            ParkingUiHelper.showPhotoPreviewDialog(act, photoManager, path) {
                activeRecord?.let { renderPhotoState(it) }
            }
        }
        binding.btnHomeRemoveSpot.setOnClickListener { confirmRemoveActiveSpot() }
    }

    fun triggerSaveFromShake() {
        if (!isAdded || _binding == null || isSaveInProgress || isPinSelectionMode) return
        pendingShakeConfirmation = true
        startGpsSaveFlow()
    }

    fun refreshFromStorage(focusMap: Boolean) {
        if (!isAdded || _binding == null) return
        activeRecord = parkingStorage.getActiveRecord()
        if (!isPinSelectionMode) {
            renderSheetState(focusMap = focusMap)
        }
    }

    private fun onMyLocationClicked() {
        if (!locationManager.hasLocationPermission()) {
            pendingAction = PendingPermissionAction.CENTER_MY_LOCATION
            requestLocationPermissions()
            return
        }
        centerMapOnUserLocation(silent = false)
    }

    private fun centerMapOnUserLocation(silent: Boolean = false) {
        if (!locationManager.hasLocationPermission()) return
        locationManager.getCurrentLocation(object : ParkingLocationManager.LocationResultCallback {
            override fun onLocationRetrieved(location: Location) {
                if (!isAdded || _binding == null) return
                latestUserLocation = location
                activateLocationPuckIfReady()
                ParkingUiHelper.updateUserLocationPuck(mapLibreMap, location)
                mapLibreMap?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(location.latitude, location.longitude),
                        ParkingUiHelper.MAP_DEFAULT_ZOOM
                    )
                )
                if (activeRecord != null) {
                    updateDistanceUI()
                }
            }

            override fun onLocationError(message: String) {
                if (!isAdded || _binding == null || silent) return
                showErrorBanner(message)
            }
        })
    }

    private fun enterPinSelectionMode() {
        isPinSelectionMode = true
        hideBanner()
        binding.ivCenterCrosshair.visibility = View.VISIBLE
        binding.cardPinCoordsPill.visibility = View.VISIBLE
        binding.layoutPinSelectActions.visibility = View.VISIBLE
        binding.layoutHomeEmpty.visibility = View.GONE
        binding.layoutHomeActiveSpot.visibility = View.GONE

        val initialTarget = mapLibreMap?.cameraPosition?.target
            ?: activeRecord?.let { LatLng(it.latitude, it.longitude) }
            ?: latestUserLocation?.let { LatLng(it.latitude, it.longitude) }
            ?: LatLng(FALLBACK_LAT, FALLBACK_LNG)

        selectedManualLatLng = initialTarget
        val formatted = NativeGeocoder.formatCoordinates(initialTarget.latitude, initialTarget.longitude)
        binding.tvTopSelectedCoords.text = formatted
        binding.tvPinSelectCoords.text = formatted

        bottomSheetBehavior.isDraggable = false
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun exitPinSelectionMode() {
        isPinSelectionMode = false
        bottomSheetBehavior.isDraggable = true
        binding.ivCenterCrosshair.visibility = View.GONE
        binding.cardPinCoordsPill.visibility = View.GONE
        binding.layoutPinSelectActions.visibility = View.GONE
        renderSheetState(focusMap = false)
    }

    private fun confirmManualPinLocation() {
        val chosen = mapLibreMap?.cameraPosition?.target ?: selectedManualLatLng
        if (chosen == null || !DistanceCalculator.isValidCoordinate(chosen.latitude, chosen.longitude)) {
            showErrorBanner(getString(R.string.map_load_error))
            return
        }

        // Preserve exact manual coordinates and confirmation timestamp independently of GPS or geocoding.
        val record = ParkingRecord.createNew(
            chosen.latitude,
            chosen.longitude,
            System.currentTimeMillis()
        )
        parkingStorage.saveNewActiveRecord(record)
        activeRecord = record
        renderedPhotoPath = null

        exitPinSelectionMode()
        renderSheetState(focusMap = true)
        startLiveDistanceUpdates()
        resolveAddressAsync(record)
        Toast.makeText(requireContext(), R.string.location_saved_toast, Toast.LENGTH_SHORT).show()
        (activity as? MainActivity)?.notifyParkingDataChanged()
    }

    private fun showChangeLocationDialog() {
        val options = arrayOf(
            getString(R.string.use_current_gps),
            getString(R.string.choose_on_map)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.change_location_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        pendingShakeConfirmation = false
                        startGpsSaveFlow()
                    }
                    1 -> enterPinSelectionMode()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startGpsSaveFlow() {
        if (isSaveInProgress) return
        if (!locationManager.hasLocationPermission()) {
            isSaveInProgress = true
            binding.btnSaveGpsSpot.isEnabled = false
            pendingAction = PendingPermissionAction.SAVE_GPS_SPOT
            requestLocationPermissions()
            return
        }
        verifyGpsAndSaveSpot()
    }

    private fun requestLocationPermissions() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun verifyGpsAndSaveSpot() {
        setSaveLoading(true)
        locationManager.checkLocationSettings(object : ParkingLocationManager.SettingsCheckCallback {
            override fun onSettingsSatisfied() {
                if (!isAdded || _binding == null) return
                hideBanner()
                fetchAndSaveCurrentSpot()
            }

            override fun onSettingsResolvable(exception: ResolvableApiException) {
                if (!isAdded || _binding == null) return
                pendingShakeConfirmation = false
                setSaveLoading(false)
                try {
                    val request = IntentSenderRequest.Builder(exception.resolution).build()
                    gpsResolutionLauncher.launch(request)
                } catch (_: Throwable) {
                    showGpsDisabledBanner()
                }
            }

            override fun onSettingsUnavailable(message: String) {
                if (!isAdded || _binding == null) return
                pendingShakeConfirmation = false
                setSaveLoading(false)
                showGpsDisabledBanner(message)
            }
        })
    }

    private fun fetchAndSaveCurrentSpot() {
        setSaveLoading(true)
        locationManager.getCurrentLocation(object : ParkingLocationManager.LocationResultCallback {
            override fun onLocationRetrieved(location: Location) {
                if (!isAdded || _binding == null) return
                setSaveLoading(false)
                hideBanner()
                latestUserLocation = location
                activateLocationPuckIfReady()
                ParkingUiHelper.updateUserLocationPuck(mapLibreMap, location)

                val record = ParkingRecord.createNew(
                    location.latitude,
                    location.longitude,
                    System.currentTimeMillis()
                )
                parkingStorage.saveNewActiveRecord(record)
                activeRecord = record
                renderedPhotoPath = null

                renderSheetState(focusMap = true)
                startLiveDistanceUpdates()
                resolveAddressAsync(record)
                (activity as? MainActivity)?.notifyParkingDataChanged()

                if (pendingShakeConfirmation) {
                    pendingShakeConfirmation = false
                    Toast.makeText(
                        requireContext(),
                        R.string.shake_saved_confirmation,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onLocationError(message: String) {
                if (!isAdded || _binding == null) return
                pendingShakeConfirmation = false
                setSaveLoading(false)
                showErrorBanner(message)
            }
        })
    }

    private fun resolveAddressAsync(record: ParkingRecord) {
        binding.tvHomeAddress.setText(R.string.resolving_address)
        nativeGeocoder.reverseGeocode(
            record.latitude,
            record.longitude,
            object : NativeGeocoder.GeocodeCallback {
                override fun onSuccess(formattedAddress: String) {
                    if (!isAdded || _binding == null) return
                    parkingStorage.updateRecordAddress(record.id, formattedAddress)
                    val current = parkingStorage.getActiveRecord()
                    if (current != null && current.id == record.id) {
                        activeRecord = current
                        binding.tvHomeAddress.text = formattedAddress
                    }
                    (activity as? MainActivity)?.notifyParkingDataChanged()
                }

                override fun onFailure(coordinateFallback: String, reason: String) {
                    if (!isAdded || _binding == null) return
                    if (activeRecord?.id == record.id) {
                        binding.tvHomeAddress.setText(R.string.address_unavailable_fallback)
                    }
                }
            }
        )
    }

    private fun renderSheetState(focusMap: Boolean) {
        if (isPinSelectionMode) return

        val record = activeRecord
        if (record == null) {
            binding.layoutHomeEmpty.visibility = View.VISIBLE
            binding.layoutHomeActiveSpot.visibility = View.GONE
            binding.layoutPinSelectActions.visibility = View.GONE
            renderedPhotoPath = null
            binding.ivHomePhoto.setImageDrawable(null)
            ParkingUiHelper.clearMapMarker(mapLibreMap)
            stopElapsedTimer()
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            return
        }

        binding.layoutHomeEmpty.visibility = View.GONE
        binding.layoutHomeActiveSpot.visibility = View.VISIBLE
        binding.layoutPinSelectActions.visibility = View.GONE

        val address = record.address
        binding.tvHomeAddress.text = if (!address.isNullOrBlank()) {
            address
        } else {
            getString(R.string.address_unavailable_fallback)
        }
        binding.tvHomeCoordinates.text =
            NativeGeocoder.formatCoordinates(record.latitude, record.longitude)

        val note = record.note
        if (!note.isNullOrBlank()) {
            binding.tvHomeNote.text = note
            binding.btnHomeEditNote.setText(R.string.edit_note)
        } else {
            binding.tvHomeNote.setText(R.string.no_note_placeholder)
            binding.btnHomeEditNote.setText(R.string.add_note)
        }

        renderPhotoState(record)
        updateTimestampViews(record.savedTimestamp)
        startElapsedTimer()
        updateDistanceUI()
        mapLibreMap?.let {
            ParkingUiHelper.showRecordOnMap(it, isMapStyleLoaded, record, animateCamera = focusMap)
        }
    }

    private fun renderPhotoState(record: ParkingRecord) {
        val photoPath = record.photoPath
        if (photoPath.isNullOrBlank()) {
            renderedPhotoPath = null
            binding.cardHomePhotoContainer.visibility = View.GONE
            binding.tvHomePhotoMissing.visibility = View.GONE
            binding.layoutHomePhotoMetadata.visibility = View.GONE
            binding.ivHomePhoto.setImageDrawable(null)
            binding.btnHomeRemovePhoto.visibility = View.GONE
            binding.btnHomeAddPhoto.setText(R.string.add_photo)
            return
        }

        binding.btnHomeRemovePhoto.visibility = View.VISIBLE
        binding.btnHomeAddPhoto.setText(R.string.replace_photo)
        binding.layoutHomePhotoMetadata.visibility = View.VISIBLE
        binding.tvHomePhotoTime.text = ParkingUiHelper.formatPhotoTimeText(requireContext(), record)
        binding.tvHomePhotoGps.text = ParkingUiHelper.formatPhotoLocationText(requireContext(), record)

        if (renderedPhotoPath == photoPath && binding.ivHomePhoto.drawable != null) {
            return
        }

        photoManager.loadSampledBitmap(photoPath, THUMB_REQ_SIZE, THUMB_REQ_SIZE) { bitmap ->
            if (!isAdded || _binding == null) return@loadSampledBitmap
            if (activeRecord?.photoPath != photoPath) return@loadSampledBitmap
            if (bitmap != null) {
                renderedPhotoPath = photoPath
                binding.ivHomePhoto.setImageBitmap(bitmap)
                binding.cardHomePhotoContainer.visibility = View.VISIBLE
                binding.tvHomePhotoMissing.visibility = View.GONE
            } else {
                renderedPhotoPath = null
                binding.ivHomePhoto.setImageDrawable(null)
                binding.cardHomePhotoContainer.visibility = View.GONE
                binding.tvHomePhotoMissing.visibility = View.VISIBLE
            }
        }
    }

    private fun updateTimestampViews(timestampMillis: Long) {
        if (timestampMillis <= 0L) {
            binding.tvHomeDate.text = "--"
            binding.tvHomeTime.text = "--"
            binding.tvHomeElapsed.text = "--"
            return
        }
        val date = Date(timestampMillis)
        binding.tvHomeDate.text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
        binding.tvHomeTime.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
        binding.tvHomeElapsed.text = ParkingUiHelper.formatElapsedDuration(timestampMillis)
    }

    private fun updateDistanceUI() {
        val record = activeRecord
        val distance = DistanceCalculator.calculateDistanceMeters(
            latestUserLocation,
            record?.toParkingSpot()
        )
        binding.tvHomeDistance.text = DistanceCalculator.formatDistance(distance)
        binding.tvHomeGpsStatus.setText(
            if (distance >= 0f) R.string.live_gps_active else R.string.waiting_live_gps
        )
    }

    private fun editActiveNote() {
        val record = activeRecord ?: return
        ParkingUiHelper.showNoteEditDialog(requireContext(), record.note) { updated ->
            parkingStorage.updateRecordNote(record.id, updated)
            activeRecord = parkingStorage.getActiveRecord()
            renderSheetState(focusMap = false)
            (activity as? MainActivity)?.notifyParkingDataChanged()
        }
    }

    private fun showPhotoSourceDialog() {
        if (activeRecord == null) return
        val options = arrayOf(
            getString(R.string.take_photo),
            getString(R.string.choose_from_gallery)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.choose_photo_source)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchCameraCaptureFlow()
                    1 -> launchGalleryPicker()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun launchCameraCaptureFlow() {
        val pm = requireContext().packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(requireContext(), R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            openCameraCaptureActivity()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCameraCaptureActivity() {
        cameraCaptureLauncher.launch(Intent(requireContext(), CameraCaptureActivity::class.java))
    }

    private fun launchGalleryPicker() {
        try {
            pickMediaLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.photo_picker_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeActivePhoto() {
        val record = activeRecord ?: return
        val oldPath = record.photoPath ?: return
        parkingStorage.updateRecordPhotoWithMetadata(record.id, null, 0L, 0L, Double.NaN, Double.NaN)
        deleteUnreferencedPhoto(oldPath, null)
        activeRecord = parkingStorage.getActiveRecord()
        renderSheetState(focusMap = false)
        (activity as? MainActivity)?.notifyParkingDataChanged()
    }

    private fun deleteUnreferencedPhoto(oldPath: String?, replacementPath: String?) {
        if (oldPath.isNullOrBlank() || oldPath == replacementPath) return
        if (!parkingStorage.isPhotoReferenced(oldPath)) {
            photoManager.deletePhotoFile(oldPath)
        }
    }

    private fun confirmRemoveActiveSpot() {
        if (activeRecord == null) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remove_spot_confirm_title)
            .setMessage(R.string.remove_spot_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ ->
                parkingStorage.clearActiveSpot()
                activeRecord = null
                stopLiveDistanceUpdates()
                renderSheetState(focusMap = false)
                (activity as? MainActivity)?.notifyParkingDataChanged()
            }
            .show()
    }

    private fun startLiveDistanceUpdates() {
        if (isLiveUpdatesActive || !locationManager.hasLocationPermission()) return
        isLiveUpdatesActive = true
        locationManager.startLocationUpdates(object : ParkingLocationManager.LocationResultCallback {
            override fun onLocationRetrieved(location: Location) {
                if (!isAdded || _binding == null) return
                latestUserLocation = location
                ParkingUiHelper.updateUserLocationPuck(mapLibreMap, location)
                if (activeRecord != null) {
                    updateDistanceUI()
                }
            }

            override fun onLocationError(message: String) {
                if (!isAdded || _binding == null) return
                if (activeRecord != null) {
                    binding.tvHomeGpsStatus.text = message
                }
            }
        })
    }

    private fun stopLiveDistanceUpdates() {
        if (!isLiveUpdatesActive) return
        isLiveUpdatesActive = false
        locationManager.stopLocationUpdates()
    }

    private fun setSaveLoading(loading: Boolean) {
        isSaveInProgress = loading
        binding.progressHome.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSaveGpsSpot.isEnabled = !loading
    }

    private fun setPhotoSaving(saving: Boolean) {
        binding.progressHome.visibility = if (saving) View.VISIBLE else View.GONE
        binding.btnHomeAddPhoto.isEnabled = !saving
        binding.btnHomeRemovePhoto.isEnabled = !saving
    }

    private fun showPermissionDeniedBanner() {
        val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        binding.tvBannerMessage.setText(R.string.permission_denied_message)
        binding.btnBannerAction.visibility = View.VISIBLE
        if (shouldShowRationale) {
            binding.btnBannerAction.setText(R.string.grant_permission)
            binding.btnBannerAction.setOnClickListener { requestLocationPermissions() }
        } else {
            binding.btnBannerAction.setText(R.string.open_settings)
            binding.btnBannerAction.setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", requireContext().packageName, null)
                    )
                )
            }
        }
        binding.cardStatusBanner.visibility = View.VISIBLE
    }

    private fun showGpsDisabledBanner(customMessage: String? = null) {
        binding.tvBannerMessage.text = customMessage ?: getString(R.string.gps_disabled_message)
        binding.btnBannerAction.visibility = View.VISIBLE
        binding.btnBannerAction.setText(R.string.enable_gps)
        binding.btnBannerAction.setOnClickListener {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        binding.cardStatusBanner.visibility = View.VISIBLE
    }

    private fun showErrorBanner(message: String) {
        binding.tvBannerMessage.text = message
        binding.btnBannerAction.visibility = View.GONE
        binding.cardStatusBanner.visibility = View.VISIBLE
    }

    private fun hideBanner() {
        binding.cardStatusBanner.visibility = View.GONE
    }

    private fun startElapsedTimer() {
        stopElapsedTimer()
        elapsedHandler.postDelayed(elapsedRefreshRunnable, ELAPSED_REFRESH_MS)
    }

    private fun stopElapsedTimer() {
        elapsedHandler.removeCallbacks(elapsedRefreshRunnable)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            stopLiveDistanceUpdates()
            stopElapsedTimer()
        } else {
            refreshFromStorage(focusMap = false)
            if (locationManager.hasLocationPermission()) {
                activateLocationPuckIfReady()
                startLiveDistanceUpdates()
            }
            if (activeRecord != null) startElapsedTimer()
        }
    }

    override fun onStart() {
        super.onStart()
        if (mapInitialized) binding.mapView.onStart()
        if (!isHidden) {
            if (locationManager.hasLocationPermission()) {
                activateLocationPuckIfReady()
                startLiveDistanceUpdates()
            }
            if (activeRecord != null) startElapsedTimer()
        }
    }

    override fun onResume() {
        super.onResume()
        if (mapInitialized) binding.mapView.onResume()
        activeRecord = parkingStorage.getActiveRecord()
        if (!isPinSelectionMode) {
            renderSheetState(focusMap = false)
        }
        if (locationManager.hasLocationPermission()) {
            activateLocationPuckIfReady()
        }
    }

    override fun onPause() {
        if (mapInitialized) binding.mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        stopLiveDistanceUpdates()
        stopElapsedTimer()
        if (mapInitialized) binding.mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (mapInitialized && _binding != null) binding.mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (mapInitialized && _binding != null) binding.mapView.onLowMemory()
    }

    override fun onDestroyView() {
        stopLiveDistanceUpdates()
        locationManager.cancelCurrentLocationRequest()
        stopElapsedTimer()
        nativeGeocoder.shutdown()
        photoManager.shutdown()
        if (mapInitialized) binding.mapView.onDestroy()
        mapLibreMap = null
        _binding = null
        super.onDestroyView()
    }
}
