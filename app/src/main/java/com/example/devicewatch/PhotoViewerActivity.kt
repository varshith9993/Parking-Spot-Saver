package com.example.devicewatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.devicewatch.databinding.ActivityPhotoViewerBinding
import com.example.devicewatch.databinding.ItemPhotoViewerPageBinding
import com.example.devicewatch.model.ParkingPhotoItem
import com.example.devicewatch.storage.ParkingPhotoManager
import com.example.devicewatch.storage.ParkingStorage
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PhotoViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECORD_ID = "extra_record_id"
        const val EXTRA_PHOTOS_JSON = "extra_photos_json"
        const val EXTRA_UPDATED_PHOTOS_JSON = "extra_updated_photos_json"
        const val EXTRA_INITIAL_INDEX = "extra_initial_index"
        const val EXTRA_EDITABLE = "extra_editable"
    }

    private lateinit var binding: ActivityPhotoViewerBinding
    private lateinit var parkingStorage: ParkingStorage
    private lateinit var photoManager: ParkingPhotoManager
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var pagerAdapter: PhotoPageAdapter
    private val snapHelper = PagerSnapHelper()

    private var recordId: String = ""
    private var initialPhotosJson: String? = null
    private var isEditable: Boolean = false
    private var photos: MutableList<ParkingPhotoItem> = mutableListOf()
    private var currentIndex: Int = 0
    private var replaceTargetIndex: Int = -1
    private var hasChanges: Boolean = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (isFinishing || isDestroyed) return@registerForActivityResult
        if (granted) {
            launchCameraForReplace()
        } else {
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val cameraCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (isFinishing || isDestroyed) return@registerForActivityResult
        if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult

        val newItem = ParkingUiHelper.extractCapturedPhotoItem(result.data)
            ?: return@registerForActivityResult
        applyReplacementPhoto(newItem)
    }

    private val pickReplaceMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (isFinishing || isDestroyed || uri == null) return@registerForActivityResult
        importReplacementGalleryUri(uri)
    }

    private val fallbackReplaceGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (isFinishing || isDestroyed || result.resultCode != RESULT_OK) {
            return@registerForActivityResult
        }
        val uri = ParkingUiHelper.extractPickedImageUris(result.data).firstOrNull()
            ?: return@registerForActivityResult
        importReplacementGalleryUri(uri)
    }

    private fun importReplacementGalleryUri(uri: android.net.Uri) {
        setBusy(true)
        photoManager.importGalleryPhoto(
            uri,
            null,
            object : ParkingPhotoManager.PhotoMetadataCallback {
                override fun onPhotoReady(metadata: ParkingPhotoManager.PhotoMetadata) {
                    if (isFinishing || isDestroyed) return
                    setBusy(false)
                    val importedAt = if (metadata.capturedAtMillis > 0L) 0L else metadata.importedAtMillis
                    val newItem = ParkingPhotoItem(
                        metadata.internalFilePath,
                        metadata.capturedAtMillis,
                        metadata.latitude,
                        metadata.longitude,
                        importedAt
                    )
                    applyReplacementPhoto(newItem)
                }

                override fun onPhotoError(message: String) {
                    if (isFinishing || isDestroyed) return
                    setBusy(false)
                    Toast.makeText(this@PhotoViewerActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        parkingStorage = ParkingStorage(this)
        photoManager = ParkingPhotoManager(this)

        recordId = intent.getStringExtra(EXTRA_RECORD_ID).orEmpty()
        initialPhotosJson = intent.getStringExtra(EXTRA_PHOTOS_JSON)
        isEditable = intent.getBooleanExtra(EXTRA_EDITABLE, false)
        val initialIndex = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0).coerceAtLeast(0)

        setupPager()
        setupActions()

        if (!loadPhotos(initialIndex)) {
            Toast.makeText(this, R.string.photo_missing_notice, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.photoViewerRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.viewerTopBar.setPadding(
                8 + bars.left,
                10 + bars.top,
                8 + bars.right,
                10
            )
            binding.viewerBottomBar.setPadding(
                18 + bars.left,
                14,
                18 + bars.right,
                14 + bars.bottom
            )
            insets
        }
    }

    private fun setupPager() {
        layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        pagerAdapter = PhotoPageAdapter()
        binding.rvPhotoPager.layoutManager = layoutManager
        binding.rvPhotoPager.adapter = pagerAdapter
        snapHelper.attachToRecyclerView(binding.rvPhotoPager)

        binding.rvPhotoPager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    syncCurrentIndexFromSnap()
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                syncCurrentIndexFromSnap()
            }
        })
    }

    private fun setupActions() {
        binding.btnViewerBack.setOnClickListener { finish() }
        binding.layoutViewerEditActions.visibility = if (isEditable) View.VISIBLE else View.GONE
        binding.btnViewerReplace.setOnClickListener { showReplaceSourceChooser() }
        binding.btnViewerRemove.setOnClickListener { confirmRemoveCurrentPhoto() }
    }

    private fun loadPhotos(requestedIndex: Int): Boolean {
        val list = if (recordId.isNotEmpty()) {
            val record = parkingStorage.getRecordById(recordId) ?: parkingStorage.getSavedRecordById(recordId)
            record?.photos ?: emptyList()
        } else {
            ParkingPhotoItem.fromJsonString(initialPhotosJson)
        }
        if (list.isEmpty()) return false
        photos = list.toMutableList()
        pagerAdapter.submitList(photos)
        currentIndex = requestedIndex.coerceIn(0, photos.lastIndex)
        binding.rvPhotoPager.scrollToPosition(currentIndex)
        updateCurrentPhotoMetadata()
        return true
    }

    private fun syncCurrentIndexFromSnap() {
        val snapView = snapHelper.findSnapView(layoutManager) ?: return
        val pos = layoutManager.getPosition(snapView)
        if (pos != RecyclerView.NO_POSITION && pos in photos.indices && pos != currentIndex) {
            currentIndex = pos
            updateCurrentPhotoMetadata()
        }
    }

    private fun updateCurrentPhotoMetadata() {
        if (photos.isEmpty()) return
        val safeIndex = currentIndex.coerceIn(0, photos.lastIndex)
        currentIndex = safeIndex
        val photo = photos[safeIndex]
        binding.tvViewerCounter.text = getString(R.string.photo_viewer_counter, safeIndex + 1, photos.size)
        binding.tvViewerPhotoTime.text = ParkingUiHelper.formatPhotoTimeText(this, photo)
        binding.tvViewerPhotoGps.text = ParkingUiHelper.formatPhotoLocationText(this, photo)
    }

    private fun showReplaceSourceChooser() {
        if (!isEditable || photos.isEmpty()) return
        replaceTargetIndex = currentIndex
        ParkingUiHelper.showPhotoSourceBottomSheet(
            context = this,
            titleRes = R.string.replace_single_photo,
            onTakePhoto = {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    launchCameraForReplace()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onChooseGallery = {
                ParkingUiHelper.launchPhotoPicker(
                    context = this,
                    allowMultiple = false,
                    launchPrimary = { pickReplaceMediaLauncher.launch(it) },
                    launchFallback = { fallbackReplaceGalleryLauncher.launch(it) }
                )
            }
        )
    }

    private fun launchCameraForReplace() {
        val intent = Intent(this, CameraCaptureActivity::class.java)
        cameraCaptureLauncher.launch(intent)
    }

    private fun applyReplacementPhoto(newItem: ParkingPhotoItem) {
        val targetIndex = if (replaceTargetIndex in photos.indices) replaceTargetIndex else currentIndex
        replaceTargetIndex = -1
        if (recordId.isNotEmpty()) {
            val replaced = parkingStorage.replacePhotoInRecord(recordId, targetIndex, newItem)
            if (replaced == null) {
                if (!parkingStorage.isPhotoReferenced(newItem.filePath)) {
                    photoManager.deletePhotoFile(newItem.filePath)
                }
                Toast.makeText(this, R.string.record_missing, Toast.LENGTH_SHORT).show()
                return
            }
            if (replaced.filePath != newItem.filePath && !parkingStorage.isPhotoReferenced(replaced.filePath)) {
                photoManager.deletePhotoFile(replaced.filePath)
            }
        } else {
            val old = photos.getOrNull(targetIndex)
            if (targetIndex in photos.indices) {
                photos[targetIndex] = newItem
            } else {
                photos.add(newItem)
            }
            if (old != null && old.filePath != newItem.filePath && !parkingStorage.isPhotoReferenced(old.filePath)) {
                photoManager.deletePhotoFile(old.filePath)
            }
            initialPhotosJson = ParkingPhotoItem.toJsonString(photos)
        }
        hasChanges = true
        syncResultIntent()
        Toast.makeText(this, R.string.photo_replaced_confirmation, Toast.LENGTH_SHORT).show()
        if (!loadPhotos(targetIndex)) {
            finish()
        }
    }

    private fun confirmRemoveCurrentPhoto() {
        if (!isEditable || photos.isEmpty()) return
        val targetIndex = currentIndex
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_single_photo_title)
            .setMessage(R.string.remove_single_photo_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ ->
                if (recordId.isNotEmpty()) {
                    val removed = parkingStorage.removePhotoFromRecord(recordId, targetIndex)
                    if (removed != null && !parkingStorage.isPhotoReferenced(removed.filePath)) {
                        photoManager.deletePhotoFile(removed.filePath)
                    }
                } else {
                    val removed = if (targetIndex in photos.indices) photos.removeAt(targetIndex) else null
                    if (removed != null && !parkingStorage.isPhotoReferenced(removed.filePath)) {
                        photoManager.deletePhotoFile(removed.filePath)
                    }
                    initialPhotosJson = ParkingPhotoItem.toJsonString(photos)
                }
                hasChanges = true
                syncResultIntent()
                Toast.makeText(this, R.string.photo_removed_confirmation, Toast.LENGTH_SHORT).show()
                if (!loadPhotos(targetIndex)) {
                    finish()
                }
            }
            .show()
    }

    private fun setBusy(busy: Boolean) {
        binding.progressViewer.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnViewerReplace.isEnabled = !busy
        binding.btnViewerRemove.isEnabled = !busy
    }

    private fun syncResultIntent() {
        val data = Intent()
        if (recordId.isEmpty()) {
            data.putExtra(EXTRA_UPDATED_PHOTOS_JSON, ParkingPhotoItem.toJsonString(photos))
        }
        setResult(RESULT_OK, data)
    }

    override fun finish() {
        if (hasChanges) {
            syncResultIntent()
        }
        super.finish()
    }

    override fun onDestroy() {
        photoManager.shutdown()
        super.onDestroy()
    }

    private inner class PhotoPageAdapter : RecyclerView.Adapter<PhotoPageAdapter.PageViewHolder>() {

        private var items: List<ParkingPhotoItem> = emptyList()

        fun submitList(newItems: List<ParkingPhotoItem>) {
            items = newItems.toList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val pageBinding = ItemPhotoViewerPageBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return PageViewHolder(pageBinding)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class PageViewHolder(
            private val pageBinding: ItemPhotoViewerPageBinding
        ) : RecyclerView.ViewHolder(pageBinding.root) {

            fun bind(item: ParkingPhotoItem) {
                pageBinding.ivViewerPageImage.tag = item.filePath
                pageBinding.ivViewerPageImage.setImageDrawable(null)
                pageBinding.tvViewerPageMissing.visibility = View.GONE

                photoManager.loadSampledBitmap(
                    item.filePath,
                    ParkingUiHelper.PREVIEW_REQ_SIZE,
                    ParkingUiHelper.PREVIEW_REQ_SIZE
                ) { bitmap ->
                    if (pageBinding.ivViewerPageImage.tag != item.filePath) return@loadSampledBitmap
                    if (bitmap != null) {
                        pageBinding.ivViewerPageImage.setImageBitmap(bitmap)
                        pageBinding.tvViewerPageMissing.visibility = View.GONE
                    } else {
                        pageBinding.ivViewerPageImage.setImageDrawable(null)
                        pageBinding.tvViewerPageMissing.visibility = View.VISIBLE
                    }
                }
            }
        }
    }
}
