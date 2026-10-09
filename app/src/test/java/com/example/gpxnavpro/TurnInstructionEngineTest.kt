package com.example.gpxnavpro

import org.junit.Assert.assertNotNull
import org.junit.Test

class TurnInstructionEngineTest {

    @Test
    fun detectsTurnUsingJvmSafeGeoMath() {
        val route = GpxRoute(
            name = "turn",
            points = listOf(
                GpxPoint(46.0000, 13.0000),
                GpxPoint(46.0000, 13.0020),
                GpxPoint(46.0020, 13.0020)
            ),
            distanceMeters =
                GeoMath.haversineMeters(46.0000, 13.0000, 46.0000, 13.0020) +
                GeoMath.haversineMeters(46.0000, 13.0020, 46.0020, 13.0020)
        )

        val engine = TurnInstructionEngine()
        engine.setRoute(route)

        assertNotNull(engine.next(0.0))
    }
}
