package com.example.gpxnavpro

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Un punto geografico letto da una traccia GPX. */
data class GpxPoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null
)

/** Punto di interesse o avviso contenuto nel GPX. */
data class GpxWaypoint(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val description: String? = null,
    val symbol: String? = null,
    val type: String? = null
)

/** Traccia GPX pronta per essere disegnata sulla mappa. */
data class GpxRoute(
    val name: String,
    val points: List<GpxPoint>,
    val distanceMeters: Double,
    val waypoints: List<GpxWaypoint> = emptyList()
)

object GpxParser {

    fun parse(inputStream: InputStream, fallbackName: String = "Percorso GPX"): GpxRoute {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(inputStream, null)
        }

        val trackPoints = mutableListOf<GpxPoint>()
        val routePoints = mutableListOf<GpxPoint>()
        val waypoints = mutableListOf<GpxWaypoint>()
        var currentPointType: String? = null
        var currentLat: Double? = null
        var currentLon: Double? = null
        var currentElevation: Double? = null
        var currentPointName: String? = null
        var currentPointDescription: String? = null
        var currentPointSymbol: String? = null
        var currentPointCategory: String? = null
        var routeName: String? = null
        var currentText: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    currentText = null

                    if (tag == "trkpt" || tag == "rtept" || tag == "wpt") {
                        currentPointType = tag
                        currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        currentElevation = null
                        currentPointName = null
                        currentPointDescription = null
                        currentPointSymbol = null
                        currentPointCategory = null
                    }
                }

                XmlPullParser.TEXT -> currentText = parser.text

                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    when (tag) {
                        "name" -> {
                            val value = currentText?.trim().orEmpty()
                            if (currentPointType == "wpt") {
                                currentPointName = value
                            } else if (routeName.isNullOrBlank() && value.isNotBlank()) {
                                routeName = value
                            }
                        }
                        "desc", "cmt" -> if (currentPointType == "wpt") {
                            currentPointDescription = currentText?.trim()
                        }
                        "sym" -> if (currentPointType == "wpt") {
                            currentPointSymbol = currentText?.trim()
                        }
                        "type" -> if (currentPointType == "wpt") {
                            currentPointCategory = currentText?.trim()
                        }
                        "ele" -> currentElevation = currentText?.trim()?.toDoubleOrNull()
                        "trkpt", "rtept", "wpt" -> {
                            val lat = currentLat
                            val lon = currentLon
                            if (lat != null && lon != null) {
                                when (currentPointType) {
                                    "trkpt" -> trackPoints += GpxPoint(lat, lon, currentElevation)
                                    "rtept" -> routePoints += GpxPoint(lat, lon, currentElevation)
                                    "wpt" -> waypoints += GpxWaypoint(
                                        latitude = lat,
                                        longitude = lon,
                                        name = currentPointName?.takeIf { it.isNotBlank() }
                                            ?: "Avviso",
                                        description = currentPointDescription,
                                        symbol = currentPointSymbol,
                                        type = currentPointCategory
                                    )
                                }
                            }
                            currentPointType = null
                            currentLat = null
                            currentLon = null
                            currentElevation = null
                            currentPointName = null
                            currentPointDescription = null
                            currentPointSymbol = null
                            currentPointCategory = null
                        }
                    }
                    currentText = null
                }
            }
            event = parser.next()
        }

        val points = if (trackPoints.size >= 2) trackPoints else routePoints
        require(points.size >= 2) { "Il GPX non contiene una traccia valida" }

        var distance = 0.0
        for (index in 1 until points.size) {
            distance += haversineMeters(points[index - 1], points[index])
        }

        return GpxRoute(
            name = routeName?.takeIf { it.isNotBlank() } ?: fallbackName.substringBeforeLast('.'),
            points = points,
            distanceMeters = distance,
            waypoints = waypoints
        )
    }

    private fun haversineMeters(a: GpxPoint, b: GpxPoint): Double {
        val earthRadius = 6_371_000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val deltaLat = Math.toRadians(b.latitude - a.latitude)
        val deltaLon = Math.toRadians(b.longitude - a.longitude)

        val sinLat = sin(deltaLat / 2.0)
        val sinLon = sin(deltaLon / 2.0)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        return 2.0 * earthRadius * atan2(sqrt(h), sqrt(1.0 - h))
    }
}
