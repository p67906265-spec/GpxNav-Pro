package com.example.gpxnavpro

import kotlin.math.abs

data class TurnInstruction(
    val progressMeters: Double,
    val label: String,
    val symbol: String
)

class TurnInstructionEngine {

    private var instructions: List<TurnInstruction> = emptyList()

    fun setRoute(route: GpxRoute) {
        val result = mutableListOf<TurnInstruction>()
        var cumulative = 0.0
        var lastInstructionProgress = -MIN_INSTRUCTION_SPACING_METERS

        for (index in 1 until route.points.lastIndex) {
            val previous = route.points[index - 1]
            val current = route.points[index]
            val next = route.points[index + 1]
            cumulative += distance(previous, current)
            val delta = normalizedAngle(
                GeoMath.bearingDegrees(previous, current),
                GeoMath.bearingDegrees(current, next)
            )

            if (abs(delta) >= MIN_TURN_ANGLE_DEGREES &&
                cumulative - lastInstructionProgress >= MIN_INSTRUCTION_SPACING_METERS
            ) {
                val instruction = when {
                    delta <= -100.0 -> "SVOLTA DECISA A SINISTRA" to "↰"
                    delta <= -35.0 -> "SVOLTA A SINISTRA" to "←"
                    delta >= 100.0 -> "SVOLTA DECISA A DESTRA" to "↱"
                    else -> "SVOLTA A DESTRA" to "→"
                }
                result += TurnInstruction(cumulative, instruction.first, instruction.second)
                lastInstructionProgress = cumulative
            }
        }
        instructions = result
    }

    fun next(progressMeters: Double): TurnInstruction? =
        instructions.firstOrNull { it.progressMeters > progressMeters + 8.0 }

    private fun normalizedAngle(from: Double, to: Double): Double =
        ((to - from + 540.0) % 360.0) - 180.0

    private fun distance(start: GpxPoint, end: GpxPoint): Double =
        GeoMath.haversineMeters(start, end)

    companion object {
        private const val MIN_TURN_ANGLE_DEGREES = 35.0
        private const val MIN_INSTRUCTION_SPACING_METERS = 35.0
    }
}
