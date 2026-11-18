package com.example.devicewatch

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.devicewatch.databinding.FragmentHistoryBinding
import com.example.devicewatch.location.NativeGeocoder
import com.example.devicewatch.model.HistoryDateGrouper
import com.example.devicewatch.model.ParkingRecord
import com.example.devicewatch.storage.ParkingPhotoManager
import com.example.devicewatch.storage.ParkingStorage
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var parkingStorage: ParkingStorage
    private lateinit var photoManager: ParkingPhotoManager
    private lateinit var adapter: HistoryAdapter

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

        applyTopInsets()
        adapter = HistoryAdapter(
            photoManager = photoManager,
            onItemClick = { record -> openDetail(record) },
            onDeleteClick = { record -> confirmDeleteEntry(record) }
        )
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter
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

    fun refreshData() {
        if (!isAdded || _binding == null) return
        val records = parkingStorage.getHistoryRecords()
        if (records.isEmpty()) {
            binding.rvHistory.visibility = View.GONE
            binding.tvEmptyHistory.visibility = View.VISIBLE
            binding.btnClearHistory.visibility = View.GONE
            adapter.submitRows(emptyList())
            return
        }

        val rows = HistoryDateGrouper.buildGroupedRows(
            records,
            System.currentTimeMillis(),
            getString(R.string.history_today),
            getString(R.string.history_yesterday),
            getString(R.string.history_earlier)
        )
        binding.rvHistory.visibility = View.VISIBLE
        binding.tvEmptyHistory.visibility = View.GONE
        binding.btnClearHistory.visibility = View.VISIBLE
        adapter.submitRows(rows)
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
                val path = removed?.photoPath
                if (path != null && !parkingStorage.isPhotoReferenced(path)) {
                    photoManager.deletePhotoFile(path)
                }
                refreshData()
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
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onDestroyView() {
        photoManager.shutdown()
        _binding = null
        super.onDestroyView()
    }

    private class HistoryAdapter(
        private val photoManager: ParkingPhotoManager,
        private val onItemClick: (ParkingRecord) -> Unit,
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
            private val tvDateTime: TextView = view.findViewById(R.id.tvHistoryDateTime)
            private val tvAddress: TextView = view.findViewById(R.id.tvHistoryAddress)
            private val tvNote: TextView = view.findViewById(R.id.tvHistoryNote)
            private val btnDelete: MaterialButton = view.findViewById(R.id.btnDeleteHistoryItem)

            fun bind(record: ParkingRecord) {
                tvDateTime.text = if (record.savedTimestamp > 0L) {
                    dateFormatter.format(Date(record.savedTimestamp))
                } else {
                    "--"
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
                ivThumb.tag = photoPath
                ivThumb.setImageDrawable(null)
                cardThumb.visibility = View.GONE
                if (!photoPath.isNullOrBlank()) {
                    photoManager.loadSampledBitmap(photoPath, THUMB_SIZE, THUMB_SIZE) { bitmap ->
                        if (bitmap != null && ivThumb.tag == photoPath) {
                            ivThumb.setImageBitmap(bitmap)
                            cardThumb.visibility = View.VISIBLE
                        }
                    }
                }

                itemView.setOnClickListener { onItemClick(record) }
                btnDelete.setOnClickListener { onDeleteClick(record) }
            }
        }
    }
}
