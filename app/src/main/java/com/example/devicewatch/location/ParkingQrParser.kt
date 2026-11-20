package com.example.devicewatch.location

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Parses QR payloads found in garages, meters, and shared map links.
 * Text/bay details are always placed into `placeLabel` only (never `note`).
 */
object ParkingQrParser {

    data class ParsedQrResult(
        val latitude: Double?,
        val longitude: Double?,
        val placeLabel: String?,
        val address: String?,
        val note: String?,
        val rawText: String
    ) {
        val hasCoordinates: Boolean
            get() = latitude != null && longitude != null
    }

    private val coordPairRegex = Regex("""(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)""")
    private val atCoordRegex = Regex("""@(-?\d{1,2}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""")
    private val placePathRegex = Regex("""/maps/place/([^/@]+)""", RegexOption.IGNORE_CASE)
    private val dmsOrJsonLatRegex = Regex(""""lat(?:itude)?"\s*:\s*(-?\d{1,2}(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val dmsOrJsonLngRegex = Regex(""""(?:lng|lon|longitude)"\s*:\s*(-?\d{1,3}(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

    fun parse(rawInput: String?): ParsedQrResult? {
        val raw = rawInput?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        parseJsonPayload(raw)?.let { return it }
        parseGeoUri(raw)?.let { return it }
        parseUrlPayload(raw)?.let { return it }
        parsePlainCoordinates(raw)?.let { return it }

        // Combine all lines of plain text into placeLabel only (never note).
        val mergedLabel = raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" • ")
            .take(120)
            .takeIf { it.isNotEmpty() }
        return ParsedQrResult(
            latitude = null,
            longitude = null,
            placeLabel = mergedLabel,
            address = null,
            note = null,
            rawText = raw
        )
    }

    private fun parseJsonPayload(raw: String): ParsedQrResult? {
        if (!raw.startsWith("{") || !raw.endsWith("}")) return null
        val lat = dmsOrJsonLatRegex.find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
        val lng = dmsOrJsonLngRegex.find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
        val validCoords = isValidLatLng(lat, lng)

        val labelParts = listOfNotNull(
            extractJsonString(raw, "label", "placeLabel", "name", "title"),
            extractJsonString(raw, "floor", "level"),
            extractJsonString(raw, "bay", "spot", "zone"),
            extractJsonString(raw, "note", "notes", "details")
        ).distinct()
        val mergedLabel = labelParts.joinToString(" • ").take(120).takeIf { it.isNotEmpty() }
        val address = extractJsonString(raw, "address", "location")
        if (!validCoords && mergedLabel == null && address == null) return null
        return ParsedQrResult(
            latitude = if (validCoords) lat else null,
            longitude = if (validCoords) lng else null,
            placeLabel = mergedLabel,
            address = address,
            note = null,
            rawText = raw
        )
    }

    private fun parseGeoUri(raw: String): ParsedQrResult? {
        if (!raw.startsWith("geo:", ignoreCase = true)) return null
        val withoutScheme = raw.substring(4)
        val parts = withoutScheme.split("?", limit = 2)
        val mainCoords = parseCoordPair(parts[0])
        val query = parts.getOrNull(1).orEmpty()
        val params = parseQueryParams(query)
        val qValue = params["q"]
        val qCoords = qValue?.let { parseCoordPairAndLabel(it) }

        val finalLat = mainCoords?.first?.takeIf { it != 0.0 || mainCoords.second != 0.0 }
            ?: qCoords?.first
            ?: mainCoords?.first
        val finalLng = mainCoords?.second?.takeIf { mainCoords.first != 0.0 || it != 0.0 }
            ?: qCoords?.second
            ?: mainCoords?.second

        if (!isValidLatLng(finalLat, finalLng)) return null
        val label = qCoords?.third ?: qValue?.takeIf { parseCoordPair(it) == null }
        return ParsedQrResult(
            latitude = finalLat,
            longitude = finalLng,
            placeLabel = label,
            address = null,
            note = null,
            rawText = raw
        )
    }

    private fun parseUrlPayload(raw: String): ParsedQrResult? {
        val uri = try {
            val u = URI(raw)
            if (u.scheme == null) null else u
        } catch (_: Exception) {
            null
        } ?: return null

        val params = parseQueryParams(uri.rawQuery.orEmpty())
        val floorZone = params["floor"] ?: params["level"]
        val bayZone = params["bay"] ?: params["spot"] ?: params["zone"]
        val placeFromPath = placePathRegex.find(uri.rawPath.orEmpty())?.groupValues?.get(1)
            ?.let { decode(it).replace('+', ' ').trim().takeIf { name -> name.isNotEmpty() } }
        val explicitLabel = params["label"] ?: params["name"] ?: params["title"]
        val extraDetail = params["note"] ?: params["details"]
        val labelParam = listOfNotNull(explicitLabel, floorZone, bayZone, extraDetail, placeFromPath)
            .distinct()
            .joinToString(" • ")
            .take(120)
            .takeIf { it.isNotEmpty() }

        val paramCoordKeys = listOf("q", "query", "ll", "center", "sll", "destination")
        for (key in paramCoordKeys) {
            val value = params[key] ?: continue
            val parsed = parseCoordPairAndLabel(value)
            if (parsed != null) {
                return ParsedQrResult(
                    latitude = parsed.first,
                    longitude = parsed.second,
                    placeLabel = labelParam ?: parsed.third,
                    address = params["address"],
                    note = null,
                    rawText = raw
                )
            }
        }

        val latParam = (params["lat"] ?: params["mlat"])?.toDoubleOrNull()
        val lngParam = (params["lng"] ?: params["lon"] ?: params["mlon"])?.toDoubleOrNull()
        if (isValidLatLng(latParam, lngParam)) {
            return ParsedQrResult(
                latitude = latParam,
                longitude = lngParam,
                placeLabel = labelParam,
                address = params["address"],
                note = null,
                rawText = raw
            )
        }

        val atMatch = atCoordRegex.find(raw)
        if (atMatch != null) {
            val lat = atMatch.groupValues[1].toDoubleOrNull()
            val lng = atMatch.groupValues[2].toDoubleOrNull()
            if (isValidLatLng(lat, lng)) {
                return ParsedQrResult(
                    latitude = lat,
                    longitude = lng,
                    placeLabel = labelParam,
                    address = params["address"],
                    note = null,
                    rawText = raw
                )
            }
        }

        if (labelParam != null || params["address"] != null) {
            return ParsedQrResult(
                latitude = null,
                longitude = null,
                placeLabel = labelParam,
                address = params["address"],
                note = null,
                rawText = raw
            )
        }

        return null
    }

    private fun parsePlainCoordinates(raw: String): ParsedQrResult? {
        val match = coordPairRegex.find(raw) ?: return null
        // Only treat text as plain coordinates when numbers dominate (e.g. "37.42, -122.08 (Bay 4)").
        if (match.range.first > 24) return null
        val lat = match.groupValues[1].toDoubleOrNull()
        val lng = match.groupValues[2].toDoubleOrNull()
        if (!isValidLatLng(lat, lng)) return null

        val suffix = raw.substring(match.range.last + 1)
            .trim()
            .trimStart('(', '-', '|', ':', ')')
            .trimEnd(')')
            .trim()
            .takeIf { it.isNotEmpty() }

        return ParsedQrResult(
            latitude = lat,
            longitude = lng,
            placeLabel = suffix,
            address = null,
            note = null,
            rawText = raw
        )
    }

    private fun parseCoordPair(text: String): Pair<Double, Double>? {
        val match = coordPairRegex.find(text.trim()) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull()
        val lng = match.groupValues[2].toDoubleOrNull()
        return if (lat != null && lng != null && isValidLatLng(lat, lng)) {
            lat to lng
        } else {
            null
        }
    }

    private fun parseCoordPairAndLabel(text: String): Triple<Double, Double, String?>? {
        val cleaned = text.trim()
            .removePrefix("loc:")
            .removePrefix("LOC:")
            .trim()
        val match = coordPairRegex.find(cleaned) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull()
        val lng = match.groupValues[2].toDoubleOrNull()
        if (lat == null || lng == null || !isValidLatLng(lat, lng)) return null
        val after = cleaned.substring(match.range.last + 1).trim()
        val label = if (after.startsWith("(") && after.endsWith(")")) {
            after.substring(1, after.length - 1).trim().takeIf { it.isNotEmpty() }
        } else {
            after.trimStart('-', '|', ':', ' ').takeIf { it.isNotEmpty() }
        }
        return Triple(lat, lng, label)
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (pair in rawQuery.split("&")) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = decode(pair.substring(0, idx)).lowercase()
            val value = decode(pair.substring(idx + 1))
            if (value.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }

    private fun decode(value: String): String {
        return try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
    }

    private fun extractJsonString(json: String, vararg keys: String): String? {
        for (key in keys) {
            val regex = Regex(""""${Regex.escape(key)}"\s*:\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            val match = regex.find(json)?.groupValues?.get(1)?.trim()
            if (!match.isNullOrEmpty()) return match
        }
        return null
    }

    private fun isValidLatLng(lat: Double?, lng: Double?): Boolean {
        return lat != null && lng != null &&
            lat in -90.0..90.0 && lng in -180.0..180.0
    }
}
