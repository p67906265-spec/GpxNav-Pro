package com.example.gpxnavpro

data class GpxMergeResult(
    val points: List<GpxPoint>,
    val secondReversed: Boolean,
    val connectionMeters: Double
)

object GpxTrackEditor {

    private const val DUPLICATE_POINT_THRESHOLD_METERS = 8.0

    /**
     * Mantiene la direzione della prima traccia e sceglie automaticamente
     * l'orientamento della seconda che avvicina di più le due estremità.
     */
    fun merge(first: List<GpxPoint>, second: List<GpxPoint>): GpxMergeResult {
        require(first.size >= 2) { "La prima traccia non contiene abbastanza punti" }
        require(second.size >= 2) { "La seconda traccia non contiene abbastanza punti" }

        val firstEnd = first.last()
        val normalDistance = GeoMath.haversineMeters(firstEnd, second.first())
        val reversedDistance = GeoMath.haversineMeters(firstEnd, second.last())
        val reverseSecond = reversedDistance < normalDistance
        val orderedSecond = if (reverseSecond) second.asReversed() else second
        val connection = minOf(normalDistance, reversedDistance)

        val merged = ArrayList<GpxPoint>(first.size + orderedSecond.size)
        merged.addAll(first)

        val dropFirst = GeoMath.haversineMeters(firstEnd, orderedSecond.first()) <=
            DUPLICATE_POINT_THRESHOLD_METERS
        if (dropFirst) {
            merged.addAll(orderedSecond.drop(1))
        } else {
            merged.addAll(orderedSecond)
        }

        return GpxMergeResult(
            points = merged,
            secondReversed = reverseSecond,
            connectionMeters = connection
        )
    }

    /**
     * Ripete la geometria della traccia N volte.
     * Se fine e inizio coincidono quasi, evita di duplicare il punto di chiusura.
     */
    fun repeat(points: List<GpxPoint>, laps: Int): List<GpxPoint> {
        require(points.size >= 2) { "La traccia non contiene abbastanza punti" }
        require(laps >= 2) { "Servono almeno 2 giri" }

        val closesOnStart = GeoMath.haversineMeters(points.last(), points.first()) <=
            DUPLICATE_POINT_THRESHOLD_METERS

        val result = ArrayList<GpxPoint>(points.size * laps)
        result.addAll(points)

        repeat(laps - 1) {
            if (closesOnStart) {
                result.addAll(points.drop(1))
            } else {
                result.addAll(points)
            }
        }
        return result
    }

    fun closureGapMeters(points: List<GpxPoint>): Double {
        if (points.size < 2) return 0.0
        return GeoMath.haversineMeters(points.last(), points.first())
    }
}
