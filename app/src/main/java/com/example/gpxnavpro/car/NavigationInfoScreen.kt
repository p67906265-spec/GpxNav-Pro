package com.example.gpxnavpro.car

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.gpxnavpro.NavigationLocationService
import java.util.Locale

/**
 * Schermata unica: mostra km mancanti, km fatti, velocità e prossimo avviso.
 * Si aggiorna da sola ogni 2 secondi, ma solo se il testo è cambiato.
 */
class NavigationInfoScreen(carContext: CarContext) : Screen(carContext) {

    private val handler = Handler(Looper.getMainLooper())
    private var lastShown: String? = null
    private val mapRenderer = CarMapRenderer(carContext)

    private val ticker = object : Runnable {
        override fun run() {
            val now = buildLines().joinToString("|")
            if (now != lastShown) invalidate()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        // La mappa con la traccia viene disegnata sulla superficie dell'auto.
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(mapRenderer)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                handler.post(ticker)
            }

            override fun onStop(owner: LifecycleOwner) {
                handler.removeCallbacks(ticker)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                handler.removeCallbacks(ticker)
                runCatching { carContext.getCarService(AppManager::class.java).setSurfaceCallback(null) }
                mapRenderer.destroy()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val lines = buildLines()
        lastShown = lines.joinToString("|")

        val info = MessageInfo.Builder(lines[0])
            .setText(lines.drop(1).joinToString("\n"))
            .build()

        val actionTitle = if (NavigationLocationService.isRunning) "STOP" else "AGGIORNA"
        val action = Action.Builder()
            .setTitle(actionTitle)
            .setOnClickListener {
                if (NavigationLocationService.isRunning) {
                    NavigationLocationService.requestStop(carContext)
                } else {
                    invalidate()
                }
            }
            .build()

        return NavigationTemplate.Builder()
            .setNavigationInfo(info)
            .setActionStrip(ActionStrip.Builder().addAction(action).build())
            .setBackgroundColor(CarColor.createCustom(BACKGROUND, BACKGROUND))
            .build()
    }

    /** Riga 0 = titolo grande, le altre = testo. */
    private fun buildLines(): List<String> {
        if (!NavigationLocationService.isRunning) {
            return listOf("GPX NAV", "Avvia la navigazione dal telefono")
        }
        val fix = NavigationLocationService.lastNavigationFix
            ?: return listOf("GPX NAV", "In attesa della posizione o della partenza…")

        val remainingKm = String.format(Locale.ITALY, "%.1f", fix.remainingMeters / 1000.0)
        val doneKm = String.format(Locale.ITALY, "%.1f", fix.progressMeters / 1000.0)
        val location = NavigationLocationService.lastLocation
        val speed = if (location != null && location.hasSpeed()) {
            Math.round(location.speed * 3.6f).toString()
        } else {
            "0"
        }

        val lines = mutableListOf("FINE $remainingKm km", "INIZIO $doneKm km · $speed km/h")

        if (fix.isOffRoute) {
            lines += "FUORI TRACCIA"
        }
        NavigationLocationService.lastActiveAlert?.let { active ->
            val meters = active.distanceMeters.coerceAtLeast(0.0)
            val distance = if (meters >= 1000.0) {
                String.format(Locale.ITALY, "%.1f km", meters / 1000.0)
            } else {
                "${(Math.round(meters / 10.0) * 10)} m"
            }
            lines += "${active.alert.type.shortLabel} tra $distance"
        }
        return lines
    }

    private companion object {
        const val REFRESH_MS = 2000L
        val BACKGROUND: Int = Color.parseColor("#0B1B3A")
    }
}
