package com.example.gpxnavpro

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
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

class NavigationLocationService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private val navigationEngine = NavigationEngine()
    private val routeAlertEngine = RouteAlertEngine()
    private lateinit var alertFeedback: AlertFeedback

    private var activeRoute: GpxRoute? = null
    private var activeRouteFile: File? = null
    private var mainRouteActive = false

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        alertFeedback = AlertFeedback(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("GPX NAV")
                .setContentText("Navigazione GPS attiva")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )

        loadNavigationState()
        if (activeRoute == null) {
            getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_NAVIGATION_ACTIVE, false)
                .putBoolean(PREF_MAIN_ROUTE_ACTIVE, false)
                .apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        requestUpdates()
        isRunning = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(this) }
        runCatching { alertFeedback.release() }
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        val copy = Location(location)
        lastLocation = copy

        processBackgroundNavigation(copy)

        // La UI è opzionale: il servizio continua comunque matching e avvisi.
        listener?.invoke(Location(copy))
    }

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    @Deprecated("Deprecated in Android 10")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun loadNavigationState() {
        val preferences = getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
        val routePath = preferences.getString(PREF_NAVIGATION_ROUTE_PATH, null)
        val file = routePath?.let(::File)

        if (file == null || !file.exists() || file.length() == 0L) {
            activeRoute = null
            activeRouteFile = null
            return
        }

        val route = runCatching {
            file.inputStream().buffered().use { input ->
                GpxParser.parse(input, file.name)
            }
        }.getOrNull() ?: return

        activeRoute = route
        activeRouteFile = file
        navigationEngine.setRoute(route)
        navigationEngine.resetProgress()
        routeAlertEngine.setRoute(route, loadManualAlerts(route, file))
        alertFeedback.reset()
        mainRouteActive = preferences.getBoolean(PREF_MAIN_ROUTE_ACTIVE, false)
    }

    private fun processBackgroundNavigation(location: Location) {
        val route = activeRoute ?: return

        if (!mainRouteActive) {
            val start = route.points.firstOrNull() ?: return
            val distanceToStart = GeoMath.haversineMeters(
                GpxPoint(location.latitude, location.longitude),
                start
            )
            if (distanceToStart <= START_REACHED_DISTANCE_METERS) {
                mainRouteActive = true
                getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_MAIN_ROUTE_ACTIVE, true)
                    .apply()
                navigationEngine.resetProgress()
            } else {
                return
            }
        }

        val fix = navigationEngine.match(location) ?: return
        alertFeedback.offRoute(fix.isOffRoute)

        val preferences = getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
        val activeAlert = routeAlertEngine.activeAlert(fix.progressMeters) { type ->
            preferences.getInt(type.preferenceKey, DEFAULT_ALERT_DISTANCE)
        }
        if (activeAlert != null) {
            alertFeedback.routeAlert(activeAlert)
        }
    }

    private fun loadManualAlerts(
        route: GpxRoute,
        file: File
    ): Map<RouteAlertType, List<Double>> {
        val preferences = getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
        val key = "route_alert_entries_${file.name.hashCode()}"
        val maximumKilometers = route.distanceMeters / 1000.0

        val entries = preferences.getString(key, "").orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('|')
                val type = parts.getOrNull(0)?.let {
                    runCatching { RouteAlertType.valueOf(it) }.getOrNull()
                }
                val kilometer = parts.getOrNull(1)?.toDoubleOrNull()
                if (type != null && kilometer != null && kilometer in 0.0..maximumKilometers) {
                    type to kilometer
                } else {
                    null
                }
            }
            .toList()

        return entries.groupBy({ it.first }, { it.second })
    }

    private fun requestUpdates() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            stopSelf()
            return
        }

        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                1f,
                this
            )
        }.onFailure {
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Navigazione GPX",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val CHANNEL_ID = "gpx_navigation"
        private const val NOTIFICATION_ID = 2101

        private const val PREFS_NAVIGATION = "navigation_settings"
        private const val PREF_NAVIGATION_ACTIVE = "navigation_active"
        private const val PREF_NAVIGATION_ROUTE_PATH = "navigation_route_path"
        private const val PREF_MAIN_ROUTE_ACTIVE = "navigation_main_route_active"
        private const val DEFAULT_ALERT_DISTANCE = 1000
        private const val START_REACHED_DISTANCE_METERS = 40.0

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var lastLocation: Location? = null
            private set
        @Volatile var listener: ((Location) -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, NavigationLocationService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            isRunning = false
            context.stopService(Intent(context, NavigationLocationService::class.java))
        }
    }
}
