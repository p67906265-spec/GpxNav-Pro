package com.example.gpxnavpro

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PmtilesBounds(
    val minLongitude: Double,
    val minLatitude: Double,
    val maxLongitude: Double,
    val maxLatitude: Double
) {
    fun contains(point: GpxPoint): Boolean =
        point.longitude in minLongitude..maxLongitude &&
            point.latitude in minLatitude..maxLatitude
}

data class PmtilesHeader(
    val version: Int,
    val bounds: PmtilesBounds,
    val minZoom: Int,
    val maxZoom: Int
)

object PmtilesHeaderReader {
    private const val HEADER_SIZE = 127

    fun read(file: File): PmtilesHeader {
        require(file.exists() && file.length() >= HEADER_SIZE) {
            "File PMTiles troppo corto o non valido"
        }

        val bytes = ByteArray(HEADER_SIZE)
        RandomAccessFile(file, "r").use { raf ->
            raf.readFully(bytes)
        }

        val magic = bytes.copyOfRange(0, 7).toString(Charsets.US_ASCII)
        val version = bytes[7].toInt() and 0xFF
        require(magic == "PMTiles") {
            "Il file selezionato non è un archivio PMTiles valido"
        }
        require(version == 3) {
            "Versione PMTiles non supportata: $version"
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val minZoom = bytes[100].toInt() and 0xFF
        val maxZoom = bytes[101].toInt() and 0xFF
        val minLon = buffer.getInt(102) / 10_000_000.0
        val minLat = buffer.getInt(106) / 10_000_000.0
        val maxLon = buffer.getInt(110) / 10_000_000.0
        val maxLat = buffer.getInt(114) / 10_000_000.0

        require(minLon in -180.0..180.0 && maxLon in -180.0..180.0 && minLon <= maxLon) {
            "Bounds PMTiles non validi"
        }
        require(minLat in -90.0..90.0 && maxLat in -90.0..90.0 && minLat <= maxLat) {
            "Bounds PMTiles non validi"
        }

        return PmtilesHeader(
            version = version,
            bounds = PmtilesBounds(minLon, minLat, maxLon, maxLat),
            minZoom = minZoom,
            maxZoom = maxZoom
        )
    }
}
