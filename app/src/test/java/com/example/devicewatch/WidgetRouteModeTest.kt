package com.example.devicewatch

import com.example.devicewatch.navigation.NavigationManager
import com.example.devicewatch.storage.ParkingStorage
import com.example.devicewatch.widget.WidgetMapRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRouteModeTest {

    @Test
    fun greenDottedModeForcesStraightLineEvenForLongDistances() {
        assertFalse(
            WidgetMapRenderer.shouldUseRoadRoute(
                routeMode = ParkingStorage.WIDGET_ROUTE_MODE_GREEN_DOTTED,
                hasValidUser = true,
                directDistanceMeters = 1_500.0
            )
        )

        val req = WidgetMapRenderer.MapRenderRequest(
            parkedLat = 17.385044,
            parkedLng = 78.486712,
            centerLat = 17.385044,
            centerLng = 78.486712,
            userLat = 17.395044,
            userLng = 78.496712,
            zoom = 16.0,
            isExpanded = true,
            isDark = false,
            travelMode = NavigationManager.TRAVEL_WALKING,
            routeMode = ParkingStorage.WIDGET_ROUTE_MODE_GREEN_DOTTED
        )
        assertFalse(req.usesRoadRoute)
        assertEquals("green", req.routeModeTag)
    }

    @Test
    fun blueAutoModeUsesRoadRouteWhenFarAndGreenDottedWhenVeryClose() {
        assertTrue(
            WidgetMapRenderer.shouldUseRoadRoute(
                routeMode = ParkingStorage.WIDGET_ROUTE_MODE_BLUE_AUTO,
                hasValidUser = true,
                directDistanceMeters = WidgetMapRenderer.NEAR_GREEN_DOTTED_METERS
            )
        )
        assertFalse(
            WidgetMapRenderer.shouldUseRoadRoute(
                routeMode = ParkingStorage.WIDGET_ROUTE_MODE_BLUE_AUTO,
                hasValidUser = true,
                directDistanceMeters = 12.0
            )
        )
        assertFalse(
            WidgetMapRenderer.shouldUseRoadRoute(
                routeMode = ParkingStorage.WIDGET_ROUTE_MODE_BLUE_AUTO,
                hasValidUser = false,
                directDistanceMeters = 500.0
            )
        )
    }

    @Test
    fun cacheKeyDiffersBetweenGreenDottedAndBlueAutoModes() {
        val blueReq = WidgetMapRenderer.MapRenderRequest(
            parkedLat = 17.385044,
            parkedLng = 78.486712,
            centerLat = 17.385044,
            centerLng = 78.486712,
            userLat = 17.392000,
            userLng = 78.493000,
            zoom = 16.0,
            isExpanded = false,
            isDark = false,
            travelMode = NavigationManager.TRAVEL_DRIVING,
            routeMode = ParkingStorage.WIDGET_ROUTE_MODE_BLUE_AUTO
        )
        val greenReq = blueReq.copy(routeMode = ParkingStorage.WIDGET_ROUTE_MODE_GREEN_DOTTED)

        assertTrue(blueReq.usesRoadRoute)
        assertEquals("driving", blueReq.routeModeTag)
        assertFalse(greenReq.usesRoadRoute)
        assertEquals("green", greenReq.routeModeTag)
        assertNotEquals(blueReq.cacheKey, greenReq.cacheKey)
    }
}
