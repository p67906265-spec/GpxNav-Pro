package com.example.gpxnavpro

import android.net.Uri
import androidx.core.content.FileProvider

/** Restituisce un MIME esplicito per GPX: senza questa mappatura Android può
 * trattare .gpx come application/octet-stream e WhatsApp lo presenta come BIN. */
class GpxFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? {
        val segment = uri.lastPathSegment.orEmpty()
        return if (segment.substringBefore('?').endsWith(".gpx", ignoreCase = true)) {
            "application/gpx+xml"
        } else {
            super.getType(uri)
        }
    }
}
