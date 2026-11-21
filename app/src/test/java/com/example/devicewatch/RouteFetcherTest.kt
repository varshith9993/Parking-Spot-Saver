package com.example.devicewatch

import com.example.devicewatch.navigation.NavigationManager
import com.example.devicewatch.navigation.RouteFetcher
import com.example.devicewatch.navigation.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteFetcherTest {

    @Test
    fun parsesValidOsrmGeoJsonCoordinatesInLatLngOrder() {
        val sampleJson = """
            {
              "code": "Ok",
              "routes": [
                {
                  "geometry": {
                    "coordinates": [
                      [78.486712, 17.385044],
                      [78.48685, 17.38519],
                      [78.487123, 17.38551]
                    ],
                    "type": "LineString"
                  },
                  "distance": 120.5
                }
              ]
            }
        """.trimIndent()

        val points = RouteFetcher.parseOsrmGeoJsonCoordinates(sampleJson)
        assertEquals(3, points.size)
        assertEquals(17.385044, points[0].latitude, 0.000001)
        assertEquals(78.486712, points[0].longitude, 0.000001)
        assertEquals(17.38519, points[1].latitude, 0.000001)
        assertEquals(78.48685, points[1].longitude, 0.000001)
        assertEquals(17.38551, points[2].latitude, 0.000001)
        assertEquals(78.487123, points[2].longitude, 0.000001)
    }

    @Test
    fun parsesNegativeAndAltitudeCoordinatesWithWhitespace() {
        val sampleJson = """
            {
              "code" : "Ok",
              "routes": [{
                "geometry": {
                  "coordinates": [
                    [-122.0841, 37.4220, 12.5],
                    [ -122.0852 , -33.8688 ]
                  ]
                }
              }]
            }
        """.trimIndent()

        val points = RouteFetcher.parseOsrmGeoJsonCoordinates(sampleJson)
        assertEquals(2, points.size)
        assertEquals(37.4220, points[0].latitude, 0.000001)
        assertEquals(-122.0841, points[0].longitude, 0.000001)
        assertEquals(-33.8688, points[1].latitude, 0.000001)
        assertEquals(-122.0852, points[1].longitude, 0.000001)
    }

    @Test
    fun returnsEmptyListOnErrorStatusEmptyOrMalformedJson() {
        assertTrue(
            RouteFetcher.parseOsrmGeoJsonCoordinates(
                """{"code":"NoRoute","routes":[{"geometry":{"coordinates":[[78.48,17.38],[78.49,17.39]]}}]}"""
            ).isEmpty()
        )
        assertTrue(
            RouteFetcher.parseOsrmGeoJsonCoordinates(
                """{"code":"Ok","routes":[{"geometry":{"coordinates":[]}}]}"""
            ).isEmpty()
        )
        assertTrue(
            RouteFetcher.parseOsrmGeoJsonCoordinates(
                """{"code":"Ok","routes":[{"geometry":{"coordinates":[[78.48,17.38]]}}]}"""
            ).isEmpty()
        )
        assertTrue(
            RouteFetcher.parseOsrmGeoJsonCoordinates(
                """{"code":"Ok","routes":[{"geometry":{"coordinates":[["bad",17.38],[78.49,17.39]]}}]}"""
            ).isEmpty()
        )
        assertTrue(
            RouteFetcher.parseOsrmGeoJsonCoordinates(
                """{"code":"Ok","routes":[{"geometry":{"coordinates":[[200.0,95.0],[78.49,17.39]]}}]}"""
            ).isEmpty()
        )
        assertTrue(RouteFetcher.parseOsrmGeoJsonCoordinates("").isEmpty())
    }

    @Test
    fun connectsOriginAndDestinationOnlyWhenSnappedEndpointsDiffer() {
        val snappedOrigin = RoutePoint(17.385200, 78.486712)
        val snappedDest = RoutePoint(17.386200, 78.487712)
        val roadPoints = listOf(snappedOrigin, snappedDest)

        val prependedAndAppended = RouteFetcher.buildConnectedRoutePoints(
            originLat = 17.385000,
            originLng = 78.486712,
            destLat = 17.386400,
            destLng = 78.487712,
            roadPoints = roadPoints
        )
        assertEquals(4, prependedAndAppended.size)
        assertEquals(RoutePoint(17.385000, 78.486712), prependedAndAppended.first())
        assertEquals(RoutePoint(17.386400, 78.487712), prependedAndAppended.last())

        val noDuplicateEnds = RouteFetcher.buildConnectedRoutePoints(
            originLat = 17.385200,
            originLng = 78.486712,
            destLat = 17.386200,
            destLng = 78.487712,
            roadPoints = roadPoints
        )
        assertEquals(2, noDuplicateEnds.size)
    }

    @Test
    fun buildsOsrmUrlAndResolvesProfilesCorrectly() {
        assertEquals("driving", RouteFetcher.resolveOsrmProfile(NavigationManager.TRAVEL_DRIVING))
        assertEquals("foot", RouteFetcher.resolveOsrmProfile(NavigationManager.TRAVEL_WALKING))
        assertEquals("foot", RouteFetcher.resolveOsrmProfile("unknown"))

        val url = RouteFetcher.buildOsrmUrl(
            originLat = 17.385044,
            originLng = 78.486712,
            destLat = 17.391000,
            destLng = 78.492000,
            travelMode = NavigationManager.TRAVEL_DRIVING
        )
        assertEquals(
            "https://router.project-osrm.org/route/v1/driving/78.486712,17.385044;78.492000,17.391000?overview=full&geometries=geojson",
            url
        )
    }

    @Test
    fun haversineAndFallbackRouteProduceExpectedValues() {
        assertEquals(0.0, RouteFetcher.haversineMeters(17.385, 78.486, 17.385, 78.486), 0.001)
        val oneDegreeLatMeters = RouteFetcher.haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertTrue(oneDegreeLatMeters in 110_500.0..111_500.0)

        val fallback = RouteFetcher.fallbackStraightRoute(17.385, 78.486, 17.390, 78.490)
        assertFalse(fallback.isRoadRoute)
        assertEquals(2, fallback.points.size)
    }

    @Test
    fun calculatePolylineMetersHandlesEmptyDetourAndStraightSegments() {
        assertEquals(0.0, RouteFetcher.calculatePolylineMeters(emptyList()), 0.001)
        assertEquals(
            0.0,
            RouteFetcher.calculatePolylineMeters(listOf(RoutePoint(17.385, 78.486))),
            0.001
        )

        val a = RoutePoint(17.3850, 78.4860)
        val mid = RoutePoint(17.3865, 78.4880)
        val b = RoutePoint(17.3870, 78.4860)
        val straightMeters = RouteFetcher.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        val detourMeters = RouteFetcher.calculatePolylineMeters(listOf(a, mid, b))
        val expected = RouteFetcher.haversineMeters(a.latitude, a.longitude, mid.latitude, mid.longitude) +
            RouteFetcher.haversineMeters(mid.latitude, mid.longitude, b.latitude, b.longitude)

        assertEquals(expected, detourMeters, 0.001)
        assertTrue(detourMeters > straightMeters)
    }
}
