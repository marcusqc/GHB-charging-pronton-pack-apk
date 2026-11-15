package com.example.ghbprontoncharge.service

import android.service.dreams.DreamService
import com.example.ghbprontoncharge.overlay.ProtonPackHybridView

class ProtonPackDreamService : DreamService() {

    private var dreamView: ProtonPackHybridView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // Configuration pour affichage plein écran et fermeture immédiate au toucher
        isInteractive = false
        isFullscreen = true

        val view = ProtonPackHybridView(this) {
            finish()
        }
        dreamView = view
        setContentView(view)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dreamView?.releaseResources()
        dreamView = null
    }
}
