package com.example.gpxnavpro

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.gpxnavpro.databinding.ActivityMainBinding
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import org.maplibre.geojson.LineString
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var binding: ActivityMainBinding
    private var mapLibreMap: MapLibreMap? = null
    private val fileExecutor = Executors.newSingleThreadExecutor()
    private var incomingGpxHandled = false
    private lateinit var locationManager: LocationManager
    private var lastLocation: Location? = null
    private var firstGpsFix = true
    private var followGps = true
    private var manualZoomLevel: Double? = null
    private var isScalingMap = false
    private var activeRoute: GpxRoute? = null
    private var activeRouteFile: File? = null
    private var routesDialog: AlertDialog? = null
    private var currentRouteProgressMeters = 0.0
    private var tripResetOffsetMeters = 0.0
    private var manualStartKilometers: Double = 0.0

    private val simulationHandler = Handler(Looper.getMainLooper())
    private var isSimulationActive = false
    private var isSimulationPaused = false
    private var simulationSpeedMultiplier = 1.0
    private var simulationProgressMeters = 0.0
    private var simulationLastTickMs = 0L
    private var simulationCumulativeMeters = DoubleArray(0)
    private val simulationAlertFeedback by lazy { AlertFeedback(this) }
    private val navigationEngine = NavigationEngine()
    private val routeAlertEngine = RouteAlertEngine()
    private val approachNavigationEngine = NavigationEngine()
    private val turnInstructionEngine = TurnInstructionEngine()
    private lateinit var bRouterClient: BRouterClient
    private var wasOffRoute = false
    private var isNavigationActive = false
    private var pendingNavigationStartAfterNotificationPermission = false
    private var isApproachingStart = false
    private var activeApproachRoute: GpxRoute? = null
    private var approachOffRouteFixCount = 0
    private var isApproachRecalculationInProgress = false
    private var lastApproachRecalculationAt = 0L
    private var pendingExportFile: File? = null
    private var pendingMapExportFile: File? = null
    private var isGpxEditSelectionMode = false
    private var pendingGpxEditAction: String? = null
    private var editSegmentStartIndex: Int? = null
    private var editSegmentEndIndex: Int? = null
    private var editMovePointIndex: Int? = null
    private val createGpxControlPoints = mutableListOf<GpxPoint>()
    private var createGpxRoutePoints: List<GpxPoint> = emptyList()

    private val exportGpxLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        val source = pendingExportFile
        pendingExportFile = null
        if (uri == null || source == null) return@registerForActivityResult
        fileExecutor.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri).use { output ->
                    requireNotNull(output) { "Destinazione non accessibile" }
                    source.inputStream().use { input -> input.copyTo(output) }
                }
            }
            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(this, "GPX salvato", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "Impossibile salvare il GPX", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val exportMapLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val source = pendingMapExportFile
        pendingMapExportFile = null
        if (uri == null || source == null) return@registerForActivityResult
        fileExecutor.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri).use { output ->
                    requireNotNull(output) { "Destinazione non accessibile" }
                    source.inputStream().use { input -> input.copyTo(output) }
                }
            }
            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(this, "Mappa salvata", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "Impossibile salvare la mappa", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (granted) {
            startLocationUpdates()
        } else {
            showStatus(
                message = "Permesso posizione non concesso",
                showButton = false
            )
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (pendingNavigationStartAfterNotificationPermission) {
                pendingNavigationStartAfterNotificationPermission = false
                startNavigation()
            } else {
                restoreNavigationServiceIfNeeded()
            }
        } else {
            pendingNavigationStartAfterNotificationPermission = false
            if (isNavigationActive && !NavigationLocationService.isRunning) {
                getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_NAVIGATION_ACTIVE, false)
                    .putBoolean(PREF_NAVIGATION_MAIN_ROUTE_ACTIVE, false)
                    .apply()
                resetNavigationUiAfterExternalStop()
            }
            Toast.makeText(
                this,
                "Abilita le notifiche per usare la navigazione in background",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val mapPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importMap(uri)
        }
    }

    private val gpxPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importGpx(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        incomingGpxHandled = savedInstanceState?.getBoolean(STATE_INCOMING_GPX_HANDLED, false) ?: false

        MapLibre.getInstance(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        manualStartKilometers = getSharedPreferences(PREFS_DISPLAY, Context.MODE_PRIVATE)
            .getFloat(PREF_MANUAL_START_KM, 0f)
            .toDouble()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        bRouterClient = BRouterClient(this)
        applyKeepScreenOnPreference()
        updateRecenterButtonColor()

        binding.recenterButton.setOnClickListener {
            setFollowGps(true)
            lastLocation?.let { centerOnLocation(it, animated = true) }
        }
        binding.startNavigationButton.setOnClickListener {
            toggleNavigation()
        }
        binding.finishPanel.setOnClickListener {
            showStartKilometersDialog()
        }

        binding.menuButton.setOnClickListener { openDrawer() }
        binding.drawerScrim.setOnClickListener { closeDrawer() }
        binding.trackSectionHeader.setOnClickListener {
            toggleDrawerSection(
                binding.trackSectionHeader,
                binding.trackSectionContent,
                R.drawable.ic_route
            )
        }
        binding.raceSectionHeader.setOnClickListener {
            toggleDrawerSection(
                binding.raceSectionHeader,
                binding.raceSectionContent,
                R.drawable.ic_alert
            )
        }
        binding.routesSectionHeader.setOnClickListener {
            toggleDrawerSection(
                binding.routesSectionHeader,
                binding.routesSectionContent,
                R.drawable.ic_route_profile
            )
        }
        binding.settingsSectionHeader.setOnClickListener {
            toggleDrawerSection(
                binding.settingsSectionHeader,
                binding.settingsSectionContent,
                R.drawable.ic_settings
            )
        }
        binding.importGpxDrawerButton.setOnClickListener {
            closeDrawer()
            gpxPicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*"))
        }
        binding.alertsDrawerButton.setOnClickListener {
            closeDrawer()
            showAlertDistanceSettings()
        }
        binding.alertKilometersDrawerButton.setOnClickListener {
            closeDrawer()
            showAlertKilometerSettings()
        }
        binding.offlineMapDrawerButton.setOnClickListener {
            closeDrawer()
            showOfflineMapActions()
        }
        binding.keepScreenOnDrawerButton.setOnClickListener {
            toggleKeepScreenOnPreference()
        }
        binding.brouterProfileDrawerButton.setOnClickListener {
            closeDrawer()
            showBRouterProfileDialog()
        }
        binding.guideDrawerButton.setOnClickListener {
            closeDrawer()
            showCommandGuide()
        }
        binding.appearanceDrawerButton.setOnClickListener {
            val file = activeRouteFile
            closeDrawer()
            if (file != null) showRouteAppearanceSettings(file)
            else Toast.makeText(this, "Apri prima un percorso GPX", Toast.LENGTH_SHORT).show()
        }
        binding.editGpxDrawerButton.setOnClickListener {
            closeDrawer()
            showGpxEditorStart()
        }
        binding.createGpxDrawerButton.setOnClickListener {
            closeDrawer()
            startCreateGpxMode()
        }
        binding.confirmEditPointButton.setOnClickListener {
            val target = mapLibreMap?.cameraPosition?.target ?: return@setOnClickListener
            when (pendingGpxEditAction) {
                EDIT_ACTION_ADD_VIA -> createAddedGpxSegment(target)
                EDIT_ACTION_MOVE_TARGET -> moveSelectedGpxPoint(target)
                else -> selectGpxEditPoint(target)
            }
        }
        binding.cancelEditPointButton.setOnClickListener {
            setGpxEditSelectionMode(false)
            removeGpxEditSelectionMarker()
        }
        binding.editAddSegmentButton.setOnClickListener {
            editSegmentStartIndex = null
            editSegmentEndIndex = null
            beginGpxPointSelection(
                EDIT_ACTION_ADD_START,
                "Sposta il mirino sul punto iniziale del tratto da sostituire"
            )
        }
        binding.editCutButton.setOnClickListener {
            beginGpxPointSelection(EDIT_ACTION_CUT, "Sposta il mirino sul punto di taglio")
        }
        binding.editReverseButton.setOnClickListener {
            val route = activeRoute ?: return@setOnClickListener
            saveEditedGpx(route.points.reversed(), route.waypoints, "invertito")
        }
        binding.editMovePointButton.setOnClickListener {
            editMovePointIndex = null
            beginGpxPointSelection(
                EDIT_ACTION_MOVE_SELECT,
                "Sposta il mirino sul punto GPX da modificare"
            )
        }
        binding.editMergeRouteButton.setOnClickListener {
            showMergeRouteDialog()
        }
        binding.editRepeatRouteButton.setOnClickListener {
            showRepeatRouteDialog()
        }
        binding.editCenterRouteButton.setOnClickListener {
            activeRoute?.let(::zoomToRoute)
        }
        binding.editCloseButton.setOnClickListener { finishGpxEditMode() }
        binding.createAddPointButton.setOnClickListener { addCreateGpxPoint() }
        binding.createUndoButton.setOnClickListener { undoCreateGpxPoint() }
        binding.createSaveButton.setOnClickListener { showSaveCreatedGpxDialog() }
        binding.createCloseButton.setOnClickListener { finishCreateGpxMode() }
        binding.simulationPauseButton.setOnClickListener {
            toggleSimulationPause()
        }
        binding.simulationSpeed1Button.setOnClickListener { setSimulationSpeed(1.0) }
        binding.simulationSpeed2Button.setOnClickListener { setSimulationSpeed(2.0) }
        binding.simulationSpeed5Button.setOnClickListener { setSimulationSpeed(5.0) }
        binding.simulationSpeed10Button.setOnClickListener { setSimulationSpeed(10.0) }
        binding.simulationStopButton.setOnClickListener {
            stopSimulation()
        }
        binding.startPanel.setOnClickListener {
            tripResetOffsetMeters = currentRouteProgressMeters
            updateDistancePanels()
            Toast.makeText(this, "Contachilometri azzerato", Toast.LENGTH_SHORT).show()
        }
        binding.startPanel.setOnLongClickListener {
            tripResetOffsetMeters = 0.0
            updateDistancePanels()
            Toast.makeText(this, "Contachilometri ripristinato", Toast.LENGTH_SHORT).show()
            true
        }
        binding.zoomInButton.setOnClickListener {
            val map = mapLibreMap ?: return@setOnClickListener
            val targetZoom = (map.cameraPosition.zoom + 1.0).coerceAtMost(22.0)
            manualZoomLevel = targetZoom
            if (isSimulationActive) {
                updateSimulationMarker(simulationProgressMeters)
            } else {
                map.animateCamera(CameraUpdateFactory.zoomTo(targetZoom), 250)
            }
        }
        binding.zoomOutButton.setOnClickListener {
            val map = mapLibreMap ?: return@setOnClickListener
            val targetZoom = (map.cameraPosition.zoom - 1.0).coerceAtLeast(0.0)
            manualZoomLevel = targetZoom
            if (isSimulationActive) {
                updateSimulationMarker(simulationProgressMeters)
            } else {
                map.animateCamera(CameraUpdateFactory.zoomTo(targetZoom), 250)
            }
        }
        binding.importGpxButton.setOnClickListener {
            gpxPicker.launch(
                arrayOf(
                    "application/gpx+xml",
                    "application/xml",
                    "text/xml",
                    "*/*"
                )
            )
        }

        binding.importMapButton.setOnClickListener {
            mapPicker.launch(
                arrayOf(
                    "application/octet-stream",
                    "application/vnd.pmtiles",
                    "*/*"
                )
            )
        }

        binding.mapView.onCreate(savedInstanceState)

        binding.mapView.addOnDidFailLoadingMapListener { error ->
            val offlineAvailable = installedMapFile().exists()
            showStatus(
                message = if (offlineAvailable) {
                    "Mappa online non disponibile.\nControlla Internet oppure seleziona la mappa offline dal menu.\n\n$error"
                } else {
                    "Mappa online non disponibile.\nControlla la connessione Internet.\nPuoi anche importare una mappa PMTiles.\n\n$error"
                },
                showButton = !offlineAvailable
            )
        }

        binding.mapView.getMapAsync { map ->
            mapLibreMap = map
            map.addOnMapClickListener { latLng -> handleGpxRouteMapTap(map, latLng) }
            map.addOnCameraIdleListener {
                updateScaleBar()
            }
            map.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                override fun onScaleBegin(detector: StandardScaleGestureDetector) {
                    isScalingMap = true
                }

                override fun onScale(detector: StandardScaleGestureDetector) = Unit

                override fun onScaleEnd(detector: StandardScaleGestureDetector) {
                    manualZoomLevel = map.cameraPosition.zoom
                    isScalingMap = false
                    if (isSimulationActive) {
                        updateSimulationMarker(simulationProgressMeters)
                    }
                    updateRecenterButtonColor()
                }
            })
            map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) {
                    if (!isScalingMap) {
                        setFollowGps(false)
                    }
                }
                override fun onMove(detector: MoveGestureDetector) = Unit
                override fun onMoveEnd(detector: MoveGestureDetector) = Unit
            })
            loadInstalledMap()
            updateScaleBar()
        }

        handleIncomingGpxIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingGpxHandled = false
        setIntent(intent)
        handleIncomingGpxIntent(intent)
    }

    /**
     * Riceve un file GPX aperto dal File Manager, Drive,
     * Telegram, WhatsApp o altre applicazioni Android.
     *
     * importGpx() copia subito il file nello spazio privato
     * di GpxNav Pro e lo imposta come percorso attivo.
     */
    private fun handleIncomingGpxIntent(incomingIntent: Intent?) {
        if (incomingGpxHandled) return
        if (incomingIntent?.action != Intent.ACTION_VIEW) return

        val uri = incomingIntent.data ?: return
        incomingGpxHandled = true

        // Consuma l'intent nell'istanza corrente: una ricreazione dell'Activity
        // non deve generare copie _2, _3 dello stesso GPX.
        setIntent(
            Intent(incomingIntent).apply {
                action = Intent.ACTION_MAIN
                data = null
                clipData = null
            }
        )

        importGpx(uri)
    }

    // =========================================================
    // CARICAMENTO MAPPA ONLINE / PMTILES OFFLINE
    // =========================================================

    private fun loadInstalledMap() {
        val selectedMode = getSharedPreferences(PREFS_MAP, Context.MODE_PRIVATE)
            .getString(PREF_MAP_MODE, MAP_MODE_ONLINE)
        val offlineMap = installedMapFile()

        if (selectedMode == MAP_MODE_OFFLINE && offlineMap.exists() && offlineMap.length() > 0L) {
            loadOfflineMap(offlineMap)
        } else {
            if (selectedMode == MAP_MODE_OFFLINE) {
                saveMapMode(MAP_MODE_ONLINE)
            }
            loadOnlineMap()
        }
    }

    private fun loadOnlineMap() {
        val map = mapLibreMap ?: return

        showStatus(
            message = "Caricamento mappa online…",
            showButton = false
        )

        map.setStyle(Style.Builder().fromUri(ONLINE_STYLE_URL)) { style ->
            centerOnFriuli(map)
            loadInstalledGpx(style)
            installGpsMarker(style)
            requestLocationPermissionIfNeeded()

            showStatus(
                message = "Mappa online caricata\nDati © OpenStreetMap",
                showButton = false
            )
            binding.statusPanel.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    binding.statusPanel.visibility = View.GONE
                }
            }, 1200L)
        }
    }

    private fun loadOfflineMap(mapFile: File) {
        val map = mapLibreMap ?: return

        showStatus(
            message = "Apertura mappa offline…",
            showButton = false
        )

        val mapUri = "pmtiles://file://${mapFile.absolutePath}"
        val styleJson = createVectorStyleJson(mapUri)

        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            centerOnFriuli(map)
            loadInstalledGpx(style)
            installGpsMarker(style)
            requestLocationPermissionIfNeeded()

            showStatus(
                message = "Mappa offline caricata\nDati © OpenStreetMap",
                showButton = false
            )

            binding.statusPanel.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    binding.statusPanel.visibility = View.GONE
                }
            }, 1800L)
        }
    }

    private fun centerOnFriuli(map: MapLibreMap) {
        map.cameraPosition = CameraPosition.Builder()
            .target(FRIULI_VENEZIA_GIULIA)
            .zoom(8.2)
            .build()
    }

    // =========================================================
    // POSIZIONE GPS E SIMBOLO MOTO
    // =========================================================

    private fun requestLocationPermissionIfNeeded() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            startLocationUpdates()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun startLocationUpdates() {
        if (NavigationLocationService.isRunning) {
            NavigationLocationService.freshLastLocation()?.let { onLocationChanged(it) }
            return
        }

        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) return

        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                1f,
                this
            )

            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                onLocationChanged(it)
            }
        }.onFailure { error ->
            showStatus(
                message = "GPS non disponibile: ${error.message ?: "errore sconosciuto"}",
                showButton = false
            )
        }
    }

    private fun installGpsMarker(style: Style) {
        if (style.getImage(GPS_IMAGE_ID) == null) {
            style.addImage(GPS_IMAGE_ID, createNavigationArrowBitmap())
        }

        if (style.getSource(GPS_SOURCE_ID) == null) {
            style.addSource(
                GeoJsonSource(
                    GPS_SOURCE_ID,
                    Feature.fromGeometry(Point.fromLngLat(13.2346, 46.0711))
                )
            )
        }

        if (style.getLayer(GPS_LAYER_ID) == null) {
            style.addLayer(
                SymbolLayer(GPS_LAYER_ID, GPS_SOURCE_ID).withProperties(
                    iconImage(GPS_IMAGE_ID),
                    iconSize(1.0f),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    iconRotate(0f)
                )
            )
        }

        lastLocation?.let { updateGpsMarker(it) }
    }

    private fun createNavigationArrowBitmap(): Bitmap {
        // Freccia più alta, leggermente più grande e con punta più affilata.
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

        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, outlinePaint)

        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF005BBB.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawPath(path, arrowPaint)
        return bitmap
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        updateSpeedPanel(location)
        updateGpsMarker(location)
        activeRoute?.let {
            if (isNavigationActive && isApproachingStart) {
                updateApproachNavigation(location)
            } else if (isNavigationActive) {
                val navigationFix = NavigationLocationService.lastNavigationFix
                if (navigationFix != null) {
                    currentRouteProgressMeters = navigationFix.progressMeters
                    updateDistancePanels()
                    updateOffRouteWarning(navigationFix)
                    updateRouteAlertPanel()
                }
            } else {
                // Fuori dalla navigazione reale non facciamo avanzare il
                // NavigationEngine: evita di "sporcare" il progresso prima
                // della partenza o passando vicino a tratti finali della traccia.
                updateDistancePanels()
            }
        }

        if (firstGpsFix || followGps) {
            centerOnLocation(location, animated = !firstGpsFix)
            firstGpsFix = false
        }
    }

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    @Deprecated("Deprecated in Android 10")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun toggleNavigation() {
        if (activeRoute == null) {
            Toast.makeText(this, "Apri prima un percorso GPX", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isNavigationActive) {
            startNavigation()
        } else {
            stopNavigation()
        }
    }

    private fun startNavigation() {
        if (isSimulationActive) {
            stopSimulation(silent = true)
        }
        val route = activeRoute ?: return
        val location = lastLocation?.takeIf(::isFreshLocation)
        if (location == null) {
            lastLocation = null
            Toast.makeText(this, "Attendo una posizione GPS recente", Toast.LENGTH_SHORT).show()
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNavigationStartAfterNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        navigationEngine.resetProgress()
        approachNavigationEngine.resetProgress()
        isNavigationActive = true
        applyKeepScreenOnPreference()
        getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_NAVIGATION_ACTIVE, true)
            .putString(PREF_NAVIGATION_ROUTE_PATH, activeRouteFile?.absolutePath)
            .putBoolean(PREF_NAVIGATION_MAIN_ROUTE_ACTIVE, false)
            .putFloat(PREF_NAVIGATION_PROGRESS_METERS, 0f)
            .apply()
        NavigationLocationService.start(this)
        runCatching { locationManager.removeUpdates(this) }
        setFollowGps(true)
        binding.eventAlertPanel.visibility = View.GONE
        binding.startNavigationButton.text = "STOP"
        centerOnLocation(
            location,
            animated = true,
            scaleDistanceMeters = INITIAL_NAVIGATION_SCALE_METERS
        )

        val start = route.points.first()
        val distanceToStart = distanceMeters(
            location.latitude,
            location.longitude,
            start.latitude,
            start.longitude
        )
        if (distanceToStart <= START_REACHED_DISTANCE_METERS) {
            beginMainRouteNavigation()
            return
        }

        binding.startNavigationButton.text = "CALCOLO…"
        binding.startNavigationButton.isEnabled = false
        binding.turnText.text = "CALCOLO PERCORSO VERSO LA PARTENZA…"
        binding.turnPanel.visibility = View.VISIBLE
        bRouterClient.calculateToStart(location, start) { result ->
            binding.startNavigationButton.isEnabled = true
            if (!isNavigationActive) return@calculateToStart

            result.onSuccess { approachRoute ->
                activeApproachRoute = approachRoute
                approachNavigationEngine.setRoute(approachRoute)
                turnInstructionEngine.setRoute(approachRoute)
                isApproachingStart = true
                binding.eventAlertPanel.visibility = View.GONE
                binding.startNavigationButton.text = "STOP"
                mapLibreMap?.style?.let { drawApproachRoute(it, approachRoute) }
                lastLocation?.let(::updateApproachNavigation)
                Toast.makeText(this, "Percorso verso la partenza calcolato", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                isNavigationActive = false
                applyKeepScreenOnPreference()
                isApproachingStart = false
                getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_NAVIGATION_ACTIVE, false)
                    .apply()
                NavigationLocationService.stop(this)
                startLocationUpdates()
                binding.startNavigationButton.text = "AVVIA"
                binding.turnPanel.visibility = View.GONE
                showStatus(
                    "Impossibile calcolare il percorso:\n${error.message ?: "errore BRouter"}",
                    false
                )
            }
        }
    }

    private fun updateApproachNavigation(location: Location) {
        if (isApproachRecalculationInProgress) return
        val fix = approachNavigationEngine.match(location) ?: return
        val routeStart = activeRoute?.points?.firstOrNull()
        val distanceToStart = routeStart?.let {
            distanceMeters(location.latitude, location.longitude, it.latitude, it.longitude)
        } ?: Double.MAX_VALUE
        if (distanceToStart <= START_REACHED_DISTANCE_METERS) {
            beginMainRouteNavigation()
            return
        }

        if (fix.distanceFromRouteMeters >= APPROACH_REROUTE_THRESHOLD_METERS) {
            approachOffRouteFixCount++
            if (approachOffRouteFixCount >= APPROACH_REROUTE_REQUIRED_FIXES) {
                recalculateApproachRoute(location)
                return
            }
        } else {
            approachOffRouteFixCount = 0
        }

        val nextTurn = turnInstructionEngine.next(fix.progressMeters)
        binding.turnText.text = if (nextTurn != null) {
            val distance = (nextTurn.progressMeters - fix.progressMeters).coerceAtLeast(0.0)
            "${nextTurn.symbol}  ${nextTurn.label}\n${formatNavigationDistance(distance)}"
        } else {
            "↑  PROSEGUI VERSO LA PARTENZA\n${formatNavigationDistance(fix.remainingMeters)}"
        }
        binding.turnPanel.visibility = View.VISIBLE
    }

    private fun recalculateApproachRoute(location: Location) {
        val start = activeRoute?.points?.firstOrNull() ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (isApproachRecalculationInProgress ||
            now - lastApproachRecalculationAt < APPROACH_REROUTE_COOLDOWN_MS
        ) return

        isApproachRecalculationInProgress = true
        approachOffRouteFixCount = 0
        lastApproachRecalculationAt = now
        binding.turnText.text = "RICALCOLO PERCORSO…"
        binding.turnPanel.visibility = View.VISIBLE

        bRouterClient.calculateToStart(location, start) { result ->
            isApproachRecalculationInProgress = false
            if (!isNavigationActive || !isApproachingStart) return@calculateToStart

            result.onSuccess { recalculatedRoute ->
                activeApproachRoute = recalculatedRoute
                approachNavigationEngine.setRoute(recalculatedRoute)
                turnInstructionEngine.setRoute(recalculatedRoute)
                mapLibreMap?.style?.let { drawApproachRoute(it, recalculatedRoute) }
                lastLocation?.let(::updateApproachNavigation)
                Toast.makeText(this, "Percorso ricalcolato", Toast.LENGTH_SHORT).show()
            }.onFailure {
                binding.turnText.text = "RICALCOLO NON RIUSCITO\nPROSEGUI VERSO LA PARTENZA"
                binding.turnPanel.visibility = View.VISIBLE
            }
        }
    }

    private fun beginMainRouteNavigation() {
        isApproachingStart = false
        activeApproachRoute = null
        navigationEngine.resetProgress()
        getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_NAVIGATION_MAIN_ROUTE_ACTIVE, true)
            .putFloat(PREF_NAVIGATION_PROGRESS_METERS, 0f)
            .apply()
        approachOffRouteFixCount = 0
        isApproachRecalculationInProgress = false
        binding.turnPanel.visibility = View.GONE
        mapLibreMap?.style?.let(::removeApproachRoute)
        binding.startNavigationButton.text = "STOP"
        Toast.makeText(this, "Inizio percorso GPX", Toast.LENGTH_SHORT).show()
    }

    private fun updateRouteAlertPanel() {
        if ((!isNavigationActive && !isSimulationActive) || isApproachingStart) {
            binding.eventAlertPanel.visibility = View.GONE
            return
        }

        val preferences = getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
        val activeAlert = routeAlertEngine.activeAlert(currentRouteProgressMeters) { type ->
            preferences.getInt(type.preferenceKey, DEFAULT_ALERT_DISTANCE)
        }

        if (activeAlert == null) {
            binding.eventAlertPanel.visibility = View.GONE
            return
        }

        if (isSimulationActive) {
            simulationAlertFeedback.routeAlert(activeAlert)
        }

        val type = activeAlert.alert.type
        val distance = formatNavigationDistance(activeAlert.distanceMeters.coerceAtLeast(0.0))
        val eventKilometer = String.format(
            java.util.Locale.ITALY,
            "%.1f",
            activeAlert.alert.progressMeters / 1000.0
        )
        binding.eventAlertText.text =
            "${type.shortLabel}  •  TRA $distance\nKM $eventKilometer DALL'INIZIO"
        binding.eventAlertPanel.setCardBackgroundColor(
            android.graphics.Color.parseColor(type.color)
        )
        binding.eventAlertText.setTextColor(
            if (type == RouteAlertType.GPM || type == RouteAlertType.GREEN_ZONE) {
                android.graphics.Color.rgb(23, 32, 42)
            } else {
                android.graphics.Color.WHITE
            }
        )
        binding.eventAlertPanel.visibility = View.VISIBLE
    }

    private fun stopNavigation() {
        isNavigationActive = false
        applyKeepScreenOnPreference()
        getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_NAVIGATION_ACTIVE, false)
            .putBoolean(PREF_NAVIGATION_MAIN_ROUTE_ACTIVE, false)
            .putFloat(PREF_NAVIGATION_PROGRESS_METERS, 0f)
            .remove(PREF_NAVIGATION_ROUTE_PATH)
            .apply()
        NavigationLocationService.stop(this)
        isApproachingStart = false
        activeApproachRoute = null
        approachOffRouteFixCount = 0
        isApproachRecalculationInProgress = false
        binding.startNavigationButton.text = "AVVIA"
        binding.startNavigationButton.isEnabled = true
        binding.turnPanel.visibility = View.GONE
        binding.offRoutePanel.visibility = View.GONE
        binding.eventAlertPanel.visibility = View.GONE
        mapLibreMap?.style?.let(::removeApproachRoute)
        wasOffRoute = false
        startLocationUpdates()
        Toast.makeText(this, "Navigazione terminata", Toast.LENGTH_SHORT).show()
    }

    private fun resetNavigationUiAfterExternalStop() {
        isNavigationActive = false
        isApproachingStart = false
        activeApproachRoute = null
        approachOffRouteFixCount = 0
        isApproachRecalculationInProgress = false
        binding.startNavigationButton.text = "AVVIA"
        binding.startNavigationButton.isEnabled = true
        binding.turnPanel.visibility = View.GONE
        binding.offRoutePanel.visibility = View.GONE
        binding.eventAlertPanel.visibility = View.GONE
        mapLibreMap?.style?.let(::removeApproachRoute)
        wasOffRoute = false
        applyKeepScreenOnPreference()
        startLocationUpdates()
    }

    private fun formatNavigationDistance(distanceMeters: Double): String =
        if (distanceMeters < 1000.0) {
            "${distanceMeters.toInt()} m"
        } else {
            String.format(java.util.Locale.ITALY, "%.1f km", distanceMeters / 1000.0)
        }

    private fun prepareNavigationForRoute() {
        if (isSimulationActive) {
            stopSimulation(silent = true)
        }
        currentRouteProgressMeters = 0.0
        tripResetOffsetMeters = 0.0
        updateDistancePanels()
        isNavigationActive = false
        isApproachingStart = false
        activeApproachRoute = null
        approachOffRouteFixCount = 0
        isApproachRecalculationInProgress = false
        wasOffRoute = false
        binding.turnPanel.visibility = View.GONE
        binding.offRoutePanel.visibility = View.GONE
        binding.eventAlertPanel.visibility = View.GONE
        binding.startNavigationButton.isEnabled = true
        binding.startNavigationButton.text = "AVVIA"
    }

    private fun drawApproachRoute(style: Style, route: GpxRoute) {
        removeApproachRoute(style)
        val coordinates = route.points.map {
            Point.fromLngLat(it.longitude, it.latitude)
        }
        style.addSource(
            GeoJsonSource(
                APPROACH_SOURCE_ID,
                Feature.fromGeometry(LineString.fromLngLats(coordinates))
            )
        )
        val approachLayer = LineLayer(APPROACH_LAYER_ID, APPROACH_SOURCE_ID).withProperties(
            lineColor("#7C3AED"),
            lineWidth(7f),
            lineOpacity(0.95f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND)
        )
        if (style.getLayer(GPS_LAYER_ID) != null) {
            style.addLayerBelow(approachLayer, GPS_LAYER_ID)
        } else {
            style.addLayer(approachLayer)
        }
    }

    private fun removeApproachRoute(style: Style) {
        if (style.getLayer(APPROACH_LAYER_ID) != null) {
            style.removeLayer(APPROACH_LAYER_ID)
        }
        if (style.getSource(APPROACH_SOURCE_ID) != null) {
            style.removeSource(APPROACH_SOURCE_ID)
        }
    }

    private fun updateOffRouteWarning(fix: NavigationFix?) {
        val isOffRoute = fix?.isOffRoute == true
        if (isOffRoute) {
            val distance = fix?.distanceFromRouteMeters?.toInt() ?: return
            binding.offRouteText.text = "FUORI PERCORSO  •  $distance m"
            binding.offRoutePanel.visibility = View.VISIBLE
        } else if (wasOffRoute) {
            binding.offRoutePanel.visibility = View.GONE
        }
        wasOffRoute = isOffRoute
    }

    private fun isFreshLocation(location: Location, maxAgeMs: Long = 15_000L): Boolean {
        val ageNanos = android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return ageNanos >= 0L && ageNanos <= maxAgeMs * 1_000_000L
    }

    private fun updateGpsMarker(location: Location) {
        val style = mapLibreMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(GPS_SOURCE_ID) ?: return

        source.setGeoJson(
            Feature.fromGeometry(
                Point.fromLngLat(location.longitude, location.latitude)
            )
        )

        style.getLayerAs<SymbolLayer>(GPS_LAYER_ID)?.setProperties(
            iconRotate(if (location.hasBearing()) location.bearing else 0f)
        )
    }

    private fun centerOnLocation(
        location: Location,
        animated: Boolean,
        scaleDistanceMeters: Double? = null
    ) {
        val map = mapLibreMap ?: return
        // Sposta il punto seguito di circa 3 cm verso il basso, lasciando
        // più mappa visibile davanti alla direzione di marcia.
        val topPaddingPx = (228f * resources.displayMetrics.density).toInt()
        map.setPadding(0, topPaddingPx, 0, 0)

        val scaleZoom = scaleDistanceMeters?.let { distance ->
            val scaleWidthPx = 91.0 * resources.displayMetrics.density
            val metersPerPixel = distance / scaleWidthPx
            kotlin.math.log2(
                156543.03392 * kotlin.math.cos(Math.toRadians(location.latitude)) /
                    metersPerPixel
            ).coerceIn(0.0, 22.0)
        }
        val camera = CameraPosition.Builder()
            .target(LatLng(location.latitude, location.longitude))
            .zoom(scaleZoom ?: manualZoomLevel ?: when {
                !location.hasSpeed() -> 17.2
                location.speed < 3f -> 17.5
                location.speed < 14f -> 17.1
                location.speed < 25f -> 16.7
                else -> 16.2
            })
            .bearing(if (location.hasBearing()) location.bearing.toDouble() else map.cameraPosition.bearing)
            .tilt(35.0)
            .build()

        if (animated) {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 700)
        } else {
            map.cameraPosition = camera
        }
    }

    // =========================================================
    // IMPORTAZIONE E DISEGNO GPX
    // =========================================================

    private fun updateSpeedPanel(location: Location) {
        val speedKmh = if (location.hasSpeed()) (location.speed * 3.6f).coerceAtLeast(0f) else null
        val number = speedKmh?.let { kotlin.math.round(it).toInt().toString() } ?: "--"
        val text = "$number\nKM/H"
        binding.speedText.text = SpannableString(text).apply {
            setSpan(AbsoluteSizeSpan(23, true), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan(9, true), number.length + 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun updateScaleBar() {
        val map = mapLibreMap ?: return
        val latitude = map.cameraPosition.target?.latitude ?: return
        val zoom = map.cameraPosition.zoom
        val metersPerPixel =
            156543.03392 * kotlin.math.cos(Math.toRadians(latitude)) / Math.pow(2.0, zoom)
        if (!metersPerPixel.isFinite() || metersPerPixel <= 0.0) return

        val targetWidthPx = 91.0 * resources.displayMetrics.density
        val distanceMeters = metersPerPixel * targetWidthPx
        binding.scaleText.text = if (distanceMeters >= 1000.0) {
            val kilometers = distanceMeters / 1000.0
            String.format(java.util.Locale.ITALY, "%.1f km", kilometers)
        } else {
            val roundedMeters = when {
                distanceMeters >= 100.0 ->
                    (distanceMeters / 10.0).toInt() * 10
                distanceMeters >= 20.0 ->
                    (distanceMeters / 5.0).toInt() * 5
                else -> distanceMeters.toInt()
            }.coerceAtLeast(1)
            "$roundedMeters m"
        }
    }

    private fun applyKeepScreenOnPreference() {
        val keepOnDuringNavigation = getSharedPreferences(PREFS_DISPLAY, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEEP_SCREEN_ON_NAVIGATION, true)
        val shouldKeepScreenOn = keepOnDuringNavigation && isNavigationActive
        if (shouldKeepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun toggleKeepScreenOnPreference() {
        val preferences = getSharedPreferences(PREFS_DISPLAY, Context.MODE_PRIVATE)
        val enabled = !preferences.getBoolean(PREF_KEEP_SCREEN_ON_NAVIGATION, true)
        preferences.edit().putBoolean(PREF_KEEP_SCREEN_ON_NAVIGATION, enabled).apply()
        applyKeepScreenOnPreference()
        updateDrawerPreferenceLabels()
        Toast.makeText(
            this,
            if (enabled) "Schermo acceso solo durante la navigazione" else "Schermo acceso automatico disattivato",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateDrawerPreferenceLabels() {
        val keepScreenOn = getSharedPreferences(PREFS_DISPLAY, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEEP_SCREEN_ON_NAVIGATION, true)
        binding.keepScreenOnDrawerButton.text =
            "Schermo acceso in navigazione: ${if (keepScreenOn) "Sì" else "No"}"

        val profile = getSharedPreferences(BRouterClient.PREFS_ROUTING, Context.MODE_PRIVATE)
            .getString(
                BRouterClient.PREF_BROUTER_PROFILE,
                BRouterClient.DEFAULT_BROUTER_PROFILE
            )
            ?: BRouterClient.DEFAULT_BROUTER_PROFILE
        binding.brouterProfileDrawerButton.text =
            "Profilo BRouter: ${bRouterProfileLabel(profile)}"
    }

    private fun showBRouterProfileDialog() {
        val profiles = arrayOf("car-fast", "trekking", "fastbike")
        val labels = arrayOf(
            "Moto / auto rapido",
            "Trekking",
            "Bici veloce"
        )
        val preferences = getSharedPreferences(BRouterClient.PREFS_ROUTING, Context.MODE_PRIVATE)
        val current = preferences.getString(
            BRouterClient.PREF_BROUTER_PROFILE,
            BRouterClient.DEFAULT_BROUTER_PROFILE
        ) ?: BRouterClient.DEFAULT_BROUTER_PROFILE
        val selected = profiles.indexOf(current).coerceAtLeast(0)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Profilo BRouter")
            .setSingleChoiceItems(labels, selected) { dialogInterface, which ->
                preferences.edit()
                    .putString(BRouterClient.PREF_BROUTER_PROFILE, profiles[which])
                    .apply()
                dialogInterface.dismiss()
                updateDrawerPreferenceLabels()
                Toast.makeText(
                    this,
                    "Profilo: ${labels[which]}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener { styleBlueDialog(dialog) }
        dialog.show()
    }

    private fun bRouterProfileLabel(profile: String): String = when (profile) {
        "trekking" -> "Trekking"
        "fastbike" -> "Bici veloce"
        else -> "Moto / auto rapido"
    }

    private fun toggleDrawerSection(header: TextView, content: View, startIconRes: Int) {
        val opening = content.visibility != View.VISIBLE
        content.visibility = if (opening) View.VISIBLE else View.GONE
        header.setCompoundDrawablesRelativeWithIntrinsicBounds(
            startIconRes,
            0,
            if (opening) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
            0
        )
    }

    private fun openDrawer() {
        populateDrawerRoutes()
        updateDrawerPreferenceLabels()
        binding.drawerScrim.visibility = View.VISIBLE
        binding.drawerPanel.animate().translationX(0f).setDuration(220L).start()
    }

    private fun closeDrawer() {
        binding.drawerPanel.animate().translationX(-binding.drawerPanel.width.toFloat())
            .setDuration(200L)
            .withEndAction { binding.drawerScrim.visibility = View.GONE }
            .start()
    }

    private fun startCreateGpxMode() {
        if (isNavigationActive) {
            Toast.makeText(this, "Ferma la navigazione prima di creare un GPX", Toast.LENGTH_LONG).show()
            return
        }
        finishGpxEditMode()
        createGpxControlPoints.clear()
        createGpxRoutePoints = emptyList()
        binding.gpxCreateToolbar.visibility = View.VISIBLE
        binding.gpxEditPointer.visibility = View.VISIBLE
        setFollowGps(false)
        Toast.makeText(
            this,
            "Sposta il mirino e premi + per inserire il primo punto",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun addCreateGpxPoint() {
        val target = mapLibreMap?.cameraPosition?.target ?: return
        createGpxControlPoints += GpxPoint(target.latitude, target.longitude)
        recalculateCreatedGpx()
    }

    private fun undoCreateGpxPoint() {
        if (createGpxControlPoints.isEmpty()) return
        createGpxControlPoints.removeAt(createGpxControlPoints.lastIndex)
        recalculateCreatedGpx()
    }

    private fun recalculateCreatedGpx() {
        when (createGpxControlPoints.size) {
            0 -> {
                createGpxRoutePoints = emptyList()
                drawCreatedGpxDraft()
            }
            1 -> {
                createGpxRoutePoints = createGpxControlPoints.toList()
                drawCreatedGpxDraft()
                Toast.makeText(this, "Primo punto inserito", Toast.LENGTH_SHORT).show()
            }
            else -> {
                binding.createAddPointButton.isEnabled = false
                Toast.makeText(this, "Calcolo della traccia…", Toast.LENGTH_SHORT).show()
                bRouterClient.calculateRoute(
                    createGpxControlPoints,
                    "Nuova traccia GPX"
                ) { result ->
                    binding.createAddPointButton.isEnabled = true
                    result.onSuccess { route ->
                        createGpxRoutePoints = route.points
                        drawCreatedGpxDraft()
                    }.onFailure { error ->
                        createGpxControlPoints.removeAt(createGpxControlPoints.lastIndex)
                        Toast.makeText(
                            this,
                            "Impossibile collegare il punto: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun drawCreatedGpxDraft() {
        val style = mapLibreMap?.style ?: return
        removeCreatedGpxDraft()
        if (createGpxControlPoints.isEmpty()) return

        val features = mutableListOf<Feature>()
        if (createGpxRoutePoints.size >= 2) {
            features += Feature.fromGeometry(
                LineString.fromLngLats(
                    createGpxRoutePoints.map { Point.fromLngLat(it.longitude, it.latitude) }
                )
            )
        }
        createGpxControlPoints.forEach { point ->
            features += Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))
        }
        style.addSource(
            GeoJsonSource(
                GPX_CREATE_SOURCE_ID,
                org.maplibre.geojson.FeatureCollection.fromFeatures(features)
            )
        )
        style.addLayer(
            LineLayer(GPX_CREATE_LINE_LAYER_ID, GPX_CREATE_SOURCE_ID).withProperties(
                lineColor("#1261A0"),
                lineWidth(7f),
                lineOpacity(0.9f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        style.addLayer(
            CircleLayer(GPX_CREATE_POINTS_LAYER_ID, GPX_CREATE_SOURCE_ID).withProperties(
                circleRadius(8f),
                circleColor("#FFFFFF"),
                circleStrokeColor("#1261A0"),
                circleStrokeWidth(4f)
            )
        )
    }

    private fun removeCreatedGpxDraft() {
        val style = mapLibreMap?.style ?: return
        listOf(GPX_CREATE_POINTS_LAYER_ID, GPX_CREATE_LINE_LAYER_ID).forEach { id ->
            if (style.getLayer(id) != null) style.removeLayer(id)
        }
        if (style.getSource(GPX_CREATE_SOURCE_ID) != null) style.removeSource(GPX_CREATE_SOURCE_ID)
    }

    private fun showSaveCreatedGpxDialog() {
        if (createGpxRoutePoints.size < 2) {
            Toast.makeText(this, "Inserisci almeno due punti", Toast.LENGTH_LONG).show()
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = "Nome della traccia"
            setText("Nuova_traccia")
            selectAll()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Salva traccia GPX")
            .setView(input)
            .setPositiveButton("Salva") { _, _ ->
                saveCreatedGpx(input.text.toString())
            }
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener { styleBlueDialog(dialog) }
        dialog.show()
    }

    private fun saveCreatedGpx(requestedName: String) {
        val points = createGpxRoutePoints.toList()
        val safeName = requestedName.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "Nuova_traccia" }
        fileExecutor.execute {
            val result = runCatching {
                var file = File(gpxDirectory(), "$safeName.gpx")
                var counter = 2
                while (file.exists()) {
                    file = File(gpxDirectory(), "${safeName}_$counter.gpx")
                    counter++
                }
                file.writeText(buildEditedGpxXml(safeName, points, emptyList()))
                file
            }
            runOnUiThread {
                result.onSuccess { file ->
                    finishCreateGpxMode()
                    openGpxFile(file, zoomToRoute = true)
                    Toast.makeText(this, "Traccia creata: ${file.name}", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    Toast.makeText(this, "Salvataggio non riuscito: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun finishCreateGpxMode() {
        binding.gpxCreateToolbar.visibility = View.GONE
        binding.gpxEditPointer.visibility = View.GONE
        createGpxControlPoints.clear()
        createGpxRoutePoints = emptyList()
        removeCreatedGpxDraft()
    }

    private fun showGpxEditorStart() {
        if (activeRoute == null || activeRouteFile == null) {
            Toast.makeText(this, "Apri prima un percorso GPX", Toast.LENGTH_SHORT).show()
            return
        }
        if (isNavigationActive) {
            Toast.makeText(this, "Ferma la navigazione prima di modificare il GPX", Toast.LENGTH_LONG).show()
            return
        }
        finishCreateGpxMode()
        binding.gpxEditToolbar.visibility = View.VISIBLE
        setFollowGps(false)
        Toast.makeText(this, "Scegli un comando dal pannello a sinistra", Toast.LENGTH_LONG).show()
    }

    private fun beginGpxPointSelection(action: String, message: String) {
        pendingGpxEditAction = action
        setGpxEditSelectionMode(true)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun selectGpxEditPoint(latLng: LatLng) {
        if (!isGpxEditSelectionMode) return
        val route = activeRoute ?: return
        val selectedIndex = route.points.indices.minByOrNull { index ->
            val point = route.points[index]
            distanceMeters(latLng.latitude, latLng.longitude, point.latitude, point.longitude)
        } ?: return
        val selectedPoint = route.points[selectedIndex]
        showGpxEditSelectionMarker(selectedPoint)
        when (pendingGpxEditAction) {
            EDIT_ACTION_CUT -> showGpxCutChoice(route, selectedIndex)
            EDIT_ACTION_ADD_START -> {
                editSegmentStartIndex = selectedIndex
                beginGpxPointSelection(
                    EDIT_ACTION_ADD_END,
                    "Ora sposta il mirino sul punto finale del tratto da sostituire"
                )
            }
            EDIT_ACTION_ADD_END -> {
                val startIndex = editSegmentStartIndex
                if (startIndex == null || startIndex == selectedIndex) {
                    Toast.makeText(this, "Scegli un punto finale diverso", Toast.LENGTH_LONG).show()
                    return
                }
                editSegmentStartIndex = minOf(startIndex, selectedIndex)
                editSegmentEndIndex = maxOf(startIndex, selectedIndex)
                pendingGpxEditAction = EDIT_ACTION_ADD_VIA
                setGpxEditSelectionMode(true)
                Toast.makeText(
                    this,
                    "Sposta il mirino sulla strada attraverso cui deve passare il nuovo tratto",
                    Toast.LENGTH_LONG
                ).show()
            }
            EDIT_ACTION_MOVE_SELECT -> {
                editMovePointIndex = selectedIndex
                pendingGpxEditAction = EDIT_ACTION_MOVE_TARGET
                setGpxEditSelectionMode(true)
                Toast.makeText(
                    this,
                    "Ora sposta il mirino nella nuova posizione del punto",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun moveSelectedGpxPoint(target: LatLng) {
        val route = activeRoute ?: return
        val index = editMovePointIndex ?: return
        val movedPoint = GpxPoint(target.latitude, target.longitude, route.points[index].elevation)
        setGpxEditSelectionMode(false)

        if (index == 0 || index == route.points.lastIndex) {
            val points = route.points.toMutableList().apply { this[index] = movedPoint }
            saveEditedGpx(points, route.waypoints, "punto_spostato")
            return
        }

        Toast.makeText(this, "Ricalcolo del tratto intorno al punto…", Toast.LENGTH_LONG).show()
        bRouterClient.calculateSegment(
            route.points[index - 1],
            movedPoint,
            route.points[index + 1]
        ) { result ->
            result.onSuccess { replacement ->
                val mergedPoints = buildList {
                    addAll(route.points.take(index))
                    addAll(replacement.points.drop(1).dropLast(1))
                    addAll(route.points.drop(index + 1))
                }
                saveEditedGpx(mergedPoints, route.waypoints, "punto_spostato")
            }.onFailure { error ->
                Toast.makeText(
                    this,
                    "Impossibile spostare il punto: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showGpxCutChoice(route: GpxRoute, selectedIndex: Int) {
        setGpxEditSelectionMode(false)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Quale parte vuoi mantenere?")
            .setItems(arrayOf("Dall'inizio fino alla forbice", "Dalla forbice fino alla fine")) { _, which ->
                val points = if (which == 0) {
                    route.points.take(selectedIndex + 1)
                } else {
                    route.points.drop(selectedIndex)
                }
                saveTrimmedGpx(points)
            }
            .setNegativeButton("Annulla") { _, _ -> removeGpxEditSelectionMarker() }
            .create()
        dialog.setOnShowListener { styleBlueDialog(dialog) }
        dialog.show()
    }

    private fun saveTrimmedGpx(points: List<GpxPoint>) {
        val route = activeRoute ?: return
        if (points.size < 2) {
            Toast.makeText(this, "Il taglio lascerebbe una traccia vuota", Toast.LENGTH_LONG).show()
            return
        }
        val waypoints = route.waypoints.filter { waypoint ->
            points.any { point ->
                distanceMeters(
                    waypoint.latitude,
                    waypoint.longitude,
                    point.latitude,
                    point.longitude
                ) <= EDIT_WAYPOINT_KEEP_DISTANCE_METERS
            }
        }
        saveEditedGpx(points, waypoints, "modificato")
    }

    private fun createAddedGpxSegment(via: LatLng) {
        val route = activeRoute ?: return
        val startIndex = editSegmentStartIndex ?: return
        val endIndex = editSegmentEndIndex ?: return
        setGpxEditSelectionMode(false)
        Toast.makeText(this, "Calcolo del nuovo tratto…", Toast.LENGTH_LONG).show()
        bRouterClient.calculateSegment(
            route.points[startIndex],
            GpxPoint(via.latitude, via.longitude),
            route.points[endIndex]
        ) { result ->
            result.onSuccess { newSegment ->
                val mergedPoints = buildList {
                    addAll(route.points.take(startIndex + 1))
                    addAll(newSegment.points.drop(1).dropLast(1))
                    addAll(route.points.drop(endIndex))
                }
                saveEditedGpx(mergedPoints, route.waypoints, "tratto_aggiunto")
            }.onFailure { error ->
                Toast.makeText(
                    this,
                    "Impossibile creare il tratto: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showGpxEditSelectionMarker(point: GpxPoint) {
        val style = mapLibreMap?.style ?: return
        removeGpxEditSelectionMarker()
        style.addSource(
            GeoJsonSource(
                GPX_EDIT_SELECTION_SOURCE_ID,
                Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))
            )
        )
        style.addLayer(
            CircleLayer(GPX_EDIT_SELECTION_LAYER_ID, GPX_EDIT_SELECTION_SOURCE_ID).withProperties(
                circleRadius(13f),
                circleColor("#FFD21F"),
                circleStrokeColor("#082F5B"),
                circleStrokeWidth(4f)
            )
        )
    }

    private fun removeGpxEditSelectionMarker() {
        val style = mapLibreMap?.style ?: return
        if (style.getLayer(GPX_EDIT_SELECTION_LAYER_ID) != null) {
            style.removeLayer(GPX_EDIT_SELECTION_LAYER_ID)
        }
        if (style.getSource(GPX_EDIT_SELECTION_SOURCE_ID) != null) {
            style.removeSource(GPX_EDIT_SELECTION_SOURCE_ID)
        }
    }

    private fun setGpxEditSelectionMode(enabled: Boolean) {
        isGpxEditSelectionMode = enabled
        binding.gpxEditPointer.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.confirmEditPointButton.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.cancelEditPointButton.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) {
            setFollowGps(false)
        }
    }

    private fun showMergeRouteDialog() {
        val firstFile = activeRouteFile ?: return
        val firstRoute = activeRoute ?: return

        val candidates = gpxDirectory()
            .listFiles { file -> file.extension.equals("gpx", true) }
            ?.filter { file ->
                runCatching { file.canonicalPath != firstFile.canonicalPath }
                    .getOrDefault(file.absolutePath != firstFile.absolutePath)
            }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

        if (candidates.isEmpty()) {
            Toast.makeText(
                this,
                "Non ci sono altre tracce GPX da unire",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val names = candidates.map { it.nameWithoutExtension }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Unisci con seconda traccia")
            .setItems(names) { _, which ->
                val secondFile = candidates[which]
                loadSecondRouteForMerge(firstRoute, secondFile)
            }
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener { styleBlueDialog(dialog) }
        dialog.show()
    }

    private fun loadSecondRouteForMerge(
        firstRoute: GpxRoute,
        secondFile: File
    ) {
        showStatus("Analisi seconda traccia…", false)

        fileExecutor.execute {
            val result = runCatching {
                val secondRoute = secondFile.inputStream().buffered().use { input ->
                    GpxParser.parse(input, secondFile.name)
                }
                val merge = GpxTrackEditor.merge(firstRoute.points, secondRoute.points)
                Triple(secondRoute, merge, secondFile)
            }

            runOnUiThread {
                binding.statusPanel.visibility = View.GONE

                result.onSuccess { (secondRoute, merge, file) ->
                    val connectionText = if (merge.connectionMeters < 1000.0) {
                        "${merge.connectionMeters.toInt()} m"
                    } else {
                        String.format(
                            java.util.Locale.ITALY,
                            "%.1f km",
                            merge.connectionMeters / 1000.0
                        )
                    }

                    val directionText = if (merge.secondReversed) {
                        "La seconda traccia verrà invertita automaticamente."
                    } else {
                        "La seconda traccia mantiene il suo verso."
                    }

                    val warning = if (merge.connectionMeters > MERGE_CONNECTION_WARNING_METERS) {
                        "\n\nAttenzione: tra le due tracce c'è un collegamento di $connectionText."
                    } else {
                        ""
                    }

                    val confirm = AlertDialog.Builder(this)
                        .setTitle("Unire le tracce?")
                        .setMessage(
                            "Prima: ${firstRoute.name}\n" +
                                "Seconda: ${secondRoute.name}\n\n" +
                                "$directionText\n" +
                                "Distanza collegamento: $connectionText$warning"
                        )
                        .setNegativeButton("Annulla", null)
                        .setPositiveButton("Unisci") { _, _ ->
                            val mergedWaypoints = (
                                firstRoute.waypoints + secondRoute.waypoints
                            ).distinctBy { waypoint ->
                                "${waypoint.latitude}:${waypoint.longitude}:${waypoint.name}"
                            }
                            saveEditedGpx(
                                merge.points,
                                mergedWaypoints,
                                "unita_${safeSuffix(file.nameWithoutExtension)}"
                            )
                        }
                        .create()
                    confirm.setOnShowListener { styleBlueDialog(confirm) }
                    confirm.show()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        "Impossibile leggere la seconda traccia: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showRepeatRouteDialog() {
        val route = activeRoute ?: return
        if (route.points.size < 2) return

        val picker = NumberPicker(this).apply {
            minValue = 2
            maxValue = 30
            value = 6
            wrapSelectorWheel = false
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (24 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt(),
                0
            )
            addView(TextView(this@MainActivity).apply {
                text = "Numero totale di giri"
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(0, 0, 0, 8)
            })
            addView(picker)
        }

        val closureGap = GpxTrackEditor.closureGapMeters(route.points)
        val message = if (closureGap > REPEAT_CLOSURE_WARNING_METERS) {
            val gapText = if (closureGap < 1000.0) {
                "${closureGap.toInt()} m"
            } else {
                String.format(
                    java.util.Locale.ITALY,
                    "%.1f km",
                    closureGap / 1000.0
                )
            }
            "La traccia non è perfettamente chiusa: tra fine e inizio ci sono $gapText. " +
                "Ogni giro verrà comunque collegato al successivo."
        } else {
            "La traccia è adatta alla ripetizione in più giri."
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Ripeti traccia")
            .setMessage(message)
            .setView(container)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Crea") { _, _ ->
                val laps = picker.value
                val repeatedPoints = GpxTrackEditor.repeat(route.points, laps)
                saveEditedGpx(
                    repeatedPoints,
                    route.waypoints,
                    "${laps}_giri"
                )
            }
            .create()
        dialog.setOnShowListener { styleBlueDialog(dialog) }
        dialog.show()
    }

    private fun safeSuffix(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "traccia" }

    private fun finishGpxEditMode() {
        pendingGpxEditAction = null
        editSegmentStartIndex = null
        editSegmentEndIndex = null
        editMovePointIndex = null
        setGpxEditSelectionMode(false)
        removeGpxEditSelectionMarker()
        binding.gpxEditToolbar.visibility = View.GONE
    }

    private fun saveEditedGpx(
        points: List<GpxPoint>,
        waypoints: List<GpxWaypoint>,
        suffix: String
    ) {
        val sourceFile = activeRouteFile ?: return
        val routeName = activeRoute?.name.orEmpty().ifBlank { sourceFile.nameWithoutExtension }
        fileExecutor.execute {
            val result = runCatching {
                val safeBaseName = sourceFile.nameWithoutExtension
                    .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                    .trim('_')
                    .ifBlank { "percorso" }
                var outputFile = File(gpxDirectory(), "${safeBaseName}_$suffix.gpx")
                var counter = 2
                while (outputFile.exists()) {
                    outputFile = File(gpxDirectory(), "${safeBaseName}_${suffix}_$counter.gpx")
                    counter++
                }
                outputFile.writeText(buildEditedGpxXml("$routeName $suffix", points, waypoints))
                outputFile
            }
            runOnUiThread {
                finishGpxEditMode()
                result.onSuccess { file ->
                    openGpxFile(file, zoomToRoute = true)
                    Toast.makeText(this, "Nuova copia salvata: ${file.name}", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        "Impossibile salvare il GPX: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun buildEditedGpxXml(
        name: String,
        points: List<GpxPoint>,
        waypoints: List<GpxWaypoint>
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"GpxNav Pro\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        waypoints.forEach { waypoint ->
            append("  <wpt lat=\"").append(waypoint.latitude).append("\" lon=\"")
                .append(waypoint.longitude).append("\">\n")
            append("    <name>").append(xmlEscape(waypoint.name)).append("</name>\n")
            waypoint.description?.let {
                append("    <desc>").append(xmlEscape(it)).append("</desc>\n")
            }
            waypoint.symbol?.let {
                append("    <sym>").append(xmlEscape(it)).append("</sym>\n")
            }
            waypoint.type?.let {
                append("    <type>").append(xmlEscape(it)).append("</type>\n")
            }
            append("  </wpt>\n")
        }
        append("  <trk>\n    <name>").append(xmlEscape(name)).append("</name>\n    <trkseg>\n")
        points.forEach { point ->
            append("      <trkpt lat=\"").append(point.latitude).append("\" lon=\"")
                .append(point.longitude).append("\">")
            point.elevation?.let { append("<ele>").append(it).append("</ele>") }
            append("</trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun populateDrawerRoutes() {
        val container = binding.drawerRouteList
        container.removeAllViews()
        val files = gpxDirectory().listFiles { file -> file.extension.equals("gpx", true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
            .take(4)

        if (files.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Nessun percorso importato"
                setTextColor(android.graphics.Color.parseColor("#D9ECFF"))
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(8, 14, 8, 14)
                background = createMenuRowBackground()
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            return
        }

        files.forEach { file ->
            val row = TextView(this).apply {
                text = file.nameWithoutExtension
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = createMenuRowBackground()
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_route_profile,
                    0,
                    R.drawable.ic_chevron_right,
                    0
                )
                compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()
                setPadding(
                    (12 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt()
                )
                setOnClickListener {
                    showDrawerRouteActions(file)
                }
                setOnLongClickListener {
                    closeDrawer()
                    showRouteAppearanceSettings(file)
                    true
                }
            }

            container.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (5 * resources.displayMetrics.density).toInt()
            })
        }
    }

    private fun showDrawerRouteActions(file: File) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(file.nameWithoutExtension)
            .setItems(
                arrayOf(
                    "Apri traccia",
                    "Info traccia",
                    "Simula giro",
                    "Modifica traccia",
                    "Rinomina",
                    "Salva",
                    "Condividi",
                    "Elimina traccia"
                )
            ) { _, which ->
                closeDrawer()
                when (which) {
                    0 -> openGpxFile(file, zoomToRoute = true)
                    1 -> showRouteInfo(file)
                    2 -> openGpxFile(file, zoomToRoute = true) { startSimulation() }
                    3 -> openGpxFile(file, zoomToRoute = true) { showGpxEditorStart() }
                    4 -> showRenameRouteDialog(file)
                    5 -> {
                        pendingExportFile = file
                        exportGpxLauncher.launch(file.name)
                    }
                    6 -> shareGpx(file)
                    7 -> confirmDeleteDrawerRoute(file)
                }
            }
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener { styleBlueDialog(dialog) }
        dialog.show()
    }

    private fun showRenameRouteDialog(file: File) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setSelectAllOnFocus(true)
            setText(file.nameWithoutExtension)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rinomina traccia")
            .setMessage("Inserisci il nuovo nome della traccia.")
            .setView(input)
            .setPositiveButton("Rinomina", null)
            .setNegativeButton("Annulla", null)
            .create()

        dialog.setOnShowListener {
            styleBlueDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val requested = input.text.toString().trim()
                if (requested.isBlank()) {
                    input.error = "Inserisci un nome"
                    return@setOnClickListener
                }

                val safeName = safeSuffix(
                    requested.removeSuffix(".gpx").removeSuffix(".GPX")
                )
                val target = File(file.parentFile, "$safeName.gpx")

                if (target.absolutePath.equals(file.absolutePath, ignoreCase = true)) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                if (target.exists()) {
                    input.error = "Esiste già una traccia con questo nome"
                    return@setOnClickListener
                }

                val oldPath = file.absolutePath
                if (!file.renameTo(target)) {
                    Toast.makeText(this, "Impossibile rinominare la traccia", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                if (activeRouteFile?.absolutePath == oldPath) {
                    activeRouteFile = target
                    binding.routeNameText.text = target.nameWithoutExtension
                    getPreferences(Context.MODE_PRIVATE)
                        .edit()
                        .putString(PREF_ACTIVE_GPX, target.absolutePath)
                        .apply()
                }

                val navPrefs = getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
                if (navPrefs.getString(PREF_NAVIGATION_ROUTE_PATH, null) == oldPath) {
                    navPrefs.edit()
                        .putString(PREF_NAVIGATION_ROUTE_PATH, target.absolutePath)
                        .apply()
                }

                populateDrawerRoutes()
                Toast.makeText(this, "Traccia rinominata", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            input.requestFocus()
        }
        dialog.show()
    }

    private fun handleGpxRouteMapTap(map: MapLibreMap, latLng: LatLng): Boolean {
        if (
            isGpxEditSelectionMode ||
            binding.gpxEditToolbar.visibility == View.VISIBLE ||
            binding.gpxCreateToolbar.visibility == View.VISIBLE
        ) return false
        val file = activeRouteFile ?: return false
        val screenPoint = map.projection.toScreenLocation(latLng)
        val touchRadius = 18f * resources.displayMetrics.density
        val hitArea = android.graphics.RectF(
            screenPoint.x - touchRadius,
            screenPoint.y - touchRadius,
            screenPoint.x + touchRadius,
            screenPoint.y + touchRadius
        )
        val hit = map.queryRenderedFeatures(hitArea, GPX_ROUTE_LAYER_ID).isNotEmpty()
        if (!hit) return false

        val dialog = AlertDialog.Builder(this)
            .setTitle(file.nameWithoutExtension)
            .setItems(arrayOf("Info traccia", "Simula giro", "Modifica traccia", "Elimina traccia")) { _, which ->
                when (which) {
                    0 -> showRouteInfo(file)
                    1 -> startSimulation()
                    2 -> showGpxEditorStart()
                    3 -> confirmDeleteDrawerRoute(file)
                }
            }
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener { styleBlueDialog(dialog) }
        dialog.show()
        return true
    }

    private fun showGpxExportActions(file: File) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(file.nameWithoutExtension)
            .setItems(arrayOf("Salva una copia", "Condividi")) { _, which ->
                if (which == 0) {
                    pendingExportFile = file
                    exportGpxLauncher.launch(file.name)
                } else {
                    shareGpx(file)
                }
            }
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener { styleBlueDialog(dialog) }
        dialog.show()
    }

    private fun shareGpx(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Condividi GPX"))
        }.onFailure {
            Toast.makeText(this, "Impossibile condividere il GPX", Toast.LENGTH_LONG).show()
        }
    }

    private fun showOfflineMapActions() {
        val mapFile = installedMapFile()
        val selectedMode = getSharedPreferences(PREFS_MAP, Context.MODE_PRIVATE)
            .getString(PREF_MAP_MODE, MAP_MODE_ONLINE)
        val modeText = if (selectedMode == MAP_MODE_OFFLINE && mapFile.exists()) {
            "Mappa offline"
        } else {
            "Mappa online"
        }
        val offlineText = if (mapFile.exists()) {
            String.format(
                java.util.Locale.ITALY,
                "%.1f MB installati",
                mapFile.length() / (1024.0 * 1024.0)
            )
        } else {
            "non installata"
        }
        val actions = if (mapFile.exists()) {
            arrayOf(
                "Usa mappa online",
                "Usa mappa offline",
                "Importa o sostituisci",
                "Salva una copia",
                "Condividi"
            )
        } else {
            arrayOf("Usa mappa online", "Importa mappa offline")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Mappe")
            .setMessage("Modalità attuale: $modeText\nMappa offline: $offlineText")
            .setItems(actions) { _, which ->
                if (mapFile.exists()) {
                    when (which) {
                        0 -> {
                            saveMapMode(MAP_MODE_ONLINE)
                            loadInstalledMap()
                        }
                        1 -> {
                            saveMapMode(MAP_MODE_OFFLINE)
                            loadInstalledMap()
                        }
                        2 -> mapPicker.launch(arrayOf("application/octet-stream", "*/*"))
                        3 -> {
                            pendingMapExportFile = mapFile
                            exportMapLauncher.launch(mapFile.name)
                        }
                        4 -> shareOfflineMap(mapFile)
                    }
                } else {
                    when (which) {
                        0 -> {
                            saveMapMode(MAP_MODE_ONLINE)
                            loadInstalledMap()
                        }
                        1 -> mapPicker.launch(arrayOf("application/octet-stream", "*/*"))
                    }
                }
            }
            .setNegativeButton("Chiudi", null)
            .create()
        dialog.setOnShowListener { styleBlueDialog(dialog) }
        dialog.show()
    }

    private fun saveMapMode(mode: String) {
        getSharedPreferences(PREFS_MAP, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_MAP_MODE, mode)
            .apply()
    }

    private fun shareOfflineMap(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Mappa offline ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Condividi mappa offline"))
        }.onFailure {
            Toast.makeText(this, "Impossibile condividere la mappa", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDeleteDrawerRoute(file: File) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Eliminare percorso?")
            .setMessage(file.name)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Elimina") { _, _ ->
                if (deleteGpxRoute(file)) {
                    populateDrawerRoutes()
                    Toast.makeText(this, "Percorso eliminato", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Impossibile eliminare il percorso", Toast.LENGTH_SHORT).show()
                }
            }
            .create()
        dialog.setOnShowListener {
            styleBlueDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(android.graphics.Color.parseColor("#FF8A80"))
        }
        dialog.show()
    }

    private fun deleteGpxRoute(file: File): Boolean {
        val activeFile = activeRouteFile
        val isActive = activeFile != null && runCatching {
            activeFile.canonicalPath == file.canonicalPath
        }.getOrDefault(activeFile.absolutePath == file.absolutePath)

        if (isActive) {
            if (isNavigationActive) stopNavigation()
            finishGpxEditMode()
            finishCreateGpxMode()
            mapLibreMap?.style?.let(::removeRouteLayers)
            activeRoute = null
            activeRouteFile = null
            currentRouteProgressMeters = 0.0
            tripResetOffsetMeters = 0.0
            binding.routeNameText.text = "Nessun percorso"
        }

        val storedPath = getPreferences(Context.MODE_PRIVATE)
            .getString(PREF_ACTIVE_GPX, null)
        if (storedPath == file.absolutePath || isActive) {
            getPreferences(Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_ACTIVE_GPX)
                .apply()
        }

        return !file.exists() || runCatching { file.delete() }.getOrDefault(false)
    }

    private fun warnIfRouteOutsideOfflineMap(route: GpxRoute) {
        val mapFile = installedMapFile()
        if (!mapFile.exists()) return

        val bounds = runCatching { PmtilesHeaderReader.read(mapFile).bounds }
            .getOrNull() ?: return
        val outside = route.points.any { point -> !bounds.contains(point) }
        if (!outside) return

        AlertDialog.Builder(this)
            .setTitle("Mappa offline incompleta")
            .setMessage(
                "Una parte del percorso GPX è fuori dai bounds dichiarati dalla mappa PMTiles installata. " +
                    "Durante la navigazione la mappa potrebbe risultare vuota in quella zona."
            )
            .setPositiveButton("Continua", null)
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun showRoutesDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 18, 28, 18)
        }
        val scroll = ScrollView(this).apply { addView(container) }
        val files = gpxDirectory().listFiles { file -> file.extension.equals("gpx", true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        if (files.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Nessun percorso importato"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 17f
                setPadding(8, 22, 8, 22)
            })
        } else {
            files.forEach { file ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(8, 12, 8, 12)
                }
                row.addView(TextView(this).apply {
                    text = file.name
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                row.addView(TextView(this).apply {
                    text = "${file.length() / 1024} KB"
                    setTextColor(android.graphics.Color.parseColor("#D7E8FF"))
                    textSize = 13f
                })
                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val openButton = Button(this).apply {
                    text = "Apri"
                    styleMenuButton(this)
                    setOnClickListener {
                        openGpxFile(file, zoomToRoute = true)
                        routesDialog?.dismiss()
                    }
                }
                val settingsButton = Button(this).apply {
                    text = "Impostazioni"
                    styleMenuButton(this)
                    setOnClickListener {
                        routesDialog?.dismiss()
                        showRouteAppearanceSettings(file)
                    }
                }
                val infoButton = Button(this).apply {
                    text = "Info"
                    styleMenuButton(this)
                    setOnClickListener {
                        routesDialog?.dismiss()
                        showRouteInfo(file)
                    }
                }
                val deleteButton = Button(this).apply {
                    text = "Elimina"
                    styleMenuButton(this, destructive = true)
                    setOnClickListener {
                        val deleteDialog = AlertDialog.Builder(this@MainActivity)
                            .setTitle("Eliminare percorso?")
                            .setMessage(file.name)
                            .setNegativeButton("Annulla", null)
                            .setPositiveButton("Elimina") { _, _ ->
                                if (file.delete()) {
                                    if (activeGpxFile()?.absolutePath == file.absolutePath) {
                                        getPreferences(Context.MODE_PRIVATE).edit().remove(PREF_ACTIVE_GPX).apply()
                                    }
                                    routesDialog?.dismiss()
                                    showRoutesDialog()
                                }
                            }
                            .create()
                        deleteDialog.setOnShowListener {
                            styleBlueDialog(deleteDialog)
                            deleteDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                .setTextColor(android.graphics.Color.parseColor("#FF8A80"))
                        }
                        deleteDialog.show()
                    }
                }
                actions.addView(openButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f))
                actions.addView(infoButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f))
                actions.addView(settingsButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f))
                actions.addView(deleteButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(actions)
                container.addView(row)
                container.addView(View(this).apply {
                    setBackgroundColor(0x22000000)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                })
            }
        }

        routesDialog = AlertDialog.Builder(this)
            .setTitle("Menu GPX NAV")
            .setView(scroll)
            .setPositiveButton("Importa GPX") { _, _ ->
                gpxPicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*"))
            }
            .setNeutralButton("Avvisi") { _, _ ->
                showAlertDistanceSettings()
            }
            .setNegativeButton("Chiudi", null)
            .create()
        routesDialog?.setOnShowListener { routesDialog?.let(::styleBlueDialog) }
        routesDialog?.show()
    }

    private fun showRouteInfo(file: File) {
        showStatus("Calcolo informazioni traccia…", false)

        fileExecutor.execute {
            val result = runCatching {
                file.inputStream().buffered().use { input ->
                    GpxParser.parse(input, file.name)
                }
            }

            runOnUiThread {
                result.onSuccess { route ->
                    binding.statusPanel.visibility = View.GONE
                    val points = route.points
                    val elevations = points.mapNotNull { it.elevation }

                    val message = if (elevations.size < 2) {
                        buildString {
                            append("Lunghezza: ")
                            append(String.format(java.util.Locale.ITALY, "%.1f km", route.distanceMeters / 1000.0))
                            append("\n\nDati altimetrici non disponibili in questo file GPX.")
                        }
                    } else {
                        var ascent = 0.0
                        var descent = 0.0
                        var uphillDistance = 0.0
                        val cumulative = DoubleArray(points.size)

                        for (index in 1 until points.size) {
                            val previous = points[index - 1]
                            val current = points[index]
                            val segmentDistance = distanceMeters(
                                previous.latitude, previous.longitude,
                                current.latitude, current.longitude
                            )
                            cumulative[index] = cumulative[index - 1] + segmentDistance
                            val e1 = previous.elevation
                            val e2 = current.elevation
                            if (e1 != null && e2 != null) {
                                val delta = e2 - e1
                                if (delta > 0.0) {
                                    ascent += delta
                                    uphillDistance += segmentDistance
                                } else {
                                    descent += -delta
                                }
                            }
                        }

                        var maxUphill: Double? = null
                        var maxDownhill: Double? = null
                        for (index in 1 until points.size) {
                            val midpoint = (cumulative[index - 1] + cumulative[index]) / 2.0
                            val windowStartDistance = midpoint - SLOPE_HALF_WINDOW_METERS
                            val windowEndDistance = midpoint + SLOPE_HALF_WINDOW_METERS
                            var startIndex = index - 1
                            while (startIndex > 0 && cumulative[startIndex] > windowStartDistance) startIndex--
                            var endIndex = index
                            while (endIndex < points.lastIndex && cumulative[endIndex] < windowEndDistance) endIndex++
                            val startElevation = points[startIndex].elevation
                            val endElevation = points[endIndex].elevation
                            val horizontalDistance = cumulative[endIndex] - cumulative[startIndex]
                            if (startElevation != null && endElevation != null && horizontalDistance >= MIN_SLOPE_SAMPLE_METERS) {
                                val grade = (endElevation - startElevation) / horizontalDistance * 100.0
                                if (grade > 0.0 && (maxUphill == null || grade > maxUphill!!)) maxUphill = grade
                                if (grade < 0.0 && (maxDownhill == null || grade < maxDownhill!!)) maxDownhill = grade
                            }
                        }

                        val averageUphill = if (uphillDistance >= 1.0) ascent / uphillDistance * 100.0 else 0.0
                        buildString {
                            append("Lunghezza: ")
                            append(String.format(java.util.Locale.ITALY, "%.1f km", route.distanceMeters / 1000.0))
                            append("\n\nDislivello +: ")
                            append(String.format(java.util.Locale.ITALY, "%.0f m", ascent))
                            append("\nDislivello -: ")
                            append(String.format(java.util.Locale.ITALY, "%.0f m", descent))
                            append("\n\nQuota minima: ")
                            append(String.format(java.util.Locale.ITALY, "%.0f m", elevations.minOrNull() ?: 0.0))
                            append("\nQuota massima: ")
                            append(String.format(java.util.Locale.ITALY, "%.0f m", elevations.maxOrNull() ?: 0.0))
                            append("\n\nPendenza media salita: ")
                            append(String.format(java.util.Locale.ITALY, "%.1f%%", averageUphill))
                            append("\nPendenza max salita: ")
                            append(maxUphill?.let { String.format(java.util.Locale.ITALY, "%.1f%%", it) } ?: "--")
                            append("\nPendenza max discesa: ")
                            append(maxDownhill?.let { String.format(java.util.Locale.ITALY, "%.1f%%", it) } ?: "--")
                        }
                    }

                    val dialog = AlertDialog.Builder(this)
                        .setTitle("Info traccia — ${route.name}")
                        .setMessage(message)
                        .setPositiveButton("Chiudi", null)
                        .create()
                    dialog.setOnShowListener { styleBlueDialog(dialog) }
                    dialog.show()
                }.onFailure { error ->
                    showStatus(
                        "Impossibile leggere le informazioni:\n${error.message ?: "Errore sconosciuto"}",
                        false
                    )
                }
            }
        }
    }

    private fun importGpx(uri: Uri) {
        showStatus(
            message = "Importazione GPX in background…",
            showButton = false
        )
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        ImportService.importGpx(this, uri)
    }

    private fun loadInstalledGpx(style: Style) {
        val file = activeGpxFile() ?: return
        openGpxFile(file, zoomToRoute = true, styleOverride = style)
    }

    private fun openGpxFile(
        file: File,
        zoomToRoute: Boolean,
        styleOverride: Style? = null,
        onLoaded: (() -> Unit)? = null
    ) {
        if (!file.exists() || file.length() == 0L) return
        fileExecutor.execute {
            val result = runCatching {
                file.inputStream().buffered().use { input -> GpxParser.parse(input, file.name) }
            }
            runOnUiThread {
                result.onSuccess { route ->
                    getPreferences(Context.MODE_PRIVATE).edit()
                        .putString(PREF_ACTIVE_GPX, file.absolutePath)
                        .apply()
                    activeRouteFile = file
                    activeRoute = route
                    navigationEngine.setRoute(route)
                    routeAlertEngine.setRoute(route, loadManualAlertKilometers(route))
                    prepareNavigationForRoute()
                    restoreNavigationSessionIfNeeded(file)
                    val style = styleOverride ?: mapLibreMap?.style
                    if (style != null) drawRoute(style, route, zoomToRoute, file)
                    updateRouteHeader(route)
                    Toast.makeText(this, "Percorso caricato: ${route.name}", Toast.LENGTH_SHORT).show()
                    warnIfRouteOutsideOfflineMap(route)
                    onLoaded?.invoke()
                }.onFailure { error ->
                    val storedPath = getPreferences(Context.MODE_PRIVATE)
                        .getString(PREF_ACTIVE_GPX, null)
                    if (storedPath == file.absolutePath) {
                        getPreferences(Context.MODE_PRIVATE)
                            .edit()
                            .remove(PREF_ACTIVE_GPX)
                            .apply()
                    }
                    showStatus("Impossibile aprire GPX:\n${error.message ?: "Errore sconosciuto"}", false)
                }
            }
        }
    }

    private fun restoreNavigationSessionIfNeeded(file: File) {
        val preferences = getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
        val shouldRestore = preferences.getBoolean(PREF_NAVIGATION_ACTIVE, false) &&
            preferences.getString(PREF_NAVIGATION_ROUTE_PATH, null) == file.absolutePath
        if (!shouldRestore) return

        isNavigationActive = true
        applyKeepScreenOnPreference()
        binding.startNavigationButton.text = "STOP"

        val authoritativeProgress = NavigationLocationService.lastNavigationFix?.progressMeters
            ?: preferences.getFloat(PREF_NAVIGATION_PROGRESS_METERS, 0f).toDouble()
        currentRouteProgressMeters = authoritativeProgress
        updateDistancePanels()

        if (!NavigationLocationService.isRunning) {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                NavigationLocationService.start(this)
            }
        }

        NavigationLocationService.freshLastLocation()?.let { onLocationChanged(it) }
    }

    private fun restoreNavigationServiceIfNeeded() {
        val file = activeRouteFile ?: return
        val preferences = getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
        val shouldRestore = preferences.getBoolean(PREF_NAVIGATION_ACTIVE, false) &&
            preferences.getString(PREF_NAVIGATION_ROUTE_PATH, null) == file.absolutePath
        if (shouldRestore && !NavigationLocationService.isRunning) {
            NavigationLocationService.start(this)
        }
    }

    private fun drawRoute(style: Style, route: GpxRoute, zoomToRoute: Boolean, file: File? = activeRouteFile) {
        removeRouteLayers(style)
        val appearance = file?.let { loadRouteAppearance(it) } ?: RouteAppearance()

        val coloredRouteFeatures = if (appearance.useSlopeColors) {
            buildSlopeColoredRouteFeatures(route, appearance.color)
        } else {
            buildUniformRouteFeatures(route, appearance.color)
        }

        style.addSource(
            GeoJsonSource(
                GPX_ROUTE_SOURCE_ID,
                org.maplibre.geojson.FeatureCollection.fromFeatures(coloredRouteFeatures)
            )
        )

        style.addLayer(
            LineLayer(GPX_ROUTE_LAYER_ID, GPX_ROUTE_SOURCE_ID).withProperties(
                lineColor(get(SLOPE_COLOR_PROPERTY)),
                lineWidth(appearance.width),
                lineOpacity(0.95f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )
        )

        if (appearance.showDirectionArrows) {
            installRouteArrowImage(style)
            val arrowFeatures = buildDirectionArrowFeatures(route, appearance.arrowSpacingMeters)
            if (arrowFeatures.isNotEmpty()) {
                style.addSource(GeoJsonSource(GPX_ARROWS_SOURCE_ID, org.maplibre.geojson.FeatureCollection.fromFeatures(arrowFeatures)))
                style.addLayer(
                    SymbolLayer(GPX_ARROWS_LAYER_ID, GPX_ARROWS_SOURCE_ID).withProperties(
                        iconImage(GPX_ARROW_IMAGE_ID),
                        iconSize(0.85f),
                        iconAllowOverlap(true),
                        iconIgnorePlacement(true),
                        iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                        iconRotate(get("bearing"))
                    )
                )
            }
        }

        val start = route.points.first()
        val finish = route.points.last()

        style.addSource(
            GeoJsonSource(
                GPX_START_SOURCE_ID,
                Feature.fromGeometry(Point.fromLngLat(start.longitude, start.latitude))
            )
        )
        style.addLayer(
            CircleLayer(GPX_START_LAYER_ID, GPX_START_SOURCE_ID).withProperties(
                circleRadius(8f),
                circleColor("#16A34A"),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(3f)
            )
        )

        style.addSource(
            GeoJsonSource(
                GPX_FINISH_SOURCE_ID,
                Feature.fromGeometry(Point.fromLngLat(finish.longitude, finish.latitude))
            )
        )
        installFinishFlagImage(style)
        style.addLayer(
            SymbolLayer(GPX_FINISH_LAYER_ID, GPX_FINISH_SOURCE_ID).withProperties(
                iconImage(GPX_FINISH_IMAGE_ID),
                iconSize(1.0f),
                iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT)
            )
        )
        drawRouteAlertMarkers(style, route)
        drawKilometerMarkers(style, route)

        if (isSimulationActive) {
            installSimulationMarker()
            updateSimulationMarker(simulationProgressMeters)
        }

        // Il simbolo GPS deve restare sopra la traccia.
        style.getLayer(GPS_LAYER_ID)?.let { style.removeLayer(GPS_LAYER_ID) }
        if (style.getSource(GPS_SOURCE_ID) != null) {
            style.addLayer(
                SymbolLayer(GPS_LAYER_ID, GPS_SOURCE_ID).withProperties(
                    iconImage(GPS_IMAGE_ID),
                    iconSize(1.0f),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    iconRotate(lastLocation?.bearing ?: 0f)
                )
            )
        }

        updateRouteHeader(route)
        if (zoomToRoute) zoomToRoute(route)
    }

    private fun buildUniformRouteFeatures(route: GpxRoute, color: String): List<Feature> {
        if (route.points.size < 2) return emptyList()
        val coordinates = route.points.map { point ->
            Point.fromLngLat(point.longitude, point.latitude)
        }
        return listOf(
            Feature.fromGeometry(LineString.fromLngLats(coordinates)).apply {
                addStringProperty(SLOPE_COLOR_PROPERTY, color)
            }
        )
    }

    private fun buildSlopeColoredRouteFeatures(
        route: GpxRoute,
        fallbackColor: String
    ): List<Feature> {
        if (route.points.size < 2) return emptyList()

        val cumulative = DoubleArray(route.points.size)
        for (index in 1 until route.points.size) {
            val previous = route.points[index - 1]
            val current = route.points[index]
            cumulative[index] = cumulative[index - 1] + distanceMeters(
                previous.latitude,
                previous.longitude,
                current.latitude,
                current.longitude
            )
        }

        val segmentColors = ArrayList<String>(route.points.size - 1)
        for (index in 1 until route.points.size) {
            val midpoint = (cumulative[index - 1] + cumulative[index]) / 2.0
            val windowStartDistance = midpoint - SLOPE_HALF_WINDOW_METERS
            val windowEndDistance = midpoint + SLOPE_HALF_WINDOW_METERS

            var startIndex = index - 1
            while (startIndex > 0 && cumulative[startIndex] > windowStartDistance) startIndex--
            var endIndex = index
            while (endIndex < route.points.lastIndex && cumulative[endIndex] < windowEndDistance) endIndex++

            val startElevation = route.points[startIndex].elevation
            val endElevation = route.points[endIndex].elevation
            val horizontalDistance = cumulative[endIndex] - cumulative[startIndex]
            val grade = if (
                startElevation != null &&
                endElevation != null &&
                horizontalDistance >= MIN_SLOPE_SAMPLE_METERS
            ) {
                (endElevation - startElevation) / horizontalDistance * 100.0
            } else {
                null
            }
            segmentColors += grade?.let(::slopeColor) ?: fallbackColor
        }

        val features = mutableListOf<Feature>()
        var groupStart = 0
        var groupColor = segmentColors.first()
        for (segmentIndex in 1..segmentColors.size) {
            val colorChanged = segmentIndex == segmentColors.size ||
                segmentColors[segmentIndex] != groupColor
            if (!colorChanged) continue

            val coordinates = (groupStart..segmentIndex).map { pointIndex ->
                val point = route.points[pointIndex]
                Point.fromLngLat(point.longitude, point.latitude)
            }
            features += Feature.fromGeometry(LineString.fromLngLats(coordinates)).apply {
                addStringProperty(SLOPE_COLOR_PROPERTY, groupColor)
            }
            if (segmentIndex < segmentColors.size) {
                groupStart = segmentIndex
                groupColor = segmentColors[segmentIndex]
            }
        }
        return features
    }

    private fun slopeColor(gradePercent: Double): String = when {
        gradePercent >= 7.0 -> "#E53935"   // salita forte: rosso
        gradePercent > 1.0 -> "#FDD835"    // salita: giallo
        gradePercent <= -7.0 -> "#1565C0"  // discesa forte: blu
        gradePercent < -1.0 -> "#4FC3F7"   // discesa: azzurro
        else -> "#43A047"                  // pianura: verde
    }

    private fun updateRouteHeader(route: GpxRoute) {
        binding.routeNameText.text =
            activeRouteFile?.nameWithoutExtension ?: route.name
        currentRouteProgressMeters = lastLocation
            ?.let { navigationEngine.match(it)?.progressMeters }
            ?: 0.0
        tripResetOffsetMeters = 0.0
        updateDistancePanels()
    }

    private fun updateDistancePanels() {
        val route = activeRoute
        if (route == null) {
            binding.startDistanceText.text = formatDistancePanel(
                String.format(java.util.Locale.ITALY, "%.1f", manualStartKilometers)
            )
            binding.finishDistanceText.text = formatDistancePanel("--,-")
            return
        }

        val routeProgressFromResetMeters =
            (currentRouteProgressMeters - tripResetOffsetMeters).coerceAtLeast(0.0)
        val displayedStartKilometers =
            manualStartKilometers + (routeProgressFromResetMeters / 1000.0)
        val remaining = (route.distanceMeters - currentRouteProgressMeters).coerceAtLeast(0.0)
        binding.startDistanceText.text = formatDistancePanel(
            String.format(java.util.Locale.ITALY, "%.1f", displayedStartKilometers)
        )
        binding.finishDistanceText.text = formatDistancePanel(
            String.format(java.util.Locale.ITALY, "%.1f", remaining / 1000.0)
        )
    }

    private fun showStartKilometersDialog() {
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Km iniziali"
            setSingleLine(true)
            setSelectAllOnFocus(true)
            val currentDisplayedKilometers = if (activeRoute == null) {
                manualStartKilometers
            } else {
                val routeProgressFromResetMeters =
                    (currentRouteProgressMeters - tripResetOffsetMeters).coerceAtLeast(0.0)
                manualStartKilometers + (routeProgressFromResetMeters / 1000.0)
            }
            setText(
                if (currentDisplayedKilometers == 0.0) ""
                else String.format(java.util.Locale.ITALY, "%.1f", currentDisplayedKilometers)
            )
            setPadding(
                (18 * density).toInt(),
                (10 * density).toInt(),
                (18 * density).toInt(),
                (10 * density).toInt()
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(),
                (8 * density).toInt(),
                (20 * density).toInt(),
                0
            )
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Km campo INIZIO")
            .setMessage("Inserisci i km da visualizzare nel campo INIZIO.")
            .setView(container)
            .setPositiveButton("Imposta", null)
            .setNeutralButton("Azzera", null)
            .setNegativeButton("Annulla", null)
            .create()

        dialog.setOnShowListener {
            styleBlueDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rawValue = input.text.toString().trim().replace(',', '.')
                val kilometers = rawValue.toDoubleOrNull()
                if (kilometers == null || kilometers < 0.0 || kilometers > 99999.9) {
                    input.error = "Inserisci un valore valido"
                    return@setOnClickListener
                }
                val routeProgressFromResetKilometers = if (activeRoute == null) {
                    0.0
                } else {
                    (currentRouteProgressMeters - tripResetOffsetMeters)
                        .coerceAtLeast(0.0) / 1000.0
                }
                manualStartKilometers = kilometers - routeProgressFromResetKilometers
                getSharedPreferences(PREFS_DISPLAY, Context.MODE_PRIVATE)
                    .edit()
                    .putFloat(PREF_MANUAL_START_KM, manualStartKilometers.toFloat())
                    .apply()
                updateDistancePanels()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                manualStartKilometers = 0.0
                getSharedPreferences(PREFS_DISPLAY, Context.MODE_PRIVATE)
                    .edit()
                    .putFloat(PREF_MANUAL_START_KM, 0f)
                    .apply()
                updateDistancePanels()
                dialog.dismiss()
            }
            input.requestFocus()
        }
        dialog.show()
    }

    private fun formatDistancePanel(number: String): SpannableString {
        val text = "$number\nKM"
        return SpannableString(text).apply {
            // Numero aumentato di altri 2 sp; la scritta KM resta invariata.
            setSpan(AbsoluteSizeSpan(21, true), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan(8, true), number.length + 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    private fun showAlertKilometerSettings() {
        val route = activeRoute
        if (route == null) {
            Toast.makeText(this, "Apri prima un percorso GPX", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 10, 28, 10)
        }
        container.addView(TextView(this).apply {
            text = "Aggiungi liberamente tutti gli avvisi presenti sul percorso."
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setPadding(12, 8, 12, 18)
        })

        val entriesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(entriesContainer)

        lateinit var refreshEntries: () -> Unit
        refreshEntries = {
            entriesContainer.removeAllViews()
            val entries = loadManualAlertEntries(route)
            if (entries.isEmpty()) {
                entriesContainer.addView(TextView(this).apply {
                    text = "Nessun avviso impostato"
                    setTextColor(android.graphics.Color.LTGRAY)
                    textSize = 15f
                    gravity = android.view.Gravity.CENTER
                    setPadding(12, 24, 12, 24)
                })
            } else {
                entries.forEachIndexed { index, entry ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(20, 12, 8, 12)
                        background = createMenuRowBackground()
                        isClickable = true
                    }
                    row.addView(TextView(this).apply {
                        text = entry.first.shortLabel
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 16f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    })
                    row.addView(TextView(this).apply {
                        text = String.format(java.util.Locale.ITALY, "KM %.1f", entry.second)
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 16f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                    row.addView(ImageButton(this).apply {
                        setImageResource(android.R.drawable.ic_menu_delete)
                        imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        contentDescription = "Elimina avviso"
                        setOnClickListener {
                            val updated = loadManualAlertEntries(route).toMutableList()
                            if (index in updated.indices) {
                                updated.removeAt(index)
                                saveManualAlertEntries(route, updated)
                                refreshEntries()
                            }
                        }
                    })
                    row.setOnClickListener {
                        showAlertKilometerEditor(route, entry, index, refreshEntries)
                    }
                    entriesContainer.addView(row)
                }
            }
        }

        container.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "+  Aggiungi avviso"
            setAllCaps(false)
            setTextColor(android.graphics.Color.rgb(23, 32, 42))
            backgroundTintList =
                ColorStateList.valueOf(android.graphics.Color.parseColor("#FFD21F"))
            setOnClickListener {
                val labels = MANUAL_ALERT_TYPES.map { it.shortLabel }.toTypedArray()
                val typeDialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("Tipo di avviso")
                    .setItems(labels) { _, which ->
                        showAlertKilometerEditor(
                            route,
                            MANUAL_ALERT_TYPES[which] to 0.0,
                            null,
                            refreshEntries
                        )
                    }
                    .setNegativeButton("Annulla", null)
                    .create()
                typeDialog.setOnShowListener { styleBlueDialog(typeDialog) }
                typeDialog.show()
            }
        })
        refreshEntries()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Km avvisi — ${route.name}")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Chiudi", null)
            .create()
        dialog.setOnShowListener { styleBlueDialog(dialog) }
        dialog.show()
    }

    private fun showAlertKilometerSettingsLegacy() {
        val route = activeRoute
        if (route == null) {
            Toast.makeText(this, "Apri prima un percorso GPX", Toast.LENGTH_SHORT).show()
            return
        }

        val preferences = getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 10, 28, 10)
        }

        val help = TextView(this).apply {
            text = "Tocca un evento e inserisci il chilometro dall'inizio. " +
                "Per più eventi usa il punto e virgola, ad esempio: 12,5; 38,2"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setPadding(12, 8, 12, 18)
        }
        container.addView(help)

        MANUAL_ALERT_TYPES.forEachIndexed { index, type ->
            val preferenceKey = manualAlertPreferenceKey(route, type)
            val savedValue = preferences.getString(preferenceKey, "").orEmpty()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(22, 18, 14, 18)
                isClickable = true
                isFocusable = true
                background = createMenuRowBackground()
            }
            val nameText = TextView(this).apply {
                text = type.shortLabel
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            val kilometerText = TextView(this).apply {
                text = savedValue.takeIf { it.isNotBlank() }?.let { "$it km   ›" } ?: "Non impostato   ›"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 15f
                gravity = android.view.Gravity.END
            }
            row.addView(nameText)
            row.addView(kilometerText)

            row.setOnClickListener {
                val input = android.widget.EditText(this).apply {
                    setText(preferences.getString(preferenceKey, "").orEmpty())
                    hint = "Esempio: 12,5; 38,2"
                    setTextColor(android.graphics.Color.WHITE)
                    setHintTextColor(android.graphics.Color.LTGRAY)
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    setPadding(28, 18, 28, 18)
                }
                val editDialog = AlertDialog.Builder(this)
                    .setTitle("${type.shortLabel} — km dall'inizio")
                    .setView(input)
                    .setPositiveButton("Salva") { _, _ ->
                        val values = parseAlertKilometers(input.text.toString(), route.distanceMeters)
                        val stored = values.joinToString("; ") {
                            String.format(java.util.Locale.ITALY, "%.1f", it)
                        }
                        preferences.edit().putString(preferenceKey, stored).apply()
                        kilometerText.text =
                            stored.takeIf { it.isNotBlank() }?.let { "$it km   ›" }
                                ?: "Non impostato   ›"
                        routeAlertEngine.setRoute(route, loadManualAlertKilometers(route))
                    }
                    .setNeutralButton("Elimina") { _, _ ->
                        preferences.edit().remove(preferenceKey).apply()
                        kilometerText.text = "Non impostato   ›"
                        routeAlertEngine.setRoute(route, loadManualAlertKilometers(route))
                    }
                    .setNegativeButton("Annulla", null)
                    .create()
                editDialog.setOnShowListener { styleBlueDialog(editDialog) }
                editDialog.show()
            }
            container.addView(row)
            if (index < MANUAL_ALERT_TYPES.lastIndex) {
                container.addView(android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    )
                    setBackgroundColor(android.graphics.Color.parseColor("#5B8BC0"))
                })
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Km avvisi — ${route.name}")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Chiudi", null)
            .create()
        dialog.setOnShowListener { styleBlueDialog(dialog) }
        dialog.show()
    }

    private fun loadManualAlertKilometers(
        route: GpxRoute
    ): Map<RouteAlertType, List<Double>> {
        return loadManualAlertEntries(route)
            .groupBy({ it.first }, { it.second })
    }

    private fun manualAlertPreferenceKey(route: GpxRoute, type: RouteAlertType): String {
        val routeIdentity = activeRouteFile?.name ?: route.name
        return "route_alert_km_${routeIdentity.hashCode()}_${type.name}"
    }

    private fun parseAlertKilometers(value: String, routeDistanceMeters: Double): List<Double> {
        val maximumKilometers = routeDistanceMeters / 1000.0
        return value.split(';', '\n')
            .mapNotNull { it.trim().replace(',', '.').toDoubleOrNull() }
            .filter { it >= 0.0 && it <= maximumKilometers }
            .distinct()
            .sorted()
    }

    private fun loadManualAlertEntries(
        route: GpxRoute
    ): List<Pair<RouteAlertType, Double>> {
        val preferences = getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
        val maximumKilometers = route.distanceMeters / 1000.0
        return preferences.getString(manualAlertEntriesPreferenceKey(route), "").orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('|')
                val type = parts.getOrNull(0)?.let {
                    runCatching { RouteAlertType.valueOf(it) }.getOrNull()
                }
                val kilometer = parts.getOrNull(1)?.toDoubleOrNull()
                if (type != null && kilometer != null &&
                    type in MANUAL_ALERT_TYPES && kilometer in 0.0..maximumKilometers
                ) {
                    type to kilometer
                } else {
                    null
                }
            }
            .sortedBy { it.second }
            .toList()
    }

    private fun saveManualAlertEntries(
        route: GpxRoute,
        entries: List<Pair<RouteAlertType, Double>>
    ) {
        val stored = entries.sortedBy { it.second }.joinToString("\n") {
            "${it.first.name}|${it.second}"
        }
        getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
            .edit()
            .putString(manualAlertEntriesPreferenceKey(route), stored)
            .apply()
        routeAlertEngine.setRoute(route, loadManualAlertKilometers(route))
        mapLibreMap?.style?.let { style ->
            if (style.getLayer(GPX_ALERTS_LAYER_ID) != null) {
                style.removeLayer(GPX_ALERTS_LAYER_ID)
            }
            if (style.getLayer(GPX_ALERT_CONNECTORS_LAYER_ID) != null) {
                style.removeLayer(GPX_ALERT_CONNECTORS_LAYER_ID)
            }
            if (style.getLayer(GPX_ALERT_CONNECTORS_OUTLINE_LAYER_ID) != null) {
                style.removeLayer(GPX_ALERT_CONNECTORS_OUTLINE_LAYER_ID)
            }
            if (style.getSource(GPX_ALERTS_SOURCE_ID) != null) {
                style.removeSource(GPX_ALERTS_SOURCE_ID)
            }
            if (style.getSource(GPX_ALERT_CONNECTORS_SOURCE_ID) != null) {
                style.removeSource(GPX_ALERT_CONNECTORS_SOURCE_ID)
            }
            drawRouteAlertMarkers(style, route)
        }
    }

    private fun manualAlertEntriesPreferenceKey(route: GpxRoute): String {
        val routeIdentity = activeRouteFile?.name ?: route.name
        return "route_alert_entries_${routeIdentity.hashCode()}"
    }

    private fun showAlertKilometerEditor(
        route: GpxRoute,
        entry: Pair<RouteAlertType, Double>,
        existingIndex: Int?,
        onSaved: () -> Unit
    ) {
        val input = android.widget.EditText(this).apply {
            if (existingIndex != null) {
                setText(String.format(java.util.Locale.ITALY, "%.1f", entry.second))
            }
            hint = "Chilometro dall'inizio"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.LTGRAY)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(28, 18, 28, 18)
        }
        val editor = AlertDialog.Builder(this)
            .setTitle("${entry.first.shortLabel} — chilometro")
            .setView(input)
            .setPositiveButton("Salva") { _, _ ->
                val kilometer = input.text.toString().trim()
                    .replace(',', '.')
                    .toDoubleOrNull()
                val maximum = route.distanceMeters / 1000.0
                if (kilometer == null || kilometer !in 0.0..maximum) {
                    Toast.makeText(
                        this,
                        "Inserisci un chilometro compreso nel percorso",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val entries = loadManualAlertEntries(route).toMutableList()
                    val updatedEntry = entry.first to kilometer
                    if (existingIndex != null && existingIndex in entries.indices) {
                        entries[existingIndex] = updatedEntry
                    } else {
                        entries += updatedEntry
                    }
                    saveManualAlertEntries(route, entries)
                    onSaved()
                }
            }
            .setNegativeButton("Annulla", null)
            .create()
        editor.setOnShowListener { styleBlueDialog(editor) }
        editor.show()
    }

    private fun showAlertDistanceSettings() {
        val preferences = getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 10, 28, 10)
        }

        ALERT_TYPES.forEachIndexed { index, (key, label) ->
            val savedDistance = preferences.getInt(key, DEFAULT_ALERT_DISTANCE)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(22, 18, 14, 18)
                isClickable = true
                isFocusable = true
                background = createMenuRowBackground()
            }

            val nameText = TextView(this).apply {
                text = label
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val distanceText = TextView(this).apply {
                text = "${savedDistance} m   ›"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            row.addView(nameText)
            row.addView(distanceText)

            row.setOnClickListener {
                val currentDistance = preferences.getInt(key, DEFAULT_ALERT_DISTANCE)
                val selectedIndex = ALERT_DISTANCE_OPTIONS
                    .indexOf(currentDistance)
                    .takeIf { it >= 0 } ?: 2
                val labels = ALERT_DISTANCE_OPTIONS.map { "$it m" }.toTypedArray()

                val distanceDialog = AlertDialog.Builder(this)
                    .setTitle(label)
                    .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                        val selectedDistance = ALERT_DISTANCE_OPTIONS[which]
                        preferences.edit().putInt(key, selectedDistance).apply()
                        distanceText.text = "${selectedDistance} m   ›"
                        Toast.makeText(
                            this,
                            "$label: ${selectedDistance} m",
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Annulla", null)
                    .create()

                distanceDialog.setOnShowListener { styleBlueDialog(distanceDialog) }
                distanceDialog.show()
            }

            container.addView(row)

            if (index < ALERT_TYPES.lastIndex) {
                container.addView(android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    ).apply {
                        marginStart = 12
                        marginEnd = 12
                    }
                    setBackgroundColor(android.graphics.Color.parseColor("#3C67A8"))
                })
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Distanze avvisi")
            .setMessage("Tocca un avviso per scegliere la distanza.")
            .setView(container)
            .setPositiveButton("Chiudi", null)
            .create()

        dialog.setOnShowListener { styleBlueDialog(dialog) }
        dialog.show()
    }

    private fun createMenuRowBackground(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 14f
            setColor(android.graphics.Color.parseColor("#123F7A"))
        }
    }


    private fun showRouteAppearanceSettings(file: File) {
        val current = loadRouteAppearance(file)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 18, 42, 8)
        }

        var selectedColor = current.color
        var useSlopeColors = current.useSlopeColors
        val colorLabel = TextView(this).apply {
            text = if (useSlopeColors) "Colore traccia: Pendenza" else "Colore traccia: ${colorName(selectedColor)}"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 12, 0, 12)
            setTextColor(if (useSlopeColors) android.graphics.Color.WHITE else android.graphics.Color.parseColor(selectedColor))
        }
        colorLabel.setOnClickListener {
            val names = (ROUTE_COLORS.map { colorName(it) } + "Pendenza").toTypedArray()
            val selectedIndex = if (useSlopeColors) ROUTE_COLORS.size else ROUTE_COLORS.indexOf(selectedColor).coerceAtLeast(0)
            val colorDialog = AlertDialog.Builder(this)
                .setTitle("Colore traccia")
                .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                    if (which == ROUTE_COLORS.size) {
                        useSlopeColors = true
                        colorLabel.text = "Colore traccia: Pendenza"
                        colorLabel.setTextColor(android.graphics.Color.WHITE)
                    } else {
                        useSlopeColors = false
                        selectedColor = ROUTE_COLORS[which]
                        colorLabel.text = "Colore traccia: ${colorName(selectedColor)}"
                        colorLabel.setTextColor(android.graphics.Color.parseColor(selectedColor))
                    }
                    dialog.dismiss()
                }
                .create()
            colorDialog.setOnShowListener { styleBlueDialog(colorDialog) }
            colorDialog.show()
        }
        container.addView(colorLabel)

        container.addView(TextView(this).apply {
            text = "Spessore traccia"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        var selectedWidth = current.width.toInt().coerceIn(3, 16)
        val widthChoices = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val widthButtons = mutableListOf<TextView>()
        (3..16).forEach { value ->
            val choice = createCompactNumberChoice(value.toString(), value == selectedWidth)
            choice.setOnClickListener {
                selectedWidth = value
                widthButtons.forEachIndexed { index, button ->
                    styleCompactNumberChoice(button, index + 3 == selectedWidth)
                }
            }
            widthButtons += choice
            widthChoices.addView(choice)
        }
        container.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(widthChoices)
        })

        val arrowsCheck = CheckBox(this).apply {
            text = "Mostra frecce di direzione"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            isChecked = current.showDirectionArrows
            setPadding(0, 14, 0, 8)
        }
        container.addView(arrowsCheck)

        container.addView(TextView(this).apply {
            text = "Distanza tra le frecce"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        var selectedSpacing = current.arrowSpacingMeters
            .takeIf { it in ROUTE_ARROW_SPACING } ?: 300
        val spacingChoices = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val spacingButtons = mutableListOf<TextView>()
        ROUTE_ARROW_SPACING.forEach { value ->
            val spacingLabel = if (value >= 1000) {
                "${value / 1000} km"
            } else {
                "$value m"
            }
            val choice = createCompactNumberChoice(spacingLabel, value == selectedSpacing)
            choice.setOnClickListener {
                selectedSpacing = value
                spacingButtons.forEachIndexed { index, button ->
                    styleCompactNumberChoice(
                        button,
                        ROUTE_ARROW_SPACING[index] == selectedSpacing
                    )
                }
            }
            spacingButtons += choice
            spacingChoices.addView(choice)
        }
        fun updateSpacingChoicesEnabled(enabled: Boolean) {
            spacingChoices.alpha = if (enabled) 1f else 0.4f
            spacingButtons.forEach { it.isEnabled = enabled }
        }
        arrowsCheck.setOnCheckedChangeListener { _, checked ->
            updateSpacingChoicesEnabled(checked)
        }
        updateSpacingChoicesEnabled(arrowsCheck.isChecked)
        container.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(spacingChoices)
        })

        val settingsDialog = AlertDialog.Builder(this)
            .setTitle("Impostazioni — ${file.name}")
            .setView(container)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva") { _, _ ->
                val appearance = RouteAppearance(
                    color = selectedColor,
                    width = selectedWidth.toFloat(),
                    showDirectionArrows = arrowsCheck.isChecked,
                    arrowSpacingMeters = selectedSpacing,
                    useSlopeColors = useSlopeColors
                )
                saveRouteAppearance(file, appearance)
                if (activeRouteFile?.absolutePath == file.absolutePath) {
                    activeRoute?.let { route ->
                        mapLibreMap?.style?.let { style ->
                            drawRoute(style, route, zoomToRoute = false, file = file)
                        }
                    }
                }
                Toast.makeText(this, "Impostazioni percorso salvate", Toast.LENGTH_SHORT).show()
            }
            .create()
        settingsDialog.setOnShowListener { styleBlueDialog(settingsDialog) }
        settingsDialog.show()
    }

    private fun createCompactNumberChoice(label: String, selected: Boolean): TextView {
        val density = resources.displayMetrics.density
        val height = (42 * density).toInt()
        val width = ((if (label.contains(" ")) 58 else 42) * density).toInt()
        return TextView(this).apply {
            text = label
            gravity = android.view.Gravity.CENTER
            textSize = if (label.length >= 4) 12f else 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(width, height).apply {
                marginEnd = (6 * resources.displayMetrics.density).toInt()
            }
            styleCompactNumberChoice(this, selected)
        }
    }

    private fun styleCompactNumberChoice(choice: TextView, selected: Boolean) {
        choice.setTextColor(
            if (selected) android.graphics.Color.rgb(23, 32, 42)
            else android.graphics.Color.WHITE
        )
        choice.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 8 * resources.displayMetrics.density
            setColor(
                android.graphics.Color.parseColor(
                    if (selected) "#FFD21F" else "#123F7A"
                )
            )
            setStroke(
                (1 * resources.displayMetrics.density).toInt(),
                android.graphics.Color.parseColor("#5B8BC0")
            )
        }
    }

    private fun styleMenuButton(button: Button, destructive: Boolean = false) {
        val background = if (destructive) "#C62828" else "#1267B1"
        button.setTextColor(android.graphics.Color.WHITE)
        button.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor(background))
        button.isAllCaps = false
        button.setPadding(12, 4, 12, 4)
    }

    private fun styleBlueDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_blue)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(android.graphics.Color.parseColor("#FFD500"))
            isAllCaps = false
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            setTextColor(android.graphics.Color.WHITE)
            isAllCaps = false
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
            setTextColor(android.graphics.Color.parseColor("#D7E8FF"))
            isAllCaps = false
        }

        applyBlueDialogColors(dialog.window?.decorView)
    }

    private fun applyBlueDialogColors(view: View?) {
        when (view) {
            null -> Unit
            is Button -> {
                // I pulsanti creati nel contenuto mantengono il proprio sfondo blu/rosso.
                if (view.backgroundTintList == null) {
                    view.setTextColor(android.graphics.Color.WHITE)
                }
            }
            is TextView -> {
                if (view.currentTextColor == android.graphics.Color.BLACK ||
                    view.currentTextColor == android.graphics.Color.DKGRAY) {
                    view.setTextColor(android.graphics.Color.WHITE)
                }
            }
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyBlueDialogColors(view.getChildAt(index))
            }
        }
    }

    private fun routePreferencePrefix(file: File): String =
        "${file.name}_${file.length()}_${file.absolutePath.hashCode()}"

    private fun loadRouteAppearance(file: File): RouteAppearance {
        val preferences = getSharedPreferences(PREFS_ROUTE_APPEARANCE, Context.MODE_PRIVATE)
        val prefix = routePreferencePrefix(file)
        return RouteAppearance(
            color = preferences.getString("${prefix}_color", "#005BBB") ?: "#005BBB",
            width = preferences.getFloat("${prefix}_width", 7f),
            showDirectionArrows = preferences.getBoolean("${prefix}_arrows", true),
            arrowSpacingMeters = preferences.getInt("${prefix}_spacing", 300),
            useSlopeColors = preferences.getBoolean("${prefix}_slope_colors", false)
        )
    }

    private fun saveRouteAppearance(file: File, appearance: RouteAppearance) {
        val prefix = routePreferencePrefix(file)
        getSharedPreferences(PREFS_ROUTE_APPEARANCE, Context.MODE_PRIVATE)
            .edit()
            .putString("${prefix}_color", appearance.color)
            .putFloat("${prefix}_width", appearance.width)
            .putBoolean("${prefix}_arrows", appearance.showDirectionArrows)
            .putInt("${prefix}_spacing", appearance.arrowSpacingMeters)
            .putBoolean("${prefix}_slope_colors", appearance.useSlopeColors)
            .apply()
    }

    private fun colorName(color: String): String = when (color.uppercase()) {
        "#005BBB" -> "Blu"
        "#E31B23" -> "Rosso"
        "#16A34A" -> "Verde"
        "#FF8C00" -> "Arancione"
        "#7C3AED" -> "Viola"
        "#111827" -> "Nero"
        "#FFFFFF" -> "Bianco"
        else -> color
    }

    private fun installRouteArrowImage(style: Style) {
        if (style.getImage(GPX_ARROW_IMAGE_ID) != null) return
        val size = 72
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#005BBB")
            this.style = Paint.Style.FILL
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            this.style = Paint.Style.FILL
        }
        val outer = Path().apply {
            moveTo(size / 2f, 2f)
            lineTo(size - 3f, size - 6f)
            lineTo(size / 2f, size - 15f)
            lineTo(3f, size - 6f)
            close()
        }
        val inner = Path().apply {
            moveTo(size / 2f, 8f)
            lineTo(size - 10f, size - 12f)
            lineTo(size / 2f, size - 20f)
            lineTo(10f, size - 12f)
            close()
        }
        canvas.drawPath(outer, outline)
        canvas.drawPath(inner, fill)
        style.addImage(GPX_ARROW_IMAGE_ID, bitmap)
    }

    private fun installFinishFlagImage(style: Style) {
        if (style.getImage(GPX_FINISH_IMAGE_ID) != null) return
        val width = 56
        val height = 64
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val polePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#263238")
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(8f, 5f, 8f, 60f, polePaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            this.style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            this.style = Paint.Style.FILL
        }
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            this.style = Paint.Style.FILL
        }
        val left = 10f
        val top = 5f
        val cellWidth = 11f
        val cellHeight = 10f
        for (row in 0 until 3) {
            for (column in 0 until 4) {
                val paint = if ((row + column) % 2 == 0) blackPaint else whitePaint
                canvas.drawRect(
                    left + column * cellWidth,
                    top + row * cellHeight,
                    left + (column + 1) * cellWidth,
                    top + (row + 1) * cellHeight,
                    paint
                )
            }
        }
        canvas.drawRect(left, top, left + 4 * cellWidth, top + 3 * cellHeight, borderPaint)
        style.addImage(GPX_FINISH_IMAGE_ID, bitmap)
    }

    private fun drawRouteAlertMarkers(style: Style, route: GpxRoute) {
        val alerts = routeAlertEngine.routeAlerts()
            .filter { it.type != RouteAlertType.FINISH }
        if (alerts.isEmpty()) return

        RouteAlertType.entries
            .filter { it != RouteAlertType.FINISH }
            .forEach { type -> installAlertMarkerImage(style, type) }

        val connectorFeatures = mutableListOf<Feature>()
        val features = alerts.mapIndexed { index, alert ->
            val routePoint = pointAtRouteProgress(route, alert.progressMeters)
            val routeBearing = bearingAtRouteProgress(route, alert.progressMeters)
            val sideBearing = routeBearing + if (index % 2 == 0) 90.0 else -90.0
            val markerPoint = offsetPoint(routePoint, sideBearing, ALERT_MARKER_OFFSET_METERS)
            connectorFeatures += Feature.fromGeometry(
                LineString.fromLngLats(
                    listOf(
                        Point.fromLngLat(routePoint.longitude, routePoint.latitude),
                        Point.fromLngLat(markerPoint.longitude, markerPoint.latitude)
                    )
                )
            )
            Feature.fromGeometry(Point.fromLngLat(markerPoint.longitude, markerPoint.latitude)).apply {
                addStringProperty(ALERT_MARKER_ICON_PROPERTY, alertMarkerImageId(alert.type))
            }
        }
        style.addSource(
            GeoJsonSource(
                GPX_ALERT_CONNECTORS_SOURCE_ID,
                org.maplibre.geojson.FeatureCollection.fromFeatures(connectorFeatures)
            )
        )
        style.addLayer(
            LineLayer(
                GPX_ALERT_CONNECTORS_OUTLINE_LAYER_ID,
                GPX_ALERT_CONNECTORS_SOURCE_ID
            ).withProperties(
                lineColor("#174F8B"),
                lineWidth(5f),
                lineCap(Property.LINE_CAP_ROUND)
            )
        )
        style.addLayer(
            LineLayer(
                GPX_ALERT_CONNECTORS_LAYER_ID,
                GPX_ALERT_CONNECTORS_SOURCE_ID
            ).withProperties(
                lineColor("#FFFFFF"),
                lineWidth(2.5f),
                lineCap(Property.LINE_CAP_ROUND)
            )
        )
        style.addSource(
            GeoJsonSource(
                GPX_ALERTS_SOURCE_ID,
                org.maplibre.geojson.FeatureCollection.fromFeatures(features)
            )
        )
        style.addLayer(
            SymbolLayer(GPX_ALERTS_LAYER_ID, GPX_ALERTS_SOURCE_ID).withProperties(
                iconImage(get(ALERT_MARKER_ICON_PROPERTY)),
                iconSize(1.0f),
                iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT)
            )
        )
    }

    private fun drawKilometerMarkers(style: Style, route: GpxRoute) {
        val kilometerCount = kotlin.math.floor(route.distanceMeters / 1000.0).toInt()
        if (kilometerCount < 1) return

        val features = (1..kilometerCount).map { kilometer ->
            val imageId = "$GPX_KILOMETER_IMAGE_PREFIX$kilometer"
            installKilometerMarkerImage(style, imageId, kilometer)
            val point = pointAtRouteProgress(route, kilometer * 1000.0)
            Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply {
                addStringProperty(KILOMETER_IMAGE_PROPERTY, imageId)
            }
        }

        style.addSource(
            GeoJsonSource(
                GPX_KILOMETERS_SOURCE_ID,
                org.maplibre.geojson.FeatureCollection.fromFeatures(features)
            )
        )
        style.addLayer(
            SymbolLayer(GPX_KILOMETERS_LABEL_LAYER_ID, GPX_KILOMETERS_SOURCE_ID).withProperties(
                iconImage(get(KILOMETER_IMAGE_PROPERTY)),
                iconSize(1.0f),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT)
            )
        )
    }

    private fun installKilometerMarkerImage(style: Style, imageId: String, kilometer: Int) {
        if (style.getImage(imageId) != null) return

        val size = 42
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            this.style = Paint.Style.FILL
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#7C3AED")
            this.style = Paint.Style.STROKE
            strokeWidth = 6.5f
        }
        canvas.drawCircle(size / 2f, size / 2f, 32f, fill)
        canvas.drawCircle(size / 2f, size / 2f, 32f, border)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#5B21B6")
            textSize = if (kilometer < 100) 28f else 23f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val baseline = size / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(kilometer.toString(), size / 2f, baseline, textPaint)
        style.addImage(imageId, bitmap)
    }

    private fun installAlertMarkerImage(style: Style, type: RouteAlertType) {
        val imageId = alertMarkerImageId(type)
        if (style.getImage(imageId) != null) return

        val isTriangle = type == RouteAlertType.GPM || type == RouteAlertType.TV
        val width = if (isTriangle) 78 else 76
        val height = if (isTriangle) 74 else 44
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(type.color)
            this.style = Paint.Style.FILL
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            this.style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val bounds = android.graphics.RectF(2f, 2f, width - 2f, height - 7f)
        if (isTriangle) {
            background.color = android.graphics.Color.WHITE
            border.color = android.graphics.Color.parseColor("#E31B23")
            border.strokeWidth = 6f
            border.strokeJoin = Paint.Join.ROUND
            val triangle = Path().apply {
                moveTo(width / 2f, height - 4f)
                lineTo(5f, 5f)
                lineTo(width - 5f, 5f)
                close()
            }
            canvas.drawPath(triangle, background)
            canvas.drawPath(triangle, border)
        } else {
            canvas.drawRoundRect(bounds, 9f, 9f, background)
            canvas.drawRoundRect(bounds, 9f, 9f, border)
        }

        val label = when (type) {
            RouteAlertType.TV -> "TV"
            RouteAlertType.GPM -> "GPM"
            RouteAlertType.GREEN_ZONE -> "GZ"
            RouteAlertType.REFRESHMENT -> "RIF"
            RouteAlertType.DANGER -> "!"
            RouteAlertType.FINISH -> ""
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isTriangle || type == RouteAlertType.GREEN_ZONE) {
                android.graphics.Color.rgb(23, 32, 42)
            } else {
                android.graphics.Color.WHITE
            }
            textSize = if (isTriangle && type == RouteAlertType.GPM) 19f else if (isTriangle) 23f else 21f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val centerY = if (isTriangle) 28f else bounds.centerY()
        val baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, width / 2f, baseline, textPaint)
        style.addImage(imageId, bitmap)
    }

    private fun alertMarkerImageId(type: RouteAlertType): String =
        "gpx-alert-marker-${type.name.lowercase()}"

    private fun pointAtRouteProgress(route: GpxRoute, progressMeters: Double): GpxPoint {
        if (route.points.size < 2) return route.points.first()
        val target = progressMeters.coerceIn(0.0, route.distanceMeters)
        var cumulative = 0.0
        for (index in 1 until route.points.size) {
            val start = route.points[index - 1]
            val end = route.points[index]
            val segment = distanceMeters(
                start.latitude,
                start.longitude,
                end.latitude,
                end.longitude
            )
            if (cumulative + segment >= target && segment > 0.0) {
                val fraction = ((target - cumulative) / segment).coerceIn(0.0, 1.0)
                return GpxPoint(
                    latitude = start.latitude + (end.latitude - start.latitude) * fraction,
                    longitude = start.longitude + (end.longitude - start.longitude) * fraction
                )
            }
            cumulative += segment
        }
        return route.points.last()
    }

    private fun bearingAtRouteProgress(route: GpxRoute, progressMeters: Double): Double {
        if (route.points.size < 2) return 0.0
        val target = progressMeters.coerceIn(0.0, route.distanceMeters)
        var cumulative = 0.0
        for (index in 1 until route.points.size) {
            val start = route.points[index - 1]
            val end = route.points[index]
            val segment = distanceMeters(
                start.latitude,
                start.longitude,
                end.latitude,
                end.longitude
            )
            if (cumulative + segment >= target) {
                return bearingDegrees(start, end).toDouble()
            }
            cumulative += segment
        }
        return bearingDegrees(route.points[route.points.lastIndex - 1], route.points.last()).toDouble()
    }

    private fun offsetPoint(point: GpxPoint, bearingDegrees: Double, distanceMeters: Double): GpxPoint {
        val bearingRadians = Math.toRadians(bearingDegrees)
        val latitudeOffset = distanceMeters * kotlin.math.cos(bearingRadians) / 111_320.0
        val longitudeScale = 111_320.0 *
            kotlin.math.cos(Math.toRadians(point.latitude)).coerceAtLeast(0.01)
        val longitudeOffset = distanceMeters * kotlin.math.sin(bearingRadians) / longitudeScale
        return GpxPoint(
            latitude = point.latitude + latitudeOffset,
            longitude = point.longitude + longitudeOffset
        )
    }

    private fun buildDirectionArrowFeatures(route: GpxRoute, spacingMeters: Int): List<Feature> {
        if (route.points.size < 2 || spacingMeters <= 0) return emptyList()
        val features = mutableListOf<Feature>()
        var cumulative = 0.0
        var nextArrow = spacingMeters.toDouble()

        for (index in 1 until route.points.size) {
            val a = route.points[index - 1]
            val b = route.points[index]
            val segment = distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            if (segment <= 0.0) continue

            while (cumulative + segment >= nextArrow) {
                val fraction = ((nextArrow - cumulative) / segment).coerceIn(0.0, 1.0)
                val latitude = a.latitude + (b.latitude - a.latitude) * fraction
                val longitude = a.longitude + (b.longitude - a.longitude) * fraction
                val feature = Feature.fromGeometry(Point.fromLngLat(longitude, latitude))
                feature.addNumberProperty("bearing", bearingDegrees(a, b))
                features += feature
                nextArrow += spacingMeters
            }
            cumulative += segment
        }
        return features
    }

    private fun bearingDegrees(a: GpxPoint, b: GpxPoint): Float {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val deltaLon = Math.toRadians(b.longitude - a.longitude)
        val y = kotlin.math.sin(deltaLon) * kotlin.math.cos(lat2)
        val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
            kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(deltaLon)
        return ((Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    private fun removeRouteLayers(style: Style) {
        listOf(
            GPX_ROUTE_LAYER_ID,
            GPX_ARROWS_LAYER_ID,
            GPX_KILOMETERS_LABEL_LAYER_ID,
            GPX_START_LAYER_ID,
            GPX_FINISH_LAYER_ID,
            GPX_ALERTS_LAYER_ID,
            GPX_ALERT_CONNECTORS_LAYER_ID,
            GPX_ALERT_CONNECTORS_OUTLINE_LAYER_ID,
            GPX_EDIT_SELECTION_LAYER_ID,
            SIMULATION_LAYER_ID
        ).forEach { id ->
            if (style.getLayer(id) != null) style.removeLayer(id)
        }
        listOf(
            GPX_ROUTE_SOURCE_ID,
            GPX_ARROWS_SOURCE_ID,
            GPX_KILOMETERS_SOURCE_ID,
            GPX_START_SOURCE_ID,
            GPX_FINISH_SOURCE_ID,
            GPX_ALERTS_SOURCE_ID,
            GPX_ALERT_CONNECTORS_SOURCE_ID,
            GPX_EDIT_SELECTION_SOURCE_ID,
            SIMULATION_SOURCE_ID
        ).forEach { id ->
            if (style.getSource(id) != null) style.removeSource(id)
        }
    }

    private fun zoomToRoute(route: GpxRoute) {
        val map = mapLibreMap ?: return
        val boundsBuilder = LatLngBounds.Builder()
        route.points.forEach { point ->
            boundsBuilder.include(LatLng(point.latitude, point.longitude))
        }
        setFollowGps(false)
        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 90),
            900
        )
    }

    private fun setFollowGps(enabled: Boolean) {
        followGps = enabled
        updateRecenterButtonColor()
    }

    private fun updateRecenterButtonColor() {
        val backgroundColor = if (followGps) "#005BBB" else "#FFD21F"
        val iconColor = if (followGps) android.graphics.Color.WHITE else
            android.graphics.Color.rgb(23, 32, 42)
        binding.recenterButton.backgroundTintList =
            ColorStateList.valueOf(android.graphics.Color.parseColor(backgroundColor))
        binding.recenterButton.imageTintList = ColorStateList.valueOf(iconColor)
    }

    private fun gpxDirectory(): File {
        val baseDirectory = getExternalFilesDir(null) ?: filesDir
        return File(baseDirectory, "gpx")
    }

    private fun activeGpxFile(): File? {
        val saved = getPreferences(Context.MODE_PRIVATE).getString(PREF_ACTIVE_GPX, null)
        if (!saved.isNullOrBlank()) {
            val file = File(saved)
            if (file.exists()) return file
        }
        return gpxDirectory().listFiles { file -> file.extension.equals("gpx", true) }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun formatDistance(meters: Double): String =
        if (meters < 1000.0) "${meters.toInt()} m" else String.format(java.util.Locale.ITALY, "%.1f km", meters / 1000.0)

    // =========================================================
    // IMPORTAZIONE MAPPA
    // =========================================================

    private fun importMap(uri: Uri) {
        showStatus(
            message = "Importazione mappa in background…",
            showButton = false
        )
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        ImportService.importMap(this, uri)
    }

    private fun handleImportResult(result: ImportServiceResult) {
        when (result) {
            is ImportServiceResult.GpxImported -> {
                val file = File(result.filePath)
                showTransientStatus("GPX importato correttamente")
                openGpxFile(file, zoomToRoute = true) {
                    populateDrawerRoutes()
                }
            }
            ImportServiceResult.MapImported -> {
                showTransientStatus("Mappa importata correttamente")
                saveMapMode(MAP_MODE_OFFLINE)
                loadInstalledMap()
            }
            is ImportServiceResult.Failed -> {
                showStatus(
                    "Importazione ${result.kind} non riuscita:\n${result.message}",
                    true
                )
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)

        return contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val columnIndex = cursor.getColumnIndex(
                android.provider.OpenableColumns.DISPLAY_NAME
            )

            if (columnIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(columnIndex)
            } else {
                null
            }
        }
    }

    private fun installedMapFile(): File {
        val baseDirectory = getExternalFilesDir(null) ?: filesDir
        return File(baseDirectory, "maps/friuli.pmtiles")
    }

    // =========================================================
    // STILE VETTORIALE OFFLINE
    // =========================================================

    private fun createVectorStyleJson(mapUri: String): String {
        val escapedUri = mapUri
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        return """
            {
              "version": 8,
              "name": "GpxNav Pro Offline",
              "sources": {
                "$MAP_SOURCE_ID": {
                  "type": "vector",
                  "url": "$escapedUri",
                  "minzoom": 0,
                  "maxzoom": 15
                }
              },
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": { "background-color": "#F2EFE6" }
                },
                {
                  "id": "earth",
                  "type": "fill",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "earth",
                  "paint": { "fill-color": "#F2EFE6" }
                },
                {
                  "id": "landuse",
                  "type": "fill",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "landuse",
                  "paint": {
                    "fill-color": [
                      "match", ["get", "kind"],
                      "park", "#D6E8C8",
                      "forest", "#C7DFB5",
                      "wood", "#C7DFB5",
                      "grass", "#DDECCE",
                      "residential", "#EEEAE1",
                      "industrial", "#E3DED6",
                      "commercial", "#E8E1D8",
                      "#E9E6DC"
                    ],
                    "fill-opacity": 0.82
                  }
                },
                {
                  "id": "water",
                  "type": "fill",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "water",
                  "paint": {
                    "fill-color": [
                      "case",
                      ["==", ["get", "intermittent"], true], "#E4DED1",
                      ["==", ["get", "kind"], "playa"], "#E8E1D2",
                      ["==", ["get", "kind"], "other"], "#DED9CF",
                      ["==", ["get", "kind"], "ocean"], "#A8CBE0",
                      ["==", ["get", "kind"], "lake"], "#C1DCE8",
                      "#C9E0E8"
                    ],
                    "fill-opacity": 0.92
                  }
                },
                {
                  "id": "waterways",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "physical_line",
                  "filter": ["==", ["get", "kind"], "waterway"],
                  "paint": {
                    "line-color": "#8ABFD3",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 7, 0.5, 15, 2.2]
                  }
                },
                {
                  "id": "buildings",
                  "type": "fill",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "buildings",
                  "minzoom": 13,
                  "paint": {
                    "fill-color": "#CDBEAD",
                    "fill-outline-color": "#AFA08F"
                  }
                },
                {
                  "id": "boundaries",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "boundaries",
                  "paint": {
                    "line-color": "#A3978A",
                    "line-width": 0.8,
                    "line-dasharray": [3, 2]
                  }
                },
                {
                  "id": "roads-path",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "roads",
                  "filter": ["==", ["get", "kind"], "path"],
                  "minzoom": 12,
                  "paint": {
                    "line-color": "#B9AA91",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.7, 16, 2.2],
                    "line-dasharray": [2, 1]
                  }
                },
                {
                  "id": "roads-minor-casing",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "roads",
                  "filter": ["==", ["get", "kind"], "minor_road"],
                  "minzoom": 10,
                  "paint": {
                    "line-color": "#C5BBAE",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1.1, 16, 5.4]
                  }
                },
                {
                  "id": "roads-minor",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "roads",
                  "filter": ["==", ["get", "kind"], "minor_road"],
                  "minzoom": 10,
                  "paint": {
                    "line-color": "#FAF8F2",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.7, 16, 4.0]
                  }
                },
                {
                  "id": "roads-major-casing",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "roads",
                  "filter": ["in", ["get", "kind"], ["literal", ["major_road", "highway"]]],
                  "paint": {
                    "line-color": "#C88952",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 6, 1.8, 16, 9.0]
                  }
                },
                {
                  "id": "roads-major",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "roads",
                  "filter": ["in", ["get", "kind"], ["literal", ["major_road", "highway"]]],
                  "paint": {
                    "line-color": "#F2C98D",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 6, 1.0, 16, 7.0]
                  }
                },
                {
                  "id": "roads-rail",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "roads",
                  "filter": ["==", ["get", "kind"], "rail"],
                  "minzoom": 10,
                  "paint": {
                    "line-color": "#716E69",
                    "line-width": 1.2,
                    "line-dasharray": [2, 2]
                  }
                },
                {
                  "id": "transit",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "transit",
                  "minzoom": 10,
                  "paint": {
                    "line-color": "#77736E",
                    "line-width": 1.2,
                    "line-dasharray": [2, 2]
                  }
                }
              ]
            }
        """.trimIndent()
    }

    private fun emptyStyleJson(): String = """
        {
          "version": 8,
          "name": "GpxNav Pro Offline",
          "sources": {},
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": { "background-color": "#F2EFE6" }
            }
          ]
        }
    """.trimIndent()

    // =========================================================
    // PANNELLO DI STATO
    // =========================================================

    private fun showStatus(message: String, showButton: Boolean) {
        binding.statusText.text = message
        binding.importMapButton.visibility = if (showButton) View.VISIBLE else View.GONE
        binding.statusPanel.visibility = View.VISIBLE
    }

    private fun showTransientStatus(message: String, durationMs: Long = 1800L) {
        showStatus(message, false)
        binding.statusPanel.removeCallbacks(hideTransientStatusRunnable)
        binding.statusPanel.postDelayed(hideTransientStatusRunnable, durationMs)
    }

    private val hideTransientStatusRunnable = Runnable {
        binding.statusPanel.visibility = View.GONE
    }

    private fun showCommandGuide() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (24 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt()
            )
        }
        scroll.addView(container)

        fun addGuideItem(title: String, description: String) {
            container.addView(TextView(this).apply {
                text = title
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                setPadding(0, 14, 0, 4)
            })
            container.addView(TextView(this).apply {
                text = description
                textSize = 14f
                setTextColor(android.graphics.Color.LTGRAY)
                setPadding(0, 0, 0, 8)
            })
        }

        addGuideItem("＋  Aggiungi tratto", "Sostituisce una parte della traccia facendo passare il nuovo tratto da un punto scelto sulla mappa.")
        addGuideItem("✂  Taglia traccia", "Scegli un punto e conserva la parte precedente oppure quella successiva.")
        addGuideItem("↕  Inverti traccia", "Scambia il senso di percorrenza: partenza e arrivo vengono invertiti.")
        addGuideItem("✥  Sposta punto", "Seleziona un punto GPX e spostalo in una nuova posizione.")
        addGuideItem("↪  Unisci tracce", "Usa la traccia aperta come prima e scegli una seconda traccia. Il verso della seconda viene adattato automaticamente.")
        addGuideItem("↻  Ripeti giri", "Duplica una traccia circuito per il numero di giri scelto, da 2 a 30.")
        addGuideItem("▣  Centra traccia", "Adatta la mappa per mostrare tutta la traccia.")
        addGuideItem("✓  Fine modifica", "Chiude gli strumenti di modifica e torna alla mappa normale.")
        addGuideItem("Simula giro", "Fa avanzare un pallino arancione lungo il GPX. Puoi mettere in pausa, cambiare velocità e controllare km e avvisi.")

        val dialog = AlertDialog.Builder(this)
            .setTitle("Guida comandi GPX NAV")
            .setView(scroll)
            .setPositiveButton("Chiudi", null)
            .create()
        dialog.setOnShowListener { styleBlueDialog(dialog) }
        dialog.show()
    }

    private fun startSimulation() {
        val route = activeRoute ?: run {
            Toast.makeText(this, "Apri prima un percorso GPX", Toast.LENGTH_SHORT).show()
            return
        }
        if (route.points.size < 2) {
            Toast.makeText(this, "Traccia troppo corta per la simulazione", Toast.LENGTH_SHORT).show()
            return
        }
        if (isNavigationActive) {
            Toast.makeText(
                this,
                "Ferma la navigazione prima di avviare la simulazione",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        finishGpxEditMode()
        finishCreateGpxMode()
        setFollowGps(false)

        simulationCumulativeMeters = DoubleArray(route.points.size)
        for (index in 1 until route.points.size) {
            simulationCumulativeMeters[index] =
                simulationCumulativeMeters[index - 1] +
                    GeoMath.haversineMeters(route.points[index - 1], route.points[index])
        }

        manualZoomLevel = mapLibreMap?.cameraPosition?.zoom ?: manualZoomLevel
        simulationProgressMeters = 0.0
        simulationSpeedMultiplier = 1.0
        simulationLastTickMs = android.os.SystemClock.elapsedRealtime()
        isSimulationPaused = false
        isSimulationActive = true
        tripResetOffsetMeters = 0.0
        currentRouteProgressMeters = 0.0
        simulationAlertFeedback.reset()

        installSimulationMarker()
        updateSimulationMarker(0.0)
        updateDistancePanels()
        updateRouteAlertPanel()
        updateSimulationPanel()
        binding.simulationPanel.visibility = View.VISIBLE

        simulationHandler.removeCallbacks(simulationRunnable)
        simulationHandler.post(simulationRunnable)
    }

    private val simulationRunnable = object : Runnable {
        override fun run() {
            if (!isSimulationActive) return

            val now = android.os.SystemClock.elapsedRealtime()
            val elapsedSeconds = ((now - simulationLastTickMs).coerceAtMost(1500L)) / 1000.0
            simulationLastTickMs = now

            if (!isSimulationPaused) {
                val route = activeRoute
                if (route == null) {
                    stopSimulation(silent = true)
                    return
                }

                simulationProgressMeters +=
                    SIMULATION_BASE_SPEED_METERS_PER_SECOND *
                        simulationSpeedMultiplier *
                        elapsedSeconds

                if (simulationProgressMeters >= route.distanceMeters) {
                    simulationProgressMeters = route.distanceMeters
                }

                currentRouteProgressMeters = simulationProgressMeters
                updateSimulationMarker(simulationProgressMeters)
                updateDistancePanels()
                updateRouteAlertPanel()
                updateSimulationPanel()

                if (simulationProgressMeters >= route.distanceMeters) {
                    Toast.makeText(
                        this@MainActivity,
                        "Simulazione completata",
                        Toast.LENGTH_SHORT
                    ).show()
                    stopSimulation(silent = true, keepFinalMarker = true)
                    return
                }
            }

            simulationHandler.postDelayed(this, SIMULATION_TICK_MS)
        }
    }

    private fun toggleSimulationPause() {
        if (!isSimulationActive) return
        isSimulationPaused = !isSimulationPaused
        simulationLastTickMs = android.os.SystemClock.elapsedRealtime()
        updateSimulationPanel()
    }

    private fun setSimulationSpeed(multiplier: Double) {
        if (!isSimulationActive) return
        simulationSpeedMultiplier = multiplier
        simulationLastTickMs = android.os.SystemClock.elapsedRealtime()
        updateSimulationPanel()
    }

    private fun updateSimulationPanel() {
        if (!isSimulationActive) return
        val route = activeRoute ?: return
        val progressKm = simulationProgressMeters / 1000.0
        val totalKm = route.distanceMeters / 1000.0

        binding.simulationStatusText.text = String.format(
            java.util.Locale.ITALY,
            "SIMULAZIONE  %.1f×   %.1f / %.1f km",
            simulationSpeedMultiplier,
            progressKm,
            totalKm
        )
        binding.simulationPauseButton.text = if (isSimulationPaused) "Riprendi" else "Pausa"

        val selected = android.graphics.Color.parseColor("#FFD21F")
        val normal = android.graphics.Color.parseColor("#1261A0")
        val darkText = android.graphics.Color.rgb(23, 32, 42)
        val lightText = android.graphics.Color.WHITE

        fun styleSpeedButton(button: Button, speed: Double) {
            val active = simulationSpeedMultiplier == speed
            button.setBackgroundColor(if (active) selected else normal)
            button.setTextColor(if (active) darkText else lightText)
        }

        styleSpeedButton(binding.simulationSpeed1Button, 1.0)
        styleSpeedButton(binding.simulationSpeed2Button, 2.0)
        styleSpeedButton(binding.simulationSpeed5Button, 5.0)
        styleSpeedButton(binding.simulationSpeed10Button, 10.0)
    }

    private fun installSimulationMarker() {
        val style = mapLibreMap?.style ?: return

        if (style.getSource(SIMULATION_SOURCE_ID) == null) {
            val route = activeRoute ?: return
            val first = route.points.first()
            style.addSource(
                GeoJsonSource(
                    SIMULATION_SOURCE_ID,
                    Feature.fromGeometry(Point.fromLngLat(first.longitude, first.latitude))
                )
            )
        }

        if (style.getLayer(SIMULATION_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(SIMULATION_LAYER_ID, SIMULATION_SOURCE_ID).withProperties(
                    circleRadius(9f),
                    circleColor("#FF8C00"),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(3f)
                )
            )
        }
    }

    private fun updateSimulationMarker(progressMeters: Double) {
        val point = simulationPointAt(progressMeters) ?: return
        val source = mapLibreMap?.style
            ?.getSourceAs<GeoJsonSource>(SIMULATION_SOURCE_ID) ?: return

        source.setGeoJson(
            Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))
        )

        centerSimulationCamera(point)
    }

    private fun centerSimulationCamera(point: GpxPoint) {
        val map = mapLibreMap ?: return

        // Durante il pinch lasciamo completamente libera la camera.
        // Al rilascio verrà memorizzato il nuovo zoom e il pallino
        // tornerà al centro senza cambiare il livello scelto dall'utente.
        if (isScalingMap) return

        map.setPadding(0, 0, 0, 0)

        val currentCamera = map.cameraPosition
        val targetZoom = (manualZoomLevel ?: currentCamera.zoom)
            .coerceIn(0.0, 22.0)

        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(point.latitude, point.longitude))
            .zoom(targetZoom)
            .bearing(currentCamera.bearing)
            .tilt(currentCamera.tilt)
            .build()
    }

    private fun simulationPointAt(progressMeters: Double): GpxPoint? {
        val route = activeRoute ?: return null
        if (route.points.isEmpty()) return null
        if (route.points.size == 1) return route.points.first()
        if (simulationCumulativeMeters.size != route.points.size) return route.points.first()

        val target = progressMeters.coerceIn(0.0, route.distanceMeters)
        var low = 0
        var high = simulationCumulativeMeters.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (simulationCumulativeMeters[mid] <= target) low = mid else high = mid - 1
        }

        val index = low.coerceIn(0, route.points.lastIndex - 1)
        val start = route.points[index]
        val end = route.points[index + 1]
        val segmentStart = simulationCumulativeMeters[index]
        val segmentEnd = simulationCumulativeMeters[index + 1]
        val segmentLength = (segmentEnd - segmentStart).coerceAtLeast(0.001)
        val fraction = ((target - segmentStart) / segmentLength).coerceIn(0.0, 1.0)

        return GpxPoint(
            latitude = start.latitude + (end.latitude - start.latitude) * fraction,
            longitude = start.longitude + (end.longitude - start.longitude) * fraction,
            elevation = if (start.elevation != null && end.elevation != null) {
                start.elevation + (end.elevation - start.elevation) * fraction
            } else {
                start.elevation ?: end.elevation
            }
        )
    }

    private fun stopSimulation(
        silent: Boolean = false,
        keepFinalMarker: Boolean = false
    ) {
        if (!isSimulationActive && !keepFinalMarker) return

        isSimulationActive = false
        isSimulationPaused = false
        simulationHandler.removeCallbacks(simulationRunnable)
        binding.simulationPanel.visibility = View.GONE
        binding.eventAlertPanel.visibility = View.GONE

        if (!keepFinalMarker) {
            mapLibreMap?.style?.let { style ->
                if (style.getLayer(SIMULATION_LAYER_ID) != null) {
                    style.removeLayer(SIMULATION_LAYER_ID)
                }
                if (style.getSource(SIMULATION_SOURCE_ID) != null) {
                    style.removeSource(SIMULATION_SOURCE_ID)
                }
            }
            currentRouteProgressMeters = 0.0
            tripResetOffsetMeters = 0.0
            updateDistancePanels()
        }

        simulationAlertFeedback.reset()
        mapLibreMap?.setPadding(0, 0, 0, 0)

        if (!silent) {
            Toast.makeText(this, "Simulazione terminata", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================
    // CICLO DI VITA MAPVIEW
    // =========================================================

    override fun onStart() {
        super.onStart()
        NavigationLocationService.listener = { location ->
            if (!isFinishing && !isDestroyed) onLocationChanged(location)
        }
        NavigationLocationService.navigationStoppedListener = {
            if (!isFinishing && !isDestroyed) {
                runOnUiThread { resetNavigationUiAfterExternalStop() }
            }
        }

        val navigationPreferences = getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
        val navigationPrefActive = navigationPreferences.getBoolean(PREF_NAVIGATION_ACTIVE, false)
        if (isNavigationActive && !NavigationLocationService.isRunning && !navigationPrefActive) {
            resetNavigationUiAfterExternalStop()
        }
        applyKeepScreenOnPreference()

        ImportService.setListener(::handleImportResult)
        ImportService.consumePendingResult(this)?.let(::handleImportResult)
        NavigationLocationService.freshLastLocation()?.let { lastLocation = it }
        NavigationLocationService.lastNavigationFix?.let { fix ->
            if (isNavigationActive && !isApproachingStart) {
                currentRouteProgressMeters = fix.progressMeters
                updateDistancePanels()
                updateOffRouteWarning(fix)
                updateRouteAlertPanel()
            }
        }
        binding.mapView.onStart()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.root.post {
            updateScaleBar()
            if (isSimulationActive) {
                updateSimulationMarker(simulationProgressMeters)
            } else if (followGps) {
                lastLocation?.takeIf(::isFreshLocation)?.let {
                    centerOnLocation(it, animated = false)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        binding.mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        NavigationLocationService.listener = null
        NavigationLocationService.navigationStoppedListener = null
        ImportService.setListener(null)
        binding.mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_INCOMING_GPX_HANDLED, incomingGpxHandled)
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onDestroy() {
        if (::locationManager.isInitialized) {
            runCatching { locationManager.removeUpdates(this) }
        }
        routesDialog?.dismiss()
        if (::bRouterClient.isInitialized) {
            bRouterClient.close()
        }
        fileExecutor.shutdownNow()
        simulationHandler.removeCallbacksAndMessages(null)
        runCatching { simulationAlertFeedback.release() }
        binding.mapView.onDestroy()
        mapLibreMap = null
        super.onDestroy()
    }

    companion object {
        private const val STATE_INCOMING_GPX_HANDLED = "incoming_gpx_handled"
        private const val MAP_SOURCE_ID = "offline-map-source"
        private const val ONLINE_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val PREFS_DISPLAY = "display_settings"
        private const val PREF_KEEP_SCREEN_ON_NAVIGATION = "keep_screen_on_navigation"
        private const val PREF_MANUAL_START_KM = "manual_start_km"
        private const val PREFS_MAP = "map_settings"
        private const val PREF_MAP_MODE = "map_mode"
        private const val MAP_MODE_ONLINE = "online"
        private const val MAP_MODE_OFFLINE = "offline"
        private const val GPS_SOURCE_ID = "gps-position-source"
        private const val GPS_LAYER_ID = "gps-position-layer"
        private const val GPS_IMAGE_ID = "gps-navigation-arrow"
        private const val APPROACH_SOURCE_ID = "approach-route-source"
        private const val APPROACH_LAYER_ID = "approach-route-layer"
        private const val START_REACHED_DISTANCE_METERS = 40.0
        private const val APPROACH_REROUTE_THRESHOLD_METERS = 70.0
        private const val APPROACH_REROUTE_REQUIRED_FIXES = 2
        private const val APPROACH_REROUTE_COOLDOWN_MS = 8_000L
        private const val INITIAL_NAVIGATION_SCALE_METERS = 200.0
        private const val EDIT_WAYPOINT_KEEP_DISTANCE_METERS = 250.0
        private const val EDIT_ACTION_CUT = "cut"
        private const val EDIT_ACTION_ADD_START = "add_start"
        private const val EDIT_ACTION_ADD_END = "add_end"
        private const val EDIT_ACTION_ADD_VIA = "add_via"
        private const val EDIT_ACTION_MOVE_SELECT = "move_select"
        private const val EDIT_ACTION_MOVE_TARGET = "move_target"
        private const val MERGE_CONNECTION_WARNING_METERS = 250.0
        private const val REPEAT_CLOSURE_WARNING_METERS = 100.0
        private const val SIMULATION_SOURCE_ID = "gpx-simulation-source"
        private const val SIMULATION_LAYER_ID = "gpx-simulation-layer"
        private const val SIMULATION_TICK_MS = 250L
        private const val SIMULATION_BASE_SPEED_METERS_PER_SECOND = 16.6667
        private const val GPX_ROUTE_SOURCE_ID = "gpx-route-source"
        private const val GPX_ROUTE_LAYER_ID = "gpx-route-layer"
        private const val GPX_EDIT_SELECTION_SOURCE_ID = "gpx-edit-selection-source"
        private const val GPX_EDIT_SELECTION_LAYER_ID = "gpx-edit-selection-layer"
        private const val GPX_CREATE_SOURCE_ID = "gpx-create-source"
        private const val GPX_CREATE_LINE_LAYER_ID = "gpx-create-line-layer"
        private const val GPX_CREATE_POINTS_LAYER_ID = "gpx-create-points-layer"
        private const val SLOPE_COLOR_PROPERTY = "slopeColor"
        private const val SLOPE_HALF_WINDOW_METERS = 50.0
        private const val MIN_SLOPE_SAMPLE_METERS = 25.0
        private const val GPX_KILOMETERS_SOURCE_ID = "gpx-kilometers-source"
        private const val GPX_KILOMETERS_LABEL_LAYER_ID = "gpx-kilometers-label-layer"
        private const val KILOMETER_IMAGE_PROPERTY = "kilometerImage"
        private const val GPX_KILOMETER_IMAGE_PREFIX = "gpx-kilometer-"
        private const val GPX_ARROWS_SOURCE_ID = "gpx-arrows-source"
        private const val GPX_ARROWS_LAYER_ID = "gpx-arrows-layer"
        private const val GPX_ARROW_IMAGE_ID = "gpx-direction-arrow"
        private const val PREFS_ROUTE_APPEARANCE = "route_appearance"
        private const val GPX_START_SOURCE_ID = "gpx-start-source"
        private const val GPX_START_LAYER_ID = "gpx-start-layer"
        private const val GPX_FINISH_SOURCE_ID = "gpx-finish-source"
        private const val GPX_FINISH_LAYER_ID = "gpx-finish-layer"
        private const val GPX_FINISH_IMAGE_ID = "gpx-finish-flag"
        private const val GPX_ALERTS_SOURCE_ID = "gpx-alerts-source"
        private const val GPX_ALERTS_LAYER_ID = "gpx-alerts-layer"
        private const val GPX_ALERT_CONNECTORS_SOURCE_ID = "gpx-alert-connectors-source"
        private const val GPX_ALERT_CONNECTORS_LAYER_ID = "gpx-alert-connectors-layer"
        private const val GPX_ALERT_CONNECTORS_OUTLINE_LAYER_ID =
            "gpx-alert-connectors-outline-layer"
        private const val ALERT_MARKER_ICON_PROPERTY = "alertIcon"
        private const val ALERT_MARKER_OFFSET_METERS = 45.0
        private const val PREF_ACTIVE_GPX = "active_gpx_path"
        private const val PREFS_NAVIGATION = "navigation_settings"
        private const val PREF_NAVIGATION_ACTIVE = "navigation_active"
        private const val PREF_NAVIGATION_ROUTE_PATH = "navigation_route_path"
        private const val PREF_NAVIGATION_PROGRESS_METERS = "navigation_progress_meters"
        private const val PREF_NAVIGATION_MAIN_ROUTE_ACTIVE = "navigation_main_route_active"
        private const val DEFAULT_ALERT_DISTANCE = 1000
        private val ALERT_DISTANCE_OPTIONS = intArrayOf(250, 500, 1000, 1500, 2000, 3000)
        private val ROUTE_ARROW_SPACING = intArrayOf(100, 200, 300, 500, 750, 1000, 5000, 10000)
        private val ROUTE_COLORS = listOf(
            "#005BBB", "#E31B23", "#16A34A", "#FF8C00", "#7C3AED", "#111827", "#FFFFFF"
        )
        private val ALERT_TYPES = listOf(
            "alert_tv_distance" to "Traguardo Volante",
            "alert_gpm_distance" to "Gran Premio Montagna",
            "alert_gz_distance" to "Green Zone",
            "alert_refreshment_distance" to "Rifornimento",
            "alert_danger_distance" to "Pericolo",
            "alert_finish_distance" to "Arrivo"
        )
        private val MANUAL_ALERT_TYPES = listOf(
            RouteAlertType.TV,
            RouteAlertType.GPM,
            RouteAlertType.GREEN_ZONE,
            RouteAlertType.REFRESHMENT,
            RouteAlertType.DANGER
        )

        private val FRIULI_VENEZIA_GIULIA = LatLng(46.0711, 13.2346)
    }
}
