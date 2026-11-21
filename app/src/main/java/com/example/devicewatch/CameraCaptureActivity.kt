package com.example.devicewatch

import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.devicewatch.databinding.ActivityCameraCaptureBinding
import com.example.devicewatch.location.ParkingLocationManager
import com.example.devicewatch.storage.ParkingPhotoManager
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PREVIOUS_PHOTO_PATH = "extra_previous_photo_path"
        const val EXTRA_PHOTO_PATH = "extra_photo_path"
        const val EXTRA_CAPTURED_AT = "extra_captured_at"
        const val EXTRA_PHOTO_LAT = "extra_photo_lat"
        const val EXTRA_PHOTO_LNG = "extra_photo_lng"
        private const val ZOOM_STEP = 0.5f
    }

    private lateinit var binding: ActivityCameraCaptureBinding
    private lateinit var locationManager: ParkingLocationManager
    private lateinit var photoManager: ParkingPhotoManager
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var currentZoomRatio: Float = 1.0f
    private var minZoomRatio: Float = 1.0f
    private var maxZoomRatio: Float = 1.0f

    private var captureLocation: Location? = null
    private var isCapturing = false
    private var isFinalizing = false
    private var captureCompleted = false
    private var pendingOutputFile: File? = null
    private var pendingCaptureTimeMillis: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCameraCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        locationManager = ParkingLocationManager(this)
        photoManager = ParkingPhotoManager(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupControls()
        setupPinchToZoom()
        setupBackHandling()

        fetchBestCaptureLocation()
        startCamera()
    }

    private fun setupControls() {
        binding.btnCloseCamera.setOnClickListener {
            deletePendingFileQuietly()
            setResult(RESULT_CANCELED)
            finish()
        }
        binding.btnCapturePhoto.setOnClickListener { capturePhoto() }
        binding.btnSwitchCamera.setOnClickListener { switchCameraLens() }
        binding.btnCameraZoomIn.setOnClickListener { adjustZoomBy(ZOOM_STEP) }
        binding.btnCameraZoomOut.setOnClickListener { adjustZoomBy(-ZOOM_STEP) }
        binding.btnCameraZoomLevel.setOnClickListener { applyZoomRatio(1.0f) }

        binding.btnRetakePhoto.setOnClickListener { discardPreviewAndRetake() }
        binding.btnConfirmPhoto.setOnClickListener { confirmPendingPhoto() }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.previewConfirmOverlay.visibility == View.VISIBLE && !isFinalizing) {
                    discardPreviewAndRetake()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPinchToZoom() {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val camera = boundCamera ?: return false
                    val zoomState = camera.cameraInfo.zoomState.value ?: return false
                    val nextRatio = (zoomState.zoomRatio * detector.scaleFactor)
                        .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                    camera.cameraControl.setZoomRatio(nextRatio)
                    return true
                }
            }
        )
        binding.previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            true
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.cameraRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.setPadding(
                binding.topBar.paddingLeft,
                12 + bars.top,
                binding.topBar.paddingRight,
                12
            )
            binding.bottomBar.setPadding(
                binding.bottomBar.paddingLeft,
                20,
                binding.bottomBar.paddingRight,
                20 + bars.bottom
            )
            binding.previewConfirmBottomBar.setPadding(
                binding.previewConfirmBottomBar.paddingLeft,
                18,
                binding.previewConfirmBottomBar.paddingRight,
                18 + bars.bottom
            )
            insets
        }
    }

    private fun fetchBestCaptureLocation() {
        if (!locationManager.hasLocationPermission()) {
            binding.tvCameraGpsStatus.setText(R.string.camera_gps_unavailable)
            return
        }
        locationManager.getCurrentLocation(object : ParkingLocationManager.LocationResultCallback {
            override fun onLocationRetrieved(location: Location) {
                if (isFinishing || isDestroyed) return
                captureLocation = location
                binding.tvCameraGpsStatus.setText(R.string.camera_gps_ready)
            }

            override fun onLocationError(message: String) {
                if (isFinishing || isDestroyed) return
                binding.tvCameraGpsStatus.setText(R.string.camera_gps_unavailable)
            }
        })
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (_: Exception) {
                Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val hasBack = ParkingUiHelper.hasCameraSafely(provider, CameraSelector.DEFAULT_BACK_CAMERA)
        val hasFront = ParkingUiHelper.hasCameraSafely(provider, CameraSelector.DEFAULT_FRONT_CAMERA)

        if (lensFacing == CameraSelector.LENS_FACING_FRONT && !hasFront && hasBack) {
            lensFacing = CameraSelector.LENS_FACING_BACK
        } else if (lensFacing == CameraSelector.LENS_FACING_BACK && !hasBack && hasFront) {
            lensFacing = CameraSelector.LENS_FACING_FRONT
        }

        val canSwitch = hasBack && hasFront
        binding.btnSwitchCamera.isEnabled = canSwitch
        binding.btnSwitchCamera.alpha = if (canSwitch) 1.0f else 0.4f

        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = binding.previewView.surfaceProvider
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        imageCapture = capture

        provider.unbindAll()
        val camera = provider.bindToLifecycle(this, selector, preview, capture)
        boundCamera = camera
        camera.cameraControl.setZoomRatio(1.0f)
        camera.cameraInfo.zoomState.observe(this) { state ->
            if (state != null) {
                currentZoomRatio = state.zoomRatio
                minZoomRatio = state.minZoomRatio
                maxZoomRatio = state.maxZoomRatio
                updateZoomControlsUi()
            }
        }
    }

    private fun switchCameraLens() {
        val provider = cameraProvider ?: return
        val targetFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val targetSelector = CameraSelector.Builder().requireLensFacing(targetFacing).build()
        if (!ParkingUiHelper.hasCameraSafely(provider, targetSelector)) {
            Toast.makeText(this, R.string.camera_switch_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        lensFacing = targetFacing
        try {
            bindCameraUseCases()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun adjustZoomBy(delta: Float) {
        applyZoomRatio(currentZoomRatio + delta)
    }

    private fun applyZoomRatio(requested: Float) {
        val camera = boundCamera ?: return
        val clamped = requested.coerceIn(minZoomRatio, maxZoomRatio)
        camera.cameraControl.setZoomRatio(clamped)
    }

    private fun updateZoomControlsUi() {
        binding.btnCameraZoomLevel.text = String.format(Locale.US, "%.1fx", currentZoomRatio)
        binding.btnCameraZoomOut.isEnabled = currentZoomRatio > minZoomRatio + 0.05f
        binding.btnCameraZoomIn.isEnabled = currentZoomRatio < maxZoomRatio - 0.05f
        binding.btnCameraZoomOut.alpha = if (binding.btnCameraZoomOut.isEnabled) 1.0f else 0.45f
        binding.btnCameraZoomIn.alpha = if (binding.btnCameraZoomIn.isEnabled) 1.0f else 0.45f
    }

    private fun capturePhoto() {
        if (isCapturing || isFinalizing) return
        val capture = imageCapture ?: return

        val outputFile: File = try {
            photoManager.createPersistentPhotoFile()
        } catch (_: IOException) {
            Toast.makeText(this, R.string.camera_capture_failed, Toast.LENGTH_SHORT).show()
            return
        }

        deletePendingFileQuietly()
        isCapturing = true
        pendingOutputFile = outputFile
        pendingCaptureTimeMillis = System.currentTimeMillis()
        binding.btnCapturePhoto.isEnabled = false

        val metadata = ImageCapture.Metadata().apply {
            location = captureLocation
            isReversedHorizontal = (lensFacing == CameraSelector.LENS_FACING_FRONT)
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile)
            .setMetadata(metadata)
            .build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        isCapturing = false
                        binding.btnCapturePhoto.isEnabled = true
                        showCapturedPreview(outputFile)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (outputFile.exists()) {
                        outputFile.delete()
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        isCapturing = false
                        pendingOutputFile = null
                        binding.btnCapturePhoto.isEnabled = true
                        Toast.makeText(
                            this@CameraCaptureActivity,
                            exception.message ?: getString(R.string.camera_capture_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun showCapturedPreview(file: File) {
        val maxDim = resources.displayMetrics.heightPixels.coerceAtLeast(1200)
        photoManager.loadSampledBitmap(file.absolutePath, maxDim, maxDim) { bitmap ->
            if (isFinishing || isDestroyed) return@loadSampledBitmap
            if (bitmap == null) {
                deletePendingFileQuietly()
                Toast.makeText(this, R.string.camera_capture_failed, Toast.LENGTH_SHORT).show()
                return@loadSampledBitmap
            }
            binding.ivCapturedPreview.setImageBitmap(bitmap)
            binding.btnRetakePhoto.isEnabled = true
            binding.btnConfirmPhoto.isEnabled = true
            binding.previewConfirmOverlay.visibility = View.VISIBLE
        }
    }

    private fun discardPreviewAndRetake() {
        if (isFinalizing) return
        binding.previewConfirmOverlay.visibility = View.GONE
        binding.ivCapturedPreview.setImageDrawable(null)
        deletePendingFileQuietly()
    }

    private fun confirmPendingPhoto() {
        if (isFinalizing) return
        val file = pendingOutputFile ?: return
        isFinalizing = true
        binding.btnRetakePhoto.isEnabled = false
        binding.btnConfirmPhoto.isEnabled = false
        val previousPhotoPath = intent.getStringExtra(EXTRA_PREVIOUS_PHOTO_PATH)

        photoManager.finalizeCapturedPhoto(
            file,
            pendingCaptureTimeMillis,
            captureLocation,
            previousPhotoPath,
            object : ParkingPhotoManager.PhotoMetadataCallback {
                override fun onPhotoReady(metadata: ParkingPhotoManager.PhotoMetadata) {
                    if (isFinishing || isDestroyed) return
                    captureCompleted = true
                    val resultIntent = Intent().apply {
                        putExtra(EXTRA_PHOTO_PATH, metadata.internalFilePath)
                        putExtra(EXTRA_CAPTURED_AT, metadata.capturedAtMillis)
                        putExtra(EXTRA_PHOTO_LAT, metadata.latitude)
                        putExtra(EXTRA_PHOTO_LNG, metadata.longitude)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }

                override fun onPhotoError(message: String) {
                    if (isFinishing || isDestroyed) return
                    isFinalizing = false
                    binding.btnRetakePhoto.isEnabled = true
                    binding.btnConfirmPhoto.isEnabled = true
                    Toast.makeText(this@CameraCaptureActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun deletePendingFileQuietly() {
        val file = pendingOutputFile ?: return
        if (!captureCompleted && file.exists()) {
            file.delete()
        }
        pendingOutputFile = null
    }

    override fun onDestroy() {
        if (!captureCompleted) {
            deletePendingFileQuietly()
        }
        cameraProvider?.unbindAll()
        boundCamera = null
        imageCapture = null
        cameraProvider = null
        locationManager.cancelCurrentLocationRequest()
        photoManager.shutdown()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
