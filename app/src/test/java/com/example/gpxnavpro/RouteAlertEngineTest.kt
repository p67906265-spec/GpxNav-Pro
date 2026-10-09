package com.example.gpxnavpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAlertEngineTest {

    @Test
    fun dangerWinsWhenAlertsAreAtSameProgress() {
        val route = straightRoute()
        val engine = RouteAlertEngine()
        engine.setRoute(
            route,
            mapOf(
                RouteAlertType.TV to listOf(0.5),
                RouteAlertType.DANGER to listOf(0.5)
            )
        )

        val active = engine.activeAlert(0.0) { 1000 }
        assertNotNull(active)
        assertEquals(RouteAlertType.DANGER, active!!.alert.type)
    }

    @Test
    fun waypointIsProjectedOnRouteAndBecomesAlert() {
        val base = straightRoute()
        val waypoint = GpxWaypoint(
            latitude = 46.0000,
            longitude = 13.0050,
            name = "TV"
        )
        val route = base.copy(waypoints = listOf(waypoint))
        val engine = RouteAlertEngine()
        engine.setRoute(route)

        val tv = engine.routeAlerts().firstOrNull { it.type == RouteAlertType.TV }
        assertNotNull(tv)
        assertTrue(tv!!.progressMeters > 100.0)
        assertTrue(tv.progressMeters < route.distanceMeters)
    }

    @Test
    fun alertDisappearsAfterPassingTolerance() {
        val route = straightRoute()
        val engine = RouteAlertEngine()
        engine.setRoute(route, mapOf(RouteAlertType.TV to listOf(0.2)))

        val active = engine.activeAlert(150.0) { 1000 }
        assertNotNull(active)

        val passed = engine.activeAlert(260.0) { 1000 }
        assertTrue(passed == null || passed.alert.type == RouteAlertType.FINISH)
    }


    @Test
    fun repeatedLaps_createAlertForEveryWaypointPassage() {
        val oneLapPoints = listOf(
            GpxPoint(46.0000, 13.0000),
            GpxPoint(46.0000, 13.0100),
            GpxPoint(46.0100, 13.0100),
            GpxPoint(46.0100, 13.0000),
            GpxPoint(46.0000, 13.0000)
        )
        val repeatedPoints = GpxTrackEditor.repeat(oneLapPoints, 6)
        val distance = repeatedPoints.zipWithNext().sumOf { (a, b) ->
            GeoMath.haversineMeters(a, b)
        }
        val route = GpxRoute(
            name = "6 giri",
            points = repeatedPoints,
            distanceMeters = distance,
            waypoints = listOf(
                GpxWaypoint(
                    latitude = 46.0000,
                    longitude = 13.0050,
                    name = "TV"
                )
            )
        )

        val engine = RouteAlertEngine()
        engine.setRoute(route)

        val tvAlerts = engine.routeAlerts()
            .filter { it.type == RouteAlertType.TV }

        assertEquals(6, tvAlerts.size)
        assertTrue(tvAlerts.zipWithNext().all { (a, b) ->
            b.progressMeters > a.progressMeters + 100.0
        })
    }

    private fun straightRoute(): GpxRoute {
        val points = listOf(
            GpxPoint(46.0000, 13.0000),
            GpxPoint(46.0000, 13.0200)
        )
        val distance = GeoMath.haversineMeters(points[0], points[1])
        return GpxRoute("linea", points, distance)
    }
}
