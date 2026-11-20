package com.example.devicewatch

import com.example.devicewatch.location.ParkingQrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingQrParserTest {

    @Test
    fun parsesGeoUriWithLabelIntoLabelOnly() {
        val result = ParkingQrParser.parse("geo:0,0?q=37.421998,-122.084(Garage%20B2)")
        assertNotNull(result)
        assertTrue(result!!.hasCoordinates)
        assertEquals(37.421998, result.latitude!!, 0.0001)
        assertEquals(-122.084, result.longitude!!, 0.0001)
        assertEquals("Garage B2", result.placeLabel)
        assertNull(result.note)
    }

    @Test
    fun parsesLocationOnlyGoogleMapsUrlWithNullLabel() {
        val result = ParkingQrParser.parse("https://maps.google.com/?q=17.3850,78.4867")
        assertNotNull(result)
        assertTrue(result!!.hasCoordinates)
        assertEquals(17.3850, result.latitude!!, 0.0001)
        assertEquals(78.4867, result.longitude!!, 0.0001)
        assertNull(result.placeLabel)
        assertNull(result.note)
    }

    @Test
    fun parsesGoogleMapsPlacePathAndLocPrefix() {
        val placeResult = ParkingQrParser.parse(
            "https://www.google.com/maps/place/Central+Mall+Basement+P2/@17.3850,78.4867,18z"
        )
        assertNotNull(placeResult)
        assertTrue(placeResult!!.hasCoordinates)
        assertEquals(17.3850, placeResult.latitude!!, 0.0001)
        assertEquals(78.4867, placeResult.longitude!!, 0.0001)
        assertEquals("Central Mall Basement P2", placeResult.placeLabel)
        assertNull(placeResult.note)

        val locResult = ParkingQrParser.parse("https://maps.google.com/?q=loc:37.4220,-122.0841")
        assertNotNull(locResult)
        assertTrue(locResult!!.hasCoordinates)
        assertEquals(37.4220, locResult.latitude!!, 0.0001)
        assertEquals(-122.0841, locResult.longitude!!, 0.0001)
        assertNull(locResult.placeLabel)
        assertNull(locResult.note)
    }

    @Test
    fun parsesJsonPayloadAndMergesAllDetailsIntoLabelOnly() {
        val explicitResult = ParkingQrParser.parse(
            """{"lat":40.7128,"lng":-74.006,"label":"Lot C","note":"Level 2 Bay 18"}"""
        )
        assertNotNull(explicitResult)
        assertTrue(explicitResult!!.hasCoordinates)
        assertEquals(40.7128, explicitResult.latitude!!, 0.0001)
        assertEquals(-74.006, explicitResult.longitude!!, 0.0001)
        assertEquals("Lot C • Level 2 Bay 18", explicitResult.placeLabel)
        assertNull(explicitResult.note)

        val garageResult = ParkingQrParser.parse("""{"floor":"B2","bay":"P14"}""")
        assertNotNull(garageResult)
        assertFalse(garageResult!!.hasCoordinates)
        assertEquals("B2 • P14", garageResult.placeLabel)
        assertNull(garageResult.note)
    }

    @Test
    fun parsesPlainCoordinatesOnlyOrWithSuffixLabel() {
        val locOnly = ParkingQrParser.parse("51.5074, -0.1278")
        assertNotNull(locOnly)
        assertTrue(locOnly!!.hasCoordinates)
        assertNull(locOnly.placeLabel)
        assertNull(locOnly.note)

        val withLabel = ParkingQrParser.parse("51.5074, -0.1278 - West Zone")
        assertNotNull(withLabel)
        assertTrue(withLabel!!.hasCoordinates)
        assertEquals("West Zone", withLabel.placeLabel)
        assertNull(withLabel.note)
    }

    @Test
    fun parsesMultiLinePlainTextAllIntoLabelAndNeverIntoNote() {
        val result = ParkingQrParser.parse("Level B2 - Pillar 14\nNear East Elevator")
        assertNotNull(result)
        assertFalse(result!!.hasCoordinates)
        assertNull(result.latitude)
        assertEquals("Level B2 - Pillar 14 • Near East Elevator", result.placeLabel)
        assertNull(result.note)
    }

    @Test
    fun ignoresOutOfBoundsCoordinates() {
        val result = ParkingQrParser.parse("95.0, 200.0")
        assertNotNull(result)
        assertFalse(result!!.hasCoordinates)
        assertNull(result.latitude)
        assertEquals("95.0, 200.0", result.placeLabel)
        assertNull(result.note)
    }
}
