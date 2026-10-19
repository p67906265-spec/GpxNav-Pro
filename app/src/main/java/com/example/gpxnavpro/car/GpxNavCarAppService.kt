package com.example.gpxnavpro.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Punto di ingresso di GPX NAV su Android Auto (categoria navigazione).
 *
 * Fase 1: pannello informativo (km, avvisi, velocità) che legge lo stato del servizio di
 * navigazione già esistente. La navigazione si avvia dal telefono.
 */
class GpxNavCarAppService : CarAppService() {

    // App installata a mano (non dal Play Store) per uso personale: si accetta qualsiasi host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = GpxNavCarSession()
}

class GpxNavCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = NavigationInfoScreen(carContext)
}
