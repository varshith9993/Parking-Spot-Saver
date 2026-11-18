package com.example.devicewatch

import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PREVIOUS_PHOTO_PATH = "extra_previous_photo_path"
        const val EXTRA_PHOTO_PATH = "extra_photo_path"
        const val EXTRA_CAPTURED_AT = "extra_captured_at"
        const val EXTRA_PHOTO_LAT = "extra_photo_lat"
        const val EXTRA_PHOTO_LNG = "extra_photo_lng"
    }

    private lateinit var binding: ActivityCameraCaptureBinding
    private lateinit var locationManager: ParkingLocationManager
    private lateinit var photoManager: ParkingPhotoManager
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var captureLocation: Location? = null
    private var isCapturing = false
    private var captureCompleted = false
    private var activeOutputFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCameraCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        locationManager = ParkingLocationManager(this)
        photoManager = ParkingPhotoManager(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnCloseCamera.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        binding.btnCapturePhoto.setOnClickListener { capturePhoto() }

        fetchBestCaptureLocation()
        startCamera()
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
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
            } catch (_: Exception) {
                Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        if (isCapturing) return
        val capture = imageCapture ?: return

        val outputFile: File = try {
            photoManager.createPersistentPhotoFile()
        } catch (_: IOException) {
            Toast.makeText(this, R.string.camera_capture_failed, Toast.LENGTH_SHORT).show()
            return
        }

        isCapturing = true
        activeOutputFile = outputFile
        binding.btnCapturePhoto.isEnabled = false
        val captureTimeMillis = System.currentTimeMillis()
        val metadata = ImageCapture.Metadata().apply {
            location = captureLocation
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile)
            .setMetadata(metadata)
            .build()

        val previousPhotoPath = intent.getStringExtra(EXTRA_PREVIOUS_PHOTO_PATH)

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    photoManager.finalizeCapturedPhoto(
                        outputFile,
                        captureTimeMillis,
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
                                isCapturing = false
                                binding.btnCapturePhoto.isEnabled = true
                                Toast.makeText(this@CameraCaptureActivity, message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    if (outputFile.exists()) {
                        outputFile.delete()
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        isCapturing = false
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

    override fun onDestroy() {
        if (!captureCompleted) {
            activeOutputFile?.let { file ->
                if (file.exists()) {
                    file.delete()
                }
            }
        }
        locationManager.cancelCurrentLocationRequest()
        photoManager.shutdown()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
