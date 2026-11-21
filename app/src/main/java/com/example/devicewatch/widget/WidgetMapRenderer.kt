package com.example.devicewatch.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import androidx.core.content.res.ResourcesCompat
import com.example.devicewatch.ParkingUiHelper
import com.example.devicewatch.R
import com.example.devicewatch.location.DistanceCalculator
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.utils.BitmapUtils
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * High-speed MapLibre OpenFreeMap Liberty snapshot renderer with multi-entry LRU disk cache,
 * dashed walking-path overlay between user GPS and parked vehicle, and zero-flash fallback.
 */
object WidgetMapRenderer {

    private const val MINI_LOGICAL_DP = 132
    private const val MINI_PIXEL_RATIO = 1.25f // 165x165 px
    private const val EXPANDED_LOGICAL_W = 280
    private const val EXPANDED_LOGICAL_H = 148
    private const val EXPANDED_PIXEL_RATIO = 1.25f // 350x185 px
    private const val TILE_SIZE_PX = 256
    private const val CORNER_RADIUS_PX = 12f
    private const val MAX_CACHED_MAP_FILES = 10
    private const val CACHE_SUBDIR = "widget_maps"
    private const val CONNECT_TIMEOUT_MS = 3500
    private const val READ_TIMEOUT_MS = 4000

    private val mapExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeSnapshotters = ConcurrentHashMap<String, MapSnapshotter>()
    private val inFlightCallbacks = ConcurrentHashMap<String, CopyOnWriteArrayList<() -> Unit>>()

    data class MapRenderRequest(
        val parkedLat: Double,
        val parkedLng: Double,
        val centerLat: Double,
        val centerLng: Double,
        val userLat: Double,
        val userLng: Double,
        val zoom: Double,
        val isExpanded: Boolean,
        val isDark: Boolean
    ) {
        val cacheName: String get() = if (isExpanded) "full" else "mini"
        val cacheKey: String
            get() = String.format(
                Locale.US,
                "%s_%.5f_%.5f_%.5f_%.5f_%.4f_%.4f_%.1f",
                cacheName,
                parkedLat, parkedLng,
                centerLat, centerLng,
                if (userLat.isNaN()) 999.0 else userLat,
                if (userLng.isNaN()) 999.0 else userLng,
                zoom
            )
        val fileName: String get() = "${cacheName}_${cacheKey.hashCode().toUInt().toString(16)}.png"
    }

