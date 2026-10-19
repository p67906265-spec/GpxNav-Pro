package com.example.gpxnavpro.car

import android.Manifest
import android.app.Presentation
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.ContextCompat
import com.example.gpxnavpro.GeoMath
import com.example.gpxnavpro.GpxParser
import com.example.gpxnavpro.GpxRoute
import com.example.gpxnavpro.NavigationLocationService
import com.example.gpxnavpro.OfflineMapStyle
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File

/**
 * Disegna la mappa con la traccia sullo schermo di Android Auto.
 *
 * La superficie fornita dall'auto viene collegata a un display virtuale, sul quale si mostra
 * una normale MapView di MapLibre (stessa mappa, stessa traccia, stessi colori del telefono).
 * La posizione arriva da un GPS proprio, così la mappa funziona anche a telefono bloccato.
 */
class CarMapRenderer(private val carContext: CarContext) : SurfaceCallback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val locationManager =
        carContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var visibleArea: Rect? = null

    private var lastBearing = 0f
    private var pendingLocation: Location? = null
    private var routeFitted = false
    private var routeBounds: LatLngBounds? = null
    private var destroyed = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onNewLocation(location)
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit

        @Deprecated("Deprecated in Android 10")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    // ---------------------------------------------------------------- SurfaceCallback

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        releaseDisplay()
        destroyed = false
        val surface = surfaceContainer.surface ?: return
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return

        MapLibre.getInstance(carContext.applicationContext)

        val displayManager = carContext.getSystemService(DisplayManager::class.java)
        val display = displayManager.createVirtualDisplay(
            "GpxNavCarMap",
            surfaceWidth,
            surfaceHeight,
            surfaceContainer.dpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
        virtualDisplay = display

        val shown = Presentation(carContext, display.display)
        presentation = shown

        // TextureView: sul display virtuale è la modalità che disegna in modo affidabile.
        val options = MapLibreMapOptions.createFromAttributes(shown.context)
            .textureMode(true)
            .compassEnabled(false)
            .logoEnabled(false)
            .attributionEnabled(false)
        val view = MapView(shown.context, options)
        mapView = view
        shown.setContentView(view)
        shown.show()

        view.onCreate(null)
        view.onStart()
        view.onResume()
        view.getMapAsync { readyMap ->
            if (destroyed) return@getMapAsync
            map = readyMap
            readyMap.uiSettings.setAllGesturesEnabled(false)
            applyPadding()
            loadStyle(readyMap)
        }

        startLocation()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        applyPadding()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        destroy()
    }

    /** Libera mappa, display virtuale e GPS. Si può chiamare più volte. */
    fun destroy() {
        destroyed = true
        stopLocation()
        releaseDisplay()
    }

    private fun releaseDisplay() {
        val view = mapView
        val shown = presentation
        val display = virtualDisplay
        mapView = null
        presentation = null
        virtualDisplay = null
        map = null
        style = null
        routeFitted = false

        runCatching {
            view?.onPause()
            view?.onStop()
        }
        runCatching { shown?.dismiss() }
        runCatching { view?.onDestroy() }
        runCatching { display?.release() }
    }

    private fun applyPadding() {
        val currentMap = map ?: return
        val area = visibleArea ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        // Il centro della mappa deve cadere nella parte visibile, non sotto il pannello.
        currentMap.setPadding(
            area.left.coerceAtLeast(0),
            area.top.coerceAtLeast(0),
            (surfaceWidth - area.right).coerceAtLeast(0),
            (surfaceHeight - area.bottom).coerceAtLeast(0)
        )
    }

    // ---------------------------------------------------------------- Stile e traccia

    private fun loadStyle(readyMap: MapLibreMap) {
        val mapPrefs = carContext.getSharedPreferences(PREFS_MAP, Context.MODE_PRIVATE)
        val mode = mapPrefs.getString(PREF_MAP_MODE, MAP_MODE_ONLINE)
        val activeName = mapPrefs.getString(PREF_ACTIVE_MAP, "friuli.pmtiles") ?: "friuli.pmtiles"
        val mapsDirectory = File(carContext.getExternalFilesDir(null) ?: carContext.filesDir, "maps")
        val mapFile = File(mapsDirectory, activeName)

        val builder = if (mode == MAP_MODE_OFFLINE && mapFile.exists() && mapFile.length() > 0L) {
            Style.Builder().fromJson(OfflineMapStyle.json("pmtiles://file://${mapFile.absolutePath}"))
        } else {
            Style.Builder().fromUri(ONLINE_STYLE_URL)
        }

        readyMap.cameraPosition = CameraPosition.Builder()
            .target(LatLng(46.0711, 13.2346))
            .zoom(8.2)
            .build()

        readyMap.setStyle(builder) { loadedStyle ->
            if (destroyed) return@setStyle
            style = loadedStyle
            installPositionMarker(loadedStyle)
            loadRouteAsync(loadedStyle)
            pendingLocation?.let { onNewLocation(it) }
        }
    }

    private fun activeRouteFile(): File? {
        val navigationPrefs = carContext.getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
        val navigationPath = navigationPrefs.getString(PREF_NAVIGATION_ROUTE_PATH, null)
        val appPrefs = carContext.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        val appPath = appPrefs.getString(PREF_ACTIVE_GPX, null)

        val preferred = if (NavigationLocationService.isRunning) {
            listOf(navigationPath, appPath)
        } else {
            listOf(appPath, navigationPath)
        }
        return preferred
            .mapNotNull { path -> path?.takeIf { it.isNotBlank() }?.let(::File) }
            .firstOrNull { it.exists() && it.length() > 0L }
    }

    private fun loadRouteAsync(targetStyle: Style) {
        val file = activeRouteFile() ?: return
        Thread {
            val route = runCatching {
                file.inputStream().buffered().use { GpxParser.parse(it, file.name) }
            }.getOrNull()
            if (route != null && route.points.size >= 2) {
                mainHandler.post {
                    if (!destroyed && style === targetStyle) drawRoute(targetStyle, route, file)
                }
            }
        }.start()
    }

    private fun drawRoute(targetStyle: Style, route: GpxRoute, file: File) {
        val prefs = carContext.getSharedPreferences(PREFS_ROUTE_APPEARANCE, Context.MODE_PRIVATE)
        val prefix = "${file.name}_${file.length()}_${file.absolutePath.hashCode()}"
        val color = prefs.getString("${prefix}_color", "#005BBB") ?: "#005BBB"
        val width = prefs.getFloat("${prefix}_width", 7f)
        val useSlope = prefs.getBoolean("${prefix}_slope_colors", false)

        val features = if (useSlope) slopeFeatures(route, color) else uniformFeatures(route, color)

        runCatching {
            targetStyle.getLayer(ROUTE_LAYER_ID)?.let { targetStyle.removeLayer(it) }
            targetStyle.getSource(ROUTE_SOURCE_ID)?.let { targetStyle.removeSource(it) }
        }

        targetStyle.addSource(GeoJsonSource(ROUTE_SOURCE_ID, FeatureCollection.fromFeatures(features)))

        val routeLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
            lineColor(Expression.get(COLOR_PROPERTY)),
            lineWidth(width + 1f),
            lineOpacity(0.95f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND)
        )
        // La traccia va sotto il simbolo della moto.
        if (targetStyle.getLayer(POSITION_LAYER_ID) != null) {
            targetStyle.addLayerBelow(routeLayer, POSITION_LAYER_ID)
        } else {
            targetStyle.addLayer(routeLayer)
        }

        val start = route.points.first()
        val finish = route.points.last()
        addEndpoint(targetStyle, "car-start", start.latitude, start.longitude, "#16A34A")
        addEndpoint(targetStyle, "car-finish", finish.latitude, finish.longitude, "#DC2626")

        routeBounds = runCatching {
            LatLngBounds.Builder()
                .includes(route.points.map { LatLng(it.latitude, it.longitude) })
                .build()
        }.getOrNull()
        fitRouteIfNoPosition()
    }

    private fun addEndpoint(targetStyle: Style, id: String, lat: Double, lon: Double, color: String) {
        runCatching {
            targetStyle.getLayer("$id-layer")?.let { targetStyle.removeLayer(it) }
            targetStyle.getSource("$id-source")?.let { targetStyle.removeSource(it) }
        }
        targetStyle.addSource(GeoJsonSource("$id-source", Feature.fromGeometry(Point.fromLngLat(lon, lat))))
        val layer = CircleLayer("$id-layer", "$id-source").withProperties(
            circleRadius(9f),
            circleColor(color),
            circleStrokeColor("#FFFFFF"),
            circleStrokeWidth(3f)
        )
        if (targetStyle.getLayer(POSITION_LAYER_ID) != null) {
            targetStyle.addLayerBelow(layer, POSITION_LAYER_ID)
        } else {
            targetStyle.addLayer(layer)
        }
    }

    private fun fitRouteIfNoPosition() {
        if (routeFitted || pendingLocation != null) return
        val bounds = routeBounds ?: return
        val currentMap = map ?: return
        routeFitted = true
        runCatching {
            currentMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 60))
        }
    }

    private fun installPositionMarker(targetStyle: Style) {
        if (targetStyle.getImage(POSITION_IMAGE_ID) == null) {
            targetStyle.addImage(POSITION_IMAGE_ID, createArrowBitmap())
        }
        targetStyle.addSource(
            GeoJsonSource(POSITION_SOURCE_ID, Feature.fromGeometry(Point.fromLngLat(13.2346, 46.0711)))
        )
        targetStyle.addLayer(
            SymbolLayer(POSITION_LAYER_ID, POSITION_SOURCE_ID).withProperties(
                iconImage(POSITION_IMAGE_ID),
                iconSize(0.9f),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconRotate(0f)
            )
        )
    }

    // ---------------------------------------------------------------- Posizione

    private fun startLocation() {
        val granted = ContextCompat.checkSelfPermission(
            carContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                locationListener,
                Looper.getMainLooper()
            )
        }

        val seed = NavigationLocationService.lastLocation
            ?: runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        seed?.let { onNewLocation(it) }
    }

    private fun stopLocation() {
        runCatching { locationManager.removeUpdates(locationListener) }
    }

    private fun onNewLocation(location: Location) {
        pendingLocation = location
        val currentStyle = style ?: return
        val currentMap = map ?: return

        if (location.hasBearing() && location.hasSpeed() && location.speed > MIN_BEARING_SPEED_MS) {
            lastBearing = location.bearing
        }

        currentStyle.getSourceAs<GeoJsonSource>(POSITION_SOURCE_ID)
            ?.setGeoJson(Point.fromLngLat(location.longitude, location.latitude))
        currentStyle.getLayerAs<SymbolLayer>(POSITION_LAYER_ID)
            ?.setProperties(iconRotate(lastBearing))

        routeFitted = true
        currentMap.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(location.latitude, location.longitude))
                    .zoom(FOLLOW_ZOOM)
                    .bearing(lastBearing.toDouble())
                    .tilt(0.0)
                    .build()
            ),
            900
        )
    }

    // ---------------------------------------------------------------- Colori traccia

    private fun uniformFeatures(route: GpxRoute, color: String): List<Feature> {
        val coordinates = route.points.map { Point.fromLngLat(it.longitude, it.latitude) }
        return listOf(
            Feature.fromGeometry(LineString.fromLngLats(coordinates)).apply {
                addStringProperty(COLOR_PROPERTY, color)
            }
        )
    }

    private fun slopeFeatures(route: GpxRoute, fallbackColor: String): List<Feature> {
        val points = route.points
        val cumulative = DoubleArray(points.size)
        for (index in 1 until points.size) {
            cumulative[index] = cumulative[index - 1] +
                GeoMath.haversineMeters(points[index - 1], points[index])
        }

        val segmentColors = ArrayList<String>(points.size - 1)
        for (index in 1 until points.size) {
            val midpoint = (cumulative[index - 1] + cumulative[index]) / 2.0
            var startIndex = index - 1
            while (startIndex > 0 && cumulative[startIndex] > midpoint - SLOPE_HALF_WINDOW_METERS) startIndex--
            var endIndex = index
            while (endIndex < points.lastIndex && cumulative[endIndex] < midpoint + SLOPE_HALF_WINDOW_METERS) endIndex++

            val startElevation = points[startIndex].elevation
            val endElevation = points[endIndex].elevation
            val horizontal = cumulative[endIndex] - cumulative[startIndex]
            segmentColors += if (
                startElevation != null && endElevation != null && horizontal >= MIN_SLOPE_SAMPLE_METERS
            ) {
                slopeColor((endElevation - startElevation) / horizontal * 100.0)
            } else {
                fallbackColor
            }
        }

        val features = mutableListOf<Feature>()
        var groupStart = 0
        var groupColor = segmentColors.first()
        for (segmentIndex in 1..segmentColors.size) {
            val changed = segmentIndex == segmentColors.size || segmentColors[segmentIndex] != groupColor
            if (!changed) continue
            val coordinates = (groupStart..segmentIndex).map { pointIndex ->
                Point.fromLngLat(points[pointIndex].longitude, points[pointIndex].latitude)
            }
            features += Feature.fromGeometry(LineString.fromLngLats(coordinates)).apply {
                addStringProperty(COLOR_PROPERTY, groupColor)
            }
            if (segmentIndex < segmentColors.size) {
                groupStart = segmentIndex
                groupColor = segmentColors[segmentIndex]
            }
        }
        return features
    }

    private fun slopeColor(gradePercent: Double): String = when {
        gradePercent >= 7.0 -> "#E53935"
        gradePercent > 1.0 -> "#FDD835"
        gradePercent <= -7.0 -> "#1565C0"
        gradePercent < -1.0 -> "#4FC3F7"
        else -> "#43A047"
    }

    private fun createArrowBitmap(): Bitmap {
        val width = 108
        val height = 148
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val path = Path().apply {
            moveTo(width / 2f, 3f)
            lineTo(width - 8f, height - 8f)
            lineTo(width / 2f, height - 42f)
            lineTo(8f, height - 8f)
            close()
        }
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 7f
                strokeJoin = Paint.Join.ROUND
            }
        )
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF005BBB.toInt()
                style = Paint.Style.FILL
            }
        )
        return bitmap
    }

    private companion object {
        const val PREFS_MAP = "map_settings"
        const val PREF_MAP_MODE = "map_mode"
        const val PREF_ACTIVE_MAP = "active_map"
        const val MAP_MODE_ONLINE = "online"
        const val MAP_MODE_OFFLINE = "offline"
        const val ONLINE_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

        const val PREFS_NAVIGATION = "navigation_settings"
        const val PREF_NAVIGATION_ROUTE_PATH = "navigation_route_path"
        const val PREF_ACTIVE_GPX = "active_gpx_path"
        const val PREFS_ROUTE_APPEARANCE = "route_appearance"

        const val ROUTE_SOURCE_ID = "car-route-source"
        const val ROUTE_LAYER_ID = "car-route-layer"
        const val COLOR_PROPERTY = "slopeColor"
        const val POSITION_SOURCE_ID = "car-position-source"
        const val POSITION_LAYER_ID = "car-position-layer"
        const val POSITION_IMAGE_ID = "car-position-arrow"

        const val FOLLOW_ZOOM = 15.0
        const val MIN_BEARING_SPEED_MS = 1.0f
        const val SLOPE_HALF_WINDOW_METERS = 50.0
        const val MIN_SLOPE_SAMPLE_METERS = 25.0
    }
}
