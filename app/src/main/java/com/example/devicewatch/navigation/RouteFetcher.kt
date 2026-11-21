package com.example.devicewatch.navigation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class RoutePoint(
    val latitude: Double,
    val longitude: Double
)

internal data class RouteResult(
    val points: List<RoutePoint>,
    val isRoadRoute: Boolean
)

/**
 * Fetches road/foot routes between user location and a parking spot from the public OSRM server
 * without requiring any API key, falling back cleanly to a straight guide line when offline.
 */
internal class RouteFetcher {

    companion object {
        private const val OSRM_BASE_URL = "https://router.project-osrm.org/route/v1"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val MIN_ROUTE_DISTANCE_METERS = 8.0
        private const val REQUERY_ORIGIN_DISTANCE_METERS = 20.0
        private const val DESTINATION_CHANGE_THRESHOLD_METERS = 5.0
        private const val MIN_REQUERY_INTERVAL_MS = 8_000L
        private const val ENDPOINT_SNAP_GAP_METERS = 2.5
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private val CODE_FIELD_REGEX = Regex(""""code"\s*:\s*"([^"]+)"""")

        internal fun resolveOsrmProfile(travelMode: String): String {
            return if (travelMode == NavigationManager.TRAVEL_DRIVING) "driving" else "foot"
        }

        internal fun isValidCoord(lat: Double, lng: Double): Boolean {
            return lat.isFinite() &&
                lng.isFinite() &&
                lat in -90.0..90.0 &&
                lng in -180.0..180.0
        }

        internal fun haversineMeters(
            lat1: Double,
            lng1: Double,
            lat2: Double,
            lng2: Double
        ): Double {
            if (!isValidCoord(lat1, lng1) || !isValidCoord(lat2, lng2)) return 0.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val rLat1 = Math.toRadians(lat1)
            val rLat2 = Math.toRadians(lat2)
            val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(rLat1) * cos(rLat2) * sin(dLng / 2.0) * sin(dLng / 2.0)
            val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
            return EARTH_RADIUS_METERS * c
        }

        internal fun buildOsrmUrl(
            originLat: Double,
            originLng: Double,
            destLat: Double,
            destLng: Double,
            travelMode: String
        ): String {
            return String.format(
                Locale.US,
                "%s/%s/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
                OSRM_BASE_URL,
                resolveOsrmProfile(travelMode),
                originLng,
                originLat,
                destLng,
                destLat
            )
        }

        internal fun parseOsrmGeoJsonCoordinates(json: String): List<RoutePoint> {
            if (json.isBlank()) return emptyList()
            val codeMatch = CODE_FIELD_REGEX.find(json)
            if (codeMatch != null && codeMatch.groupValues[1] != "Ok") {
                return emptyList()
            }

            val routesIdx = json.indexOf("\"routes\"")
            val geometrySearchStart = if (routesIdx >= 0) routesIdx else 0
            val geometryIdx = json.indexOf("\"geometry\"", geometrySearchStart)
            val coordSearchStart = if (geometryIdx >= 0) geometryIdx else geometrySearchStart
            val coordsKeyIdx = json.indexOf("\"coordinates\"", coordSearchStart)
            if (coordsKeyIdx < 0) return emptyList()

            val openBracketIdx = json.indexOf('[', coordsKeyIdx + "\"coordinates\"".length)
            if (openBracketIdx < 0) return emptyList()

            val points = ArrayList<RoutePoint>()
            val numBuf = StringBuilder(24)
            val tupleNums = ArrayList<Double>(3)
            var depth = 0
            var i = openBracketIdx

            while (i < json.length) {
                val ch = json[i]
                when (ch) {
                    '[' -> {
                        depth++
                        if (depth == 2) {
                            numBuf.setLength(0)
                            tupleNums.clear()
                        } else if (depth > 2) {
                            return emptyList()
                        }
                    }
                    ']' -> {
                        if (depth == 2) {
                            if (!flushCoordinateNumber(numBuf, tupleNums)) {
                                return emptyList()
                            }
                            if (tupleNums.size >= 2) {
                                val lng = tupleNums[0]
                                val lat = tupleNums[1]
                                if (isValidCoord(lat, lng)) {
                                    points.add(RoutePoint(latitude = lat, longitude = lng))
                                } else {
                                    return emptyList()
                                }
                            } else {
                                return emptyList()
                            }
                        }
                        depth--
                        if (depth == 0) {
                            return if (points.size >= 2) points else emptyList()
                        }
                    }
                    ',' -> {
                        if (depth == 2 && !flushCoordinateNumber(numBuf, tupleNums)) {
                            return emptyList()
                        }
                    }
                    else -> {
                        if (depth == 2) {
                            when {
                                ch.isWhitespace() -> Unit
                                isJsonNumberChar(ch) -> numBuf.append(ch)
                                else -> return emptyList()
                            }
                        }
                    }
                }
                i++
            }
            return emptyList()
        }

        private fun isJsonNumberChar(ch: Char): Boolean {
            return ch in '0'..'9' || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E'
        }

        private fun flushCoordinateNumber(
            numBuf: StringBuilder,
            outNumbers: MutableList<Double>
        ): Boolean {
            if (numBuf.isEmpty()) return true
            val parsed = numBuf.toString().toDoubleOrNull() ?: return false
            numBuf.setLength(0)
            outNumbers.add(parsed)
            return true
        }

        internal fun buildConnectedRoutePoints(
            originLat: Double,
            originLng: Double,
            destLat: Double,
            destLng: Double,
            roadPoints: List<RoutePoint>
        ): List<RoutePoint> {
            if (roadPoints.size < 2) return emptyList()
            val connected = ArrayList<RoutePoint>(roadPoints.size + 2)
            val first = roadPoints.first()
            if (haversineMeters(originLat, originLng, first.latitude, first.longitude) > ENDPOINT_SNAP_GAP_METERS) {
                connected.add(RoutePoint(latitude = originLat, longitude = originLng))
            }
            connected.addAll(roadPoints)
            val last = roadPoints.last()
            if (haversineMeters(last.latitude, last.longitude, destLat, destLng) > ENDPOINT_SNAP_GAP_METERS) {
                connected.add(RoutePoint(latitude = destLat, longitude = destLng))
            }
            return connected
        }

        internal fun fallbackStraightRoute(
            originLat: Double,
            originLng: Double,
            destLat: Double,
            destLng: Double
        ): RouteResult {
            return RouteResult(
                points = listOf(
                    RoutePoint(latitude = originLat, longitude = originLng),
                    RoutePoint(latitude = destLat, longitude = destLng)
                ),
                isRoadRoute = false
            )
        }
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestSeq = AtomicLong(0L)

    @Volatile
    private var isShutDown = false

    private var lastOriginLat = Double.NaN
    private var lastOriginLng = Double.NaN
    private var lastDestLat = Double.NaN
    private var lastDestLng = Double.NaN
    private var lastTravelMode = ""
    private var lastAttemptElapsedMs = 0L
    private var lastResult: RouteResult? = null

    fun fetchRoute(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        travelMode: String,
        onResult: (RouteResult) -> Unit
    ) {
        if (isShutDown) return
        if (!isValidCoord(originLat, originLng) || !isValidCoord(destLat, destLng)) {
            onResult(RouteResult(emptyList(), isRoadRoute = false))
            return
        }

        val directMeters = haversineMeters(originLat, originLng, destLat, destLng)
        if (directMeters < MIN_ROUTE_DISTANCE_METERS) {
            lastResult = null
            requestSeq.incrementAndGet()
            onResult(RouteResult(emptyList(), isRoadRoute = false))
            return
        }

        val cached = lastResult
        val sameDest = cached != null &&
            haversineMeters(lastDestLat, lastDestLng, destLat, destLng) < DESTINATION_CHANGE_THRESHOLD_METERS
        val sameMode = lastTravelMode == travelMode
        val originMovedMeters = if (cached != null && isValidCoord(lastOriginLat, lastOriginLng)) {
            haversineMeters(lastOriginLat, lastOriginLng, originLat, originLng)
        } else {
            Double.MAX_VALUE
        }
        val elapsedSinceAttempt = SystemClock.elapsedRealtime() - lastAttemptElapsedMs

        if (cached != null && sameDest && sameMode) {
            val withinRoadReuse = cached.isRoadRoute && originMovedMeters < REQUERY_ORIGIN_DISTANCE_METERS
            val withinCooldown = elapsedSinceAttempt < MIN_REQUERY_INTERVAL_MS
            if (withinRoadReuse || withinCooldown) {
                val updatedResult = if (cached.isRoadRoute && cached.points.size >= 2) {
                    val glued = ArrayList<RoutePoint>(cached.points.size + 1)
                    glued.add(RoutePoint(latitude = originLat, longitude = originLng))
                    glued.addAll(cached.points.drop(1))
                    RouteResult(glued, isRoadRoute = true)
                } else {
                    fallbackStraightRoute(originLat, originLng, destLat, destLng)
                }
                onResult(updatedResult)
                return
            }
        }

        lastOriginLat = originLat
        lastOriginLng = originLng
        lastDestLat = destLat
        lastDestLng = destLng
        lastTravelMode = travelMode
        lastAttemptElapsedMs = SystemClock.elapsedRealtime()

        val seq = requestSeq.incrementAndGet()
        val requestUrl = buildOsrmUrl(originLat, originLng, destLat, destLng, travelMode)

        executor.execute {
            val result = try {
                val body = executeHttpGet(requestUrl)
                val roadPoints = if (body != null) parseOsrmGeoJsonCoordinates(body) else emptyList()
                val connected = buildConnectedRoutePoints(originLat, originLng, destLat, destLng, roadPoints)
                if (connected.size >= 2) {
                    RouteResult(connected, isRoadRoute = true)
                } else {
                    fallbackStraightRoute(originLat, originLng, destLat, destLng)
                }
            } catch (_: Exception) {
                fallbackStraightRoute(originLat, originLng, destLat, destLng)
            }

            mainHandler.post {
                if (!isShutDown && requestSeq.get() == seq) {
                    lastResult = result
                    onResult(result)
                }
            }
        }
    }

    private fun executeHttpGet(urlString: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "DeviceWatch-ParkingSpotSaver/1.0")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) {
                null
            } else {
                BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }
        } finally {
            connection?.disconnect()
        }
    }

    fun reset() {
        requestSeq.incrementAndGet()
        lastResult = null
        lastAttemptElapsedMs = 0L
    }

    fun shutdown() {
        isShutDown = true
        requestSeq.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }
}
