package com.example.devicewatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.devicewatch.databinding.ActivityQrScannerBinding
import com.example.devicewatch.storage.ParkingStorage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fullscreen CameraX QR scanner supporting live camera scanning, auto-flash preference,
 * and picking a QR image directly from the device Gallery/Albums.
 */
class QrScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QR_RAW_TEXT = "extra_qr_raw_text"
        private const val MAX_GALLERY_DECODE_DIM = 1600
    }

    private lateinit var binding: ActivityQrScannerBinding
    private lateinit var analysisExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var autoFlashEnabled = true
    private var torchEnabled = false
    private var initialTorchApplied = false
    private val hasDeliveredResult = AtomicBoolean(false)

    private val qrReader: MultiFormatReader by lazy {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                )
            )
        }
    }

    private val pickGalleryQrLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            decodeQrFromUriAsync(uri)
        }
    }

    private val fallbackGalleryQrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = ParkingUiHelper.extractPickedImageUris(result.data).firstOrNull() ?: return@registerForActivityResult
        decodeQrFromUriAsync(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        autoFlashEnabled = ParkingStorage(this).isQrAutoFlashEnabled()
        analysisExecutor = Executors.newSingleThreadExecutor()

        binding.btnCloseQrScanner.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        binding.btnToggleTorch.setOnClickListener { toggleTorch() }
        binding.btnScanQrFromGallery.setOnClickListener { openGalleryQrPicker() }

        startCameraIfPermitted()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.qrScannerRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.setPadding(
                binding.topBar.paddingLeft,
                10 + bars.top,
                binding.topBar.paddingRight,
                10
            )
            binding.bottomBar.setPadding(
                binding.bottomBar.paddingLeft,
                16,
                binding.bottomBar.paddingRight,
                20 + bars.bottom
            )
            insets
        }
    }

    private fun startCameraIfPermitted() {
        val hasCameraFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraFeature || !hasPermission) {
            showGalleryOnlyFallbackUi()
            return
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                bindUseCases(provider)
            } catch (_: Exception) {
                showGalleryOnlyFallbackUi()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showGalleryOnlyFallbackUi() {
        binding.qrReticle.visibility = View.GONE
        binding.btnToggleTorch.visibility = View.INVISIBLE
        binding.tvQrHint.setText(R.string.qr_camera_unavailable_gallery_hint)
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val selector = when {
            hasCameraSafely(provider, CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            hasCameraSafely(provider, CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> {
                showGalleryOnlyFallbackUi()
                return
            }
        }

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = binding.previewView.surfaceProvider
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
            decodeQrFrame(imageProxy)
        }

        provider.unbindAll()
        val camera = provider.bindToLifecycle(this, selector, preview, analysis)
        boundCamera = camera
        val hasFlash = camera.cameraInfo.hasFlashUnit()
        binding.btnToggleTorch.visibility = if (hasFlash) View.VISIBLE else View.INVISIBLE
        if (hasFlash && !initialTorchApplied) {
            initialTorchApplied = true
            if (autoFlashEnabled) {
                torchEnabled = true
                camera.cameraControl.enableTorch(true)
            }
        } else if (hasFlash && torchEnabled) {
            camera.cameraControl.enableTorch(true)
        }
        updateTorchIcon()
    }

    private fun openGalleryQrPicker() {
        try {
            pickGalleryQrLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (_: Exception) {
            try {
                fallbackGalleryQrLauncher.launch(
                    ParkingUiHelper.buildFallbackGalleryIntent(allowMultiple = false)
                )
            } catch (_: Exception) {
                Toast.makeText(this, R.string.photo_picker_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun decodeQrFromUriAsync(uri: Uri) {
        analysisExecutor.execute {
            val bitmap = decodeSampledBitmapFromUri(uri)
            if (bitmap == null) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, R.string.qr_gallery_read_error, Toast.LENGTH_SHORT).show()
                    }
                }
                return@execute
            }

            val decodedText = try {
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                val source = RGBLuminanceSource(width, height, pixels)
                decodeQrSourceWithInvertedFallback(source)
            } catch (_: Exception) {
                null
            } finally {
                bitmap.recycle()
            }

            if (!decodedText.isNullOrEmpty()) {
                deliverQrResult(decodedText)
            } else {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, R.string.qr_gallery_no_code, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun decodeSampledBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var inSampleSize = 1
            while ((bounds.outWidth / inSampleSize) > MAX_GALLERY_DECODE_DIM ||
                (bounds.outHeight / inSampleSize) > MAX_GALLERY_DECODE_DIM
            ) {
                inSampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeQrFrame(imageProxy: ImageProxy) {
        try {
            if (hasDeliveredResult.get()) return
            val plane = imageProxy.planes.firstOrNull() ?: return
            val width = imageProxy.width
            val height = imageProxy.height
            if (width <= 0 || height <= 0) return

            val yBytes = extractLuminanceBytes(plane.buffer, plane.rowStride, width, height)
            val source = PlanarYUVLuminanceSource(
                yBytes,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )
            val text = decodeQrSourceWithInvertedFallback(source)
            if (!text.isNullOrEmpty()) {
                deliverQrResult(text)
            }
        } catch (_: Exception) {
            // Skip malformed frame silently and wait for next camera frame.
        } finally {
            imageProxy.close()
        }
    }

    private fun decodeQrSourceWithInvertedFallback(source: LuminanceSource): String? {
        tryDecodeBitmap(BinaryBitmap(HybridBinarizer(source)))?.let { return it }
        return tryDecodeBitmap(BinaryBitmap(HybridBinarizer(source.invert())))
    }

    private fun tryDecodeBitmap(bitmap: BinaryBitmap): String? {
        return try {
            qrReader.decodeWithState(bitmap)?.text?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: NotFoundException) {
            null
        } finally {
            qrReader.reset()
        }
    }

    private fun deliverQrResult(text: String) {
        if (!hasDeliveredResult.compareAndSet(false, true)) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val out = Intent().putExtra(EXTRA_QR_RAW_TEXT, text)
            setResult(RESULT_OK, out)
            finish()
        }
    }

    private fun extractLuminanceBytes(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int
    ): ByteArray {
        val dup = buffer.duplicate().apply { rewind() }
        val yBytes = ByteArray(width * height)
        if (rowStride == width && dup.remaining() >= yBytes.size) {
            dup.get(yBytes)
            return yBytes
        }
        val rowBuffer = ByteArray(rowStride)
        for (row in 0 until height) {
            val remaining = dup.remaining()
            if (remaining <= 0) break
            val toRead = minOf(rowStride, remaining)
            dup.get(rowBuffer, 0, toRead)
            System.arraycopy(rowBuffer, 0, yBytes, row * width, minOf(width, toRead))
        }
        return yBytes
    }

    private fun toggleTorch() {
        val camera = boundCamera ?: return
        if (!camera.cameraInfo.hasFlashUnit()) return
        torchEnabled = !torchEnabled
        camera.cameraControl.enableTorch(torchEnabled)
        updateTorchIcon()
    }

    private fun updateTorchIcon() {
        binding.btnToggleTorch.setImageResource(
            if (torchEnabled) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        )
    }

    private fun hasCameraSafely(
        provider: ProcessCameraProvider,
        selector: CameraSelector
    ): Boolean {
        return try {
            provider.hasCamera(selector)
        } catch (_: Exception) {
            false
        }
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
