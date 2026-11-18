package com.example.devicewatch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.devicewatch.databinding.FragmentSavedBinding
import com.example.devicewatch.location.NativeGeocoder
import com.example.devicewatch.location.ParkingLocationManager
import com.example.devicewatch.model.ParkingRecord
import com.example.devicewatch.model.RelativeTimeFormatter
import com.example.devicewatch.navigation.NavigationManager
import com.example.devicewatch.storage.ParkingPhotoManager
import com.example.devicewatch.storage.ParkingStorage
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date

class SavedLocationsFragment : Fragment() {

    companion object {
        private const val RELATIVE_TIME_REFRESH_MS = 60_000L
    }

    private var _binding: FragmentSavedBinding? = null
    private val binding get() = _binding!!

    private lateinit var parkingStorage: ParkingStorage
    private lateinit var photoManager: ParkingPhotoManager
    private lateinit var locationManager: ParkingLocationManager
    private lateinit var distanceController: OnDemandDistanceController
    private lateinit var adapter: SavedAdapter

    private val relativeTimeHandler = Handler(Looper.getMainLooper())
    private val relativeTimeTickRunnable = object : Runnable {
        override fun run() {
            if (_binding != null && isAdded && !isHidden && ::adapter.isInitialized) {
                adapter.notifyDataSetChanged()
                relativeTimeHandler.postDelayed(this, RELATIVE_TIME_REFRESH_MS)
            }
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (!isAdded || _binding == null || !::distanceController.isInitialized) return@registerForActivityResult
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val allowRetry = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
        distanceController.onPermissionResult(granted, allowRetry)
    }

    private val gpsResolutionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (!isAdded || _binding == null || !::distanceController.isInitialized) return@registerForActivityResult
        distanceController.onGpsResolutionResult(result.resultCode == Activity.RESULT_OK)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSavedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        parkingStorage = ParkingStorage(requireContext())
        photoManager = ParkingPhotoManager(requireContext())
        locationManager = ParkingLocationManager(requireContext())
        distanceController = OnDemandDistanceController(
            context = requireContext(),
            locationManager = locationManager,
            isActive = { isAdded && _binding != null && !isHidden },
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
                if (isAdded && _binding != null) {
                    ParkingUiHelper.showDistancePermissionDialog(
                        requireContext(),
                        allowRetry,
                        onRetryPermission = { distanceController.retryAfterPermissionGrantRequest() },
                        onOpenSettings = { ParkingUiHelper.openAppPermissionSettings(requireContext()) }
                    )
                }
            },
            onShowGpsDisabledDialog = {
                if (isAdded && _binding != null) {
                    ParkingUiHelper.showDistanceGpsDisabledDialog(requireContext()) {
                        ParkingUiHelper.openDeviceLocationSettings(requireContext())
                    }
                }
            },
            onStatesChanged = {
                if (isAdded && _binding != null && ::adapter.isInitialized) {
                    adapter.notifyDataSetChanged()
                }
            }
        )

        applyTopInsets()
        adapter = SavedAdapter(
            photoManager = photoManager,
            distanceStateProvider = { recordId -> distanceController.stateFor(recordId) },
            onDistanceClick = { record -> distanceController.requestDistance(record) },
            onView = { record -> openDetail(record) },
            onNavigate = { record -> navigateTo(record) },
            onShare = { record -> shareRecord(record) },
            onEditNote = { record -> editNote(record) },
            onUnsave = { record -> confirmUnsave(record) }
        )
        binding.rvSaved.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSaved.adapter = adapter

