package com.example.gpxnavpro

/** Aspetto salvato separatamente per ogni file GPX. */
data class RouteAppearance(
    val color: String = "#005BBB",
    val width: Float = 7f,
    val showDirectionArrows: Boolean = true,
    val arrowSpacingMeters: Int = 300
)
