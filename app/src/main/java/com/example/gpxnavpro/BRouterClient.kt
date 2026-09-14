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
import java.util.concurrent.Executors

class BRouterClient(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: IBRouterService? = null
    private var isBound = false
    private var pendingRequest: RoutingRequest? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IBRouterService.Stub.asInterface(binder)
            isBound = true
            pendingRequest?.also {
                pendingRequest = null
                execute(it)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
        }
    }

    fun calculateToStart(
        location: android.location.Location,
        destination: GpxPoint,
        callback: (Result<GpxRoute>) -> Unit
    ) {
        val request = RoutingRequest(
            latitudes = doubleArrayOf(location.latitude, destination.latitude),
            longitudes = doubleArrayOf(location.longitude, destination.longitude),
            routeName = "Avvicinamento alla partenza",
            callback = callback
        )
        submit(request)
    }

    fun calculateSegment(
        start: GpxPoint,
        via: GpxPoint,
        end: GpxPoint,
        callback: (Result<GpxRoute>) -> Unit
    ) {
        val request = RoutingRequest(
            latitudes = doubleArrayOf(start.latitude, via.latitude, end.latitude),
            longitudes = doubleArrayOf(start.longitude, via.longitude, end.longitude),
            routeName = "Tratto modificato",
            callback = callback
        )
        submit(request)
    }

    fun calculateRoute(
        points: List<GpxPoint>,
        routeName: String,
        callback: (Result<GpxRoute>) -> Unit
    ) {
        require(points.size >= 2) { "Servono almeno due punti" }
        submit(
            RoutingRequest(
                latitudes = points.map { it.latitude }.toDoubleArray(),
                longitudes = points.map { it.longitude }.toDoubleArray(),
                routeName = routeName,
                callback = callback
            )
        )
    }

    private fun submit(request: RoutingRequest) {
        if (service != null) {
            execute(request)
            return
        }

        pendingRequest = request
        val intent = Intent().setClassName(
            BROUTER_PACKAGE,
            BROUTER_SERVICE
        )
        val connected = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!connected) {
            pendingRequest = null
            request.callback(
                Result.failure(
                    IllegalStateException(
                        "BRouter non è installato o il servizio offline non è disponibile"
                    )
                )
            )
        }
    }

    private fun execute(request: RoutingRequest) {
        val routingService = service ?: run {
            request.callback(Result.failure(IllegalStateException("Servizio BRouter non connesso")))
            return
        }
        executor.execute {
            val result = runCatching {
                val params = Bundle().apply {
                    putDoubleArray(
                        "lats",
                        request.latitudes
                    )
                    putDoubleArray(
                        "lons",
                        request.longitudes
                    )
                    putString(
                        "lonlats",
                        request.longitudes.indices.joinToString("|") { index ->
                            "${request.longitudes[index]},${request.latitudes[index]}"
                        }
                    )
                    putString("profile", "car-fast")
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
            mainHandler.post { request.callback(result) }
        }
    }

    fun close() {
        if (isBound) {
            runCatching { context.unbindService(connection) }
        }
        isBound = false
        service = null
        executor.shutdownNow()
    }

    private data class RoutingRequest(
        val latitudes: DoubleArray,
        val longitudes: DoubleArray,
        val routeName: String,
        val callback: (Result<GpxRoute>) -> Unit
    )

    companion object {
        private const val BROUTER_PACKAGE = "btools.routingapp"
        private const val BROUTER_SERVICE = "btools.routingapp.BRouterService"
    }
}
