package com.example.devicewatch

import com.example.devicewatch.location.PlaceSearchClient
import com.example.devicewatch.location.SearchResultKind
import com.example.devicewatch.model.ParkingRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchClientTest {

    @Test
    fun parsesPhotonResponseWithFlippedCoordinatesDeduplicationAndUnicode() {
        val photonJson = """
            {
              "features": [
                {
                  "geometry": {
                    "type": "Point",
                    "coordinates": [77.750012, 12.969804]
                  },
                  "properties": {
                    "name": "Whitefield\u0020Hub",
                    "street": "ITPL Main Road",
                    "locality": "Whitefield",
                    "city": "Bengaluru",
                    "state": "Karnataka",
                    "country": "India",
                    "osm_value": "suburb"
                  }
                },
                {
                  "geometry": {
                    "type": "Point",
                    "coordinates": [77.750012, 12.969804]
                  },
                  "properties": {
                    "name": "Whitefield Hub",
                    "street": "ITPL Main Road",
                    "locality": "Whitefield",
                    "city": "Bengaluru",
                    "state": "Karnataka",
                    "country": "India",
                    "osm_value": "suburb"
                  }
                }
              ]
            }
        """.trimIndent()

        val results = PlaceSearchClient.parsePhotonResponse(photonJson)
        assertEquals(1, results.size)
        val first = results[0]
        assertEquals("Whitefield Hub", first.title)
        assertEquals("ITPL Main Road, Whitefield, Bengaluru, Karnataka, India", first.subtitle)
        assertEquals(12.969804, first.latitude, 0.000001)
        assertEquals(77.750012, first.longitude, 0.000001)
        assertEquals(14.8, first.suggestedZoom, 0.01)
    }

    @Test
    fun parsesNominatimResponseItemsAndPostcodeAddressDetails() {
        val nominatimJson = """
            [
              {
                "lat": "12.9698196",
                "lon": "77.7499721",
                "name": "Whitefield",
                "display_name": "Whitefield, Bengaluru, Karnataka, 560066, India",
                "addresstype": "suburb"
              },
              {
                "lat": 12.9715987,
                "lon": 77.5945627,
                "name": "",
                "display_name": "Bengaluru, Bengaluru Urban, Karnataka, India",
                "type": "city"
              },
              {
                "lat": "12.9766324",
                "lon": "77.7495738",
                "name": "560066",
                "display_name": "Whitefield, Bengaluru, Bengaluru Urban, Karnataka, 560066, India",
                "addresstype": "postcode",
                "type": "postcode",
                "address": {
                  "suburb": "Whitefield",
                  "city": "Bengaluru",
                  "state": "Karnataka",
                  "postcode": "560066",
                  "country": "India"
                }
              }
            ]
        """.trimIndent()

        val results = PlaceSearchClient.parseNominatimResponse(nominatimJson)
        assertEquals(3, results.size)

        assertEquals("Whitefield", results[0].title)
        assertEquals("Bengaluru, Karnataka, 560066, India", results[0].subtitle)
        assertEquals(12.9698196, results[0].latitude, 0.000001)
        assertEquals(77.7499721, results[0].longitude, 0.000001)
        assertEquals(14.8, results[0].suggestedZoom, 0.01)

        assertEquals("Bengaluru", results[1].title)
        assertEquals("Bengaluru Urban, Karnataka, India", results[1].subtitle)
        assertEquals(12.5, results[1].suggestedZoom, 0.01)

        assertEquals("Whitefield (560066)", results[2].title)
        assertEquals("Bengaluru, Karnataka, India", results[2].subtitle)
        assertEquals(SearchResultKind.POSTCODE, results[2].kind)
        assertEquals(14.0, results[2].suggestedZoom, 0.01)
    }

    @Test
    fun parsesFlexibleCoordinatesAcrossDecimalLabeledDmsHemiAndLinks() {
        val commaCoords = PlaceSearchClient.parseDirectCoordinateQuery("12.9698, 77.7500", "Coordinates")
        assertNotNull(commaCoords)
        assertEquals(12.9698, commaCoords!!.latitude, 0.0001)
        assertEquals(77.7500, commaCoords.longitude, 0.0001)

        val labeledCoords = PlaceSearchClient.parseDirectCoordinateQuery("lat: 17.3850, lng: 78.4867")
        assertNotNull(labeledCoords)
        assertEquals(17.3850, labeledCoords!!.latitude, 0.0001)
        assertEquals(78.4867, labeledCoords.longitude, 0.0001)

        val reversedLabeledCoords = PlaceSearchClient.parseDirectCoordinateQuery("lon: 78.4867, lat: 17.3850")
        assertNotNull(reversedLabeledCoords)
        assertEquals(17.3850, reversedLabeledCoords!!.latitude, 0.0001)
        assertEquals(78.4867, reversedLabeledCoords.longitude, 0.0001)

        val dmsEastFirst = PlaceSearchClient.parseDirectCoordinateQuery("""77°45'00.0"E 12°58'11.3"N""")
        assertNotNull(dmsEastFirst)
        assertEquals(12.969805, dmsEastFirst!!.latitude, 0.0001)
        assertEquals(77.750000, dmsEastFirst.longitude, 0.0001)

        val prefixHemi = PlaceSearchClient.parseDirectCoordinateQuery("S33.8688 E151.2093")
        assertNotNull(prefixHemi)
        assertEquals(-33.8688, prefixHemi!!.latitude, 0.0001)
        assertEquals(151.2093, prefixHemi.longitude, 0.0001)

        val osmLink = PlaceSearchClient.parseDirectCoordinateQuery("https://www.openstreetmap.org/#map=17/12.9698/77.7500")
        assertNotNull(osmLink)
        assertEquals(12.9698, osmLink!!.latitude, 0.0001)
        assertEquals(77.7500, osmLink.longitude, 0.0001)
        assertEquals(17.0, osmLink.suggestedZoom, 0.01)

        val googlePlaceLink = PlaceSearchClient.parseDirectCoordinateQuery(
            "https://www.google.com/maps/place/Whitefield+Mall/@12.9968,77.6964,17z"
        )
        assertNotNull(googlePlaceLink)
        assertEquals(12.9968, googlePlaceLink!!.latitude, 0.0001)
        assertEquals(77.6964, googlePlaceLink.longitude, 0.0001)
        assertEquals("Whitefield Mall", googlePlaceLink.title)

        // Swapped lng, lat near India offers swapped (lat, lng) option cleanly
        val swappedResults = PlaceSearchClient.parseCoordinateResults(
            rawQuery = "77.7500, 12.9698",
            biasLat = 17.3850,
            biasLng = 78.4867
        )
        assertEquals(2, swappedResults.size)
        assertEquals(12.9698, swappedResults[1].latitude, 0.0001)
        assertEquals(77.7500, swappedResults[1].longitude, 0.0001)

        // Reject street/pincode-looking queries cleanly
        assertNull(PlaceSearchClient.parseDirectCoordinateQuery("95.0, 200.0"))
        assertNull(PlaceSearchClient.parseDirectCoordinateQuery("560 066"))
        assertNull(PlaceSearchClient.parseDirectCoordinateQuery("12, MG Road"))
        assertNull(PlaceSearchClient.parseDirectCoordinateQuery("Whitefield"))
    }

    @Test
    fun detectsIndianAndInternationalPostalCodesCleanly() {
        val hydPin = PlaceSearchClient.detectPostalQuery("500 081", biasLat = 17.385, biasLng = 78.486, defaultCountryCode = "IN")
        assertNotNull(hydPin)
        assertEquals("500081", hydPin!!.normalizedCode)
        assertEquals("in", hydPin.preferredCountryCode)

        val prefixedPin = PlaceSearchClient.detectPostalQuery("pincode: 560066", defaultCountryCode = "US")
        assertNotNull(prefixedPin)
        assertEquals("560066", prefixedPin!!.normalizedCode)
        assertEquals("in", prefixedPin.preferredCountryCode)

        val ukPin = PlaceSearchClient.detectPostalQuery("sw1a1aa")
        assertNotNull(ukPin)
        assertEquals("SW1A 1AA", ukPin!!.normalizedCode)
        assertEquals("gb", ukPin.preferredCountryCode)

        val usZip = PlaceSearchClient.detectPostalQuery("zip 94043-1351")
        assertNotNull(usZip)
        assertEquals("94043", usZip!!.normalizedCode)
        assertEquals("us", usZip.preferredCountryCode)

        assertFalse(PlaceSearchClient.isDirectCoordinateOrLink("560066"))
        assertNull(PlaceSearchClient.detectPostalQuery("Whitefield Bengaluru"))
    }

    @Test
    fun searchesLocalSavedAndHistorySpotsByTokens() {
        val records = listOf(
            ParkingRecord(
                "id-1",
                12.9698,
                77.7500,
                "Phoenix Marketcity B2",
                "Whitefield Main Rd, Bengaluru",
                1_000L,
                "Near east pillar",
                emptyList(),
                true
            ),
            ParkingRecord(
                "id-2",
                17.4401,
                78.3489,
                "Office Visitor Gate",
                "Gachibowli, Hyderabad",
                2_000L,
                null,
                emptyList(),
                false
            )
        )

        val mallMatches = PlaceSearchClient.searchLocalRecords("phoenix b2", records)
        assertEquals(1, mallMatches.size)
        assertEquals("Phoenix Marketcity B2", mallMatches[0].title)
        assertEquals(SearchResultKind.SAVED_SPOT, mallMatches[0].kind)
        assertEquals("Phoenix Marketcity B2", mallMatches[0].savedLabel)

        val hydMatches = PlaceSearchClient.searchLocalRecords("gachibowli", records)
        assertEquals(1, hydMatches.size)
        assertEquals(SearchResultKind.HISTORY_SPOT, hydMatches[0].kind)
    }

    @Test
    fun handlesMalformedOrEmptyResponsesGracefully() {
        assertTrue(PlaceSearchClient.parsePhotonResponse("").isEmpty())
        assertTrue(PlaceSearchClient.parsePhotonResponse("{bad json").isEmpty())
        assertTrue(PlaceSearchClient.parsePhotonResponse("""{"features":[{"geometry":{"coordinates":[200.0,95.0]},"properties":{"name":"Bad"}}]}""").isEmpty())

        assertTrue(PlaceSearchClient.parseNominatimResponse("").isEmpty())
        assertTrue(PlaceSearchClient.parseNominatimResponse("[{broken").isEmpty())
        assertTrue(PlaceSearchClient.parseNominatimResponse("""[{"lat":"not-a-num","lon":"77.75","display_name":"X"}]""").isEmpty())
        assertNull(PlaceSearchClient.parseNominatimReverseSummary("{bad"))
    }

    @Test
    fun buildsEncodedUrlsWithProximityBiasAndPostalEndpoints() {
        val photonBiased = PlaceSearchClient.buildPhotonUrl("airport", biasLat = 17.385, biasLng = 78.4867)
        assertTrue(photonBiased.startsWith("https://photon.komoot.io/api/?q=airport"))
        assertTrue(photonBiased.contains("lat=17.38500"))
        assertTrue(photonBiased.contains("lon=78.48670"))

        val nominatimUrl = PlaceSearchClient.buildNominatimUrl("bengaluru, white field")
        assertTrue(nominatimUrl.startsWith("https://nominatim.openstreetmap.org/search?q=bengaluru%2C+white+field"))
        assertTrue(nominatimUrl.contains("format=jsonv2"))

        val nominatimPostal = PlaceSearchClient.buildNominatimPostalUrl("560066", "in")
        assertEquals(
            "https://nominatim.openstreetmap.org/search?postalcode=560066&countrycodes=in&format=jsonv2&addressdetails=1&limit=6",
            nominatimPostal
        )
    }
}