    fun getCachedBitmap(context: Context, req: MapRenderRequest): Bitmap? {
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        val file = File(dir, req.fileName)
        if (!file.exists()) return null
        return try {
            file.setLastModified(System.currentTimeMillis())
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns the most recent cached Liberty map for the current parked spot (even at another zoom
     * or user position) so UI transitions stay smooth without flashing a grid placeholder.
     */
    fun getPreviousSpotBitmapIfAvailable(context: Context, req: MapRenderRequest): Bitmap? {
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        val pointerKey = File(dir, "latest_${req.cacheName}.key")
        val pointerImg = File(dir, "latest_${req.cacheName}.path")
        if (!pointerKey.exists() || !pointerImg.exists()) return null
        return try {
            val parkedPrefix = String.format(Locale.US, "%s_%.5f_%.5f_", req.cacheName, req.parkedLat, req.parkedLng)
            if (!pointerKey.readText().trim().startsWith(parkedPrefix)) return null
            val targetFile = File(pointerImg.readText().trim())
            if (targetFile.exists()) BitmapFactory.decodeFile(targetFile.absolutePath) else null
        } catch (_: Exception) {
            null
        }
    }

    fun renderFallbackBitmap(context: Context, req: MapRenderRequest): Bitmap {
        val w = if (req.isExpanded) (EXPANDED_LOGICAL_W * EXPANDED_PIXEL_RATIO).toInt() else (MINI_LOGICAL_DP * MINI_PIXEL_RATIO).toInt()
        val h = if (req.isExpanded) (EXPANDED_LOGICAL_H * EXPANDED_PIXEL_RATIO).toInt() else (MINI_LOGICAL_DP * MINI_PIXEL_RATIO).toInt()

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val clipPath = buildRoundedClipPath(w, h)
        canvas.save()
        canvas.clipPath(clipPath)

        // OpenFreeMap Liberty daylight parchment base avoids dark/bright glitches
        canvas.drawColor(Color.parseColor("#EFECE6"))

        val minorRoad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        val majorRoad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FCE2A6")
            strokeWidth = 7f
            style = Paint.Style.STROKE
        }

        val step = if (req.zoom >= 17.0) 52f else if (req.zoom <= 14.0) 30f else 42f
        val offX = (((req.centerLng * 1000.0) % step).toFloat() + step) % step
        val offY = (((req.centerLat * 1000.0) % step).toFloat() + step) % step

        var x = -step + offX
        while (x < w + step) {
            canvas.drawLine(x, 0f, x - 14f, h.toFloat(), minorRoad)
            x += step
        }
        var y = -step + offY
        while (y < h + step) {
            canvas.drawLine(0f, y, w.toFloat(), y + 10f, minorRoad)
            y += step
        }
        canvas.drawLine(0f, h * 0.44f, w.toFloat(), h * 0.56f, majorRoad)
        canvas.drawLine(w * 0.56f, 0f, w * 0.46f, h.toFloat(), majorRoad)

        val parkedPt = projectLatLngApprox(req.parkedLat, req.parkedLng, req.centerLat, req.centerLng, req.zoom, w, h)
        val hasUser = DistanceCalculator.isValidCoordinate(req.userLat, req.userLng)
        val userPt = if (hasUser) {
            projectLatLngApprox(req.userLat, req.userLng, req.centerLat, req.centerLng, req.zoom, w, h)
        } else {
            null
        }
        if (userPt != null) {
            drawWalkingPathLine(canvas, userPt.first, userPt.second, parkedPt.first, parkedPt.second)
        }
        drawParkedPin(context, canvas, parkedPt.first, parkedPt.second)
        if (userPt != null) {
            drawUserLocationPuck(canvas, userPt.first, userPt.second)
        }

        canvas.restore()
        drawBorder(canvas, w, h, req.isDark)
        return bitmap
    }

    fun renderMapAsync(
        context: Context,
        req: MapRenderRequest,
        onReady: () -> Unit
    ) {
        val appCtx = context.applicationContext
        val fullKey = req.cacheKey
        if (getCachedBitmap(appCtx, req) != null) {
            onReady()
            return
        }

        val list = inFlightCallbacks.getOrPut(fullKey) { CopyOnWriteArrayList() }
        val isFirstCaller = list.isEmpty()
        list.add(onReady)
        if (!isFirstCaller) return

        mainHandler.post {
            try {
                MapLibre.getInstance(appCtx)
                val logicalW = if (req.isExpanded) EXPANDED_LOGICAL_W else MINI_LOGICAL_DP
                val logicalH = if (req.isExpanded) EXPANDED_LOGICAL_H else MINI_LOGICAL_DP
                val ratio = if (req.isExpanded) EXPANDED_PIXEL_RATIO else MINI_PIXEL_RATIO

                val camera = CameraPosition.Builder()
                    .target(LatLng(req.centerLat, req.centerLng))
                    .zoom(req.zoom.coerceIn(11.0, 19.0))
                    .build()

                val options = MapSnapshotter.Options(logicalW, logicalH)
                    .withStyle(ParkingUiHelper.MAP_STYLE_URL)
                    .withCameraPosition(camera)
                    .withPixelRatio(ratio)
                    .withLogo(false)

                activeSnapshotters.remove(req.cacheName)?.cancel()
                val snapshotter = MapSnapshotter(appCtx, options)
                activeSnapshotters[req.cacheName] = snapshotter

                snapshotter.start(
                    object : MapSnapshotter.SnapshotReadyCallback {
                        override fun onSnapshotReady(snapshot: MapSnapshot) {
                            activeSnapshotters.remove(req.cacheName, snapshotter)
                            val styledBitmap = compositePinsAndRoundCorners(appCtx, snapshot, req)
                            mapExecutor.execute {
                                try {
                                    writeBitmapToCache(appCtx, req, styledBitmap)
                                } finally {
                                    styledBitmap.recycle()
                                    notifyAndClearCallbacks(fullKey)
                                }
                            }
                        }
                    },
                    object : MapSnapshotter.ErrorHandler {
                        override fun onError(error: String) {
                            activeSnapshotters.remove(req.cacheName, snapshotter)
                            renderOsmRasterFallbackAsync(appCtx, req, fullKey)
                        }
                    }
                )
            } catch (_: Throwable) {
                renderOsmRasterFallbackAsync(appCtx, req, fullKey)
            }
        }
    }

    private fun renderOsmRasterFallbackAsync(
        appCtx: Context,
        req: MapRenderRequest,
        fullKey: String
    ) {
        mapExecutor.execute {
            try {
                val bmp = renderOsmTilesBitmap(appCtx, req)
                if (bmp != null) {
                    writeBitmapToCache(appCtx, req, bmp)
                    bmp.recycle()
                }
            } finally {
                notifyAndClearCallbacks(fullKey)
            }
        }
    }

    private fun notifyAndClearCallbacks(fullKey: String) {
        val callbacks = inFlightCallbacks.remove(fullKey) ?: return
        callbacks.forEach { cb ->
            try {
                cb()
            } catch (_: Exception) {
            }
        }
    }

    private fun compositePinsAndRoundCorners(
        context: Context,
        snapshot: MapSnapshot,
        req: MapRenderRequest
    ): Bitmap {
        val src = snapshot.bitmap
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val clipPath = buildRoundedClipPath(w, h)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(src, 0f, 0f, null)

        val parkedPt = snapshot.pixelForLatLng(LatLng(req.parkedLat, req.parkedLng))
        val hasUser = DistanceCalculator.isValidCoordinate(req.userLat, req.userLng)
        val userPt = if (hasUser) snapshot.pixelForLatLng(LatLng(req.userLat, req.userLng)) else null

        if (userPt != null) {
            drawWalkingPathLine(canvas, userPt.x, userPt.y, parkedPt.x, parkedPt.y)
        }
        drawParkedPin(context, canvas, parkedPt.x, parkedPt.y)
        if (userPt != null) {
            drawUserLocationPuck(canvas, userPt.x, userPt.y)
        }

        canvas.restore()
        drawBorder(canvas, w, h, req.isDark)
        return out
    }

    private fun drawWalkingPathLine(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ) {
        val dx = x2 - x1
        val dy = y2 - y1
        if (dx * dx + dy * dy < 16f) return

        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val dash = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 210
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
            pathEffect = dash
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#146C2E")
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
            pathEffect = dash
        }
        canvas.drawPath(path, haloPaint)
        canvas.drawPath(path, linePaint)
    }

