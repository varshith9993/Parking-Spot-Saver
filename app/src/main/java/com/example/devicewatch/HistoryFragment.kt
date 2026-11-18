package com.example.devicewatch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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
import com.example.devicewatch.databinding.FragmentHistoryBinding
import com.example.devicewatch.location.NativeGeocoder
import com.example.devicewatch.location.ParkingLocationManager
import com.example.devicewatch.model.HistoryDateGrouper
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
import java.util.Locale

class HistoryFragment : Fragment() {

    companion object {
        private const val RELATIVE_TIME_REFRESH_MS = 60_000L
    }

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var parkingStorage: ParkingStorage
    private lateinit var photoManager: ParkingPhotoManager
    private lateinit var locationManager: ParkingLocationManager
    private lateinit var distanceController: OnDemandDistanceController
    private lateinit var adapter: HistoryAdapter

    private var allRecords: List<ParkingRecord> = emptyList()
    private var currentQuery: String = ""

    private val relativeTimeHandler = Handler(Looper.getMainLooper())
    private val relativeTimeTickRunnable = object : Runnable {
        override fun run() {
            if (_binding != null && isAdded && !isHidden) {
                applyFilterAndRender()
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
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
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
        adapter = HistoryAdapter(
            photoManager = photoManager,
            distanceStateProvider = { recordId -> distanceController.stateFor(recordId) },
            onDistanceClick = { record -> distanceController.requestDistance(record) },
            onItemClick = { record -> openDetail(record) },
            onToggleBookmark = { record -> toggleBookmark(record) },
            onNavigateClick = { record -> navigateTo(record) },
            onShareClick = { record -> shareRecord(record) },
            onDeleteClick = { record -> confirmDeleteEntry(record) }
        )
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter

        setupSearchControls()
        binding.btnClearHistory.setOnClickListener { confirmClearHistory() }

        refreshData()
    }

    private fun applyTopInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.historyRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            binding.historyHeaderBar.setPadding(20, 10 + bars.top, 8, 10)
            insets
        }
    }

