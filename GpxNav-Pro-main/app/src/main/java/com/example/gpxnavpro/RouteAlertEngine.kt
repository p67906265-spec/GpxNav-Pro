package com.example.gpxnavpro

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
        val detected = route.waypoints.flatMap { waypoint ->
            val type = detectType(waypoint) ?: return@flatMap emptyList()
            projectAllPassages(
                route = route,
                latitude = waypoint.latitude,
                longitude = waypoint.longitude,
                maximumDistanceMeters = WAYPOINT_PASSAGE_RADIUS_METERS
            ).mapIndexed { index, progress ->
                RouteAlert(
                    type = type,
                    name = if (index == 0) waypoint.name else "${waypoint.name} ${index + 1}",
                    progressMeters = progress
                )
            }
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

    private fun projectAllPassages(
        route: GpxRoute,
        latitude: Double,
        longitude: Double,
        maximumDistanceMeters: Double
    ): List<Double> {
        if (route.points.size < 2) return emptyList()

        val earthRadius = 6_371_000.0
        val latitudeRadians = Math.toRadians(latitude)
        var cumulative = 0.0
        val candidates = mutableListOf<Pair<Double, Double>>()

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
                (-(startX * vectorX + startY * vectorY) / lengthSquared)
                    .coerceIn(0.0, 1.0)
            } else {
                0.0
            }

            val distanceToRoute = hypot(
                startX + fraction * vectorX,
                startY + fraction * vectorY
            )
            val segmentLength = distance(start, end)
            val progress = cumulative + fraction * segmentLength

            if (distanceToRoute <= maximumDistanceMeters) {
                candidates += progress to distanceToRoute
            }
            cumulative += segmentLength
        }

        if (candidates.isEmpty()) return emptyList()

        // Segmenti consecutivi attorno allo stesso passaggio possono produrre
        // più candidati. Li raggruppiamo per distanza lungo la traccia,
        // mantenendo il punto geometricamente più vicino al waypoint.
        val sorted = candidates.sortedBy { it.first }
        val passages = mutableListOf<Pair<Double, Double>>()
        for (candidate in sorted) {
            val previous = passages.lastOrNull()
            if (previous == null ||
                candidate.first - previous.first > PASSAGE_DEDUP_PROGRESS_METERS
            ) {
                passages += candidate
            } else if (candidate.second < previous.second) {
                passages[passages.lastIndex] = candidate
            }
        }

        return passages.map { it.first.coerceIn(0.0, route.distanceMeters) }
    }

    private fun distance(start: GpxPoint, end: GpxPoint): Double =
        GeoMath.haversineMeters(start, end)

    private fun priority(type: RouteAlertType): Int = when (type) {
        RouteAlertType.DANGER -> 0
        RouteAlertType.FINISH -> 1
        RouteAlertType.GPM -> 2
        RouteAlertType.TV -> 3
        RouteAlertType.GREEN_ZONE -> 4
        RouteAlertType.REFRESHMENT -> 5
    }

    companion object {
        private const val WAYPOINT_PASSAGE_RADIUS_METERS = 30.0
        private const val PASSAGE_DEDUP_PROGRESS_METERS = 60.0
        private const val PASSED_EVENT_TOLERANCE_METERS = 30.0
    }
}
