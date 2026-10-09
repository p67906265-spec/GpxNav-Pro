package com.example.gpxnavpro

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import btools.routingapp.IBRouterService
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class BRouterClient(context: Context) {

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestIds = AtomicLong(0L)
    private val pendingRequests = ArrayDeque<RoutingRequest>()
    private val outstanding = ConcurrentHashMap<Long, RoutingRequest>()

    @Volatile private var service: IBRouterService? = null
    @Volatile private var connected = false
    @Volatile private var bindRegistered = false
    @Volatile private var bindingInProgress = false
    @Volatile private var closed = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed) return
            service = IBRouterService.Stub.asInterface(binder)
            connected = true
            bindingInProgress = false
            drainPending()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            connected = false
            bindingInProgress = false
            failOutstanding("Connessione a BRouter interrotta")
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            connected = false
            bindingInProgress = false
            failOutstanding("Servizio BRouter terminato")
        }

        override fun onNullBinding(name: ComponentName?) {
            service = null
            connected = false
            bindingInProgress = false
            failOutstanding("BRouter non ha fornito il servizio di routing")
        }
    }

    fun calculateToStart(
        location: android.location.Location,
        destination: GpxPoint,
        callback: (Result<GpxRoute>) -> Unit
    ) {
        submit(
            RoutingRequest(
                id = requestIds.incrementAndGet(),
                latitudes = doubleArrayOf(location.latitude, destination.latitude),
                longitudes = doubleArrayOf(location.longitude, destination.longitude),
                routeName = "Avvicinamento alla partenza",
                callback = callback
            )
        )
    }

    fun calculateSegment(
        start: GpxPoint,
        via: GpxPoint,
        end: GpxPoint,
        callback: (Result<GpxRoute>) -> Unit
    ) {
        submit(
            RoutingRequest(
                id = requestIds.incrementAndGet(),
                latitudes = doubleArrayOf(start.latitude, via.latitude, end.latitude),
                longitudes = doubleArrayOf(start.longitude, via.longitude, end.longitude),
                routeName = "Tratto modificato",
                callback = callback
            )
        )
    }

    fun calculateRoute(
        points: List<GpxPoint>,
        routeName: String,
        callback: (Result<GpxRoute>) -> Unit
    ) {
        require(points.size >= 2) { "Servono almeno due punti" }
        submit(
            RoutingRequest(
                id = requestIds.incrementAndGet(),
                latitudes = points.map { it.latitude }.toDoubleArray(),
                longitudes = points.map { it.longitude }.toDoubleArray(),
                routeName = routeName,
                callback = callback
            )
        )
    }

    @Synchronized
    private fun submit(request: RoutingRequest) {
        if (closed) return

        outstanding[request.id] = request
        scheduleTimeout(request)

        if (service != null && connected) {
            execute(request)
            return
        }

        pendingRequests.addLast(request)
        ensureBound()
    }

    @Synchronized
    private fun ensureBound() {
        if (closed || connected || bindingInProgress) return

        val intent = Intent().setClassName(BROUTER_PACKAGE, BROUTER_SERVICE)
        bindingInProgress = true
        val ok = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)

        if (ok) {
            bindRegistered = true
        } else {
            bindingInProgress = false
            failOutstanding("BRouter non è installato o il servizio offline non è disponibile")
        }
    }

    @Synchronized
    private fun drainPending() {
        while (!closed && connected && pendingRequests.isNotEmpty()) {
            val request = pendingRequests.removeFirst()
            if (!request.completed.get()) execute(request)
        }
    }

    private fun execute(request: RoutingRequest) {
        val routingService = service
        if (routingService == null || !connected) {
            complete(request, Result.failure(IllegalStateException("Servizio BRouter non connesso")))
            return
        }

        executor.execute {
            if (closed || request.completed.get()) return@execute

            val result = runCatching {
                val params = Bundle().apply {
                    putDoubleArray("lats", request.latitudes)
                    putDoubleArray("lons", request.longitudes)
                    putString(
                        "lonlats",
                        request.longitudes.indices.joinToString("|") { index ->
                            "${request.longitudes[index]},${request.latitudes[index]}"
                        }
                    )
                    putString("profile", selectedProfile())
                    putString("alternativeidx", "0")
                    putString("trackFormat", "gpx")
                    putString("turnInstructionFormat", "osmand")
                    putString("timode", "3")
                    putString("maxRunningTime", "60")
                }
                val response = routingService.getTrackFromParams(params)
                    ?: error("BRouter non ha restituito un percorso")
                require(response.trimStart().startsWith("<")) { response }
                response.byteInputStream().buffered().use {
                    GpxParser.parse(it, request.routeName)
                }
            }
            complete(request, result)
        }
    }

    private fun selectedProfile(): String =
        appContext.getSharedPreferences(PREFS_ROUTING, Context.MODE_PRIVATE)
            .getString(PREF_BROUTER_PROFILE, DEFAULT_BROUTER_PROFILE)
            ?.takeIf { it in SUPPORTED_PROFILES }
            ?: DEFAULT_BROUTER_PROFILE

    private fun scheduleTimeout(request: RoutingRequest) {
        val timeout = Runnable {
            complete(
                request,
                Result.failure(IllegalStateException("BRouter non ha risposto entro il tempo massimo"))
            )
        }
        request.timeout = timeout
        mainHandler.postDelayed(timeout, REQUEST_TIMEOUT_MS)
    }

    private fun complete(request: RoutingRequest, result: Result<GpxRoute>) {
        if (!request.completed.compareAndSet(false, true)) return
        request.timeout?.let(mainHandler::removeCallbacks)
        outstanding.remove(request.id)
        synchronized(this) { pendingRequests.remove(request) }

        if (closed) return
        mainHandler.post {
            if (!closed) request.callback(result)
        }
    }

    private fun failOutstanding(message: String) {
        val snapshot = outstanding.values.toList()
        snapshot.forEach { request ->
            complete(request, Result.failure(IllegalStateException(message)))
        }
        synchronized(this) { pendingRequests.clear() }
    }

    fun close() {
        if (closed) return
        closed = true

        outstanding.values.forEach { request ->
            request.completed.set(true)
            request.timeout?.let(mainHandler::removeCallbacks)
        }
        outstanding.clear()
        synchronized(this) { pendingRequests.clear() }

        // Anche dopo onServiceDisconnected la bind rimane registrata finché
        // il client non esegue esplicitamente unbindService().
        if (bindRegistered) {
            runCatching { appContext.unbindService(connection) }
        }
        bindRegistered = false
        bindingInProgress = false
        connected = false
        service = null
        executor.shutdownNow()
    }

    private data class RoutingRequest(
        val id: Long,
        val latitudes: DoubleArray,
        val longitudes: DoubleArray,
        val routeName: String,
        val callback: (Result<GpxRoute>) -> Unit,
        val completed: AtomicBoolean = AtomicBoolean(false),
        @Volatile var timeout: Runnable? = null
    )

    companion object {
        private const val BROUTER_PACKAGE = "btools.routingapp"
        private const val BROUTER_SERVICE = "btools.routingapp.BRouterService"
        private const val REQUEST_TIMEOUT_MS = 75_000L
        const val PREFS_ROUTING = "routing_settings"
        const val PREF_BROUTER_PROFILE = "brouter_profile"
        const val DEFAULT_BROUTER_PROFILE = "car-fast"
        val SUPPORTED_PROFILES = setOf("car-fast", "trekking", "fastbike")
    }
}
