package com.example.devicewatch

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.devicewatch.databinding.FragmentSavedBinding
import com.example.devicewatch.location.NativeGeocoder
import com.example.devicewatch.model.ParkingRecord
import com.example.devicewatch.navigation.NavigationManager
import com.example.devicewatch.storage.ParkingPhotoManager
import com.example.devicewatch.storage.ParkingStorage
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date

class SavedLocationsFragment : Fragment() {

    private var _binding: FragmentSavedBinding? = null
    private val binding get() = _binding!!

    private lateinit var parkingStorage: ParkingStorage
    private lateinit var photoManager: ParkingPhotoManager
    private lateinit var adapter: SavedAdapter

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

        applyTopInsets()
        adapter = SavedAdapter(
            photoManager = photoManager,
            onView = { record -> openDetail(record) },
            onNavigate = { record -> navigateTo(record) },
            onEditNote = { record -> editNote(record) },
            onDelete = { record -> confirmDelete(record) }
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
        if (!isAdded || _binding == null) return
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
        val opened = NavigationManager.openWalkingDirections(requireContext(), record.toParkingSpot())
        if (!opened) {
            Toast.makeText(requireContext(), R.string.no_navigation_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun editNote(record: ParkingRecord) {
        ParkingUiHelper.showNoteEditDialog(requireContext(), record.note) { updated ->
            parkingStorage.updateRecordNote(record.id, updated)
            refreshData()
            (activity as? MainActivity)?.notifyParkingDataChanged()
        }
    }

    private fun confirmDelete(record: ParkingRecord) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_saved_title)
            .setMessage(R.string.delete_saved_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val removed = parkingStorage.deleteSavedRecord(record.id)
                val path = removed?.photoPath
                if (path != null && !parkingStorage.isPhotoReferenced(path)) {
                    photoManager.deletePhotoFile(path)
                }
                refreshData()
                (activity as? MainActivity)?.notifyParkingDataChanged()
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

    private class SavedAdapter(
        private val photoManager: ParkingPhotoManager,
        private val onView: (ParkingRecord) -> Unit,
        private val onNavigate: (ParkingRecord) -> Unit,
        private val onEditNote: (ParkingRecord) -> Unit,
        private val onDelete: (ParkingRecord) -> Unit
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
            private val tvAddress: TextView = view.findViewById(R.id.tvSavedRowAddress)
            private val tvCoords: TextView = view.findViewById(R.id.tvSavedRowCoords)
            private val tvDateTime: TextView = view.findViewById(R.id.tvSavedRowDateTime)
            private val tvNote: TextView = view.findViewById(R.id.tvSavedRowNote)
            private val btnView: MaterialButton = view.findViewById(R.id.btnSavedView)
            private val btnNav: MaterialButton = view.findViewById(R.id.btnSavedNavigate)
            private val btnNote: MaterialButton = view.findViewById(R.id.btnSavedEditNote)
            private val btnDelete: MaterialButton = view.findViewById(R.id.btnSavedDelete)

            fun bind(record: ParkingRecord) {
                val addr = record.address
                tvAddress.text = if (!addr.isNullOrBlank()) {
                    addr
                } else {
                    itemView.context.getString(R.string.address_unavailable_fallback)
                }
                tvCoords.text = NativeGeocoder.formatCoordinates(record.latitude, record.longitude)
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

                itemView.setOnClickListener { onView(record) }
                btnView.setOnClickListener { onView(record) }
                btnNav.setOnClickListener { onNavigate(record) }
                btnNote.setOnClickListener { onEditNote(record) }
                btnDelete.setOnClickListener { onDelete(record) }
            }
        }
    }
}
