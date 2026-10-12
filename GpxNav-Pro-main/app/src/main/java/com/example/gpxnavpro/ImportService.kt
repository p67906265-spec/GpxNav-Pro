package com.example.gpxnavpro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed class ImportServiceResult {
    data class GpxImported(val filePath: String) : ImportServiceResult()
    data object MapImported : ImportServiceResult()
    data class Failed(val kind: String, val message: String) : ImportServiceResult()
}

class ImportService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() richiede che il servizio entri SEMPRE subito
        // in foreground, anche nei rami di errore/uscita anticipata.
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Preparazione importazione…", indeterminate = true)
        )

        // Un secondo start non deve mai fermare il job già in esecuzione.
        // Il Service è unico: stopSelf(startId) / stopForeground() nel ramo busy
        // distruggerebbero anche la copia lunga già attiva.
        if (IMPORT_BUSY.get()) {
            val requestedKind = if (intent?.action == ACTION_IMPORT_MAP) "Mappa" else "GPX"
            deliver(ImportServiceResult.Failed(requestedKind, "Un'altra importazione è già in corso"))
            return START_NOT_STICKY
        }

        val action = intent?.action
        if (action == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val kind = if (action == ACTION_IMPORT_MAP) "Mappa" else "GPX"
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        if (uri == null) {
            deliver(ImportServiceResult.Failed(kind, "URI di importazione mancante"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!IMPORT_BUSY.compareAndSet(false, true)) {
            deliver(ImportServiceResult.Failed(kind, "Un'altra importazione è già in corso"))
            return START_NOT_STICKY
        }

        updateNotification("Importazione $kind…", null)

        executor.execute {
            val result = runCatching {
                when (action) {
                    ACTION_IMPORT_GPX -> importGpx(uri)
                    ACTION_IMPORT_MAP -> importMap(uri)
                    else -> error("Operazione di importazione sconosciuta")
                }
            }

            val serviceResult = result.getOrElse { error ->
                ImportServiceResult.Failed(kind, error.message ?: "Errore sconosciuto")
            }
            deliver(serviceResult)
            IMPORT_BUSY.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun importGpx(uri: Uri): ImportServiceResult {
        val displayName = queryDisplayName(uri) ?: "percorso.gpx"
        require(displayName.endsWith(".gpx", ignoreCase = true)) {
            "Seleziona un file con estensione .gpx"
        }

        val directory = File(getExternalFilesDir(null) ?: filesDir, "gpx").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var destination = File(directory, safeName)
        var counter = 2
        while (destination.exists()) {
            val base = safeName.substringBeforeLast('.', safeName)
            destination = File(directory, "${base}_$counter.gpx")
            counter++
        }
        val temporary = File(directory, ".${destination.name}.importing-${System.nanoTime()}")

        try {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Il file GPX selezionato non è leggibile" }
                temporary.outputStream().buffered().use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            }
            require(temporary.length() > 0L) { "Il file GPX selezionato è vuoto" }

            // La validazione avviene PRIMA di rendere permanente il file.
            temporary.inputStream().buffered().use { input ->
                GpxParser.parse(input, destination.name)
            }

            require(temporary.renameTo(destination)) {
                "Impossibile completare l'importazione GPX"
            }
            return ImportServiceResult.GpxImported(destination.absolutePath)
        } finally {
            temporary.delete()
        }
    }

    private fun importMap(uri: Uri): ImportServiceResult {
        val displayName = queryDisplayName(uri)
        require(displayName == null || displayName.endsWith(".pmtiles", ignoreCase = true)) {
            "Seleziona un file con estensione .pmtiles"
        }

        val mapDirectory = File(getExternalFilesDir(null) ?: filesDir, "maps").apply { mkdirs() }
        val destination = File(mapDirectory, "friuli.pmtiles")
        val temporary = File(mapDirectory, "friuli.importing")
        val backup = File(mapDirectory, "friuli.backup")
        val sourceSize = queryFileSize(uri)
        val reserveBytes = 16L * 1024L * 1024L
        val availableBytes = StatFs(mapDirectory.absolutePath).availableBytes

        if (sourceSize != null && sourceSize > 0L) {
            require(availableBytes >= sourceSize + reserveBytes) {
                "Spazio insufficiente: servono almeno ${formatMegabytes(sourceSize + reserveBytes)}"
            }
        }

        temporary.delete()
        var completed = false
        try {
            val maxWritableBytes = (StatFs(mapDirectory.absolutePath).availableBytes - reserveBytes)
                .coerceAtLeast(0L)
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Il file selezionato non è leggibile" }
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var copied = 0L
                    var lastProgress = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        require(copied <= maxWritableBytes) {
                            "Spazio insufficiente durante l'importazione"
                        }
                        output.write(buffer, 0, read)

                        if (sourceSize != null && sourceSize > 0L) {
                            val percent = ((copied * 100L) / sourceSize).toInt().coerceIn(0, 99)
                            if (percent >= lastProgress + 2) {
                                lastProgress = percent
                                updateNotification("Importazione mappa… $percent%", percent)
                            }
                        } else if (copied % (8L * 1024L * 1024L) < read) {
                            updateNotification("Importazione mappa… ${formatMegabytes(copied)}", null)
                        }
                    }
                }
            }

            require(temporary.length() > 0L) { "Il file selezionato è vuoto" }
            PmtilesHeaderReader.read(temporary) // magic PMTiles + versione 3 + bounds

            backup.delete()
            if (destination.exists()) {
                require(destination.renameTo(backup)) {
                    "Impossibile mettere al sicuro la mappa precedente"
                }
            }
            if (!temporary.renameTo(destination)) {
                if (backup.exists() && !destination.exists()) backup.renameTo(destination)
                error("Impossibile completare l'importazione")
            }
            backup.delete()
            completed = true
            return ImportServiceResult.MapImported
        } finally {
            if (!completed) {
                temporary.delete()
                if (!destination.exists() && backup.exists()) backup.renameTo(destination)
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    private fun queryFileSize(uri: Uri): Long? {
        val projection = arrayOf(OpenableColumns.SIZE)
        val cursorSize = contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                cursor.getLong(index).takeIf { it >= 0L }
            } else null
        }
        if (cursorSize != null) return cursorSize
        return runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length.takeIf { it >= 0L }
            }
        }.getOrNull()
    }

    private fun formatMegabytes(bytes: Long): String =
        String.format(java.util.Locale.ITALY, "%.1f MB", bytes / (1024.0 * 1024.0))

    private fun buildNotification(text: String, progress: Int? = null, indeterminate: Boolean = false) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("GPX NAV")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progress ?: 0, indeterminate || progress == null)
            .build()

    private fun updateNotification(text: String, progress: Int?) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text, progress, progress == null))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Importazioni GPX e mappe",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun deliver(result: ImportServiceResult) {
        val current = listener
        if (current != null) {
            mainHandler.post {
                if (listener === current) {
                    current(result)
                } else {
                    persistPendingResult(result)
                }
            }
        } else {
            persistPendingResult(result)
        }
    }

    private fun persistPendingResult(result: ImportServiceResult) {
        val editor = getSharedPreferences(PREFS_RESULTS, Context.MODE_PRIVATE).edit().clear()
        when (result) {
            is ImportServiceResult.GpxImported -> editor
                .putString(KEY_RESULT_TYPE, RESULT_GPX)
                .putString(KEY_RESULT_VALUE, result.filePath)
            ImportServiceResult.MapImported -> editor
                .putString(KEY_RESULT_TYPE, RESULT_MAP)
            is ImportServiceResult.Failed -> editor
                .putString(KEY_RESULT_TYPE, RESULT_ERROR)
                .putString(KEY_RESULT_KIND, result.kind)
                .putString(KEY_RESULT_VALUE, result.message)
        }
        editor.apply()
    }

    companion object {
        private const val CHANNEL_ID = "gpx_imports"
        private const val NOTIFICATION_ID = 2201
        private const val ACTION_IMPORT_GPX = "com.example.gpxnavpro.IMPORT_GPX"
        private const val ACTION_IMPORT_MAP = "com.example.gpxnavpro.IMPORT_MAP"
        private const val EXTRA_URI = "uri"
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private val IMPORT_BUSY = AtomicBoolean(false)

        private const val PREFS_RESULTS = "import_service_results"
        private const val KEY_RESULT_TYPE = "type"
        private const val KEY_RESULT_KIND = "kind"
        private const val KEY_RESULT_VALUE = "value"
        private const val RESULT_GPX = "gpx"
        private const val RESULT_MAP = "map"
        private const val RESULT_ERROR = "error"

        @Volatile private var listener: ((ImportServiceResult) -> Unit)? = null

        fun setListener(value: ((ImportServiceResult) -> Unit)?) {
            listener = value
        }

        fun consumePendingResult(context: Context): ImportServiceResult? {
            val preferences = context.getSharedPreferences(PREFS_RESULTS, Context.MODE_PRIVATE)
            val type = preferences.getString(KEY_RESULT_TYPE, null) ?: return null
            val result = when (type) {
                RESULT_GPX -> preferences.getString(KEY_RESULT_VALUE, null)?.let {
                    ImportServiceResult.GpxImported(it)
                }
                RESULT_MAP -> ImportServiceResult.MapImported
                RESULT_ERROR -> ImportServiceResult.Failed(
                    preferences.getString(KEY_RESULT_KIND, "Importazione") ?: "Importazione",
                    preferences.getString(KEY_RESULT_VALUE, "Errore sconosciuto") ?: "Errore sconosciuto"
                )
                else -> null
            }
            preferences.edit().clear().apply()
            return result
        }

        fun importGpx(context: Context, uri: Uri) = start(context, ACTION_IMPORT_GPX, uri)
        fun importMap(context: Context, uri: Uri) = start(context, ACTION_IMPORT_MAP, uri)

        private fun start(context: Context, action: String, uri: Uri) {
            val intent = Intent(context, ImportService::class.java).apply {
                this.action = action
                putExtra(EXTRA_URI, uri.toString())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
