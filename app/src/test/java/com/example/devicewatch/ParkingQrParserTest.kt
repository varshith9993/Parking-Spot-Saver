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
    fun parsesGeoUriWithLabel() {
        val result = ParkingQrParser.parse("geo:0,0?q=37.421998,-122.084(Garage%20B2)")
        assertNotNull(result)
        assertTrue(result!!.hasCoordinates)
        assertEquals(37.421998, result.latitude!!, 0.0001)
        assertEquals(-122.084, result.longitude!!, 0.0001)
        assertEquals("Garage B2", result.placeLabel)
    }

    @Test
    fun parsesGoogleMapsQueryUrl() {
        val result = ParkingQrParser.parse("https://maps.google.com/?q=17.3850,78.4867&label=Pillar%20A4")
        assertNotNull(result)
        assertTrue(result!!.hasCoordinates)
        assertEquals(17.3850, result.latitude!!, 0.0001)
        assertEquals(78.4867, result.longitude!!, 0.0001)
        assertEquals("Pillar A4", result.placeLabel)
    }

    @Test
    fun parsesGoogleMapsAtPathUrl() {
        val result = ParkingQrParser.parse("https://www.google.com/maps/@12.9716,77.5946,18z")
        assertNotNull(result)
        assertTrue(result!!.hasCoordinates)
        assertEquals(12.9716, result.latitude!!, 0.0001)
        assertEquals(77.5946, result.longitude!!, 0.0001)
    }

    @Test
    fun parsesJsonPayload() {
        val result = ParkingQrParser.parse(
            """{"lat":40.7128,"lng":-74.006,"label":"Lot C","note":"Level 2 Bay 18"}"""
        )
        assertNotNull(result)
        assertTrue(result!!.hasCoordinates)
        assertEquals(40.7128, result.latitude!!, 0.0001)
        assertEquals(-74.006, result.longitude!!, 0.0001)
        assertEquals("Lot C", result.placeLabel)
        assertEquals("Level 2 Bay 18", result.note)
    }

    @Test
    fun parsesPlainCoordinatesAndSuffixLabel() {
        val result = ParkingQrParser.parse("51.5074, -0.1278 - West Zone")
        assertNotNull(result)
        assertTrue(result!!.hasCoordinates)
        assertEquals(51.5074, result.latitude!!, 0.0001)
        assertEquals(-0.1278, result.longitude!!, 0.0001)
        assertEquals("West Zone", result.placeLabel)
    }

    @Test
    fun parsesPlainTextBayAsLabelAndNoteWithoutCoordinates() {
        val result = ParkingQrParser.parse("Level B2 - Pillar 14\nNear East Elevator")
        assertNotNull(result)
        assertFalse(result!!.hasCoordinates)
        assertNull(result.latitude)
        assertEquals("Level B2 - Pillar 14", result.placeLabel)
        assertEquals("Near East Elevator", result.note)
    }

    @Test
    fun ignoresOutOfBoundsCoordinates() {
        val result = ParkingQrParser.parse("95.0, 200.0")
        assertNotNull(result)
        assertFalse(result!!.hasCoordinates)
        assertNull(result.latitude)
        assertEquals("95.0, 200.0", result.placeLabel)
    }
}
