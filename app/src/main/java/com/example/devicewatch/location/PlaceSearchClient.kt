package com.example.devicewatch.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.devicewatch.model.ParkingRecord
import com.example.devicewatch.navigation.RouteFetcher
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

internal enum class SearchResultKind {
    PLACE,
    COORDINATE,
    POSTCODE,
    SAVED_SPOT,
    HISTORY_SPOT
}

internal data class PlaceSearchResult(
    val title: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    val suggestedZoom: Double = 15.5,
    val kind: SearchResultKind = SearchResultKind.PLACE,
    val savedLabel: String? = null
)

internal data class PlaceSearchOutcome(
    val results: List<PlaceSearchResult>,
    val hadNetworkError: Boolean,
    val isPendingEnrichment: Boolean = false
)

internal data class PostalSearchQuery(
    val normalizedCode: String,
    val preferredCountryCode: String?,
    val extraTerms: String? = null
)

/**
 * Flexible, API-key-free global & local place search supporting:
 * - Place, area, landmark & comma/spelling variants (Photon + Nominatim + native Geocoder)
 * - Indian pincodes, US ZIP codes, and international postcodes (including spaced/prefixed formats)
 * - Lat/Lng pairs in decimal, labeled (`lat/lng`), `N/S/E/W`, DMS/DDM, swapped check & pasted map links
 * - Instant offline search across Saved & History parking spots with proximity biasing
 */
internal class PlaceSearchClient(context: Context) {

