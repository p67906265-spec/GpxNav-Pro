package com.example.gpxnavpro

import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

data class NavigationFix(
    val progressMeters: Double,
    val remainingMeters: Double,
    val distanceFromRouteMeters: Double,
    val matchedPoint: GpxPoint,
    val segmentIndex: Int,
    val routeBearing: Double
) {
    val isOffRoute: Boolean
        get() = distanceFromRouteMeters > NavigationEngine.OFF_ROUTE_THRESHOLD_METERS
}

/**
 * Motore di navigazione indipendente dalla UI.
 *
 * Aggancia la posizione GPS al segmento più vicino della traccia, invece che
 * al solo punto GPX più vicino. In questo modo progresso e distanza residua
 * rimangono fluidi anche con GPX che hanno punti molto distanti tra loro.
 */
class NavigationEngine {

    private var route: GpxRoute? = null
    private var cumulativeMeters = DoubleArray(0)
    private var lastSegmentIndex: Int? = null

    fun setRoute(route: GpxRoute) {
        this.route = route
        cumulativeMeters = DoubleArray(route.points.size)
        for (index in 1 until route.points.size) {
            cumulativeMeters[index] = cumulativeMeters[index - 1] +
                distanceMeters(route.points[index - 1], route.points[index])
        }
        lastSegmentIndex = null
    }

    fun match(location: Location): NavigationFix? {
        val currentRoute = route ?: return null
        if (currentRoute.points.size < 2) return null

        val nearbyRange = lastSegmentIndex?.let { last ->
            maxOf(0, last - SEARCH_WINDOW)..minOf(currentRoute.points.lastIndex - 1, last + SEARCH_WINDOW)
        }

        val nearbyMatch = nearbyRange?.let { findBestMatch(location, currentRoute, it) }
        val resolvedMatch =
            if (nearbyMatch == null ||
                nearbyMatch.distanceFromRouteMeters > FULL_SEARCH_DISTANCE_METERS
            ) {
                findBestMatch(location, currentRoute, 0 until currentRoute.points.lastIndex)
            } else {
                nearbyMatch
            }

        lastSegmentIndex = resolvedMatch.segmentIndex
        return resolvedMatch
    }

    private fun findBestMatch(
        location: Location,
        route: GpxRoute,
        indices: IntProgression
    ): NavigationFix {
        val earthRadius = 6_371_000.0
        val originLatitudeRadians = Math.toRadians(location.latitude)
        var bestDistance = Double.MAX_VALUE
        var bestProgress = 0.0
        var bestPoint = route.points.first()
        var bestSegment = 0
        var bestBearing = 0.0

        for (index in indices) {
            val start = route.points[index]
            val end = route.points[index + 1]
            val startX = Math.toRadians(start.longitude - location.longitude) *
                earthRadius * cos(originLatitudeRadians)
            val startY = Math.toRadians(start.latitude - location.latitude) * earthRadius
            val endX = Math.toRadians(end.longitude - location.longitude) *
                earthRadius * cos(originLatitudeRadians)
            val endY = Math.toRadians(end.latitude - location.latitude) * earthRadius
            val vectorX = endX - startX
            val vectorY = endY - startY
            val lengthSquared = vectorX * vectorX + vectorY * vectorY
            val fraction = if (lengthSquared > 0.0) {
                (-(startX * vectorX + startY * vectorY) / lengthSquared).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val closestX = startX + fraction * vectorX
            val closestY = startY + fraction * vectorY
            val distance = hypot(closestX, closestY)

            if (distance < bestDistance) {
                val segmentLength = cumulativeMeters[index + 1] - cumulativeMeters[index]
                bestDistance = distance
                bestProgress = cumulativeMeters[index] + fraction * segmentLength
                bestPoint = GpxPoint(
                    latitude = start.latitude + fraction * (end.latitude - start.latitude),
                    longitude = start.longitude + fraction * (end.longitude - start.longitude),
                    elevation = null
                )
                bestSegment = index
                bestBearing = bearing(start, end)
            }
        }

        return NavigationFix(
            progressMeters = bestProgress.coerceIn(0.0, route.distanceMeters),
            remainingMeters = (route.distanceMeters - bestProgress).coerceAtLeast(0.0),
            distanceFromRouteMeters = bestDistance,
            matchedPoint = bestPoint,
            segmentIndex = bestSegment,
            routeBearing = bestBearing
        )
    }

    private fun distanceMeters(start: GpxPoint, end: GpxPoint): Double {
        val result = FloatArray(1)
        Location.distanceBetween(
            start.latitude,
            start.longitude,
            end.latitude,
            end.longitude,
            result
        )
        return result[0].toDouble()
    }

    private fun bearing(start: GpxPoint, end: GpxPoint): Double {
        val startLatitude = Math.toRadians(start.latitude)
        val endLatitude = Math.toRadians(end.latitude)
        val longitudeDelta = Math.toRadians(end.longitude - start.longitude)
        val y = kotlin.math.sin(longitudeDelta) * cos(endLatitude)
        val x = cos(startLatitude) * kotlin.math.sin(endLatitude) -
            kotlin.math.sin(startLatitude) * cos(endLatitude) * cos(longitudeDelta)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    companion object {
        const val OFF_ROUTE_THRESHOLD_METERS = 60.0
        private const val FULL_SEARCH_DISTANCE_METERS = 250.0
        private const val SEARCH_WINDOW = 300
    }
}