        refreshData()
    }

    private fun applyTopInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.savedRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            binding.savedHeaderBar.setPadding(20, 14 + bars.top, 20, 14)
            insets
        }
    }

    fun refreshData() {
        if (!isAdded || _binding == null || !::parkingStorage.isInitialized || !::adapter.isInitialized) return
        val records = parkingStorage.getSavedRecords()
        binding.tvSavedCount.text = getString(R.string.saved_count_format, records.size)
        if (records.isEmpty()) {
            binding.rvSaved.visibility = View.GONE
            binding.tvEmptySaved.visibility = View.VISIBLE
            adapter.submit(emptyList())
        } else {
            binding.rvSaved.visibility = View.VISIBLE
            binding.tvEmptySaved.visibility = View.GONE
            adapter.submit(records)
        }
    }

    private fun openDetail(record: ParkingRecord) {
        val intent = Intent(requireContext(), ParkingRecordDetailActivity::class.java).apply {
            putExtra(ParkingRecordDetailActivity.EXTRA_RECORD_ID, record.id)
            putExtra(
                ParkingRecordDetailActivity.EXTRA_SOURCE_MODE,
                ParkingRecordDetailActivity.MODE_SAVED
            )
        }
        startActivity(intent)
    }

    private fun navigateTo(record: ParkingRecord) {
        val opened = NavigationManager.openDirections(
            requireContext(),
            record.toParkingSpot(),
            parkingStorage.getNavigationTravelMode()
        )
        if (!opened) {
            Toast.makeText(requireContext(), R.string.no_navigation_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareRecord(record: ParkingRecord) {
        val shared = NavigationManager.shareParkingRecord(
            requireContext(),
            record,
            getString(R.string.share_chooser_title)
        )
        if (!shared) {
            Toast.makeText(requireContext(), R.string.share_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun editNote(record: ParkingRecord) {
        ParkingUiHelper.showNoteEditDialog(requireContext(), record.note) { updated ->
            parkingStorage.updateRecordNote(record.id, updated)
            refreshData()
            (activity as? MainActivity)?.notifyParkingDataChanged()
        }
    }

    private fun confirmUnsave(record: ParkingRecord) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_saved_title)
            .setMessage(R.string.delete_saved_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.unsave) { _, _ ->
                parkingStorage.unsaveRecord(record.id)
                Toast.makeText(requireContext(), R.string.bookmark_unsaved, Toast.LENGTH_SHORT).show()
                refreshData()
                (activity as? MainActivity)?.notifyParkingDataChanged()
            }
            .show()
    }

    private fun startRelativeTimeTicker() {
        stopRelativeTimeTicker()
        relativeTimeHandler.postDelayed(relativeTimeTickRunnable, RELATIVE_TIME_REFRESH_MS)
    }

    private fun stopRelativeTimeTicker() {
        relativeTimeHandler.removeCallbacks(relativeTimeTickRunnable)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding == null || !::distanceController.isInitialized) return
        if (hidden) {
            stopRelativeTimeTicker()
            distanceController.cancelAndClear(resetStates = true)
        } else {
            refreshData()
            startRelativeTimeTicker()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!isHidden) {
            startRelativeTimeTicker()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) {
            refreshData()
            startRelativeTimeTicker()
        }
    }

    override fun onStop() {
        stopRelativeTimeTicker()
        if (::distanceController.isInitialized) {
            distanceController.cancelAndClear(resetStates = true)
        }
        super.onStop()
    }

    override fun onDestroyView() {
        stopRelativeTimeTicker()
        if (::distanceController.isInitialized) {
            distanceController.cancelAndClear(resetStates = false)
        }
        if (::photoManager.isInitialized) {
            photoManager.shutdown()
        }
        _binding = null
        super.onDestroyView()
    }

    private class SavedAdapter(
        private val photoManager: ParkingPhotoManager,
        private val distanceStateProvider: (String) -> ParkingUiHelper.RecordDistanceState,
        private val onDistanceClick: (ParkingRecord) -> Unit,
        private val onView: (ParkingRecord) -> Unit,
        private val onNavigate: (ParkingRecord) -> Unit,
        private val onShare: (ParkingRecord) -> Unit,
        private val onEditNote: (ParkingRecord) -> Unit,
        private val onUnsave: (ParkingRecord) -> Unit
    ) : RecyclerView.Adapter<SavedAdapter.SavedViewHolder>() {

        companion object {
            private const val THUMB_SIZE = 180
        }

        private val items = mutableListOf<ParkingRecord>()
        private val dateTimeFormatter =
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

        fun submit(records: List<ParkingRecord>) {
            items.clear()
            items.addAll(records)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SavedViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_saved_record, parent, false)
            return SavedViewHolder(view)
        }

        override fun onBindViewHolder(holder: SavedViewHolder, position: Int) {
            holder.bind(items[position])
        }

        private inner class SavedViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val cardThumb: MaterialCardView = view.findViewById(R.id.cardSavedThumb)
            private val ivThumb: ImageView = view.findViewById(R.id.ivSavedThumb)
            private val tvPhotoBadge: TextView = view.findViewById(R.id.tvSavedPhotoBadge)
            private val tvPlaceLabel: TextView = view.findViewById(R.id.tvSavedRowPlaceLabel)
            private val tvAddress: TextView = view.findViewById(R.id.tvSavedRowAddress)
            private val tvCoords: TextView = view.findViewById(R.id.tvSavedRowCoords)
            private val tvRelativeTime: TextView = view.findViewById(R.id.tvSavedRowRelativeTime)
            private val tvDateTime: TextView = view.findViewById(R.id.tvSavedRowDateTime)
            private val tvNote: TextView = view.findViewById(R.id.tvSavedRowNote)
            private val pbDistance: ProgressBar = view.findViewById(R.id.pbSavedDistance)
            private val tvDistance: TextView = view.findViewById(R.id.tvSavedDistance)
            private val btnDistance: MaterialButton = view.findViewById(R.id.btnSavedDistance)
            private val btnBookmark: ImageButton = view.findViewById(R.id.btnSavedBookmark)
            private val btnView: MaterialButton = view.findViewById(R.id.btnSavedView)
            private val btnNav: MaterialButton = view.findViewById(R.id.btnSavedNavigate)
            private val btnShare: MaterialButton = view.findViewById(R.id.btnSavedShare)
            private val btnNote: MaterialButton = view.findViewById(R.id.btnSavedEditNote)

            fun bind(record: ParkingRecord) {
                val label = record.placeLabel
                if (!label.isNullOrBlank()) {
                    tvPlaceLabel.text = label
                    tvPlaceLabel.visibility = View.VISIBLE
                } else {
                    tvPlaceLabel.visibility = View.GONE
                }

                val addr = record.address
                tvAddress.text = if (!addr.isNullOrBlank()) {
                    addr
                } else {
                    itemView.context.getString(R.string.address_unavailable_fallback)
                }
                tvCoords.text = NativeGeocoder.formatCoordinates(record.latitude, record.longitude)
                tvRelativeTime.text = RelativeTimeFormatter.format(record.savedTimestamp)
                tvDateTime.text = if (record.savedTimestamp > 0L) {
                    dateTimeFormatter.format(Date(record.savedTimestamp))
                } else {
                    "--"
                }

                val note = record.note
                if (!note.isNullOrBlank()) {
                    tvNote.text = note
                    tvNote.visibility = View.VISIBLE
                    btnNote.setText(R.string.edit_note)
                } else {
                    tvNote.visibility = View.GONE
                    btnNote.setText(R.string.add_note)
                }

                ParkingUiHelper.bindOnDemandDistanceRow(
                    distanceStateProvider(record.id),
                    pbDistance,
                    tvDistance,
                    btnDistance
                )
                btnDistance.setOnClickListener { onDistanceClick(record) }

                ParkingUiHelper.applyBookmarkIcon(btnBookmark, true)

                val photoPath = record.photoPath
                if (record.photoCount > 1) {
                    tvPhotoBadge.text = itemView.context.getString(
                        R.string.photo_count_badge_format,
                        record.photoCount
                    )
                    tvPhotoBadge.visibility = View.VISIBLE
                } else {
                    tvPhotoBadge.visibility = View.GONE
                }
                if (photoPath.isNullOrBlank()) {
                    ivThumb.tag = null
                    ivThumb.setImageDrawable(null)
                    cardThumb.visibility = View.GONE
                } else if (ivThumb.tag != photoPath || ivThumb.drawable == null) {
                    ivThumb.tag = photoPath
                    ivThumb.setImageDrawable(null)
                    cardThumb.visibility = View.GONE
                    photoManager.loadSampledBitmap(photoPath, THUMB_SIZE, THUMB_SIZE) { bitmap ->
                        if (bitmap != null && ivThumb.tag == photoPath) {
                            ivThumb.setImageBitmap(bitmap)
                            cardThumb.visibility = View.VISIBLE
                        }
                    }
                }

                itemView.setOnClickListener { onView(record) }
                btnView.setOnClickListener { onView(record) }
                btnNav.setOnClickListener { onNavigate(record) }
                btnShare.setOnClickListener { onShare(record) }
                btnNote.setOnClickListener { onEditNote(record) }
                btnBookmark.setOnClickListener { onUnsave(record) }
            }
        }
    }
}
