package com.example.devicewatch

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fullscreen CameraX QR scanner returning the first decoded QR payload to the caller.
 */
class QrScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QR_RAW_TEXT = "extra_qr_raw_text"
    }

    private lateinit var binding: ActivityQrScannerBinding
    private lateinit var analysisExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var torchEnabled = false
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        analysisExecutor = Executors.newSingleThreadExecutor()

        binding.btnCloseQrScanner.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        binding.btnToggleTorch.setOnClickListener { toggleTorch() }

        startCamera()
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
            insets
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                bindUseCases(provider)
            } catch (_: Exception) {
                Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val selector = when {
            hasCameraSafely(provider, CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            hasCameraSafely(provider, CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> {
                Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
                setResult(RESULT_CANCELED)
                finish()
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
        updateTorchIcon()
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
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = try {
                qrReader.decodeWithState(bitmap)
            } catch (_: NotFoundException) {
                null
            } finally {
                qrReader.reset()
            }

            val text = result?.text?.trim()
            if (!text.isNullOrEmpty() && hasDeliveredResult.compareAndSet(false, true)) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val out = Intent().putExtra(EXTRA_QR_RAW_TEXT, text)
                    setResult(RESULT_OK, out)
                    finish()
                }
            }
        } catch (_: Exception) {
            // Skip malformed frame silently and wait for next camera frame.
        } finally {
            imageProxy.close()
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
