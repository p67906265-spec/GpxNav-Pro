package com.example.gpxnavpro

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Registra il giro in moto e lo scrive in un file GPX mentre procede.
 *
 * Il file è valido in ogni momento (il blocco finale viene riscritto dopo ogni punto),
 * quindi se il telefono si spegne o l'app viene chiusa il giro fatto fin lì non si perde.
 */
class TrackRecorderService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private var raf: RandomAccessFile? = null
    private var bodyEndPosition = 0L
    private var lastSaved: Location? = null
    private var lastSegmentBearing: Float? = null
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_STOP, ACTION_DISCARD -> {
                if (isRunning) finishRecording(discard = intent?.action == ACTION_DISCARD)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE -> {
                if (isRunning) {
                    isPaused = !isPaused
                    notifyState()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }
        }

        if (!isRunning) {
            if (!beginRecording()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Se il sistema ferma il servizio, il file resta valido: lo chiudo comunque.
        if (isRunning) finishRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun beginRecording(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine) return false

        val directory = File(getExternalFilesDir(null) ?: filesDir, "gpx").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
        var file = File(directory, "Giro_$stamp.gpx")
        var counter = 2
        while (file.exists()) {
            file = File(directory, "Giro_${stamp}_$counter.gpx")
            counter++
        }

        val opened = runCatching {
            val access = RandomAccessFile(file, "rw")
            access.write(
                (
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<gpx version=\"1.1\" creator=\"GpxNav Pro\" " +
                        "xmlns=\"http://www.topografix.com/GPX/1/1\">\n" +
                        "  <trk>\n    <name>Giro $stamp</name>\n    <trkseg>\n"
                    ).toByteArray(Charsets.UTF_8)
            )
            bodyEndPosition = access.filePointer
            access.write(FOOTER.toByteArray(Charsets.UTF_8))
            raf = access
        }.isSuccess
        if (!opened) return false

        val started = runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                this
            )
        }.isSuccess
        if (!started) {
            runCatching { raf?.close() }
            raf = null
            file.delete()
            return false
        }

        lastSaved = null
        lastSegmentBearing = null
        currentFile = file
        distanceMeters = 0.0
        pointCount = 0
        isPaused = false
        isRunning = true
        notifyState()
        return true
    }

    private fun finishRecording(discard: Boolean = false) {
        runCatching { locationManager.removeUpdates(this) }
        runCatching { raf?.close() }
        raf = null
        val file = currentFile
        val saved = pointCount
        isRunning = false
        isPaused = false
        if (discard) {
            file?.delete()
            currentFile = null
            // -1 = registrazione eliminata dall'utente
            stoppedListener?.invoke(null, -1)
        } else if (file != null && saved < 2) {
            file.delete()
            currentFile = null
            stoppedListener?.invoke(null, saved)
        } else {
            stoppedListener?.invoke(file, saved)
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!isRunning || isPaused) return
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_METERS) return

        val previous = lastSaved
        if (previous != null) {
            // Fermo (semaforo, sosta): il GPS balla, non si registra.
            if (location.hasSpeed() && location.speed < MIN_SPEED_MS) return

            val distance = previous.distanceTo(location)
            if (distance < MIN_POINT_DISTANCE_METERS) return

            val bearing = previous.bearingTo(location)
            val lastBearing = lastSegmentBearing
            val turned = lastBearing != null && angleDifference(lastBearing, bearing) >= TURN_ANGLE_DEGREES
            // 12 m in rettilineo, 5 m se si sta curvando: curve fedeli, rettilinei leggeri.
            val needed = if (turned) MIN_POINT_DISTANCE_METERS_TURN else MIN_POINT_DISTANCE_METERS_STRAIGHT
            if (distance < needed) return

            lastSegmentBearing = bearing
            distanceMeters += distance
        }

        if (!appendPoint(location)) return
        lastSaved = Location(location)
        pointCount++
        if (pointCount % NOTIFICATION_UPDATE_EVERY == 0) notifyState()
        stateListener?.invoke()
    }

    private fun appendPoint(location: Location): Boolean {
        val access = raf ?: return false
        return runCatching {
            val line = buildString {
                append("      <trkpt lat=\"")
                append(String.format(Locale.US, "%.6f", location.latitude))
                append("\" lon=\"")
                append(String.format(Locale.US, "%.6f", location.longitude))
                append("\">")
                if (location.hasAltitude()) {
                    append("<ele>")
                    append(String.format(Locale.US, "%.1f", location.altitude))
                    append("</ele>")
                }
                append("<time>").append(timeFormat.format(Date(location.time))).append("</time>")
                append("</trkpt>\n")
            }
            access.seek(bodyEndPosition)
            access.write(line.toByteArray(Charsets.UTF_8))
            bodyEndPosition = access.filePointer
            access.write(FOOTER.toByteArray(Charsets.UTF_8))
        }.isSuccess
    }

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    @Deprecated("Deprecated in Android 10")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun angleDifference(a: Float, b: Float): Float {
        var diff = Math.abs(a - b) % 360f
        if (diff > 180f) diff = 360f - diff
        return diff
    }

    private fun notifyState() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
        stateListener?.invoke()
    }

    private fun buildNotification(): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this,
            2201,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this,
            2202,
            Intent(this, TrackRecorderService::class.java).apply { action = ACTION_TOGGLE_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2203,
            Intent(this, TrackRecorderService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val km = String.format(Locale.ITALY, "%.1f", distanceMeters / 1000.0)
        val text = if (isPaused) "In pausa · $km km" else "Registrazione · $km km"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("GPX NAV")
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (isPaused) "Riprendi" else "Pausa",
                pauseIntent
            )
            .addAction(android.R.drawable.ic_menu_save, "Stop e salva", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Registrazione traccia",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val CHANNEL_ID = "gpx_recording"
        private const val NOTIFICATION_ID = 2201
        private const val ACTION_STOP = "com.example.gpxnavpro.action.STOP_RECORDING"
        private const val ACTION_DISCARD = "com.example.gpxnavpro.action.DISCARD_RECORDING"
        private const val ACTION_TOGGLE_PAUSE = "com.example.gpxnavpro.action.TOGGLE_PAUSE_RECORDING"
        private const val FOOTER = "    </trkseg>\n  </trk>\n</gpx>\n"

        private const val MAX_ACCURACY_METERS = 25f
        private const val MIN_SPEED_MS = 1.0f
        private const val MIN_POINT_DISTANCE_METERS = 4f
        private const val MIN_POINT_DISTANCE_METERS_STRAIGHT = 12f
        private const val MIN_POINT_DISTANCE_METERS_TURN = 5f
        private const val TURN_ANGLE_DEGREES = 20f
        private const val NOTIFICATION_UPDATE_EVERY = 10

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var isPaused: Boolean = false
            private set
        @Volatile var distanceMeters: Double = 0.0
            private set
        @Volatile var pointCount: Int = 0
            private set
        @Volatile var currentFile: File? = null
            private set

        /** Chiamato a ogni punto salvato o cambio di stato (thread principale). */
        @Volatile var stateListener: (() -> Unit)? = null

        /** Chiamato alla fine: file salvato, oppure null se i punti erano troppo pochi. */
        @Volatile var stoppedListener: ((File?, Int) -> Unit)? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackRecorderService::class.java)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TrackRecorderService::class.java).apply { action = ACTION_STOP }
            )
        }

        fun discard(context: Context) {
            context.startService(
                Intent(context, TrackRecorderService::class.java).apply { action = ACTION_DISCARD }
            )
        }

        fun togglePause(context: Context) {
            context.startService(
                Intent(context, TrackRecorderService::class.java).apply { action = ACTION_TOGGLE_PAUSE }
            )
        }
    }
}