    private fun drawParkedPin(context: Context, canvas: Canvas, cx: Float, cy: Float) {
        val w = canvas.width
        val h = canvas.height
        if (cx < -32f || cx > w + 32f || cy < -32f || cy > h + 32f) return

        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#146C2E")
            alpha = 40
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, 14f, haloPaint)

        val drawable = ResourcesCompat.getDrawable(context.resources, R.drawable.ic_parking_pin, context.theme)
        val pinBmp = drawable?.let { BitmapUtils.getBitmapFromDrawable(it) }
        if (pinBmp != null) {
            val targetSize = (if (w > 240) 40f else 34f).roundToInt()
            val scaled = Bitmap.createScaledBitmap(pinBmp, targetSize, targetSize, true)
            canvas.drawBitmap(scaled, cx - targetSize / 2f, cy - targetSize.toFloat(), null)
            scaled.recycle()
            if (scaled != pinBmp) pinBmp.recycle()
            return
        }

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1B5E20")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, 11f, dotPaint)
    }

    private fun drawUserLocationPuck(canvas: Canvas, cx: Float, cy: Float) {
        val w = canvas.width
        val h = canvas.height
        if (cx < -24f || cx > w + 24f || cy < -24f || cy > h + 24f) return

        val blue = Color.parseColor("#1A73E8")
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = blue
            alpha = 48
            style = Paint.Style.FILL
        }
        val haloRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = blue
            alpha = 95
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(4f, 0f, 2f, Color.argb(90, 0, 0, 0))
        }
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = blue
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, 22f, haloPaint)
        canvas.drawCircle(cx, cy, 22f, haloRingPaint)
        canvas.drawCircle(cx, cy, 11f, ringPaint)
        canvas.drawCircle(cx, cy, 7.5f, corePaint)
    }

    private fun renderOsmTilesBitmap(context: Context, req: MapRenderRequest): Bitmap? {
        val z = req.zoom.roundToInt().coerceIn(11, 18)
        val w = if (req.isExpanded) (EXPANDED_LOGICAL_W * EXPANDED_PIXEL_RATIO).toInt() else (MINI_LOGICAL_DP * MINI_PIXEL_RATIO).toInt()
        val h = if (req.isExpanded) (EXPANDED_LOGICAL_H * EXPANDED_PIXEL_RATIO).toInt() else (MINI_LOGICAL_DP * MINI_PIXEL_RATIO).toInt()

        val n = 1 shl z
        val latRad = Math.toRadians(req.centerLat.coerceIn(-85.0, 85.0))
        val exactTileX = (req.centerLng + 180.0) / 360.0 * n
        val exactTileY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n

        val centerTileX = floor(exactTileX).toInt()
        val centerTileY = floor(exactTileY).toInt()
        val pxInTileX = ((exactTileX - centerTileX) * TILE_SIZE_PX).toFloat()
        val pxInTileY = ((exactTileY - centerTileY) * TILE_SIZE_PX).toFloat()

        val startDx = -1
        val endDx = 1
        val startDy = if (pxInTileY < h / 2f) -1 else 0
        val endDy = startDy + 1

        val composite = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composite)
        val clipPath = buildRoundedClipPath(w, h)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawColor(Color.parseColor("#EFECE6"))

        val originX = w / 2f - (pxInTileX - startDx * TILE_SIZE_PX)
        val originY = h / 2f - (pxInTileY - startDy * TILE_SIZE_PX)

        var anyTile = false
        for (dy in startDy..endDy) {
            for (dx in startDx..endDx) {
                val tx = ((centerTileX + dx) % n + n) % n
                val ty = (centerTileY + dy).coerceIn(0, n - 1)
                val tile = downloadOsmTile(z, tx, ty)
                if (tile != null) {
                    anyTile = true
                    canvas.drawBitmap(
                        tile,
                        originX + (dx - startDx) * TILE_SIZE_PX,
                        originY + (dy - startDy) * TILE_SIZE_PX,
                        null
                    )
                    tile.recycle()
                }
            }
        }

        if (!anyTile) {
            composite.recycle()
            return null
        }

        val parkedPt = projectLatLngApprox(req.parkedLat, req.parkedLng, req.centerLat, req.centerLng, z.toDouble(), w, h)
        val hasUser = DistanceCalculator.isValidCoordinate(req.userLat, req.userLng)
        val userPt = if (hasUser) projectLatLngApprox(req.userLat, req.userLng, req.centerLat, req.centerLng, z.toDouble(), w, h) else null

        if (userPt != null) {
            drawWalkingPathLine(canvas, userPt.first, userPt.second, parkedPt.first, parkedPt.second)
        }
        drawParkedPin(context, canvas, parkedPt.first, parkedPt.second)
        if (userPt != null) {
            drawUserLocationPuck(canvas, userPt.first, userPt.second)
        }

        canvas.restore()
        drawBorder(canvas, w, h, req.isDark)
        return composite
    }

    private fun downloadOsmTile(z: Int, x: Int, y: Int): Bitmap? {
        val rawUrl = "https://tile.openstreetmap.org/$z/$x/$y.png"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "ParkingSpotSaver-Android/1.0")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun projectLatLngApprox(
        lat: Double,
        lng: Double,
        centerLat: Double,
        centerLng: Double,
        zoom: Double,
        w: Int,
        h: Int
    ): Pair<Float, Float> {
        val scale = TILE_SIZE_PX * Math.pow(2.0, zoom)
        fun projX(lon: Double) = (lon + 180.0) / 360.0 * scale
        fun projY(la: Double): Double {
            val rad = Math.toRadians(la.coerceIn(-85.0, 85.0))
            return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * scale
        }
        val dx = (projX(lng) - projX(centerLng)).toFloat()
        val dy = (projY(lat) - projY(centerLat)).toFloat()
        return (w / 2f + dx) to (h / 2f + dy)
    }

    private fun writeBitmapToCache(appCtx: Context, req: MapRenderRequest, bitmap: Bitmap) {
        try {
            val dir = File(appCtx.cacheDir, CACHE_SUBDIR).apply { if (!exists()) mkdirs() }
            val dest = File(dir, req.fileName)
            val temp = File(dir, "${req.fileName}.tmp")
            FileOutputStream(temp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)
            }
            if (dest.exists()) dest.delete()
            temp.renameTo(dest)

            File(dir, "latest_${req.cacheName}.key").writeText(req.cacheKey)
            File(dir, "latest_${req.cacheName}.path").writeText(dest.absolutePath)

            // Trim old LRU PNG cache entries beyond MAX_CACHED_MAP_FILES
            val cachedPngs = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }
            if (cachedPngs != null && cachedPngs.size > MAX_CACHED_MAP_FILES) {
                cachedPngs.sortedBy { it.lastModified() }
                    .take(cachedPngs.size - MAX_CACHED_MAP_FILES)
                    .forEach { it.delete() }
            }
        } catch (_: Exception) {
        }
    }

    private fun buildRoundedClipPath(w: Int, h: Int): Path {
        return Path().apply {
            addRoundRect(
                RectF(0f, 0f, w.toFloat(), h.toFloat()),
                CORNER_RADIUS_PX,
                CORNER_RADIUS_PX,
                Path.Direction.CW
            )
        }
    }

    private fun drawBorder(canvas: Canvas, w: Int, h: Int, isDark: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDark) Color.parseColor("#2E373E") else Color.parseColor("#D5E0D8")
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        canvas.drawRoundRect(
            RectF(1.5f, 1.5f, w - 1.5f, h - 1.5f),
            CORNER_RADIUS_PX,
            CORNER_RADIUS_PX,
            paint
        )
    }
}
