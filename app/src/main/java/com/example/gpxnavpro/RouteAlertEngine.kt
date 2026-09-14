package com.example.gpxnavpro

import android.location.Location
import java.text.Normalizer
import kotlin.math.cos
import kotlin.math.hypot

enum class RouteAlertType(
    val shortLabel: String,
    val preferenceKey: String,
    val color: String
) {
    TV("TV", "alert_tv_distance", "#16A34A"),
    GPM("GPM", "alert_gpm_distance", "#F59E0B"),
    GREEN_ZONE("GREEN ZONE", "alert_gz_distance", "#22C55E"),
    REFRESHMENT("RIFORNIMENTO", "alert_refreshment_distance", "#2563EB"),
    DANGER("PERICOLO", "alert_danger_distance", "#DC2626"),
    FINISH("ARRIVO", "alert_finish_distance", "#111827")
}

data class RouteAlert(
    val type: RouteAlertType,
    val name: String,
    val progressMeters: Double
)

data class ActiveRouteAlert(
    val alert: RouteAlert,
    val distanceMeters: Double
)

class RouteAlertEngine {

    private var alerts: List<RouteAlert> = emptyList()

    fun routeAlerts(): List<RouteAlert> = alerts

    fun setRoute(
        route: GpxRoute,
        manualKilometers: Map<RouteAlertType, List<Double>> = emptyMap()
    ) {
        val detected = route.waypoints.mapNotNull { waypoint ->
            val type = detectType(waypoint) ?: return@mapNotNull null
            RouteAlert(
                type = type,
                name = waypoint.name,
                progressMeters = projectOnRoute(route, waypoint.latitude, waypoint.longitude)
            )
        }.toMutableList()

        manualKilometers.forEach { (type, kilometers) ->
            kilometers.forEachIndexed { index, kilometer ->
                detected += RouteAlert(
                    type = type,
                    name = if (kilometers.size > 1) {
                        "${type.shortLabel} ${index + 1}"
                    } else {
                        type.shortLabel
                    },
                    progressMeters = (kilometer * 1000.0)
                        .coerceIn(0.0, route.distanceMeters)
                )
            }
        }

        if (detected.none { it.type == RouteAlertType.FINISH }) {
            detected += RouteAlert(
                type = RouteAlertType.FINISH,
                name = "Arrivo",
                progressMeters = route.distanceMeters
            )
        }
        alerts = detected.sortedBy { it.progressMeters }
    }

    fun activeAlert(
        currentProgressMeters: Double,
        thresholdFor: (RouteAlertType) -> Int
    ): ActiveRouteAlert? {
        return alerts.asSequence()
            .map { alert ->
                ActiveRouteAlert(
                    alert = alert,
                    distanceMeters = alert.progressMeters - currentProgressMeters
                )
            }
            .filter { it.distanceMeters >= -PASSED_EVENT_TOLERANCE_METERS }
            .filter { it.distanceMeters <= thresholdFor(it.alert.type) }
            .minWithOrNull(
                compareBy<ActiveRouteAlert> { it.distanceMeters.coerceAtLeast(0.0) }
                    .thenBy { priority(it.alert.type) }
            )
    }

    private fun detectType(waypoint: GpxWaypoint): RouteAlertType? {
        val normalized = normalize(
            listOfNotNull(
                waypoint.name,
                waypoint.description,
                waypoint.symbol,
                waypoint.type
            ).joinToString(" ")
        )
        val tokens = normalized.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
        return when {
            "pericolo" in tokens || "danger" in tokens || "hazard" in tokens ->
                RouteAlertType.DANGER
            "gran premio montagna" in normalized || "gpm" in tokens ->
                RouteAlertType.GPM
            "traguardo volante" in normalized || "tv" in tokens ->
                RouteAlertType.TV
            "green zone" in normalized || "zona verde" in normalized || "gz" in tokens ->
                RouteAlertType.GREEN_ZONE
            "rifornimento" in tokens || "ristoro" in tokens || "refreshment" in tokens ->
                RouteAlertType.REFRESHMENT
            "arrivo" in tokens || "finish" in tokens || "fine" in tokens ->
                RouteAlertType.FINISH
            else -> null
        }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    private fun projectOnRoute(
        route: GpxRoute,
        latitude: Double,
        longitude: Double
    ): Double {
        if (route.points.size < 2) return 0.0
        val earthRadius = 6_371_000.0
        val latitudeRadians = Math.toRadians(latitude)
        var cumulative = 0.0
        var bestDistance = Double.MAX_VALUE
        var bestProgress = 0.0

        for (index in 0 until route.points.lastIndex) {
            val start = route.points[index]
            val end = route.points[index + 1]
            val startX = Math.toRadians(start.longitude - longitude) *
                earthRadius * cos(latitudeRadians)
            val startY = Math.toRadians(start.latitude - latitude) * earthRadius
            val endX = Math.toRadians(end.longitude - longitude) *
                earthRadius * cos(latitudeRadians)
            val endY = Math.toRadians(end.latitude - latitude) * earthRadius
            val vectorX = endX - startX
            val vectorY = endY - startY
            val lengthSquared = vectorX * vectorX + vectorY * vectorY
            val fraction = if (lengthSquared > 0.0) {
                (-(startX * vectorX + startY * vectorY) / lengthSquared).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val distanceToRoute = hypot(
                startX + fraction * vectorX,
                startY + fraction * vectorY
            )
            val segmentLength = distance(start, end)
            if (distanceToRoute < bestDistance) {
                bestDistance = distanceToRoute
                bestProgress = cumulative + fraction * segmentLength
            }
            cumulative += segmentLength
        }
        return bestProgress.coerceIn(0.0, route.distanceMeters)
    }

    private fun distance(start: GpxPoint, end: GpxPoint): Double {
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

    private fun priority(type: RouteAlertType): Int = when (type) {
        RouteAlertType.DANGER -> 0
        RouteAlertType.FINISH -> 1
        RouteAlertType.GPM -> 2
        RouteAlertType.TV -> 3
        RouteAlertType.GREEN_ZONE -> 4
        RouteAlertType.REFRESHMENT -> 5
    }

    companion object {
        private const val PASSED_EVENT_TOLERANCE_METERS = 30.0
    }
}
