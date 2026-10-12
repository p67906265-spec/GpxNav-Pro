package com.example.gpxnavpro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxTrackEditorTest {

    @Test
    fun mergeReversesSecondTrackWhenItsEndIsCloser() {
        val first = listOf(
            GpxPoint(46.0000, 13.0000),
            GpxPoint(46.0000, 13.0100)
        )
        val second = listOf(
            GpxPoint(46.0000, 13.0200),
            GpxPoint(46.0000, 13.0101)
        )

        val merged = GpxTrackEditor.merge(first, second)

        assertTrue(merged.secondReversed)
        assertTrue(merged.connectionMeters < 30.0)
        assertTrue(merged.points.last().longitude > 13.019)
    }

    @Test
    fun mergeKeepsSecondDirectionWhenStartIsCloser() {
        val first = listOf(
            GpxPoint(46.0000, 13.0000),
            GpxPoint(46.0000, 13.0100)
        )
        val second = listOf(
            GpxPoint(46.0000, 13.0101),
            GpxPoint(46.0000, 13.0200)
        )

        val merged = GpxTrackEditor.merge(first, second)

        assertFalse(merged.secondReversed)
        assertTrue(merged.connectionMeters < 30.0)
    }

    @Test
    fun repeatClosedLoopCreatesRequestedLapsWithoutDuplicatingJoinPoint() {
        val lap = listOf(
            GpxPoint(46.0000, 13.0000),
            GpxPoint(46.0000, 13.0100),
            GpxPoint(46.0100, 13.0100),
            GpxPoint(46.0000, 13.0000)
        )

        val repeated = GpxTrackEditor.repeat(lap, 6)

        val expected = lap.size + (lap.size - 1) * 5
        assertTrue(repeated.size == expected)
        assertTrue(repeated.first() == repeated.last())
    }

    @Test
    fun repeatOpenTrackKeepsEveryLapGeometry() {
        val lap = listOf(
            GpxPoint(46.0000, 13.0000),
            GpxPoint(46.0000, 13.0100)
        )

        val repeated = GpxTrackEditor.repeat(lap, 3)

        assertTrue(repeated.size == 6)
    }
}
