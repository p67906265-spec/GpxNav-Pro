package com.example.gpxnavpro

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLon = Math.toRadians(lon2 - lon1)
        val sinLat = sin(deltaLat / 2.0)
        val sinLon = sin(deltaLon / 2.0)
        val h = sinLat * sinLat + cos(lat1Rad) * cos(lat2Rad) * sinLon * sinLon
        return 2.0 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt((1.0 - h).coerceAtLeast(0.0)))
    }

    fun haversineMeters(a: GpxPoint, b: GpxPoint): Double =
        haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)

    fun bearingDegrees(start: GpxPoint, end: GpxPoint): Double {
        val startLatitude = Math.toRadians(start.latitude)
        val endLatitude = Math.toRadians(end.latitude)
        val longitudeDelta = Math.toRadians(end.longitude - start.longitude)
        val y = sin(longitudeDelta) * cos(endLatitude)
        val x = cos(startLatitude) * sin(endLatitude) -
            sin(startLatitude) * cos(endLatitude) * cos(longitudeDelta)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun headingDifferenceDegrees(a: Double, b: Double): Double {
        val difference = kotlin.math.abs((a - b + 540.0) % 360.0 - 180.0)
        return difference.coerceIn(0.0, 180.0)
    }

    fun projectedX(lon: Double, originLon: Double, originLatRadians: Double): Double =
        Math.toRadians(lon - originLon) * EARTH_RADIUS_METERS * cos(originLatRadians)

    fun projectedY(lat: Double, originLat: Double): Double =
        Math.toRadians(lat - originLat) * EARTH_RADIUS_METERS
}