    private fun setupSearchControls() {
        binding.etHistorySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                currentQuery = query
                binding.btnClearHistorySearch.visibility =
                    if (query.isEmpty()) View.GONE else View.VISIBLE
                applyFilterAndRender()
            }
        })

        binding.btnClearHistorySearch.setOnClickListener {
            binding.etHistorySearch.setText("")
            binding.etHistorySearch.clearFocus()
        }
    }

    fun refreshData() {
        if (!isAdded || _binding == null || !::parkingStorage.isInitialized) return
        allRecords = parkingStorage.getHistoryRecords()
        binding.btnClearHistory.visibility =
            if (allRecords.isEmpty()) View.GONE else View.VISIBLE
        applyFilterAndRender()
    }

    private fun applyFilterAndRender() {
        if (!isAdded || _binding == null || !::adapter.isInitialized) return
        if (allRecords.isEmpty()) {
            binding.rvHistory.visibility = View.GONE
            binding.tvEmptyHistory.setText(R.string.history_empty)
            binding.tvEmptyHistory.visibility = View.VISIBLE
            adapter.submitRows(emptyList())
            return
        }

        val filtered = if (currentQuery.isEmpty()) {
            allRecords
        } else {
            val needle = currentQuery.lowercase(Locale.getDefault())
            allRecords.filter { record -> matchesQuery(record, needle) }
        }

        if (filtered.isEmpty()) {
            binding.rvHistory.visibility = View.GONE
            binding.tvEmptyHistory.text = getString(R.string.history_search_empty, currentQuery)
            binding.tvEmptyHistory.visibility = View.VISIBLE
            adapter.submitRows(emptyList())
            return
        }

        val rows = HistoryDateGrouper.buildGroupedRows(
            filtered,
            System.currentTimeMillis(),
            getString(R.string.history_today),
            getString(R.string.history_yesterday),
            getString(R.string.history_earlier)
        )
        binding.rvHistory.visibility = View.VISIBLE
        binding.tvEmptyHistory.visibility = View.GONE
        adapter.submitRows(rows)
    }

    private fun matchesQuery(record: ParkingRecord, needle: String): Boolean {
        val locale = Locale.getDefault()
        if (record.address?.lowercase(locale)?.contains(needle) == true) return true
        if (record.placeLabel?.lowercase(locale)?.contains(needle) == true) return true
        if (record.note?.lowercase(locale)?.contains(needle) == true) return true
        val coords = NativeGeocoder.formatCoordinates(record.latitude, record.longitude)
            .lowercase(locale)
        return coords.contains(needle)
    }

    private fun startRelativeTimeTicker() {
        stopRelativeTimeTicker()
        relativeTimeHandler.postDelayed(relativeTimeTickRunnable, RELATIVE_TIME_REFRESH_MS)
    }

    private fun stopRelativeTimeTicker() {
        relativeTimeHandler.removeCallbacks(relativeTimeTickRunnable)
    }

    private fun toggleBookmark(record: ParkingRecord) {
        val nextSaved = !record.isSaved
        parkingStorage.setRecordSaved(record.id, nextSaved)
        Toast.makeText(
            requireContext(),
            if (nextSaved) R.string.bookmark_saved else R.string.bookmark_unsaved,
            Toast.LENGTH_SHORT
        ).show()
        refreshData()
        (activity as? MainActivity)?.notifyParkingDataChanged()
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

    private fun openDetail(record: ParkingRecord) {
        val intent = Intent(requireContext(), ParkingRecordDetailActivity::class.java).apply {
            putExtra(ParkingRecordDetailActivity.EXTRA_RECORD_ID, record.id)
            putExtra(
                ParkingRecordDetailActivity.EXTRA_SOURCE_MODE,
                ParkingRecordDetailActivity.MODE_HISTORY
            )
        }
        startActivity(intent)
    }

    private fun confirmDeleteEntry(record: ParkingRecord) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_history_item_title)
            .setMessage(R.string.delete_history_item_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val removed = parkingStorage.deleteHistoryRecord(record.id)
                removed?.photos?.forEach { photo ->
                    if (!parkingStorage.isPhotoReferenced(photo.filePath)) {
                        photoManager.deletePhotoFile(photo.filePath)
                    }
                }
                refreshData()
                (activity as? MainActivity)?.notifyParkingDataChanged()
            }
            .show()
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_history_confirm_title)
            .setMessage(R.string.clear_history_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear_confirm_action) { _, _ ->
                val removedPhotos = parkingStorage.clearParkingHistory()
                for (path in removedPhotos) {
                    if (!parkingStorage.isPhotoReferenced(path)) {
                        photoManager.deletePhotoFile(path)
                    }
                }
                refreshData()
                (activity as? MainActivity)?.notifyParkingDataChanged()
            }
            .show()
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
        refreshData()
        if (!isHidden) {
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

    private class HistoryAdapter(
        private val photoManager: ParkingPhotoManager,
        private val distanceStateProvider: (String) -> ParkingUiHelper.RecordDistanceState,
        private val onDistanceClick: (ParkingRecord) -> Unit,
        private val onItemClick: (ParkingRecord) -> Unit,
        private val onToggleBookmark: (ParkingRecord) -> Unit,
        private val onNavigateClick: (ParkingRecord) -> Unit,
        private val onShareClick: (ParkingRecord) -> Unit,
        private val onDeleteClick: (ParkingRecord) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val VIEW_TYPE_HEADER = 0
            private const val VIEW_TYPE_ITEM = 1
            private const val THUMB_SIZE = 180
        }

        private val rows = mutableListOf<HistoryDateGrouper.ListRow>()
        private val dateFormatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

        fun submitRows(newRows: List<HistoryDateGrouper.ListRow>) {
            rows.clear()
            rows.addAll(newRows)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return if (rows[position].isHeader) VIEW_TYPE_HEADER else VIEW_TYPE_ITEM
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_TYPE_HEADER) {
                HeaderViewHolder(inflater.inflate(R.layout.item_history_header, parent, false))
            } else {
                ItemViewHolder(inflater.inflate(R.layout.item_history_record, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (holder is HeaderViewHolder) {
                holder.tvHeader.text = row.headerTitle
            } else if (holder is ItemViewHolder && row.record != null) {
                holder.bind(row.record)
            }
        }

        private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvHeader: TextView = view.findViewById(R.id.tvHistorySectionHeader)
        }

        private inner class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val cardThumb: MaterialCardView = view.findViewById(R.id.cardHistoryThumb)
            private val ivThumb: ImageView = view.findViewById(R.id.ivHistoryThumb)
            private val tvPhotoBadge: TextView = view.findViewById(R.id.tvHistoryPhotoBadge)
            private val tvRelativeTime: TextView = view.findViewById(R.id.tvHistoryRelativeTime)
            private val tvDateTime: TextView = view.findViewById(R.id.tvHistoryDateTime)
            private val pbDistance: ProgressBar = view.findViewById(R.id.pbHistoryDistance)
            private val tvDistance: TextView = view.findViewById(R.id.tvHistoryDistance)
            private val btnDistance: MaterialButton = view.findViewById(R.id.btnHistoryDistance)
            private val btnBookmark: ImageButton = view.findViewById(R.id.btnHistoryBookmark)
            private val tvPlaceLabel: TextView = view.findViewById(R.id.tvHistoryPlaceLabel)
            private val tvAddress: TextView = view.findViewById(R.id.tvHistoryAddress)
            private val tvNote: TextView = view.findViewById(R.id.tvHistoryNote)
            private val btnNavigate: MaterialButton = view.findViewById(R.id.btnHistoryNavigate)
            private val btnShare: MaterialButton = view.findViewById(R.id.btnHistoryShare)
            private val btnDelete: MaterialButton = view.findViewById(R.id.btnDeleteHistoryItem)

            fun bind(record: ParkingRecord) {
                tvRelativeTime.text = RelativeTimeFormatter.format(record.savedTimestamp)
                tvDateTime.text = if (record.savedTimestamp > 0L) {
                    dateFormatter.format(Date(record.savedTimestamp))
                } else {
                    "--"
                }

                ParkingUiHelper.bindOnDemandDistanceRow(
                    distanceStateProvider(record.id),
                    pbDistance,
                    tvDistance,
                    btnDistance
                )
                btnDistance.setOnClickListener { onDistanceClick(record) }

                ParkingUiHelper.applyBookmarkIcon(btnBookmark, record.isSaved)

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
                    NativeGeocoder.formatCoordinates(record.latitude, record.longitude)
                }

                val note = record.note
                if (!note.isNullOrBlank()) {
                    tvNote.text = note
                    tvNote.visibility = View.VISIBLE
                } else {
                    tvNote.visibility = View.GONE
                }

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

                itemView.setOnClickListener { onItemClick(record) }
                btnBookmark.setOnClickListener { onToggleBookmark(record) }
                btnNavigate.setOnClickListener { onNavigateClick(record) }
                btnShare.setOnClickListener { onShareClick(record) }
                btnDelete.setOnClickListener { onDeleteClick(record) }
            }
        }
    }
}
