package com.example.devicewatch

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.devicewatch.databinding.FragmentHomeBinding
import com.example.devicewatch.location.DistanceCalculator
import com.example.devicewatch.location.NativeGeocoder
import com.example.devicewatch.location.ParkingLocationManager
import com.example.devicewatch.location.ParkingQrParser
import com.example.devicewatch.location.PlaceSearchClient
import com.example.devicewatch.location.PlaceSearchResult
import com.example.devicewatch.model.ParkingPhotoItem
import com.example.devicewatch.model.ParkingRecord
import com.example.devicewatch.navigation.RouteFetcher
import com.example.devicewatch.storage.ParkingPhotoManager
import com.example.devicewatch.storage.ParkingStorage
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.text.DateFormat
import java.util.Date

class HomeFragment : Fragment() {

    private enum class PendingPermissionAction {
        NONE,
        SAVE_GPS_SPOT,
        CENTER_MY_LOCATION
    }

    private enum class PendingCameraAction {
        PHOTO,
        QR
    }

    companion object {
        private const val ELAPSED_REFRESH_MS = 30_000L
        private const val PLACE_SEARCH_DEBOUNCE_MS = 350L
        private const val INSTANT_JUMP_DISTANCE_METERS = 50_000.0
        private const val FALLBACK_LAT = 0.0
        private const val FALLBACK_LNG = 0.0
        private const val KEY_PENDING_PHOTOS_JSON = "key_pending_photos_json"
        private const val KEY_DRAFT_LAT = "key_draft_lat"
        private const val KEY_DRAFT_LNG = "key_draft_lng"
        private const val KEY_DRAFT_LABEL = "key_draft_label"
        private const val KEY_DRAFT_ADDRESS = "key_draft_address"
        private const val KEY_DRAFT_NOTE = "key_draft_note"
        private const val KEY_FORM_TIMESTAMP = "key_form_timestamp"
        private const val KEY_FORM_SAVED = "key_form_saved"
        private const val KEY_FORM_DIRTY = "key_form_dirty"
        private const val KEY_PHOTO_TARGET_PENDING = "key_photo_target_pending"
        private const val KEY_PHOTO_TARGET_RECORD_ID = "key_photo_target_record_id"
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var parkingStorage: ParkingStorage
    private lateinit var photoManager: ParkingPhotoManager
    private lateinit var locationManager: ParkingLocationManager
    private lateinit var nativeGeocoder: NativeGeocoder
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<LinearLayout>
    private lateinit var activePhotoAdapter: ParkingPhotoAdapter

    private var mapView: MapView? = null
    private var mapLibreMap: MapLibreMap? = null
    private var mapInitialized = false
    private var isMapStyleLoaded = false
    private var activeRecord: ParkingRecord? = null
    private var latestUserLocation: Location? = null
    private var routeFetcher: RouteFetcher? = null
    private var placeSearchClient: PlaceSearchClient? = null
    private lateinit var placeSearchAdapter: PlaceSearchAdapter
    private var isPlaceSearchOpen = false
    private var pendingPlaceSearchRunnable: Runnable? = null

    private val placeSearchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closePlaceSearchOverlay(hideKeyboard = true)
        }
    }

    private fun getOrCreateRouteFetcher(): RouteFetcher {
        return routeFetcher ?: RouteFetcher().also { routeFetcher = it }
    }

    private fun getOrCreatePlaceSearchClient(): PlaceSearchClient {
        return placeSearchClient ?: PlaceSearchClient(requireContext()).also { placeSearchClient = it }
    }
    private var pendingAction = PendingPermissionAction.NONE
    private var pendingCameraAction = PendingCameraAction.PHOTO
    private var pendingShakeConfirmation = false
    private var pendingSaveToSavedList = false
    private var isSaveInProgress = false
    private var isPhotoImportInProgress = false
    private var isLiveUpdatesActive = false
    private var isPinSelectionMode = false
    private var selectedManualLatLng: LatLng? = null

    private var draftLatLng: LatLng? = null
    private var draftPlaceLabel: String? = null
    private var draftAddress: String? = null
    private var draftNote: String? = null
    private var formTimestamp: Long = 0L
    private var isFormSavedToSaved = false
    private var hasUnsavedFormChanges = false
    private var isAutoDefaultGpsLocation = false
    private var skipInitialResumeRender = false

    private val pendingPhotos: MutableList<ParkingPhotoItem> = mutableListOf()
    private var photoTargetIsPending: Boolean = false
    private var photoTargetRecordId: String? = null

    private val elapsedHandler = Handler(Looper.getMainLooper())
    private val elapsedRefreshRunnable = object : Runnable {
        override fun run() {
            if (_binding != null && isAdded) {
                val ts = if (formTimestamp > 0L) formTimestamp else activeRecord?.savedTimestamp ?: 0L
                if (ts > 0L) updateTimestampViews(ts)
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
                    startLiveDistanceUpdates()
                    if (draftLatLng == null && activeRecord == null) {
                        loadInitialDefaultLocation()
                    }
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
        val action = pendingCameraAction
        pendingCameraAction = PendingCameraAction.PHOTO
        if (granted) {
            when (action) {
                PendingCameraAction.PHOTO -> openCameraCaptureActivity()
                PendingCameraAction.QR -> openQrScannerActivity()
            }
        } else if (action == PendingCameraAction.QR) {
            Toast.makeText(
                requireContext(),
                R.string.qr_camera_permission_denied_gallery_ok,
                Toast.LENGTH_LONG
            ).show()
            openQrScannerActivity()
        } else {
            Toast.makeText(requireContext(), R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded || _binding == null || result.resultCode != Activity.RESULT_OK) {
            return@registerForActivityResult
        }
        val rawText = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_RAW_TEXT)
            ?: return@registerForActivityResult
        applyScannedQrResult(rawText)
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

        val item = ParkingPhotoItem(photoPath, capturedAt, photoLat, photoLng, 0L)
        attachSinglePhotoItem(item)
    }

    private val pickSingleMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (!isAdded || _binding == null || uri == null) return@registerForActivityResult
        importSelectedGalleryUris(listOf(uri))
    }

    private val pickMultipleMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ParkingPhotoItem.MAX_PHOTOS_PER_RECORD)
    ) { uris ->
        if (!isAdded || _binding == null || uris.isEmpty()) return@registerForActivityResult
        importSelectedGalleryUris(uris)
    }

    private val fallbackGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded || _binding == null || result.resultCode != Activity.RESULT_OK) {
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
        if (!isAdded || _binding == null) return@registerForActivityResult
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null &&
            data.hasExtra(PhotoViewerActivity.EXTRA_UPDATED_PHOTOS_JSON)
        ) {
            val updated = ParkingPhotoItem.fromJsonString(
                data.getStringExtra(PhotoViewerActivity.EXTRA_UPDATED_PHOTOS_JSON)
            )
            pendingPhotos.clear()
            pendingPhotos.addAll(updated)
            hasUnsavedFormChanges = true
            if (formTimestamp <= 0L) formTimestamp = System.currentTimeMillis()
            renderSheetState(focusMap = false)
        } else {
            activeRecord = parkingStorage.getActiveRecord()
            renderSheetState(focusMap = false)
            if (result.resultCode == Activity.RESULT_OK) {
                (activity as? MainActivity)?.notifyParkingDataChanged()
            }
        }
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

        savedInstanceState?.let { state ->
            state.getString(KEY_PENDING_PHOTOS_JSON)?.let { json ->
                pendingPhotos.clear()
                pendingPhotos.addAll(ParkingPhotoItem.fromJsonString(json))
            }
            if (state.containsKey(KEY_DRAFT_LAT) && state.containsKey(KEY_DRAFT_LNG)) {
                val lat = state.getDouble(KEY_DRAFT_LAT, Double.NaN)
                val lng = state.getDouble(KEY_DRAFT_LNG, Double.NaN)
                if (DistanceCalculator.isValidCoordinate(lat, lng)) {
                    draftLatLng = LatLng(lat, lng)
                    draftPlaceLabel = state.getString(KEY_DRAFT_LABEL)
                    draftAddress = state.getString(KEY_DRAFT_ADDRESS)
                    draftNote = state.getString(KEY_DRAFT_NOTE)
                }
            }
            formTimestamp = state.getLong(KEY_FORM_TIMESTAMP, 0L)
            isFormSavedToSaved = state.getBoolean(KEY_FORM_SAVED, false)
            hasUnsavedFormChanges = state.getBoolean(KEY_FORM_DIRTY, false)
            photoTargetIsPending = state.getBoolean(KEY_PHOTO_TARGET_PENDING, true)
            photoTargetRecordId = state.getString(KEY_PHOTO_TARGET_RECORD_ID)
        }

        bottomSheetBehavior = BottomSheetBehavior.from(binding.homeBottomSheet)
        applyTopInsets()
        setupPhotoAdapters()
        setupMapView(savedInstanceState)
        setupClickListeners()
        setupPlaceSearch()

        activeRecord = parkingStorage.getActiveRecord()
        if (draftLatLng == null && activeRecord != null) {
            populateFormFromRecord(activeRecord)
        } else if (draftLatLng == null) {
            formTimestamp = System.currentTimeMillis()
            populateDefaultGpsOrFetch()
        }
        renderSheetState(focusMap = true)
        skipInitialResumeRender = true
        if (isAutoDefaultGpsLocation && draftLatLng != null && draftAddress.isNullOrBlank()) {
            val target = draftLatLng!!
            resolveFormAddressAsync(target.latitude, target.longitude)
        }
    }

    private fun populateFormFromRecord(record: ParkingRecord?) {
        isAutoDefaultGpsLocation = false
        if (record == null) {
            draftLatLng = null
            draftPlaceLabel = null
            draftAddress = null
            draftNote = null
            formTimestamp = 0L
            isFormSavedToSaved = false
            hasUnsavedFormChanges = false
            pendingPhotos.clear()
            return
        }
        draftLatLng = LatLng(record.latitude, record.longitude)
        draftPlaceLabel = record.placeLabel
        draftAddress = record.address
        draftNote = record.note
        formTimestamp = record.savedTimestamp
        isFormSavedToSaved = record.isSaved
        hasUnsavedFormChanges = false
        pendingPhotos.clear()
        pendingPhotos.addAll(record.photos)
    }

    private fun setupPhotoAdapters() {
        activePhotoAdapter = ParkingPhotoAdapter(
            photoManager = photoManager,
            onPhotoClick = { index, _ -> openPendingPhotoViewer(index) },
            onRemoveClick = { index, _ -> removePendingPhotoAt(index) }
        )
        binding.rvHomePhotos.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvHomePhotos.adapter = activePhotoAdapter
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
            val view = MapView(requireContext())
            binding.mapContainer.removeAllViews()
            binding.mapContainer.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            mapView = view
            view.onCreate(savedInstanceState)
            view.addOnDidFailLoadingMapListener {
                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        binding.cardMapFallback.visibility = View.VISIBLE
                    }
                }
            }
            view.getMapAsync { map ->
                if (!isAdded || _binding == null) return@getMapAsync
                mapLibreMap = map
                map.setStyle(Style.Builder().fromUri(ParkingUiHelper.MAP_STYLE_URL)) { style ->
                    if (!isAdded || _binding == null) return@setStyle
                    isMapStyleLoaded = true
                    binding.cardMapFallback.visibility = View.GONE
                    ParkingUiHelper.ensureMarkerLayer(requireContext(), style)
                    activateLocationPuckIfReady()

                    val selectedDraft = draftLatLng
                    val record = activeRecord
                    if (selectedDraft != null) {
                        ParkingUiHelper.showCoordinatesOnMap(
                            map,
                            true,
                            selectedDraft.latitude,
                            selectedDraft.longitude,
                            animateCamera = true
                        )
                    } else if (record != null) {
                        ParkingUiHelper.showRecordOnMap(map, true, record, animateCamera = true)
                    } else if (latestUserLocation != null) {
                        val loc = latestUserLocation!!
                        ParkingUiHelper.showCoordinatesOnMap(
                            map,
                            true,
                            loc.latitude,
                            loc.longitude,
                            animateCamera = true
                        )
                    } else if (locationManager.hasLocationPermission()) {
                        centerMapOnUserLocation(silent = true)
                    }
                    updateHomeRouteIfReady()
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
            mapView = null
            binding.mapContainer.removeAllViews()
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
        binding.btnMyLocation.setOnClickListener {
            closePlaceSearchOverlay(hideKeyboard = true)
            onMyLocationClicked()
        }
        binding.btnManualPinMode.setOnClickListener {
            closePlaceSearchOverlay(hideKeyboard = true)
            if (isPinSelectionMode) exitPinSelectionMode() else enterPinSelectionMode()
        }
        binding.btnScanQr.setOnClickListener {
            closePlaceSearchOverlay(hideKeyboard = true)
            launchQrScannerFlow()
        }

        binding.btnHomeSaveNewSpot.setOnClickListener {
            saveHomeForm()
        }
        binding.btnBannerManualPin.setOnClickListener {
            hideBanner()
            enterPinSelectionMode()
        }

        binding.btnConfirmPinLocation.setOnClickListener { confirmManualPinLocation() }
        binding.btnCancelPinSelection.setOnClickListener { exitPinSelectionMode() }

        binding.btnHomeChangeLocation.setOnClickListener { showChangeLocationDialog() }
        binding.btnHomeSave.setOnClickListener { toggleHomeSaveMark() }
        binding.btnHomeEditPlaceLabel.setOnClickListener { editActivePlaceLabel() }
        binding.btnHomeEditNote.setOnClickListener { editActiveNote() }
        binding.btnHomeAddPhoto.setOnClickListener {
            showPhotoSourceDialog(forPending = true)
        }
        binding.btnHomeClearAll.setOnClickListener { confirmClearHomeForm() }
    }

    private fun setupPlaceSearch() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            placeSearchBackCallback
        )
        placeSearchAdapter = PlaceSearchAdapter { place ->
            onPlaceSearchResultSelected(place)
        }
        binding.rvPlaceSearchResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPlaceSearchResults.adapter = placeSearchAdapter

        binding.btnPlaceSearch.setOnClickListener {
            if (isPlaceSearchOpen) {
                closePlaceSearchOverlay(hideKeyboard = true)
            } else {
                openPlaceSearchOverlay()
            }
        }
        binding.btnPlaceSearchBack.setOnClickListener {
            closePlaceSearchOverlay(hideKeyboard = true)
        }
        binding.btnClearPlaceSearch.setOnClickListener {
            pendingPlaceSearchRunnable?.let { elapsedHandler.removeCallbacks(it) }
            pendingPlaceSearchRunnable = null
            placeSearchClient?.cancelPending()
            binding.etPlaceSearch.setText("")
            hidePlaceSearchDropdown()
            binding.etPlaceSearch.requestFocus()
        }

        binding.etPlaceSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString().orEmpty()
                binding.btnClearPlaceSearch.visibility =
                    if (raw.isNotEmpty()) View.VISIBLE else View.GONE
                pendingPlaceSearchRunnable?.let { elapsedHandler.removeCallbacks(it) }
                pendingPlaceSearchRunnable = null

                if (raw.trim().length < 2) {
                    placeSearchClient?.cancelPending()
                    hidePlaceSearchDropdown()
                    return
                }
                if (PlaceSearchClient.isDirectCoordinateOrLink(raw)) {
                    executePlaceSearch(raw)
                    return
                }
                val instantLocal = PlaceSearchClient.searchLocalRecords(
                    rawQuery = raw,
                    records = collectLocalSearchRecords(),
                    savedSubtitlePrefix = getString(R.string.search_places_saved_prefix),
                    historySubtitlePrefix = getString(R.string.search_places_history_prefix)
                )
                if (instantLocal.isNotEmpty()) {
                    placeSearchAdapter.submitResults(instantLocal)
                    binding.viewPlaceSearchDivider.visibility = View.VISIBLE
                    binding.rvPlaceSearchResults.visibility = View.VISIBLE
                    binding.tvPlaceSearchStatus.visibility = View.GONE
                    binding.pbPlaceSearch.visibility = View.VISIBLE
                }
                val task = Runnable { executePlaceSearch(raw) }
                pendingPlaceSearchRunnable = task
                elapsedHandler.postDelayed(task, PLACE_SEARCH_DEBOUNCE_MS)
            }
        })

        binding.etPlaceSearch.setOnEditorActionListener { _, actionId, event ->
            val isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null &&
                    event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_DOWN)
            if (isSearchAction) {
                pendingPlaceSearchRunnable?.let { elapsedHandler.removeCallbacks(it) }
                pendingPlaceSearchRunnable = null
                executePlaceSearch(binding.etPlaceSearch.text?.toString().orEmpty())
                val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(binding.etPlaceSearch.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    private fun openPlaceSearchOverlay() {
        if (!isAdded || _binding == null) return
        isPlaceSearchOpen = true
        placeSearchBackCallback.isEnabled = true
        binding.cardPlaceSearchOverlay.visibility = View.VISIBLE
        binding.cardPinCoordsPill.visibility = View.GONE
        binding.cardPlaceSearchControl.setCardBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.ps_primary_container)
        )
        binding.etPlaceSearch.requestFocus()
        val existingLen = binding.etPlaceSearch.text?.length ?: 0
        if (existingLen > 0) {
            binding.etPlaceSearch.selectAll()
        }
        binding.etPlaceSearch.post {
            if (_binding == null || !isAdded || !isPlaceSearchOpen) return@post
            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etPlaceSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closePlaceSearchOverlay(hideKeyboard: Boolean) {
        pendingPlaceSearchRunnable?.let { elapsedHandler.removeCallbacks(it) }
        pendingPlaceSearchRunnable = null
        placeSearchClient?.cancelPending()
        placeSearchBackCallback.isEnabled = false
        if (!isPlaceSearchOpen && _binding?.cardPlaceSearchOverlay?.visibility != View.VISIBLE) {
            return
        }
        isPlaceSearchOpen = false
        _binding?.let { b ->
            b.cardPlaceSearchOverlay.visibility = View.GONE
            b.pbPlaceSearch.visibility = View.GONE
            b.viewPlaceSearchDivider.visibility = View.GONE
            b.rvPlaceSearchResults.visibility = View.GONE
            b.tvPlaceSearchStatus.visibility = View.GONE
            context?.let { ctx ->
                b.cardPlaceSearchControl.setCardBackgroundColor(
                    ContextCompat.getColor(ctx, R.color.ps_card_bg)
                )
            }
            if (isPinSelectionMode) {
                b.cardPinCoordsPill.visibility = View.VISIBLE
            }
            if (hideKeyboard) {
                b.etPlaceSearch.clearFocus()
                val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(b.etPlaceSearch.windowToken, 0)
            }
        }
    }

    private fun hidePlaceSearchDropdown() {
        _binding?.let { b ->
            b.pbPlaceSearch.visibility = View.GONE
            b.viewPlaceSearchDivider.visibility = View.GONE
            b.rvPlaceSearchResults.visibility = View.GONE
            b.tvPlaceSearchStatus.visibility = View.GONE
        }
        if (::placeSearchAdapter.isInitialized) {
            placeSearchAdapter.submitResults(emptyList())
        }
    }

    private fun collectLocalSearchRecords(): List<ParkingRecord> {
        if (!::parkingStorage.isInitialized) return emptyList()
        val combined = ArrayList<ParkingRecord>()
        val seenIds = HashSet<String>()
        activeRecord?.let {
            if (seenIds.add(it.id)) combined.add(it)
        }
        parkingStorage.getSavedRecords().forEach {
            if (seenIds.add(it.id)) combined.add(it)
        }
        parkingStorage.getHistoryRecords().forEach {
            if (seenIds.add(it.id)) combined.add(it)
        }
        return combined
    }

    private fun executePlaceSearch(rawQuery: String) {
        if (!isAdded || _binding == null || !isPlaceSearchOpen) return
        val query = rawQuery.trim()
        if (query.length < 2) {
            hidePlaceSearchDropdown()
            return
        }
        val bias = latestUserLocation?.let { LatLng(it.latitude, it.longitude) }
            ?: mapLibreMap?.cameraPosition?.target
        binding.pbPlaceSearch.visibility = View.VISIBLE
        getOrCreatePlaceSearchClient().search(
            rawQuery = query,
            coordSubtitle = getString(R.string.search_places_coordinates_subtitle),
            swapSubtitle = getString(R.string.search_places_swap_subtitle),
            savedSubtitlePrefix = getString(R.string.search_places_saved_prefix),
            historySubtitlePrefix = getString(R.string.search_places_history_prefix),
            localRecords = collectLocalSearchRecords(),
            biasLat = bias?.latitude,
            biasLng = bias?.longitude
        ) { outcome ->
            if (!isAdded || _binding == null || !isPlaceSearchOpen) return@search
            binding.pbPlaceSearch.visibility = if (outcome.isPendingEnrichment) View.VISIBLE else View.GONE
            if (outcome.results.isNotEmpty()) {
                placeSearchAdapter.submitResults(outcome.results)
                binding.viewPlaceSearchDivider.visibility = View.VISIBLE
                binding.rvPlaceSearchResults.visibility = View.VISIBLE
                binding.tvPlaceSearchStatus.visibility = View.GONE
            } else {
                placeSearchAdapter.submitResults(emptyList())
                binding.viewPlaceSearchDivider.visibility = View.VISIBLE
                binding.rvPlaceSearchResults.visibility = View.GONE
                binding.tvPlaceSearchStatus.setText(
                    if (outcome.hadNetworkError) R.string.search_places_error else R.string.search_places_empty
                )
                binding.tvPlaceSearchStatus.visibility = View.VISIBLE
            }
        }
    }

    private fun onPlaceSearchResultSelected(place: PlaceSearchResult) {
        closePlaceSearchOverlay(hideKeyboard = true)
        val target = LatLng(place.latitude, place.longitude)
        val currentCenter = mapLibreMap?.cameraPosition?.target
        val jumpMeters = if (currentCenter != null) {
            RouteFetcher.haversineMeters(
                currentCenter.latitude,
                currentCenter.longitude,
                target.latitude,
                target.longitude
            )
        } else {
            Double.MAX_VALUE
        }
        val update = CameraUpdateFactory.newLatLngZoom(target, place.suggestedZoom)
        if (jumpMeters > INSTANT_JUMP_DISTANCE_METERS) {
            mapLibreMap?.moveCamera(update)
        } else {
            mapLibreMap?.animateCamera(update)
        }
        if (!isPinSelectionMode) {
            enterPinSelectionMode(
                initialTargetOverride = target,
                initialLabelOverride = place.savedLabel
            )
        } else {
            selectedManualLatLng = target
            val formatted = NativeGeocoder.formatCoordinates(target.latitude, target.longitude)
            binding.tvTopSelectedCoords.text = formatted
            binding.tvPinSelectCoords.text = formatted
            if (place.savedLabel != null) {
                binding.etPinPlaceLabel.setText(place.savedLabel)
            }
        }
    }

    fun triggerSaveFromShake(): Boolean {
        if (!isAdded || _binding == null || isSaveInProgress || isPinSelectionMode) return false
        performShakeHapticFeedback(confirmed = false)
        Toast.makeText(requireContext(), R.string.shake_detecting_gps, Toast.LENGTH_SHORT).show()
        pendingShakeConfirmation = true
        pendingSaveToSavedList = false
        startGpsSaveFlow()
        return true
    }

    fun triggerGpsSaveFromExternal() {
        if (!isAdded || _binding == null || isSaveInProgress || isPinSelectionMode) return
        pendingShakeConfirmation = false
        pendingSaveToSavedList = false
        startGpsSaveFlow()
    }

    fun triggerQrScanFromExternal() {
        if (!isAdded || _binding == null) return
        launchQrScannerFlow()
    }

    fun refreshFromStorage(focusMap: Boolean) {
        if (!isAdded || _binding == null || !::parkingStorage.isInitialized) return
        val previousActiveId = activeRecord?.id
        val nextActive = parkingStorage.getActiveRecord()
        activeRecord = nextActive
        if (nextActive != null) {
            if (!hasUnsavedFormChanges && (nextActive.id != previousActiveId || !isAutoDefaultGpsLocation)) {
                populateFormFromRecord(nextActive)
            } else if (nextActive.id == previousActiveId) {
                isFormSavedToSaved = nextActive.isSaved
            }
        } else if (previousActiveId != null) {
            populateFormFromRecord(null)
            formTimestamp = System.currentTimeMillis()
            populateDefaultGpsOrFetch()
        }
        if (!isPinSelectionMode) {
            renderSheetState(focusMap = focusMap)
            if (isAutoDefaultGpsLocation && draftLatLng != null && draftAddress.isNullOrBlank()) {
                val target = draftLatLng!!
                resolveFormAddressAsync(target.latitude, target.longitude)
            }
        }
    }

    private fun populateDefaultGpsOrFetch() {
        if (!locationManager.hasLocationPermission()) return
        val syncLocation = latestUserLocation ?: locationManager.getLastKnownLocationSync()
        if (syncLocation != null) {
            latestUserLocation = syncLocation
            draftLatLng = LatLng(syncLocation.latitude, syncLocation.longitude)
            draftAddress = null
            isAutoDefaultGpsLocation = true
        } else {
            loadInitialDefaultLocation()
        }
    }

    private fun loadInitialDefaultLocation() {
        locationManager.getFastLocation(object : ParkingLocationManager.LocationResultCallback {
            override fun onLocationRetrieved(location: Location) {
                if (!isAdded || _binding == null) return
                latestUserLocation = location
                ParkingUiHelper.updateUserLocationPuck(mapLibreMap, location)
                if (draftLatLng == null && activeRecord == null) {
                    draftLatLng = LatLng(location.latitude, location.longitude)
                    draftAddress = null
                    isAutoDefaultGpsLocation = true
                    if (formTimestamp <= 0L) formTimestamp = System.currentTimeMillis()
                    renderSheetState(focusMap = true)
                    resolveFormAddressAsync(location.latitude, location.longitude)
                } else {
                    updateHomeRouteIfReady()
                }
            }

            override fun onLocationError(message: String) {
                if (!isAdded || _binding == null) return
                if (draftLatLng == null && activeRecord == null) {
                    centerMapOnUserLocation(silent = true)
                }
            }
        })
    }

    private fun applyAutoDefaultLocationIfNeeded(location: Location) {
        val shouldApplyInitial = draftLatLng == null && activeRecord == null
        val shouldRefineCachedDefault = isAutoDefaultGpsLocation && activeRecord == null && !hasUnsavedFormChanges
        if (shouldApplyInitial || shouldRefineCachedDefault) {
            draftLatLng = LatLng(location.latitude, location.longitude)
            draftAddress = null
            isAutoDefaultGpsLocation = false
            if (formTimestamp <= 0L) formTimestamp = System.currentTimeMillis()
            renderSheetState(focusMap = true)
            resolveFormAddressAsync(location.latitude, location.longitude)
        } else {
            updateDistanceUI()
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
                applyAutoDefaultLocationIfNeeded(location)
                updateHomeRouteIfReady()
            }

            override fun onLocationError(message: String) {
                if (!isAdded || _binding == null || silent) return
                showErrorBanner(message)
            }
        })
    }

    private fun enterPinSelectionMode(
        initialTargetOverride: LatLng? = null,
        initialLabelOverride: String? = null
    ) {
        isPinSelectionMode = true
        routeFetcher?.reset()
        ParkingUiHelper.clearRouteFromMap(mapLibreMap)
        hideBanner()
        binding.ivCenterCrosshair.visibility = View.VISIBLE
        binding.cardPinCoordsPill.visibility = View.VISIBLE
        binding.layoutPinSelectActions.visibility = View.VISIBLE
        binding.layoutHomeActiveSpot.visibility = View.GONE
        binding.cardPinDropControl.setCardBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.ps_primary_container)
        )

        val initialTarget = initialTargetOverride
            ?: draftLatLng
            ?: mapLibreMap?.cameraPosition?.target
            ?: activeRecord?.let { LatLng(it.latitude, it.longitude) }
            ?: latestUserLocation?.let { LatLng(it.latitude, it.longitude) }
            ?: LatLng(FALLBACK_LAT, FALLBACK_LNG)

        selectedManualLatLng = initialTarget
        val formatted = NativeGeocoder.formatCoordinates(initialTarget.latitude, initialTarget.longitude)
        binding.tvTopSelectedCoords.text = formatted
        binding.tvPinSelectCoords.text = formatted
        if (initialTargetOverride != null) {
            binding.etPinPlaceLabel.setText(initialLabelOverride.orEmpty())
        } else {
            binding.etPinPlaceLabel.setText(draftPlaceLabel.orEmpty())
        }

        bottomSheetBehavior.isDraggable = false
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun exitPinSelectionMode() {
        isPinSelectionMode = false
        binding.etPinPlaceLabel.clearFocus()
        bottomSheetBehavior.isDraggable = true
        binding.ivCenterCrosshair.visibility = View.GONE
        binding.cardPinCoordsPill.visibility = View.GONE
        binding.layoutPinSelectActions.visibility = View.GONE
        binding.cardPinDropControl.setCardBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.ps_card_bg)
        )
        renderSheetState(focusMap = false)
    }

    private fun confirmManualPinLocation() {
        val chosen = mapLibreMap?.cameraPosition?.target ?: selectedManualLatLng
        if (chosen == null || !DistanceCalculator.isValidCoordinate(chosen.latitude, chosen.longitude)) {
            showErrorBanner(getString(R.string.map_load_error))
            return
        }

        // Populate the Home form with the selected location without saving to History/Saved yet.
        draftLatLng = LatLng(chosen.latitude, chosen.longitude)
        val enteredLabel = binding.etPinPlaceLabel.text?.toString()?.trim()?.ifEmpty { null }
        if (enteredLabel != null) {
            draftPlaceLabel = enteredLabel
        }
        draftAddress = null
        isAutoDefaultGpsLocation = false
        formTimestamp = System.currentTimeMillis()
        hasUnsavedFormChanges = true

        exitPinSelectionMode()
        renderSheetState(focusMap = true)
        resolveFormAddressAsync(chosen.latitude, chosen.longitude)
    }

    private fun toggleHomeSaveMark() {
        val existing = activeRecord
        if (existing != null && !hasUnsavedFormChanges) {
            val nextSaved = !isFormSavedToSaved
            parkingStorage.setRecordSaved(existing.id, nextSaved)
            activeRecord = parkingStorage.getActiveRecord()
            isFormSavedToSaved = nextSaved
            renderSheetState(focusMap = false)
            Toast.makeText(
                requireContext(),
                if (nextSaved) R.string.bookmark_saved else R.string.bookmark_unsaved,
                Toast.LENGTH_SHORT
            ).show()
            (activity as? MainActivity)?.notifyParkingDataChanged()
            return
        }

        isFormSavedToSaved = !isFormSavedToSaved
        ParkingUiHelper.applyBookmarkIcon(binding.btnHomeSave, isFormSavedToSaved)
        binding.tvHomeSavedBadge.visibility = if (isFormSavedToSaved) View.VISIBLE else View.GONE
    }

    private fun saveHomeForm() {
        if (isSaveInProgress) return
        val saveToSavedList = isFormSavedToSaved
        val selected = draftLatLng
        if (selected == null) {
            pendingShakeConfirmation = false
            pendingSaveToSavedList = saveToSavedList
            startGpsSaveFlow()
            return
        }
        if (!DistanceCalculator.isValidCoordinate(selected.latitude, selected.longitude)) {
            showErrorBanner(getString(R.string.map_load_error))
            return
        }

        isSaveInProgress = true
        setSaveLoading(true)
        try {
            val record = ParkingRecord(
                java.util.UUID.randomUUID().toString(),
                selected.latitude,
                selected.longitude,
                draftPlaceLabel,
                draftAddress,
                System.currentTimeMillis(),
                draftNote,
                ArrayList(pendingPhotos),
                saveToSavedList
            )
            parkingStorage.saveNewActiveRecord(record)
            activeRecord = record
            formTimestamp = record.savedTimestamp
            isFormSavedToSaved = saveToSavedList
            hasUnsavedFormChanges = false

            renderSheetState(focusMap = true)
            startLiveDistanceUpdates()
            if (record.address.isNullOrBlank()) {
                resolveAddressAsync(record)
            }
            Toast.makeText(
                requireContext(),
                if (saveToSavedList) R.string.bookmark_saved else R.string.location_saved_toast,
                Toast.LENGTH_SHORT
            ).show()
            (activity as? MainActivity)?.notifyParkingDataChanged()
        } finally {
            setSaveLoading(false)
        }
    }

    private fun showChangeLocationDialog() {
        ParkingUiHelper.showChangeLocationBottomSheet(
            context = requireContext(),
            onUseCurrentGps = { fetchGpsLocationIntoForm(showLoading = true) },
            onChooseOnMap = { enterPinSelectionMode() },
            onScanQr = { launchQrScannerFlow() }
        )
    }

    private fun fetchGpsLocationIntoForm(showLoading: Boolean) {
        if (!locationManager.hasLocationPermission()) {
            if (showLoading) requestLocationPermissions()
            return
        }
        if (showLoading) setSaveLoading(true)
        locationManager.getCurrentLocation(object : ParkingLocationManager.LocationResultCallback {
            override fun onLocationRetrieved(location: Location) {
                if (!isAdded || _binding == null) return
                if (showLoading) setSaveLoading(false)
                hideBanner()
                latestUserLocation = location
                activateLocationPuckIfReady()
                ParkingUiHelper.updateUserLocationPuck(mapLibreMap, location)

                draftLatLng = LatLng(location.latitude, location.longitude)
                draftAddress = null
                isAutoDefaultGpsLocation = false
                formTimestamp = System.currentTimeMillis()
                hasUnsavedFormChanges = true

                renderSheetState(focusMap = true)
                resolveFormAddressAsync(location.latitude, location.longitude)
            }

            override fun onLocationError(message: String) {
                if (!isAdded || _binding == null) return
                if (showLoading) {
                    setSaveLoading(false)
                    showErrorBanner(message)
                }
            }
        })
    }

    private fun startGpsSaveFlow() {
        if (isSaveInProgress) return
        if (!locationManager.hasLocationPermission()) {
            setSaveLoading(true)
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

                val saveToSaved = pendingSaveToSavedList
                pendingSaveToSavedList = false
                val initialPhotos = if (pendingPhotos.isNotEmpty()) {
                    ArrayList(pendingPhotos)
                } else {
                    emptyList()
                }

                val record = ParkingRecord(
                    java.util.UUID.randomUUID().toString(),
                    location.latitude,
                    location.longitude,
                    draftPlaceLabel,
                    null,
                    System.currentTimeMillis(),
                    draftNote,
                    initialPhotos,
                    saveToSaved
                )
                parkingStorage.saveNewActiveRecord(record)
                activeRecord = record
                populateFormFromRecord(record)

                renderSheetState(focusMap = true)
                startLiveDistanceUpdates()
                resolveAddressAsync(record)
                (activity as? MainActivity)?.notifyParkingDataChanged()

                if (pendingShakeConfirmation) {
                    pendingShakeConfirmation = false
                    performShakeHapticFeedback(confirmed = true)
                    Toast.makeText(
                        requireContext(),
                        R.string.shake_saved_confirmation,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        if (saveToSaved) R.string.bookmark_saved else R.string.location_saved_toast,
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

    private fun resolveFormAddressAsync(latitude: Double, longitude: Double) {
        binding.tvHomeAddress.setText(R.string.resolving_address)
        nativeGeocoder.reverseGeocode(
            latitude,
            longitude,
            object : NativeGeocoder.GeocodeCallback {
                override fun onSuccess(formattedAddress: String) {
                    if (!isAdded || _binding == null) return
                    val currentDraft = draftLatLng ?: return
                    if (currentDraft.latitude == latitude && currentDraft.longitude == longitude) {
                        draftAddress = formattedAddress
                        binding.tvHomeAddress.text = formattedAddress
                    }
                }

                override fun onFailure(coordinateFallback: String, reason: String) {
                    if (!isAdded || _binding == null) return
                    val currentDraft = draftLatLng ?: return
                    if (currentDraft.latitude == latitude && currentDraft.longitude == longitude) {
                        binding.tvHomeAddress.setText(R.string.address_unavailable_fallback)
                    }
                }
            }
        )
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
                        draftAddress = formattedAddress
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

        val formLocation = draftLatLng ?: activeRecord?.let { LatLng(it.latitude, it.longitude) }

        binding.layoutHomeActiveSpot.visibility = View.VISIBLE
        binding.layoutPinSelectActions.visibility = View.GONE

        binding.tvHomeSavedBadge.visibility = if (isFormSavedToSaved) View.VISIBLE else View.GONE
        ParkingUiHelper.applyBookmarkIcon(binding.btnHomeSave, isFormSavedToSaved)

        val placeLabel = draftPlaceLabel
        if (!placeLabel.isNullOrBlank()) {
            binding.tvHomePlaceLabel.text = placeLabel
            binding.tvHomePlaceLabel.visibility = View.VISIBLE
            binding.tvHomePlaceLabelDetail.text = placeLabel
            binding.btnHomeEditPlaceLabel.setText(R.string.edit_place_label)
        } else {
            binding.tvHomePlaceLabel.visibility = View.GONE
            binding.tvHomePlaceLabelDetail.setText(R.string.no_note_placeholder)
            binding.btnHomeEditPlaceLabel.setText(R.string.add_place_label)
        }

        if (formLocation != null) {
            val address = draftAddress
            binding.tvHomeAddress.text = if (!address.isNullOrBlank()) {
                address
            } else {
                getString(R.string.resolving_address)
            }
            binding.tvHomeCoordinates.text =
                NativeGeocoder.formatCoordinates(formLocation.latitude, formLocation.longitude)
        } else {
            binding.tvHomeAddress.setText(
                if (::locationManager.isInitialized && locationManager.hasLocationPermission()) {
                    R.string.loading_gps
                } else {
                    R.string.choose_on_map
                }
            )
            binding.tvHomeCoordinates.text = "--"
        }

        val note = draftNote
        if (!note.isNullOrBlank()) {
            binding.tvHomeNote.text = note
            binding.btnHomeEditNote.setText(R.string.edit_note)
        } else {
            binding.tvHomeNote.setText(R.string.no_note_placeholder)
            binding.btnHomeEditNote.setText(R.string.add_note)
        }

        renderFormPhotoState()
        val displayTimestamp = if (formTimestamp > 0L) formTimestamp else System.currentTimeMillis()
        updateTimestampViews(displayTimestamp)
        startElapsedTimer()
        updateDistanceUI()
        if (formLocation != null) {
            mapLibreMap?.let {
                ParkingUiHelper.showCoordinatesOnMap(
                    it,
                    isMapStyleLoaded,
                    formLocation.latitude,
                    formLocation.longitude,
                    animateCamera = focusMap
                )
            }
        } else {
            ParkingUiHelper.clearMapMarker(mapLibreMap)
        }
        updateHomeRouteIfReady()
    }

    private fun renderFormPhotoState() {
        val photos = pendingPhotos
        val count = photos.size
        binding.tvHomePhotoHeader.text = getString(
            R.string.parking_photos_count_format,
            count,
            ParkingPhotoItem.MAX_PHOTOS_PER_RECORD
        )
        val canAdd = count < ParkingPhotoItem.MAX_PHOTOS_PER_RECORD && !isPhotoImportInProgress
        binding.btnHomeAddPhoto.isEnabled = canAdd
        binding.btnHomeAddPhoto.alpha = if (canAdd) 1.0f else 0.5f

        if (photos.isEmpty()) {
            binding.rvHomePhotos.visibility = View.GONE
            binding.layoutHomePhotoMetadata.visibility = View.GONE
            binding.tvHomePhotoEmptyHint.setText(R.string.no_photos_attached)
            binding.tvHomePhotoEmptyHint.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.ps_text_secondary)
            )
            binding.tvHomePhotoEmptyHint.visibility = View.VISIBLE
            activePhotoAdapter.submitList(emptyList())
            return
        }

        binding.rvHomePhotos.visibility = View.VISIBLE
        activePhotoAdapter.submitList(photos)

        if (count >= ParkingPhotoItem.MAX_PHOTOS_PER_RECORD) {
            binding.tvHomePhotoEmptyHint.setText(R.string.photo_limit_reached)
            binding.tvHomePhotoEmptyHint.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.ps_primary)
            )
            binding.tvHomePhotoEmptyHint.visibility = View.VISIBLE
        } else {
            binding.tvHomePhotoEmptyHint.visibility = View.GONE
        }

        val firstPhoto = photos.first()
        binding.layoutHomePhotoMetadata.visibility = View.VISIBLE
        binding.tvHomePhotoTime.text = ParkingUiHelper.formatPhotoTimeText(requireContext(), firstPhoto)
        binding.tvHomePhotoGps.text = ParkingUiHelper.formatPhotoLocationText(requireContext(), firstPhoto)
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
        val target = draftLatLng ?: activeRecord?.let { LatLng(it.latitude, it.longitude) }
        val distance = if (target != null) {
            DistanceCalculator.calculateDistanceMeters(
                latestUserLocation,
                com.example.devicewatch.model.ParkingSpot(target.latitude, target.longitude, 0L)
            )
        } else {
            -1f
        }
        binding.tvHomeDistance.text = DistanceCalculator.formatDistance(distance)
        binding.tvHomeGpsStatus.setText(
            if (distance >= 0f) R.string.live_gps_active else R.string.waiting_live_gps
        )
    }

    private fun editActivePlaceLabel() {
        ParkingUiHelper.showPlaceLabelDialog(
            context = requireContext(),
            titleRes = R.string.place_label_title,
            hintRes = R.string.place_label_hint,
            currentLabel = draftPlaceLabel,
            negativeRes = R.string.cancel,
            allowRemove = true
        ) { updated ->
            draftPlaceLabel = updated?.takeIf { it.isNotBlank() }
            hasUnsavedFormChanges = true
            if (formTimestamp <= 0L) formTimestamp = System.currentTimeMillis()
            renderSheetState(focusMap = false)
        }
    }

    private fun editActiveNote() {
        ParkingUiHelper.showNoteEditDialog(requireContext(), draftNote) { updated ->
            draftNote = updated?.takeIf { it.isNotBlank() }
            hasUnsavedFormChanges = true
            if (formTimestamp <= 0L) formTimestamp = System.currentTimeMillis()
            renderSheetState(focusMap = false)
        }
    }

    private fun showPhotoSourceDialog(forPending: Boolean) {
        if (!forPending && activeRecord == null) return
        val remaining = remainingPhotoSlots(forPending)
        if (remaining <= 0) {
            Toast.makeText(requireContext(), R.string.photo_limit_reached, Toast.LENGTH_SHORT).show()
            return
        }
        photoTargetIsPending = forPending
        photoTargetRecordId = if (forPending) null else activeRecord?.id

        ParkingUiHelper.showPhotoSourceBottomSheet(
            context = requireContext(),
            titleRes = R.string.choose_photo_source,
            onTakePhoto = { launchCameraCaptureFlow() },
            onChooseGallery = { launchGalleryPicker(remaining) }
        )
    }

    private fun remainingPhotoSlots(forPending: Boolean): Int {
        val currentCount = if (forPending) {
            pendingPhotos.size
        } else {
            activeRecord?.photoCount ?: 0
        }
        return (ParkingPhotoItem.MAX_PHOTOS_PER_RECORD - currentCount).coerceAtLeast(0)
    }

    private fun launchCameraCaptureFlow() {
        launchWithCameraPermission(PendingCameraAction.PHOTO) {
            openCameraCaptureActivity()
        }
    }

    private fun launchQrScannerFlow() {
        launchWithCameraPermission(PendingCameraAction.QR) {
            openQrScannerActivity()
        }
    }

    private fun launchWithCameraPermission(
        action: PendingCameraAction,
        onGranted: () -> Unit
    ) {
        val pm = requireContext().packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            if (action == PendingCameraAction.QR) {
                openQrScannerActivity()
            } else {
                Toast.makeText(requireContext(), R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
            }
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onGranted()
        } else {
            pendingCameraAction = action
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCameraCaptureActivity() {
        cameraCaptureLauncher.launch(Intent(requireContext(), CameraCaptureActivity::class.java))
    }

    private fun openQrScannerActivity() {
        qrScannerLauncher.launch(Intent(requireContext(), QrScannerActivity::class.java))
    }

    private fun applyScannedQrResult(rawText: String) {
        val parsed = ParkingQrParser.parse(rawText)
        if (parsed == null) {
            Toast.makeText(requireContext(), R.string.qr_scan_empty_or_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        if (isPinSelectionMode) {
            exitPinSelectionMode()
        }

        val lat = parsed.latitude
        val lng = parsed.longitude
        if (parsed.hasCoordinates && lat != null && lng != null &&
            DistanceCalculator.isValidCoordinate(lat, lng)
        ) {
            draftLatLng = LatLng(lat, lng)
            draftAddress = parsed.address
            draftPlaceLabel = parsed.placeLabel
            isAutoDefaultGpsLocation = false
            formTimestamp = System.currentTimeMillis()
            hasUnsavedFormChanges = true
            renderSheetState(focusMap = true)
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            if (draftAddress.isNullOrBlank()) {
                resolveFormAddressAsync(lat, lng)
            }
            val msgRes = if (!parsed.placeLabel.isNullOrBlank()) {
                R.string.qr_scan_filled_coords_and_label
            } else {
                R.string.qr_scan_filled_coords
            }
            Toast.makeText(requireContext(), msgRes, Toast.LENGTH_LONG).show()
        } else {
            if (!parsed.placeLabel.isNullOrBlank()) {
                draftPlaceLabel = parsed.placeLabel
            }
            if (!parsed.address.isNullOrBlank()) {
                draftAddress = parsed.address
            }
            isAutoDefaultGpsLocation = false
            if (formTimestamp <= 0L) {
                formTimestamp = System.currentTimeMillis()
            }
            hasUnsavedFormChanges = true
            renderSheetState(focusMap = false)
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            Toast.makeText(requireContext(), R.string.qr_scan_filled_label, Toast.LENGTH_LONG).show()
        }
    }

    private fun launchGalleryPicker(remainingSlots: Int) {
        if (remainingSlots <= 0) {
            Toast.makeText(requireContext(), R.string.photo_limit_reached, Toast.LENGTH_SHORT).show()
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
                Toast.makeText(requireContext(), R.string.photo_picker_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importSelectedGalleryUris(uris: List<Uri>) {
        val isPending = photoTargetIsPending
        val targetRecordId = photoTargetRecordId
        val remaining = remainingPhotoSlots(isPending)
        if (remaining <= 0) {
            Toast.makeText(requireContext(), R.string.photo_limit_reached, Toast.LENGTH_SHORT).show()
            return
        }
        val clipped = if (uris.size > remaining) uris.take(remaining) else uris
        val wasTrimmed = uris.size > remaining
        setPhotoSaving(true)
        importNextGalleryUri(clipped, 0, isPending, targetRecordId, 0, wasTrimmed)
    }

    private fun importNextGalleryUri(
        uris: List<Uri>,
        index: Int,
        isPending: Boolean,
        targetRecordId: String?,
        addedCount: Int,
        wasTrimmed: Boolean
    ) {
        if (!isAdded || _binding == null) return
        if (index >= uris.size) {
            setPhotoSaving(false)
            if (addedCount > 0) {
                if (wasTrimmed || remainingPhotoSlots(isPending) == 0) {
                    val msg = if (wasTrimmed) {
                        getString(R.string.photo_limit_partial_added, addedCount)
                    } else {
                        getString(R.string.photo_limit_reached)
                    }
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
                if (!isPending) {
                    (activity as? MainActivity)?.notifyParkingDataChanged()
                }
            }
            return
        }

        photoManager.importGalleryPhoto(
            uris[index],
            null,
            object : ParkingPhotoManager.PhotoMetadataCallback {
                override fun onPhotoReady(metadata: ParkingPhotoManager.PhotoMetadata) {
                    if (!isAdded || _binding == null) return
                    val item = ParkingPhotoItem(
                        metadata.internalFilePath,
                        metadata.capturedAtMillis,
                        metadata.latitude,
                        metadata.longitude,
                        metadata.importTimestampOrCaptured()
                    )
                    val appended = commitPhotoItem(item, isPending, targetRecordId, showAddedToast = false)
                    val nextAdded = if (appended) addedCount + 1 else addedCount
                    importNextGalleryUri(uris, index + 1, isPending, targetRecordId, nextAdded, wasTrimmed)
                }

                override fun onPhotoError(message: String) {
                    if (!isAdded || _binding == null) return
                    showErrorBanner(message)
                    importNextGalleryUri(uris, index + 1, isPending, targetRecordId, addedCount, wasTrimmed)
                }
            }
        )
    }

    private fun attachSinglePhotoItem(item: ParkingPhotoItem) {
        val appended = commitPhotoItem(
            item = item,
            isPending = photoTargetIsPending,
            targetRecordId = photoTargetRecordId,
            showAddedToast = true
        )
        if (appended && !photoTargetIsPending) {
            (activity as? MainActivity)?.notifyParkingDataChanged()
        }
    }

    private fun commitPhotoItem(
        item: ParkingPhotoItem,
        isPending: Boolean,
        targetRecordId: String?,
        showAddedToast: Boolean
    ): Boolean {
        if (isPending || activeRecord == null) {
            if (pendingPhotos.size >= ParkingPhotoItem.MAX_PHOTOS_PER_RECORD) {
                deleteUnreferencedPhoto(item.filePath, null)
                Toast.makeText(requireContext(), R.string.photo_limit_reached, Toast.LENGTH_SHORT).show()
                return false
            }
            pendingPhotos.add(item)
            hasUnsavedFormChanges = true
            if (formTimestamp <= 0L) formTimestamp = System.currentTimeMillis()
            renderSheetState(focusMap = false)
            if (showAddedToast) {
                val msg = if (pendingPhotos.size >= ParkingPhotoItem.MAX_PHOTOS_PER_RECORD) {
                    getString(R.string.photo_limit_reached)
                } else {
                    getString(R.string.photo_added_confirmation, pendingPhotos.size)
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
            return true
        }

        val current = activeRecord
        val recordId = targetRecordId ?: current?.id
        if (recordId == null || current == null || current.id != recordId) {
            deleteUnreferencedPhoto(item.filePath, null)
            return false
        }
        val added = parkingStorage.addPhotoToRecord(recordId, item)
        if (!added) {
            deleteUnreferencedPhoto(item.filePath, null)
            Toast.makeText(requireContext(), R.string.photo_limit_reached, Toast.LENGTH_SHORT).show()
            return false
        }
        activeRecord = parkingStorage.getActiveRecord()
        renderSheetState(focusMap = false)
        if (showAddedToast) {
            val count = activeRecord?.photoCount ?: 0
            val msg = if (count >= ParkingPhotoItem.MAX_PHOTOS_PER_RECORD) {
                getString(R.string.photo_limit_reached)
            } else {
                getString(R.string.photo_added_confirmation, count)
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun openPendingPhotoViewer(index: Int) {
        if (pendingPhotos.isEmpty()) return
        val intent = Intent(requireContext(), PhotoViewerActivity::class.java).apply {
            putExtra(PhotoViewerActivity.EXTRA_PHOTOS_JSON, ParkingPhotoItem.toJsonString(pendingPhotos))
            putExtra(PhotoViewerActivity.EXTRA_INITIAL_INDEX, index)
            putExtra(PhotoViewerActivity.EXTRA_EDITABLE, true)
        }
        photoViewerLauncher.launch(intent)
    }

    private fun removePendingPhotoAt(index: Int) {
        if (index !in pendingPhotos.indices) return
        val removed = pendingPhotos.removeAt(index)
        deleteUnreferencedPhoto(removed.filePath, null)
        hasUnsavedFormChanges = true
        if (formTimestamp <= 0L) formTimestamp = System.currentTimeMillis()
        renderSheetState(focusMap = false)
        Toast.makeText(requireContext(), R.string.photo_removed_confirmation, Toast.LENGTH_SHORT).show()
    }

    private fun clearPendingPhotos() {
        val copy = pendingPhotos.toList()
        pendingPhotos.clear()
        copy.forEach { deleteUnreferencedPhoto(it.filePath, null) }
    }

    private fun deleteUnreferencedPhoto(oldPath: String?, replacementPath: String?) {
        if (oldPath.isNullOrBlank() || oldPath == replacementPath) return
        if (::parkingStorage.isInitialized && ::photoManager.isInitialized && !parkingStorage.isPhotoReferenced(oldPath)) {
            photoManager.deletePhotoFile(oldPath)
        }
    }

    private fun performShakeHapticFeedback(confirmed: Boolean) {
        val view = _binding?.root ?: return
        val flag = if (confirmed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(flag)
    }

    private fun confirmClearHomeForm() {
        val prevLatLng = draftLatLng
        val prevAddress = draftAddress

        draftPlaceLabel = null
        draftNote = null
        clearPendingPhotos()
        parkingStorage.clearActiveSpot()
        activeRecord = null
        routeFetcher?.reset()
        ParkingUiHelper.clearRouteFromMap(mapLibreMap)
        formTimestamp = System.currentTimeMillis()
        isFormSavedToSaved = false
        hasUnsavedFormChanges = false

        draftLatLng = null
        draftAddress = null
        isAutoDefaultGpsLocation = false
        populateDefaultGpsOrFetch()

        val resolvedLoc = draftLatLng
        if (resolvedLoc != null) {
            val reusedAddress = if (prevLatLng != null && !prevAddress.isNullOrBlank()) {
                val dist = DistanceCalculator.calculateDistanceMeters(
                    latestUserLocation,
                    com.example.devicewatch.model.ParkingSpot(prevLatLng.latitude, prevLatLng.longitude, 0L)
                )
                if (dist in 0f..15f) prevAddress else null
            } else {
                null
            }
            draftAddress = reusedAddress
            renderSheetState(focusMap = true)
            if (reusedAddress == null) {
                resolveFormAddressAsync(resolvedLoc.latitude, resolvedLoc.longitude)
            }
        } else {
            renderSheetState(focusMap = false)
        }
        if (locationManager.hasLocationPermission()) {
            startLiveDistanceUpdates()
        }
        Toast.makeText(requireContext(), R.string.home_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun startLiveDistanceUpdates() {
        if (isLiveUpdatesActive || !::locationManager.isInitialized || !locationManager.hasLocationPermission()) return
        isLiveUpdatesActive = true
        locationManager.startLocationUpdates(object : ParkingLocationManager.LocationResultCallback {
            override fun onLocationRetrieved(location: Location) {
                if (!isAdded || _binding == null) return
                latestUserLocation = location
                ParkingUiHelper.updateUserLocationPuck(mapLibreMap, location)
                applyAutoDefaultLocationIfNeeded(location)
                updateHomeRouteIfReady()
            }

            override fun onLocationError(message: String) {
                if (!isAdded || _binding == null) return
                if (activeRecord != null) {
                    binding.tvHomeGpsStatus.text = message
                }
            }
        })
    }

    private fun updateHomeRouteIfReady() {
        if (!isAdded || _binding == null || !isMapStyleLoaded || !::parkingStorage.isInitialized) return
        if (isPinSelectionMode) {
            routeFetcher?.reset()
            ParkingUiHelper.clearRouteFromMap(mapLibreMap)
            return
        }
        val destination: LatLng? = when {
            activeRecord != null -> draftLatLng ?: LatLng(activeRecord!!.latitude, activeRecord!!.longitude)
            draftLatLng != null && !isAutoDefaultGpsLocation -> draftLatLng
            else -> null
        }
        if (destination == null) {
            routeFetcher?.reset()
            ParkingUiHelper.clearRouteFromMap(mapLibreMap)
            return
        }
        val userLoc = latestUserLocation
            ?: if (::locationManager.isInitialized && locationManager.hasLocationPermission()) {
                locationManager.getLastKnownLocationSync()?.also { latestUserLocation = it }
            } else {
                null
            }
        if (userLoc == null) {
            ParkingUiHelper.clearRouteFromMap(mapLibreMap)
            return
        }
        getOrCreateRouteFetcher().fetchRoute(
            originLat = userLoc.latitude,
            originLng = userLoc.longitude,
            destLat = destination.latitude,
            destLng = destination.longitude,
            travelMode = parkingStorage.getNavigationTravelMode()
        ) { result ->
            if (!isAdded || _binding == null || !isMapStyleLoaded || isPinSelectionMode) return@fetchRoute
            ParkingUiHelper.showRouteOnMap(mapLibreMap, isMapStyleLoaded, result.points, result.isRoadRoute)
        }
    }

    private fun stopLiveDistanceUpdates() {
        if (!isLiveUpdatesActive) return
        isLiveUpdatesActive = false
        if (::locationManager.isInitialized) {
            locationManager.stopLocationUpdates()
        }
    }

    private fun setSaveLoading(loading: Boolean) {
        isSaveInProgress = loading
        binding.progressHome.visibility = if (loading || isPhotoImportInProgress) View.VISIBLE else View.GONE
        binding.btnHomeSave.isEnabled = !loading
        binding.btnHomeSaveNewSpot.isEnabled = !loading
        binding.btnHomeSaveNewSpot.setText(
            if (loading) R.string.saving_parking_spot else R.string.save_new_parking_spot
        )
    }

    private fun setPhotoSaving(saving: Boolean) {
        isPhotoImportInProgress = saving
        binding.progressHome.visibility = if (saving || isSaveInProgress) View.VISIBLE else View.GONE
        renderFormPhotoState()
    }

    private fun showPermissionDeniedBanner() {
        val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        binding.tvBannerMessage.setText(R.string.permission_denied_message)
        binding.btnBannerManualPin.visibility = View.VISIBLE
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
        binding.btnBannerManualPin.visibility = View.VISIBLE
        binding.btnBannerAction.visibility = View.VISIBLE
        binding.btnBannerAction.setText(R.string.enable_gps)
        binding.btnBannerAction.setOnClickListener {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        binding.cardStatusBanner.visibility = View.VISIBLE
    }

    private fun showErrorBanner(message: String) {
        binding.tvBannerMessage.text = message
        binding.btnBannerManualPin.visibility = View.VISIBLE
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
        if (_binding == null || !::locationManager.isInitialized) return
        if (hidden) {
            closePlaceSearchOverlay(hideKeyboard = true)
            stopLiveDistanceUpdates()
            stopElapsedTimer()
        } else {
            refreshFromStorage(focusMap = false)
            if (locationManager.hasLocationPermission()) {
                activateLocationPuckIfReady()
                startLiveDistanceUpdates()
            }
            if (formTimestamp > 0L || activeRecord != null) startElapsedTimer()
        }
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
        if (!isHidden && ::locationManager.isInitialized) {
            if (locationManager.hasLocationPermission()) {
                activateLocationPuckIfReady()
                startLiveDistanceUpdates()
            }
            if (formTimestamp > 0L || activeRecord != null) startElapsedTimer()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        if (!::parkingStorage.isInitialized || !::locationManager.isInitialized) return
        val skipRender = skipInitialResumeRender
        skipInitialResumeRender = false
        if (!isHidden) {
            if (!skipRender) {
                refreshFromStorage(focusMap = false)
            }
            if (locationManager.hasLocationPermission()) {
                activateLocationPuckIfReady()
            }
        }
    }

    override fun onPause() {
        closePlaceSearchOverlay(hideKeyboard = false)
        mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        stopLiveDistanceUpdates()
        stopElapsedTimer()
        mapView?.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (pendingPhotos.isNotEmpty()) {
            outState.putString(KEY_PENDING_PHOTOS_JSON, ParkingPhotoItem.toJsonString(pendingPhotos))
        }
        draftLatLng?.let {
            outState.putDouble(KEY_DRAFT_LAT, it.latitude)
            outState.putDouble(KEY_DRAFT_LNG, it.longitude)
        }
        draftPlaceLabel?.let { outState.putString(KEY_DRAFT_LABEL, it) }
        draftAddress?.let { outState.putString(KEY_DRAFT_ADDRESS, it) }
        draftNote?.let { outState.putString(KEY_DRAFT_NOTE, it) }
        outState.putLong(KEY_FORM_TIMESTAMP, formTimestamp)
        outState.putBoolean(KEY_FORM_SAVED, isFormSavedToSaved)
        outState.putBoolean(KEY_FORM_DIRTY, hasUnsavedFormChanges)
        outState.putBoolean(KEY_PHOTO_TARGET_PENDING, photoTargetIsPending)
        photoTargetRecordId?.let { outState.putString(KEY_PHOTO_TARGET_RECORD_ID, it) }
        if (_binding != null) mapView?.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (_binding != null) mapView?.onLowMemory()
    }

    override fun onDestroyView() {
        if (pendingPhotos.isNotEmpty() && activity?.isChangingConfigurations != true) {
            clearPendingPhotos()
        }
        stopLiveDistanceUpdates()
        if (::locationManager.isInitialized) {
            locationManager.cancelCurrentLocationRequest()
        }
        stopElapsedTimer()
        pendingPlaceSearchRunnable?.let { elapsedHandler.removeCallbacks(it) }
        pendingPlaceSearchRunnable = null
        placeSearchBackCallback.isEnabled = false
        placeSearchClient?.shutdown()
        placeSearchClient = null
        routeFetcher?.shutdown()
        routeFetcher = null
        if (::nativeGeocoder.isInitialized) {
            nativeGeocoder.shutdown()
        }
        if (::photoManager.isInitialized) {
            photoManager.shutdown()
        }
        mapView?.onDestroy()
        mapView = null
        mapLibreMap = null
        _binding = null
        super.onDestroyView()
    }
}
