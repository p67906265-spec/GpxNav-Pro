package com.example.gpxnavpro

import android.location.Location
import kotlin.math.hypot

/** Input puro, usabile anche nei test JVM senza dipendere da Location Android. */
data class NavigationSample(
    val latitude: Double,
    val longitude: Double,
    val bearingDegrees: Double? = null,
    val accuracyMeters: Double = 0.0
)

data class NavigationFix(
    val progressMeters: Double,
    val remainingMeters: Double,
    val distanceFromRouteMeters: Double,
    val matchedPoint: GpxPoint,
    val segmentIndex: Int,
    val routeBearing: Double,
    val accuracyMeters: Double,
    val headingDifferenceDegrees: Double?
) {
    /** La precisione GPS viene sottratta dalla distanza prima di dichiarare fuori percorso. */
    val effectiveDistanceFromRouteMeters: Double
        get() = (distanceFromRouteMeters - accuracyMeters.coerceIn(0.0, MAX_ACCURACY_ALLOWANCE_METERS))
            .coerceAtLeast(0.0)

    val isOffRoute: Boolean
        get() = effectiveDistanceFromRouteMeters > NavigationEngine.OFF_ROUTE_THRESHOLD_METERS

    companion object {
        private const val MAX_ACCURACY_ALLOWANCE_METERS = 35.0
    }
}

/**
 * Motore di matching indipendente dalla UI.
 *
 * - geometria dei segmenti precalcolata in setRoute();
 * - distanza Haversine, quindi testabile su JVM;
 * - progresso monotono per evitare salti indietro su anelli o GPS rumoroso;
 * - bearing e accuracy contribuiscono alla scelta del segmento.
 */
class NavigationEngine {

    private data class SegmentProjection(
        val index: Int,
        val start: GpxPoint,
        val end: GpxPoint,
        val startX: Double,
        val startY: Double,
        val vectorX: Double,
        val vectorY: Double,
        val lengthSquared: Double,
        val lengthMeters: Double,
        val startProgressMeters: Double,
        val bearingDegrees: Double
    )

    private data class Candidate(
        val segment: SegmentProjection,
        val fraction: Double,
        val distanceMeters: Double,
        val progressMeters: Double,
        val matchedPoint: GpxPoint,
        val headingDifferenceDegrees: Double?,
        val score: Double
    )

    private var route: GpxRoute? = null
    private var segments: List<SegmentProjection> = emptyList()
    private var cumulativeMeters = DoubleArray(0)
    private var originLatitude = 0.0
    private var originLongitude = 0.0
    private var originLatitudeRadians = 0.0
    private var lastSegmentIndex: Int? = null
    private var lastProgressMeters = 0.0

    fun setRoute(route: GpxRoute) {
        this.route = route
        val points = route.points
        if (points.isEmpty()) {
            segments = emptyList()
            cumulativeMeters = DoubleArray(0)
            resetProgress()
            return
        }

        originLatitude = points.first().latitude
        originLongitude = points.first().longitude
        originLatitudeRadians = Math.toRadians(originLatitude)

        cumulativeMeters = DoubleArray(points.size)
        val prepared = ArrayList<SegmentProjection>((points.size - 1).coerceAtLeast(0))
        for (index in 0 until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            val length = GeoMath.haversineMeters(start, end)
            val startX = GeoMath.projectedX(start.longitude, originLongitude, originLatitudeRadians)
            val startY = GeoMath.projectedY(start.latitude, originLatitude)
            val endX = GeoMath.projectedX(end.longitude, originLongitude, originLatitudeRadians)
            val endY = GeoMath.projectedY(end.latitude, originLatitude)
            val dx = endX - startX
            val dy = endY - startY
            prepared += SegmentProjection(
                index = index,
                start = start,
                end = end,
                startX = startX,
                startY = startY,
                vectorX = dx,
                vectorY = dy,
                lengthSquared = dx * dx + dy * dy,
                lengthMeters = length,
                startProgressMeters = cumulativeMeters[index],
                bearingDegrees = GeoMath.bearingDegrees(start, end)
            )
            cumulativeMeters[index + 1] = cumulativeMeters[index] + length
        }
        segments = prepared
        resetProgress()
    }

    fun resetProgress() {
        lastSegmentIndex = null
        lastProgressMeters = 0.0
    }

