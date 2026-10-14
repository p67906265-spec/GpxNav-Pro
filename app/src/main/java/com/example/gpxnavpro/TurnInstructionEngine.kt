package com.example.gpxnavpro

import kotlin.math.abs

data class TurnInstruction(
    val progressMeters: Double,
    val label: String,
    val symbol: String
)

/**
 * @param minTurnAngleDegrees angolo minimo per generare un'indicazione.
 * @param minInstructionSpacingMeters distanza minima tra due indicazioni.
 * @param lookMeters se > 0 gli angoli sono misurati tra il punto indietro/avanti di
 *   questa distanza (ignora il rumore delle tracce GPS registrate con punti ravvicinati).
 */
class TurnInstructionEngine(
    private val minTurnAngleDegrees: Double = 35.0,
    private val minInstructionSpacingMeters: Double = 35.0,
    private val lookMeters: Double = 0.0
) {

    private var instructions: List<TurnInstruction> = emptyList()

    fun setRoute(route: GpxRoute) {
        instructions = if (lookMeters > 0.0) buildSmoothed(route) else buildSimple(route)
    }

    private fun buildSimple(route: GpxRoute): List<TurnInstruction> {
        val result = mutableListOf<TurnInstruction>()
        var cumulative = 0.0
        var lastInstructionProgress = -minInstructionSpacingMeters

        for (index in 1 until route.points.lastIndex) {
            val previous = route.points[index - 1]
            val current = route.points[index]
            val next = route.points[index + 1]
            cumulative += distance(previous, current)
            val delta = normalizedAngle(
                GeoMath.bearingDegrees(previous, current),
                GeoMath.bearingDegrees(current, next)
            )
            if (abs(delta) >= minTurnAngleDegrees &&
                cumulative - lastInstructionProgress >= minInstructionSpacingMeters
            ) {
                result += instructionFor(delta, cumulative)
                lastInstructionProgress = cumulative
            }
        }
        return result
    }

    private fun buildSmoothed(route: GpxRoute): List<TurnInstruction> {
        val points = route.points
        if (points.size < 3) return emptyList()
        val cumulative = DoubleArray(points.size)
        for (index in 1 until points.size) {
            cumulative[index] = cumulative[index - 1] + distance(points[index - 1], points[index])
        }

        val result = mutableListOf<TurnInstruction>()
        var lastInstructionProgress = -minInstructionSpacingMeters
        var back = 0
        var forward = 1
        for (index in 1 until points.lastIndex) {
            while (back + 1 < index && cumulative[back + 1] <= cumulative[index] - lookMeters) back++
            if (forward <= index) forward = index + 1
            while (forward < points.lastIndex && cumulative[forward] < cumulative[index] + lookMeters) forward++

            val delta = normalizedAngle(
                GeoMath.bearingDegrees(points[back], points[index]),
                GeoMath.bearingDegrees(points[index], points[forward])
            )
            if (abs(delta) >= minTurnAngleDegrees &&
                cumulative[index] - lastInstructionProgress >= minInstructionSpacingMeters
            ) {
                result += instructionFor(delta, cumulative[index])
                lastInstructionProgress = cumulative[index]
            }
        }
        return result
    }

    private fun instructionFor(delta: Double, progress: Double): TurnInstruction {
        val instruction = when {
            delta <= -100.0 -> "SVOLTA DECISA A SINISTRA" to "↰"
            delta <= -35.0 -> "SVOLTA A SINISTRA" to "←"
            delta >= 100.0 -> "SVOLTA DECISA A DESTRA" to "↱"
            else -> "SVOLTA A DESTRA" to "→"
        }
        return TurnInstruction(progress, instruction.first, instruction.second)
    }

    fun next(progressMeters: Double): TurnInstruction? =
        instructions.firstOrNull { it.progressMeters > progressMeters + 8.0 }

    private fun normalizedAngle(from: Double, to: Double): Double =
        ((to - from + 540.0) % 360.0) - 180.0

    private fun distance(start: GpxPoint, end: GpxPoint): Double =
        GeoMath.haversineMeters(start, end)
}