    companion object {
        private const val PHOTON_BASE_URL = "https://photon.komoot.io/api/"
        private const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org/search"
        private const val NOMINATIM_REVERSE_URL = "https://nominatim.openstreetmap.org/reverse"
        private const val CONNECT_TIMEOUT_MS = 3_500
        private const val READ_TIMEOUT_MS = 4_500
        private const val NATIVE_GEOCODER_TIMEOUT_SEC = 3L
        private const val MAX_RESULTS = 6
        private const val MAX_LOCAL_RESULTS = 2
        private const val CACHE_MAX_SIZE = 24
        private const val DEDUPE_DISTANCE_METERS = 120.0
        private const val USER_AGENT = "DeviceWatch-ParkingSpotSaver/1.0"

        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val OSM_HASH_REGEX = Regex("""#map=(\d+(?:\.\d+)?)/(-?\d+(?:\.\d+)?)/(-?\d+(?:\.\d+)?)""")
        private val LAT_LNG_LABEL_REGEX = Regex(
            """^\s*(?:lat(?:itude)?\s*[:=]?\s*)(.+?)(?:\s*[,;/|]\s*|\s+)(?:l(?:ng|on|ongitude)\s*[:=]?\s*)(.+?)\s*$""",
            RegexOption.IGNORE_CASE
        )
        private val LNG_LAT_LABEL_REGEX = Regex(
            """^\s*(?:l(?:ng|on|ongitude)\s*[:=]?\s*)(.+?)(?:\s*[,;/|]\s*|\s+)(?:lat(?:itude)?\s*[:=]?\s*)(.+?)\s*$""",
            RegexOption.IGNORE_CASE
        )
        private val LAT_PREFIX_REGEX = Regex("""^(?:lat(?:itude)?)\s*[:=]\s*""", RegexOption.IGNORE_CASE)
        private val LNG_PREFIX_REGEX = Regex("""^(?:l(?:ng|on|ongitude))\s*[:=]\s*""", RegexOption.IGNORE_CASE)
        private val POSTAL_PREFIX_REGEX = Regex(
            """^(?:pin(?:\s*code)?|pincode|postal(?:\s*code)?|postcode|zip(?:\s*code)?|zipcode)\s*[:=\-#]?\s*(.+)$""",
            RegexOption.IGNORE_CASE
        )
        private val INDIAN_PINCODE_REGEX = Regex("""^([1-8]\d{2})[\s-]?(\d{3})$""")
        private val SIX_DIGIT_REGEX = Regex("""^(\d{3})[\s-]?(\d{3})$""")
        private val US_ZIP_REGEX = Regex("""^(\d{5})(?:-\d{4})?$""")
        private val UK_POSTCODE_REGEX = Regex("""^([A-Z]{1,2}\d[A-Z\d]?)\s*(\d[A-Z]{2})$""", RegexOption.IGNORE_CASE)
        private val CANADA_POSTCODE_REGEX = Regex("""^([A-Z]\d[A-Z])\s*(\d[A-Z]\d)$""", RegexOption.IGNORE_CASE)
        private val NL_POSTCODE_REGEX = Regex("""^(\d{4})\s*([A-Z]{2})$""", RegexOption.IGNORE_CASE)
        private val JP_POSTCODE_REGEX = Regex("""^(\d{3})-(\d{4})$""")
        private val PLAIN_NUMBER_REGEX = Regex("""^([+-]?\d{1,3}(?:\.\d+)?)[°º]?$""")
        private val DMS_ANGLE_REGEX = Regex(
            """^([+-]?\d{1,3}(?:\.\d+)?)\s*[°ºd]\s*(?:(\d{1,2}(?:\.\d+)?)\s*['′’m]\s*(?:(\d{1,2}(?:\.\d+)?)\s*(?:["″”]|''|s)\s*)?)?$""",
            RegexOption.IGNORE_CASE
        )
        private val HEMI_SPLIT_MID_REGEX = Regex("""([NSEW])\s+""", RegexOption.IGNORE_CASE)
        private val HEMI_SPLIT_PRE_REGEX = Regex("""\s+(?=[NSEW]\s*[-+\d])""", RegexOption.IGNORE_CASE)

        internal fun normalizeSearchQuery(rawQuery: String): String {
            return rawQuery
                .trim()
                .trim('"', '\'')
                .replace(Regex("""\s*,\s*"""), ", ")
                .replace(WHITESPACE_REGEX, " ")
                .trim()
        }

        internal fun buildPhotonUrl(
            query: String,
            biasLat: Double? = null,
            biasLng: Double? = null
        ): String {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val biasPart = if (biasLat != null && biasLng != null && isValidCoord(biasLat, biasLng)) {
                String.format(Locale.US, "&lat=%.5f&lon=%.5f", biasLat, biasLng)
            } else {
                ""
            }
            return "$PHOTON_BASE_URL?q=$encoded&limit=$MAX_RESULTS&lang=en$biasPart"
        }

        internal fun buildNominatimUrl(query: String): String {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            return "$NOMINATIM_BASE_URL?q=$encoded&format=jsonv2&addressdetails=1&limit=$MAX_RESULTS"
        }

        internal fun buildNominatimPostalUrl(
            postalCode: String,
            countryCode: String? = null
        ): String {
            val encodedCode = URLEncoder.encode(postalCode.trim(), "UTF-8")
            val countryParam = countryCode?.trim()?.lowercase(Locale.US)?.takeIf { it.length == 2 }
                ?.let { "&countrycodes=${URLEncoder.encode(it, "UTF-8")}" }
                .orEmpty()
            return "$NOMINATIM_BASE_URL?postalcode=$encodedCode$countryParam&format=jsonv2&addressdetails=1&limit=$MAX_RESULTS"
        }

        internal fun buildNominatimReverseUrl(latitude: Double, longitude: Double): String {
            return String.format(
                Locale.US,
                "%s?lat=%.6f&lon=%.6f&format=jsonv2&addressdetails=1&zoom=16",
                NOMINATIM_REVERSE_URL,
                latitude,
                longitude
            )
        }

        internal fun isIndiaContext(
            biasLat: Double?,
            biasLng: Double?,
            defaultCountryCode: String = Locale.getDefault().country
        ): Boolean {
            if (biasLat != null && biasLng != null && isValidCoord(biasLat, biasLng)) {
                if (biasLat in 6.0..38.0 && biasLng in 68.0..98.0) return true
            }
            return defaultCountryCode.equals("IN", ignoreCase = true)
        }

        internal fun detectPostalQuery(
            rawQuery: String,
            biasLat: Double? = null,
            biasLng: Double? = null,
            defaultCountryCode: String = Locale.getDefault().country
        ): PostalSearchQuery? {
            val normalized = normalizeSearchQuery(rawQuery)
            if (normalized.isEmpty()) return null

            val prefixMatch = POSTAL_PREFIX_REGEX.matchEntire(normalized)
            val prefixLower = if (prefixMatch != null) normalized.lowercase(Locale.US) else ""
            val hasIndiaHint = prefixLower.startsWith("pin")
            val hasUsZipHint = prefixLower.startsWith("zip")
            val candidate = (prefixMatch?.groupValues?.get(1) ?: normalized).trim()
            if (candidate.isEmpty()) return null

            INDIAN_PINCODE_REGEX.matchEntire(candidate)?.let { match ->
                val code = match.groupValues[1] + match.groupValues[2]
                val preferred = if (hasIndiaHint || isIndiaContext(biasLat, biasLng, defaultCountryCode)) "in" else null
                return PostalSearchQuery(normalizedCode = code, preferredCountryCode = preferred)
            }

            if (prefixMatch != null) {
                SIX_DIGIT_REGEX.matchEntire(candidate)?.let { match ->
                    val code = match.groupValues[1] + match.groupValues[2]
                    val preferred = if (hasIndiaHint || isIndiaContext(biasLat, biasLng, defaultCountryCode)) "in" else null
                    return PostalSearchQuery(normalizedCode = code, preferredCountryCode = preferred)
                }
            }

            UK_POSTCODE_REGEX.matchEntire(candidate)?.let { match ->
                val code = "${match.groupValues[1].uppercase(Locale.US)} ${match.groupValues[2].uppercase(Locale.US)}"
                return PostalSearchQuery(normalizedCode = code, preferredCountryCode = "gb")
            }

            CANADA_POSTCODE_REGEX.matchEntire(candidate)?.let { match ->
                val code = "${match.groupValues[1].uppercase(Locale.US)} ${match.groupValues[2].uppercase(Locale.US)}"
                return PostalSearchQuery(normalizedCode = code, preferredCountryCode = "ca")
            }

            NL_POSTCODE_REGEX.matchEntire(candidate)?.let { match ->
                val code = "${match.groupValues[1]} ${match.groupValues[2].uppercase(Locale.US)}"
                return PostalSearchQuery(normalizedCode = code, preferredCountryCode = "nl")
            }

            JP_POSTCODE_REGEX.matchEntire(candidate)?.let { match ->
                val code = "${match.groupValues[1]}-${match.groupValues[2]}"
                return PostalSearchQuery(normalizedCode = code, preferredCountryCode = "jp")
            }

            US_ZIP_REGEX.matchEntire(candidate)?.let { match ->
                val preferred = when {
                    hasUsZipHint || defaultCountryCode.equals("US", ignoreCase = true) -> "us"
                    else -> null
                }
                return PostalSearchQuery(normalizedCode = match.groupValues[1], preferredCountryCode = preferred)
            }

            return null
        }

        internal fun parseDirectCoordinateQuery(
            query: String,
            coordSubtitle: String = "Coordinates"
        ): PlaceSearchResult? {
            return parseCoordinateResults(query, coordSubtitle).firstOrNull()
        }

        internal fun isDirectCoordinateOrLink(rawQuery: String): Boolean {
            return parseCoordinateResults(rawQuery).isNotEmpty()
        }

        internal fun parseCoordinateResults(
            rawQuery: String,
            coordSubtitle: String = "Coordinates",
            swapSubtitle: String = "Swapped lat, lng",
            biasLat: Double? = null,
            biasLng: Double? = null
        ): List<PlaceSearchResult> {
            val trimmed = rawQuery.trim()
            if (trimmed.isEmpty()) return emptyList()

            if (looksLikeUrlOrStructured(trimmed)) {
                parseMapUrlOrQr(trimmed, coordSubtitle)?.let { return listOf(it) }
            }

            var inner = trimmed
            if ((inner.startsWith("(") && inner.endsWith(")")) ||
                (inner.startsWith("[") && inner.endsWith("]"))
            ) {
                inner = inner.substring(1, inner.length - 1).trim()
            }

            val labeledLatLng = LAT_LNG_LABEL_REGEX.matchEntire(inner)
            val labeledLngLat = if (labeledLatLng == null) LNG_LAT_LABEL_REGEX.matchEntire(inner) else null
            val explicitLatLngLabels: Boolean
            val hadDelimitedSeparator: Boolean
            val rawLeft: String
            val rawRight: String

            when {
                labeledLatLng != null -> {
                    explicitLatLngLabels = true
                    hadDelimitedSeparator = true
                    rawLeft = labeledLatLng.groupValues[1].trim()
                    rawRight = labeledLatLng.groupValues[2].trim()
                }
                labeledLngLat != null -> {
                    explicitLatLngLabels = true
                    hadDelimitedSeparator = true
                    rawLeft = labeledLngLat.groupValues[2].trim()
                    rawRight = labeledLngLat.groupValues[1].trim()
                }
                else -> {
                    explicitLatLngLabels = false
                    val split = splitCoordinatePair(inner) ?: return emptyList()
                    rawLeft = split.first
                    rawRight = split.second
                    hadDelimitedSeparator = split.third
                }
            }

            val left = parseSingleCoordinateComponent(rawLeft.replace(LAT_PREFIX_REGEX, "").trim())
                ?: return emptyList()
            val right = parseSingleCoordinateComponent(rawRight.replace(LNG_PREFIX_REGEX, "").trim())
                ?: return emptyList()

            val hasHemisphere = left.hemisphere != null || right.hemisphere != null
            val hasExplicitMarkers = explicitLatLngLabels ||
                hasHemisphere ||
                left.hasFormattingMarker ||
                right.hasFormattingMarker

            // Avoid hijacking space-separated numbers like "560 066" (pincode) or "12 45"
            if (!hadDelimitedSeparator && !hasExplicitMarkers) {
                return emptyList()
            }

            val resolvedPair = resolveLatLngPair(left, right) ?: return emptyList()
            val lat = resolvedPair.first
            val lng = resolvedPair.second
            if (!isValidCoord(lat, lng)) return emptyList()

            val primary = PlaceSearchResult(
                title = formatCoordTitle(lat, lng),
                subtitle = coordSubtitle,
                latitude = lat,
                longitude = lng,
                suggestedZoom = 16.5,
                kind = SearchResultKind.COORDINATE
            )

            // If unlabelled plain decimals might be (lng, lat), offer swapped lat/lng cleanly when helpful
            if (!explicitLatLngLabels && !hasHemisphere && lat != lng && isValidCoord(lng, lat)) {
                val swappedFarBetterForUser = biasLat != null && biasLng != null && isValidCoord(biasLat, biasLng) &&
                    distanceKm(biasLat, biasLng, lng, lat) < 2_500.0 &&
                    distanceKm(biasLat, biasLng, lat, lng) > 4_000.0
                val looksPolarUnlessSwapped = abs(lat) > 66.5 && abs(lng) <= 66.5
                if (swappedFarBetterForUser || looksPolarUnlessSwapped) {
                    val swapped = PlaceSearchResult(
                        title = formatCoordTitle(lng, lat),
                        subtitle = swapSubtitle,
                        latitude = lng,
                        longitude = lat,
                        suggestedZoom = 16.5,
                        kind = SearchResultKind.COORDINATE
                    )
                    return listOf(primary, swapped)
                }
            }

            return listOf(primary)
        }

        private fun looksLikeUrlOrStructured(input: String): Boolean {
            return input.contains("://") ||
                input.startsWith("geo:", ignoreCase = true) ||
                (input.startsWith("{") && input.endsWith("}")) ||
                input.contains("maps.google", ignoreCase = true) ||
                input.contains("openstreetmap.org", ignoreCase = true) ||
                input.contains("#map=", ignoreCase = true)
        }

        private fun parseMapUrlOrQr(input: String, coordSubtitle: String): PlaceSearchResult? {
            val osmMatch = OSM_HASH_REGEX.find(input)
            if (osmMatch != null) {
                val zoom = osmMatch.groupValues[1].toDoubleOrNull()?.coerceIn(3.0, 18.5) ?: 16.5
                val lat = osmMatch.groupValues[2].toDoubleOrNull()
                val lng = osmMatch.groupValues[3].toDoubleOrNull()
                if (lat != null && lng != null && isValidCoord(lat, lng)) {
                    return PlaceSearchResult(
                        title = formatCoordTitle(lat, lng),
                        subtitle = coordSubtitle,
                        latitude = lat,
                        longitude = lng,
                        suggestedZoom = zoom,
                        kind = SearchResultKind.COORDINATE
                    )
                }
            }
            val qr = ParkingQrParser.parse(input) ?: return null
            val lat = qr.latitude
            val lng = qr.longitude
            if (lat == null || lng == null || !isValidCoord(lat, lng)) return null
            val coordsText = formatCoordTitle(lat, lng)
            val label = qr.placeLabel?.takeIf { it.isNotBlank() }
            val address = qr.address?.takeIf { it.isNotBlank() }
            return PlaceSearchResult(
                title = label ?: coordsText,
                subtitle = address ?: if (label != null) "$coordSubtitle · $coordsText" else coordSubtitle,
                latitude = lat,
                longitude = lng,
                suggestedZoom = 16.5,
                kind = SearchResultKind.COORDINATE,
                savedLabel = label
            )
        }

        private data class ParsedAngle(
            val value: Double,
            val hemisphere: Char?,
            val hasFormattingMarker: Boolean
        )

        private fun splitCoordinatePair(input: String): Triple<String, String, Boolean>? {
            for (delimiter in charArrayOf(',', ';', '|', '/')) {
                if (input.contains(delimiter)) {
                    val parts = input.split(delimiter)
                    if (parts.size == 2) {
                        val a = parts[0].trim()
                        val b = parts[1].trim()
                        if (a.isNotEmpty() && b.isNotEmpty()) {
                            return Triple(a, b, true)
                        }
                    }
                    return null
                }
            }

            val firstUpper = input.firstOrNull()?.uppercaseChar()
            if (firstUpper != null && firstUpper in charArrayOf('N', 'S', 'E', 'W')) {
                val preHemi = HEMI_SPLIT_PRE_REGEX.find(input)
                if (preHemi != null && preHemi.range.first > 0) {
                    val left = input.substring(0, preHemi.range.first).trim()
                    val right = input.substring(preHemi.range.last + 1).trim()
                    if (left.isNotEmpty() && right.isNotEmpty()) {
                        return Triple(left, right, false)
                    }
                }
            }

            for (midHemi in HEMI_SPLIT_MID_REGEX.findAll(input)) {
                if (midHemi.range.last + 1 < input.length) {
                    val left = input.substring(0, midHemi.range.first + 1).trim()
                    val right = input.substring(midHemi.range.last + 1).trim()
                    if (parseSingleCoordinateComponent(left) != null &&
                        parseSingleCoordinateComponent(right) != null
                    ) {
                        return Triple(left, right, false)
                    }
                }
            }

            val tokens = input.trim().split(WHITESPACE_REGEX)
            if (tokens.size == 2) {
                return Triple(tokens[0], tokens[1], false)
            }
            return null
        }

        private fun parseSingleCoordinateComponent(rawToken: String): ParsedAngle? {
            var work = rawToken.trim()
            if (work.isEmpty()) return null

            var hemisphere: Char? = null
            val firstUpper = work.first().uppercaseChar()
            val lastUpper = work.last().uppercaseChar()
            val startsWithHemi = firstUpper in charArrayOf('N', 'S', 'E', 'W')
            val endsWithHemi = lastUpper in charArrayOf('N', 'S', 'E', 'W') && work.length > 1

            if (startsWithHemi && endsWithHemi) return null
            if (startsWithHemi) {
                hemisphere = firstUpper
                work = work.substring(1).trim()
            } else if (endsWithHemi) {
                hemisphere = lastUpper
                work = work.substring(0, work.length - 1).trim()
            }
            if (work.isEmpty()) return null

            val dmsMatch = DMS_ANGLE_REGEX.matchEntire(work)
            if (dmsMatch != null && (work.contains('°') || work.contains('º') || work.contains('d', ignoreCase = true) ||
                    work.contains('\'') || work.contains('′') || work.contains('"') || work.contains('″'))
            ) {
                val degRaw = dmsMatch.groupValues[1].toDoubleOrNull() ?: return null
                val minStr = dmsMatch.groupValues[2]
                val secStr = dmsMatch.groupValues[3]
                val mins = if (minStr.isEmpty()) 0.0 else minStr.toDoubleOrNull() ?: return null
                val secs = if (secStr.isEmpty()) 0.0 else secStr.toDoubleOrNull() ?: return null
                if (mins < 0.0 || mins >= 60.0 || secs < 0.0 || secs >= 60.0) return null
                if ((minStr.isNotEmpty() || secStr.isNotEmpty()) && abs(degRaw) % 1.0 != 0.0) return null
                if (hemisphere != null && degRaw < 0.0) return null

                val sign = if (degRaw < 0.0 || hemisphere == 'S' || hemisphere == 'W') -1.0 else 1.0
                val mag = abs(degRaw) + mins / 60.0 + secs / 3600.0
                return ParsedAngle(
                    value = sign * mag,
                    hemisphere = hemisphere,
                    hasFormattingMarker = true
                )
            }

            val plainMatch = PLAIN_NUMBER_REGEX.matchEntire(work) ?: return null
            val value = plainMatch.groupValues[1].toDoubleOrNull() ?: return null
            if (!value.isFinite()) return null
            if (hemisphere != null && value < 0.0) return null

            val signedValue = if (hemisphere == 'S' || hemisphere == 'W') -abs(value) else value
            val hasMarker = work.contains('.') || work.startsWith('-') || work.startsWith('+') ||
                work.contains('°') || work.contains('º')
            return ParsedAngle(
                value = signedValue,
                hemisphere = hemisphere,
                hasFormattingMarker = hasMarker
            )
        }

        private fun resolveLatLngPair(first: ParsedAngle, second: ParsedAngle): Pair<Double, Double>? {
            val h1 = first.hemisphere
            val h2 = second.hemisphere
            return when {
                (h1 == 'N' || h1 == 'S') && (h2 == 'E' || h2 == 'W') -> first.value to second.value
                (h1 == 'E' || h1 == 'W') && (h2 == 'N' || h2 == 'S') -> second.value to first.value
                h1 == null && h2 == null -> first.value to second.value
                else -> null
            }
        }

        private fun formatCoordTitle(lat: Double, lng: Double): String {
            return String.format(Locale.US, "%.5f, %.5f", lat, lng)
        }

        internal fun searchLocalRecords(
            rawQuery: String,
            records: List<ParkingRecord>,
            savedSubtitlePrefix: String = "Saved spot",
            historySubtitlePrefix: String = "Parked earlier"
        ): List<PlaceSearchResult> {
            val normalized = normalizeSearchQuery(rawQuery)
            if (normalized.length < 2 || records.isEmpty()) return emptyList()
            val tokens = normalized.lowercase(Locale.US)
                .split(Regex("""[\s,]+"""))
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return emptyList()

            val scored = ArrayList<Pair<Int, PlaceSearchResult>>()
            val seenCoords = HashSet<String>()

            for (record in records) {
                if (!isValidCoord(record.latitude, record.longitude)) continue
                val label = record.placeLabel?.trim()?.takeIf { it.isNotEmpty() }
                val address = record.address?.trim()?.takeIf { it.isNotEmpty() }
                val note = record.note?.trim()?.takeIf { it.isNotEmpty() }
                val coords = formatCoordTitle(record.latitude, record.longitude)

                val searchable = listOfNotNull(label, address, note, coords)
                    .joinToString(" ")
                    .lowercase(Locale.US)
                if (!tokens.all { token -> searchable.contains(token) }) continue

                val coordKey = String.format(Locale.US, "%.4f,%.4f", record.latitude, record.longitude)
                if (!seenCoords.add(coordKey)) continue

                var score = if (record.isSaved) 20 else 5
                val lowerNorm = normalized.lowercase(Locale.US)
                if (label != null && label.lowercase(Locale.US).startsWith(lowerNorm)) score += 30
                else if (label != null && label.lowercase(Locale.US).contains(lowerNorm)) score += 20
                if (address != null && address.lowercase(Locale.US).contains(lowerNorm)) score += 10

                val title = label ?: address ?: coords
                val prefix = if (record.isSaved) savedSubtitlePrefix else historySubtitlePrefix
                val detail = when {
                    label != null && address != null -> address
                    address != null -> coords
                    else -> coords
                }
                scored.add(
                    score to PlaceSearchResult(
                        title = title,
                        subtitle = "$prefix · $detail",
                        latitude = record.latitude,
                        longitude = record.longitude,
                        suggestedZoom = 17.0,
                        kind = if (record.isSaved) SearchResultKind.SAVED_SPOT else SearchResultKind.HISTORY_SPOT,
                        savedLabel = label
                    )
                )
            }

            return scored
                .sortedByDescending { it.first }
                .take(MAX_LOCAL_RESULTS)
                .map { it.second }
        }

        internal fun resolveSuggestedZoom(placeType: String?): Double {
            return when (placeType?.trim()?.lowercase(Locale.US)) {
                "country", "state", "region", "province" -> 10.5
                "city", "town", "municipality", "county", "district" -> 12.5
                "postcode", "postal_code" -> 14.0
                "suburb", "neighbourhood", "neighborhood", "quarter",
                "locality", "village", "borough", "residential" -> 14.8
                "road", "street", "highway", "building", "house", "amenity",
                "shop", "tourism", "aeroway", "railway", "station", "mall",
                "parking", "hospital", "university", "office", "hotel",
                "restaurant", "attraction" -> 16.2
                else -> 15.2
            }
        }

        internal fun parsePhotonResponse(json: String): List<PlaceSearchResult> {
            val root = SimpleJsonReader.parse(json) as? Map<*, *> ?: return emptyList()
            val features = root["features"] as? List<*> ?: return emptyList()
            val results = ArrayList<PlaceSearchResult>(MAX_RESULTS)
            val seenKeys = HashSet<String>()

            for (rawFeature in features) {
                val feature = rawFeature as? Map<*, *> ?: continue
                val geometry = feature["geometry"] as? Map<*, *> ?: continue
                val coords = geometry["coordinates"] as? List<*> ?: continue
                val lng = (coords.getOrNull(0) as? Number)?.toDouble() ?: continue
                val lat = (coords.getOrNull(1) as? Number)?.toDouble() ?: continue
                if (!isValidCoord(lat, lng)) continue

                val props = feature["properties"] as? Map<*, *> ?: continue
                val name = props.optNonBlank("name")
                val housenumber = props.optNonBlank("housenumber")
                val street = props.optNonBlank("street")
                val locality = props.optNonBlank("locality")
                val district = props.optNonBlank("district")
                val city = props.optNonBlank("city")
                val county = props.optNonBlank("county")
                val state = props.optNonBlank("state")
                val postcode = props.optNonBlank("postcode")
                val country = props.optNonBlank("country")
                val osmValue = props.optNonBlank("osm_value")
                val type = props.optNonBlank("type")

                val streetAddress = listOfNotNull(housenumber, street)
                    .joinToString(" ")
                    .trim()
                    .takeIf { it.isNotEmpty() }

                val isPostcode = type.equals("postcode", ignoreCase = true) ||
                    osmValue.equals("postcode", ignoreCase = true)

                val title = when {
                    isPostcode && locality != null && postcode != null -> "$locality ($postcode)"
                    isPostcode && city != null && postcode != null -> "$city ($postcode)"
                    else -> name
                        ?: streetAddress
                        ?: locality
                        ?: city
                        ?: district
                        ?: county
                        ?: state
                        ?: postcode
                        ?: country
                        ?: continue
                }

                val subtitleParts = ArrayList<String>(6)
                if (street != null && !title.contains(street, ignoreCase = true)) {
                    addDistinctPart(subtitleParts, street, title)
                }
                addDistinctPart(subtitleParts, locality, title)
                addDistinctPart(subtitleParts, district, title)
                addDistinctPart(subtitleParts, city, title)
                addDistinctPart(subtitleParts, state, title)
                if (isPostcode && postcode != null && !title.contains(postcode, ignoreCase = true)) {
                    addDistinctPart(subtitleParts, postcode, title)
                }
                addDistinctPart(subtitleParts, country, title)

                val subtitle = subtitleParts.joinToString(", ")
                val dedupeKey = "${title.lowercase(Locale.US)}|${subtitle.lowercase(Locale.US)}"
                if (!seenKeys.add(dedupeKey)) continue

                results.add(
                    PlaceSearchResult(
                        title = title,
                        subtitle = subtitle,
                        latitude = lat,
                        longitude = lng,
                        suggestedZoom = resolveSuggestedZoom(osmValue ?: type),
                        kind = if (isPostcode) SearchResultKind.POSTCODE else SearchResultKind.PLACE
                    )
                )
                if (results.size >= MAX_RESULTS) break
            }
            return results
        }

        internal fun parseNominatimResponse(json: String): List<PlaceSearchResult> {
            val items = SimpleJsonReader.parse(json) as? List<*> ?: return emptyList()
            val results = ArrayList<PlaceSearchResult>(MAX_RESULTS)
            val seenKeys = HashSet<String>()

            for (rawItem in items) {
                val item = rawItem as? Map<*, *> ?: continue
                val lat = item.optDouble("lat") ?: continue
                val lng = item.optDouble("lon") ?: continue
                if (!isValidCoord(lat, lng)) continue

                val name = item.optNonBlank("name")
                val displayName = item.optNonBlank("display_name")
                val addresstype = item.optNonBlank("addresstype")
                val type = item.optNonBlank("type")
                val addressMap = item["address"] as? Map<*, *>

                val isPostcode = addresstype.equals("postcode", ignoreCase = true) ||
                    type.equals("postcode", ignoreCase = true)

                val displayParts = displayName
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()

                var title: String? = null
                val subtitleParts = ArrayList<String>(4)

                if (isPostcode && addressMap != null) {
                    val postcode = addressMap.optNonBlank("postcode") ?: name
                    val area = addressMap.optNonBlank("suburb")
                        ?: addressMap.optNonBlank("neighbourhood")
                        ?: addressMap.optNonBlank("town")
                        ?: addressMap.optNonBlank("village")
                        ?: addressMap.optNonBlank("city_district")
                        ?: addressMap.optNonBlank("city")
                        ?: addressMap.optNonBlank("county")
                    title = when {
                        area != null && postcode != null && !area.equals(postcode, ignoreCase = true) ->
                            "$area ($postcode)"
                        postcode != null -> postcode
                        area != null -> area
                        else -> displayParts.firstOrNull()
                    }
                    if (title != null) {
                        addDistinctPart(subtitleParts, addressMap.optNonBlank("city"), title)
                        addDistinctPart(subtitleParts, addressMap.optNonBlank("state_district"), title)
                        addDistinctPart(subtitleParts, addressMap.optNonBlank("state"), title)
                        addDistinctPart(subtitleParts, addressMap.optNonBlank("country"), title)
                    }
                }

                if (title == null) {
                    title = name ?: displayParts.firstOrNull() ?: continue
                }
                if (subtitleParts.isEmpty()) {
                    for (part in displayParts) {
                        addDistinctPart(subtitleParts, part, title)
                        if (subtitleParts.size >= 4) break
                    }
                }

                val subtitle = subtitleParts.joinToString(", ")
                val dedupeKey = "${title.lowercase(Locale.US)}|${subtitle.lowercase(Locale.US)}"
                if (!seenKeys.add(dedupeKey)) continue

                results.add(
                    PlaceSearchResult(
                        title = title,
                        subtitle = subtitle,
                        latitude = lat,
                        longitude = lng,
                        suggestedZoom = resolveSuggestedZoom(addresstype ?: type),
                        kind = if (isPostcode) SearchResultKind.POSTCODE else SearchResultKind.PLACE
                    )
                )
                if (results.size >= MAX_RESULTS) break
            }
            return results
        }

        internal fun parseNominatimReverseSummary(json: String): String? {
            val root = SimpleJsonReader.parse(json) as? Map<*, *> ?: return null
            val address = root["address"] as? Map<*, *>
            if (address != null) {
                val parts = ArrayList<String>(4)
                val area = address.optNonBlank("suburb")
                    ?: address.optNonBlank("neighbourhood")
                    ?: address.optNonBlank("road")
                    ?: address.optNonBlank("village")
                val city = address.optNonBlank("city")
                    ?: address.optNonBlank("town")
                    ?: address.optNonBlank("county")
                val state = address.optNonBlank("state")
                val postcode = address.optNonBlank("postcode")
                val country = address.optNonBlank("country")

                listOfNotNull(area, city, postcode, state, country).forEach { part ->
                    if (parts.size < 4 && parts.none { it.equals(part, ignoreCase = true) }) {
                        parts.add(part)
                    }
                }
                if (parts.isNotEmpty()) return parts.joinToString(", ")
            }
            val display = root.optNonBlank("display_name") ?: return null
            return display.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(4)
                .joinToString(", ")
                .takeIf { it.isNotEmpty() }
        }

        internal fun mergeUniqueResults(
            vararg lists: List<PlaceSearchResult>,
            limit: Int = MAX_RESULTS
        ): List<PlaceSearchResult> {
            val merged = ArrayList<PlaceSearchResult>(limit)
            for (list in lists) {
                for (candidate in list) {
                    val alreadyPresent = merged.any { existing ->
                        existing.kind == candidate.kind &&
                            distanceKm(
                                existing.latitude,
                                existing.longitude,
                                candidate.latitude,
                                candidate.longitude
                            ) * 1000.0 < DEDUPE_DISTANCE_METERS &&
                            existing.title.equals(candidate.title, ignoreCase = true)
                    }
                    if (!alreadyPresent) {
                        merged.add(candidate)
                        if (merged.size >= limit) return merged
                    }
                }
            }
            return merged
        }

        private fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            return RouteFetcher.haversineMeters(lat1, lng1, lat2, lng2) / 1000.0
        }

