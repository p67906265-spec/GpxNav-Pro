package com.example.gpxnavpro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEngineTest {

    @Test
    fun ringRoute_progressNeverMovesBackward() {
        val points = listOf(
            GpxPoint(46.0000, 13.0000),
            GpxPoint(46.0000, 13.0100),
            GpxPoint(46.0100, 13.0100),
            GpxPoint(46.0100, 13.0000),
            GpxPoint(46.0000, 13.0000)
        )
        val route = route(points)
        val engine = NavigationEngine().apply { setRoute(route) }

        val first = engine.match(
            NavigationSample(46.0000, 13.0010, bearingDegrees = 90.0, accuracyMeters = 4.0)
        )!!
        val second = engine.match(
            NavigationSample(46.0090, 13.0100, bearingDegrees = 0.0, accuracyMeters = 4.0)
        )!!
        val noisyBehind = engine.match(
            NavigationSample(46.0000, 13.0020, bearingDegrees = 90.0, accuracyMeters = 8.0)
        )!!

        assertTrue(second.progressMeters >= first.progressMeters)
        assertTrue(noisyBehind.progressMeters >= second.progressMeters)
    }

    @Test
    fun offRoute_usesAccuracyAllowance() {
        val route = route(
            listOf(
                GpxPoint(46.0000, 13.0000),
                GpxPoint(46.0000, 13.0200)
            )
        )
        val precise = NavigationEngine().apply { setRoute(route) }
            .match(NavigationSample(46.00075, 13.0100, accuracyMeters = 5.0))!!
        val imprecise = NavigationEngine().apply { setRoute(route) }
            .match(NavigationSample(46.00075, 13.0100, accuracyMeters = 35.0))!!

        assertTrue(precise.isOffRoute)
        assertFalse(imprecise.isOffRoute)
    }

    @Test
    fun bearingDisambiguatesClosedRingStart() {
        val points = listOf(
            GpxPoint(46.0000, 13.0000),
            GpxPoint(46.0000, 13.0100),
            GpxPoint(46.0100, 13.0100),
            GpxPoint(46.0100, 13.0000),
            GpxPoint(46.0000, 13.0000)
        )
        val route = route(points)
        val engine = NavigationEngine().apply { setRoute(route) }
        val fix = engine.match(
            NavigationSample(46.0000, 13.0001, bearingDegrees = 90.0, accuracyMeters = 3.0)
        )!!

        assertTrue("Deve scegliere il primo lato dell'anello", fix.segmentIndex == 0)
        assertTrue(fix.headingDifferenceDegrees ?: 180.0 < 30.0)
    }

    @Test
    fun prolongedOffRoute_allowsRecoveryToEarlierSegment() {
        val points = listOf(
            GpxPoint(46.0000, 13.0000),
            GpxPoint(46.0000, 13.0300)
        )
        val route = route(points)
        val engine = NavigationEngine().apply { setRoute(route) }

        val nearEnd = engine.match(
            NavigationSample(46.0000, 13.0280, bearingDegrees = 90.0, accuracyMeters = 4.0)
        )!!
        assertTrue(nearEnd.progressMeters > route.distanceMeters * 0.8)

        repeat(6) {
            engine.match(
                NavigationSample(46.0015, 13.0040, bearingDegrees = 90.0, accuracyMeters = 4.0)
            )
        }

        val recovered = engine.match(
            NavigationSample(46.0000, 13.0045, bearingDegrees = 90.0, accuracyMeters = 4.0)
        )!!

        assertTrue(
            "Dopo un fuori-percorso prolungato deve poter riagganciare un tratto precedente",
            recovered.progressMeters < nearEnd.progressMeters
        )
    }

    private fun route(points: List<GpxPoint>): GpxRoute {
        val distance = points.zipWithNext().sumOf { (a, b) -> GeoMath.haversineMeters(a, b) }
        return GpxRoute("test", points, distance)
    }
}
