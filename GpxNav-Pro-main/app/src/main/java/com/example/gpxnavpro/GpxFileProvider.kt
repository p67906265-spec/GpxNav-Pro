package com.example.gpxnavpro

import android.net.Uri
import androidx.core.content.FileProvider

/** FileProvider che dichiara esplicitamente il MIME corretto per le tracce GPX. */
class GpxFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? {
        val name = uri.lastPathSegment.orEmpty()
        return if (name.endsWith(".gpx", ignoreCase = true)) {
            "application/gpx+xml"
        } else {
            super.getType(uri)
        }
    }
}