        private fun isValidCoord(lat: Double, lng: Double): Boolean {
            return lat.isFinite() &&
                lng.isFinite() &&
                lat in -90.0..90.0 &&
                lng in -180.0..180.0
        }

        private fun Map<*, *>.optNonBlank(key: String): String? {
            return (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun Map<*, *>.optDouble(key: String): Double? {
            return when (val raw = this[key]) {
                is Number -> raw.toDouble()
                is String -> raw.trim().toDoubleOrNull()
                else -> null
            }
        }

        private fun addDistinctPart(parts: MutableList<String>, candidate: String?, title: String) {
            if (candidate.isNullOrBlank()) return
            val trimmed = candidate.trim()
            if (trimmed.equals(title, ignoreCase = true)) return
            if (parts.none { it.equals(trimmed, ignoreCase = true) }) {
                parts.add(trimmed)
            }
        }
    }

    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestSeq = AtomicLong(0L)

    @Volatile
    private var isShutDown = false

    private val cacheLock = Any()
    private val resultCache = object : LinkedHashMap<String, List<PlaceSearchResult>>(CACHE_MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<PlaceSearchResult>>?): Boolean {
            return size > CACHE_MAX_SIZE
        }
    }

    fun search(
        rawQuery: String,
        coordSubtitle: String,
        swapSubtitle: String = "Swapped lat, lng",
        savedSubtitlePrefix: String = "Saved spot",
        historySubtitlePrefix: String = "Parked earlier",
        localRecords: List<ParkingRecord> = emptyList(),
        biasLat: Double? = null,
        biasLng: Double? = null,
        onOutcome: (PlaceSearchOutcome) -> Unit
    ) {
        if (isShutDown) return
        val normalized = normalizeSearchQuery(rawQuery)
        if (normalized.length < 2) {
            requestSeq.incrementAndGet()
            onOutcome(PlaceSearchOutcome(emptyList(), hadNetworkError = false))
            return
        }

        val coordResults = parseCoordinateResults(
            rawQuery = rawQuery,
            coordSubtitle = coordSubtitle,
            swapSubtitle = swapSubtitle,
            biasLat = biasLat,
            biasLng = biasLng
        )
        if (coordResults.isNotEmpty()) {
            val seq = requestSeq.incrementAndGet()
            val primary = coordResults.first()
            onOutcome(
                PlaceSearchOutcome(
                    results = coordResults,
                    hadNetworkError = false,
                    isPendingEnrichment = true
                )
            )
            executor.execute {
                if (isShutDown || requestSeq.get() != seq) return@execute
                val placeSummary = reverseGeocodeSummary(primary.latitude, primary.longitude)
                mainHandler.post {
                    if (!isShutDown && requestSeq.get() == seq) {
                        val enriched = if (placeSummary != null) {
                            coordResults.mapIndexed { idx, item ->
                                if (idx == 0) {
                                    item.copy(subtitle = "$placeSummary · ${item.subtitle}")
                                } else {
                                    item
                                }
                            }
                        } else {
                            coordResults
                        }
                        onOutcome(
                            PlaceSearchOutcome(
                                results = enriched,
                                hadNetworkError = false,
                                isPendingEnrichment = false
                            )
                        )
                    }
                }
            }
            return
        }

        val localMatches = searchLocalRecords(
            rawQuery = normalized,
            records = localRecords,
            savedSubtitlePrefix = savedSubtitlePrefix,
            historySubtitlePrefix = historySubtitlePrefix
        )
        val postalQuery = detectPostalQuery(normalized, biasLat, biasLng)
        val biasTag = if (biasLat != null && biasLng != null && postalQuery == null) {
            String.format(Locale.US, "%.1f,%.1f", biasLat, biasLng)
        } else {
            postalQuery?.preferredCountryCode ?: "global"
        }
        val cacheKey = "${normalized.lowercase(Locale.US)}|$biasTag"

        val cachedOnline = synchronized(cacheLock) { resultCache[cacheKey] }
        if (cachedOnline != null) {
            requestSeq.incrementAndGet()
            val combined = mergeUniqueResults(localMatches, cachedOnline, limit = MAX_RESULTS)
            onOutcome(PlaceSearchOutcome(combined, hadNetworkError = false))
            return
        }

        val seq = requestSeq.incrementAndGet()
        executor.execute {
            if (isShutDown || requestSeq.get() != seq) return@execute

            var anyNetworkError = false
            val collectedOnline = ArrayList<PlaceSearchResult>(MAX_RESULTS)

            if (postalQuery != null) {
                var postalHits = emptyList<PlaceSearchResult>()
                try {
                    val json = executeHttpGet(
                        buildNominatimPostalUrl(postalQuery.normalizedCode, postalQuery.preferredCountryCode)
                    )
                    if (json != null) {
                        postalHits = parseNominatimResponse(json)
                    } else {
                        anyNetworkError = true
                    }
                } catch (_: Exception) {
                    anyNetworkError = true
                }

                if (postalHits.isEmpty() && postalQuery.preferredCountryCode != null &&
                    !isShutDown && requestSeq.get() == seq
                ) {
                    try {
                        val globalJson = executeHttpGet(buildNominatimPostalUrl(postalQuery.normalizedCode, null))
                        if (globalJson != null) {
                            postalHits = parseNominatimResponse(globalJson)
                        } else {
                            anyNetworkError = true
                        }
                    } catch (_: Exception) {
                        anyNetworkError = true
                    }
                }

                collectedOnline.addAll(postalHits)

                if (collectedOnline.size < 2 && !isShutDown && requestSeq.get() == seq) {
                    val fallbackQuery = if (postalQuery.preferredCountryCode == "in") {
                        "${postalQuery.normalizedCode} India"
                    } else {
                        postalQuery.normalizedCode
                    }
                    try {
                        val photonJson = executeHttpGet(buildPhotonUrl(fallbackQuery, biasLat, biasLng))
                        if (photonJson != null) {
                            collectedOnline.addAll(parsePhotonResponse(photonJson))
                        }
                    } catch (_: Exception) {
                        anyNetworkError = true
                    }
                }
            } else {
                try {
                    val photonJson = executeHttpGet(buildPhotonUrl(normalized, biasLat, biasLng))
                    if (photonJson != null) {
                        collectedOnline.addAll(parsePhotonResponse(photonJson))
                    } else {
                        anyNetworkError = true
                    }
                } catch (_: Exception) {
                    anyNetworkError = true
                }

                if (collectedOnline.size < 2 && !isShutDown && requestSeq.get() == seq) {
                    try {
                        val nominatimJson = executeHttpGet(buildNominatimUrl(normalized))
                        if (nominatimJson != null) {
                            collectedOnline.addAll(parseNominatimResponse(nominatimJson))
                        } else {
                            anyNetworkError = true
                        }
                    } catch (_: Exception) {
                        anyNetworkError = true
                    }
                }
            }

            if (collectedOnline.isEmpty() && !isShutDown && requestSeq.get() == seq) {
                collectedOnline.addAll(searchWithNativeGeocoder(normalized))
            }

            val uniqueOnline = mergeUniqueResults(collectedOnline, limit = MAX_RESULTS)
            if (uniqueOnline.isNotEmpty()) {
                synchronized(cacheLock) {
                    resultCache[cacheKey] = uniqueOnline
                }
            }

            val finalResults = mergeUniqueResults(localMatches, uniqueOnline, limit = MAX_RESULTS)
            val hadNetworkError = finalResults.isEmpty() && anyNetworkError
            mainHandler.post {
                if (!isShutDown && requestSeq.get() == seq) {
                    onOutcome(PlaceSearchOutcome(finalResults, hadNetworkError = hadNetworkError))
                }
            }
        }
    }

