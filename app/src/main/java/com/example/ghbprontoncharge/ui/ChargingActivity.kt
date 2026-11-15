package com.example.ghbprontoncharge.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.ghbprontoncharge.overlay.ProtonPackHybridView

class ChargingActivity : ComponentActivity() {

    private var overlayView: ProtonPackHybridView? = null
    private var powerDisconnectedReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Lockscreen display flags (BEFORE setContentView)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. Fullscreen immersive setup
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // 3. Set content view with ProtonPackHybridView
        val view = ProtonPackHybridView(this) {
            finish()
        }
        overlayView = view
        setContentView(view)

        // 4. Register receiver for ACTION_POWER_DISCONNECTED
        registerPowerDisconnectedReceiver()
    }

    private fun registerPowerDisconnectedReceiver() {
        powerDisconnectedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_POWER_DISCONNECTED) {
                    finish()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_POWER_DISCONNECTED)
        ContextCompat.registerReceiver(
            this,
            powerDisconnectedReceiver!!,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        powerDisconnectedReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        powerDisconnectedReceiver = null

        com.example.ghbprontoncharge.service.ChargingMonitorService.cancelFullScreenNotification(this)
        overlayView?.releaseResources()
        overlayView = null
    }
}