    fun match(location: Location): NavigationFix? = match(
        NavigationSample(
            latitude = location.latitude,
            longitude = location.longitude,
            bearingDegrees = location.bearing.takeIf { location.hasBearing() }?.toDouble(),
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() }?.toDouble() ?: 0.0
        )
    )

    fun match(sample: NavigationSample): NavigationFix? {
        val currentRoute = route ?: return null
        if (segments.isEmpty()) return null

        val nearbyIndices = lastSegmentIndex?.let { last ->
            maxOf(0, last - SEARCH_WINDOW)..minOf(segments.lastIndex, last + SEARCH_WINDOW)
        }

        val nearbyCandidate = nearbyIndices?.let { findBestMatch(sample, it) }
        var candidate = if (
            nearbyCandidate == null ||
            nearbyCandidate.distanceMeters > FULL_SEARCH_DISTANCE_METERS + sample.accuracyMeters
        ) {
            findBestMatch(sample, 0..segments.lastIndex)
        } else {
            nearbyCandidate
        }

        // Su anelli o tracce che si incrociano, a parità quasi perfetta preferisce
        // il candidato coerente con il progresso già raggiunto.
        if (candidate.progressMeters + BACKTRACK_TOLERANCE_METERS < lastProgressMeters) {
            val forwardRangeStart = segmentForProgress(lastProgressMeters)
            val forwardCandidate = findBestMatch(
                sample,
                forwardRangeStart..minOf(segments.lastIndex, forwardRangeStart + FORWARD_RECOVERY_WINDOW)
            )
            if (forwardCandidate.distanceMeters <= candidate.distanceMeters + FORWARD_DISTANCE_SLACK_METERS) {
                candidate = forwardCandidate
            }
        }

        val monotonicProgress = maxOf(lastProgressMeters, candidate.progressMeters)
            .coerceIn(0.0, currentRoute.distanceMeters)
        val resolvedSegment = segmentForProgress(monotonicProgress)
        val resolvedPoint = pointAtProgress(monotonicProgress)
        lastProgressMeters = monotonicProgress
        lastSegmentIndex = resolvedSegment

        return NavigationFix(
            progressMeters = monotonicProgress,
            remainingMeters = (currentRoute.distanceMeters - monotonicProgress).coerceAtLeast(0.0),
            distanceFromRouteMeters = candidate.distanceMeters,
            matchedPoint = resolvedPoint,
            segmentIndex = resolvedSegment,
            routeBearing = segments[resolvedSegment].bearingDegrees,
            accuracyMeters = sample.accuracyMeters.coerceAtLeast(0.0),
            headingDifferenceDegrees = sample.bearingDegrees?.let {
                GeoMath.headingDifferenceDegrees(it, segments[resolvedSegment].bearingDegrees)
            }
        )
    }

    private fun findBestMatch(sample: NavigationSample, indices: IntProgression): Candidate {
        val pointX = GeoMath.projectedX(sample.longitude, originLongitude, originLatitudeRadians)
        val pointY = GeoMath.projectedY(sample.latitude, originLatitude)
        var best: Candidate? = null

        for (index in indices) {
            if (index !in segments.indices) continue
            val segment = segments[index]
            val relX = pointX - segment.startX
            val relY = pointY - segment.startY
            val fraction = if (segment.lengthSquared > 0.0) {
                ((relX * segment.vectorX + relY * segment.vectorY) / segment.lengthSquared)
                    .coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val closestX = segment.startX + fraction * segment.vectorX
            val closestY = segment.startY + fraction * segment.vectorY
            val distance = hypot(pointX - closestX, pointY - closestY)
            val progress = segment.startProgressMeters + fraction * segment.lengthMeters
            val headingDifference = sample.bearingDegrees?.let {
                GeoMath.headingDifferenceDegrees(it, segment.bearingDegrees)
            }
            val headingPenalty = headingDifference?.let {
                (it / 180.0) * HEADING_PENALTY_METERS
            } ?: 0.0
            val backwardsPenalty = if (progress + BACKTRACK_TOLERANCE_METERS < lastProgressMeters) {
                BACKTRACK_SCORE_PENALTY_METERS
            } else {
                0.0
            }
            val score = distance + headingPenalty + backwardsPenalty
            val candidate = Candidate(
                segment = segment,
                fraction = fraction,
                distanceMeters = distance,
                progressMeters = progress,
                matchedPoint = GpxPoint(
                    latitude = segment.start.latitude + fraction * (segment.end.latitude - segment.start.latitude),
                    longitude = segment.start.longitude + fraction * (segment.end.longitude - segment.start.longitude),
                    elevation = interpolateElevation(segment.start.elevation, segment.end.elevation, fraction)
                ),
                headingDifferenceDegrees = headingDifference,
                score = score
            )

            val currentBest = best
            if (currentBest == null ||
                candidate.score < currentBest.score - SCORE_EPSILON ||
                (kotlin.math.abs(candidate.score - currentBest.score) <= SCORE_EPSILON &&
                    candidate.progressMeters < currentBest.progressMeters)
            ) {
                best = candidate
            }
        }

        return requireNotNull(best) { "Nessun segmento disponibile per il matching" }
    }

    private fun segmentForProgress(progressMeters: Double): Int {
        if (segments.isEmpty()) return 0
        var low = 0
        var high = cumulativeMeters.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (cumulativeMeters[mid] <= progressMeters) low = mid else high = mid - 1
        }
        return low.coerceIn(0, segments.lastIndex)
    }

    private fun pointAtProgress(progressMeters: Double): GpxPoint {
        if (segments.isEmpty()) return route?.points?.firstOrNull() ?: GpxPoint(0.0, 0.0)
        val index = segmentForProgress(progressMeters)
        val segment = segments[index]
        val local = (progressMeters - segment.startProgressMeters).coerceAtLeast(0.0)
        val fraction = if (segment.lengthMeters > 0.0) {
            (local / segment.lengthMeters).coerceIn(0.0, 1.0)
        } else 0.0
        return GpxPoint(
            latitude = segment.start.latitude + fraction * (segment.end.latitude - segment.start.latitude),
            longitude = segment.start.longitude + fraction * (segment.end.longitude - segment.start.longitude),
            elevation = interpolateElevation(segment.start.elevation, segment.end.elevation, fraction)
        )
    }

    private fun interpolateElevation(start: Double?, end: Double?, fraction: Double): Double? =
        if (start != null && end != null) start + fraction * (end - start) else null

    companion object {
        const val OFF_ROUTE_THRESHOLD_METERS = 60.0
        private const val FULL_SEARCH_DISTANCE_METERS = 250.0
        private const val SEARCH_WINDOW = 300
        private const val FORWARD_RECOVERY_WINDOW = 600
        private const val FORWARD_DISTANCE_SLACK_METERS = 35.0
        private const val BACKTRACK_TOLERANCE_METERS = 8.0
        private const val BACKTRACK_SCORE_PENALTY_METERS = 120.0
        private const val HEADING_PENALTY_METERS = 45.0
        private const val SCORE_EPSILON = 0.5
    }
}