    private fun reverseGeocodeSummary(latitude: Double, longitude: Double): String? {
        try {
            val json = executeHttpGet(buildNominatimReverseUrl(latitude, longitude))
            if (json != null) {
                parseNominatimReverseSummary(json)?.let { return it }
            }
        } catch (_: Exception) {
        }
        return reverseWithNativeGeocoder(latitude, longitude)
    }

    private fun reverseWithNativeGeocoder(latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            val geocoder = Geocoder(appContext, Locale.getDefault())
            val addresses: List<Address> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                var resolved: List<Address> = emptyList()
                val latch = CountDownLatch(1)
                geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(results: MutableList<Address>) {
                        resolved = results
                        latch.countDown()
                    }

                    override fun onError(errorMessage: String?) {
                        latch.countDown()
                    }
                })
                latch.await(NATIVE_GEOCODER_TIMEOUT_SEC, TimeUnit.SECONDS)
                resolved
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(latitude, longitude, 1).orEmpty()
            }
            val first = addresses.firstOrNull() ?: return null
            val parts = ArrayList<String>(4)
            listOf(
                first.subLocality,
                first.locality,
                first.adminArea,
                first.postalCode,
                first.countryName
            ).forEach { value ->
                val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
                if (trimmed != null && parts.none { it.equals(trimmed, ignoreCase = true) }) {
                    parts.add(trimmed)
                }
            }
            if (parts.isNotEmpty()) {
                parts.take(4).joinToString(", ")
            } else if (first.maxAddressLineIndex >= 0) {
                first.getAddressLine(0)?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun executeHttpGet(urlString: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
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

    private fun searchWithNativeGeocoder(query: String): List<PlaceSearchResult> {
        if (!Geocoder.isPresent()) return emptyList()
        return try {
            val geocoder = Geocoder(appContext, Locale.getDefault())
            val addresses: List<Address> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                var resolved: List<Address> = emptyList()
                val latch = CountDownLatch(1)
                geocoder.getFromLocationName(query, MAX_RESULTS, object : Geocoder.GeocodeListener {
                    override fun onGeocode(results: MutableList<Address>) {
                        resolved = results
                        latch.countDown()
                    }

                    override fun onError(errorMessage: String?) {
                        latch.countDown()
                    }
                })
                latch.await(NATIVE_GEOCODER_TIMEOUT_SEC, TimeUnit.SECONDS)
                resolved
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, MAX_RESULTS).orEmpty()
            }

            val mapped = ArrayList<PlaceSearchResult>(MAX_RESULTS)
            val seenKeys = HashSet<String>()
            for (addr in addresses) {
                if (!addr.hasLatitude() || !addr.hasLongitude()) continue
                val lat = addr.latitude
                val lng = addr.longitude
                if (!isValidCoord(lat, lng)) continue

                val line0 = if (addr.maxAddressLineIndex >= 0) {
                    addr.getAddressLine(0)?.trim()?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                val title = addr.featureName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: addr.subLocality?.trim()?.takeIf { it.isNotEmpty() }
                    ?: addr.locality?.trim()?.takeIf { it.isNotEmpty() }
                    ?: addr.thoroughfare?.trim()?.takeIf { it.isNotEmpty() }
                    ?: line0
                    ?: continue

                val subtitleParts = ArrayList<String>(5)
                addDistinctPart(subtitleParts, addr.thoroughfare, title)
                addDistinctPart(subtitleParts, addr.subLocality, title)
                addDistinctPart(subtitleParts, addr.locality, title)
                addDistinctPart(subtitleParts, addr.adminArea, title)
                addDistinctPart(subtitleParts, addr.postalCode, title)
                addDistinctPart(subtitleParts, addr.countryName, title)
                if (subtitleParts.isEmpty() && line0 != null && !line0.equals(title, ignoreCase = true)) {
                    subtitleParts.add(line0)
                }

                val subtitle = subtitleParts.joinToString(", ")
                val key = "${title.lowercase(Locale.US)}|${subtitle.lowercase(Locale.US)}"
                if (!seenKeys.add(key)) continue

                mapped.add(
                    PlaceSearchResult(
                        title = title,
                        subtitle = subtitle,
                        latitude = lat,
                        longitude = lng,
                        suggestedZoom = 15.2,
                        kind = SearchResultKind.PLACE
                    )
                )
                if (mapped.size >= MAX_RESULTS) break
            }
            mapped
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun cancelPending() {
        requestSeq.incrementAndGet()
    }

    fun shutdown() {
        isShutDown = true
        requestSeq.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    private class SimpleJsonReader private constructor(private val input: String) {

        private var pos = 0

        companion object {
            fun parse(json: String): Any? {
                if (json.isBlank()) return null
                return try {
                    val reader = SimpleJsonReader(json)
                    val value = reader.readValue()
                    reader.skipWhitespace()
                    if (reader.pos == json.length) value else null
                } catch (_: Exception) {
                    null
                }
            }
        }

        private fun readValue(): Any? {
            skipWhitespace()
            if (pos >= input.length) throw IllegalArgumentException("Unexpected EOF")
            return when (val ch = input[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                '-', in '0'..'9' -> readNumber()
                else -> throw IllegalArgumentException("Unexpected char: $ch")
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (peek('}')) {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (!peek('"')) throw IllegalArgumentException("Expected key")
                val key = readString()
                skipWhitespace()
                expect(':')
                val value = readValue()
                map[key] = value
                skipWhitespace()
                when {
                    peek(',') -> pos++
                    peek('}') -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected , or }")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            val list = ArrayList<Any?>()
            if (peek(']')) {
                pos++
                return list
            }
            while (true) {
                list.add(readValue())
                skipWhitespace()
                when {
                    peek(',') -> pos++
                    peek(']') -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected , or ]")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < input.length) {
                val ch = input[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= input.length) throw IllegalArgumentException("Unterminated escape")
                        when (val esc = input[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > input.length) throw IllegalArgumentException("Invalid unicode escape")
                                val code = input.substring(pos, pos + 4).toInt(16)
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape: $esc")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun readNumber(): Double {
            val start = pos
            if (peek('-')) pos++
            while (pos < input.length) {
                val ch = input[pos]
                if (ch in '0'..'9' || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') {
                    pos++
                } else {
                    break
                }
            }
            return input.substring(start, pos).toDouble()
        }

        private fun readBoolean(): Boolean {
            return if (input.startsWith("true", pos)) {
                pos += 4
                true
            } else if (input.startsWith("false", pos)) {
                pos += 5
                false
            } else {
                throw IllegalArgumentException("Invalid boolean")
            }
        }

        private fun readNull(): Any? {
            if (input.startsWith("null", pos)) {
                pos += 4
                return null
            }
            throw IllegalArgumentException("Invalid null")
        }

        private fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) {
                pos++
            }
        }

        private fun peek(expected: Char): Boolean {
            return pos < input.length && input[pos] == expected
        }

        private fun expect(expected: Char) {
            if (pos >= input.length || input[pos] != expected) {
                throw IllegalArgumentException("Expected $expected")
            }
            pos++
        }
    }
}
