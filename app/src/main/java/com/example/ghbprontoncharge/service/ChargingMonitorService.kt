package com.example.ghbprontoncharge.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.ghbprontoncharge.R
import com.example.ghbprontoncharge.overlay.ProtonPackHybridView
import com.example.ghbprontoncharge.ui.ChargingActivity
import com.example.ghbprontoncharge.util.PreferencesManager

class ChargingMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "proton_pack_charging_channel"
        const val NOTIFICATION_ID = 1001
        const val FULLSCREEN_CHANNEL_ID = "proton_pack_fullscreen_channel"
        const val FULLSCREEN_NOTIFICATION_ID = 1002
        private const val TAG = "ProtonCharge"

        /** Vrai seulement si un cable est reellement branche (EXTRA_PLUGGED).
         *  On n'utilise PAS EXTRA_STATUS : a 100 % debranche il vaut FULL, et juste apres
         *  un debranchement il reste parfois CHARGING -> l'animation reapparaissait. */
        fun isPluggedIn(batteryIntent: Intent?): Boolean =
            (batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) > 0

        private var windowManager: WindowManager? = null
        private var overlayView: ProtonPackHybridView? = null
        private var hasPlayedStartupSound = false
        private var isCurrentlyPlugged = false

        fun startService(context: Context) {
            if (PreferencesManager.isServiceEnabled(context)) {
                val intent = Intent(context, ChargingMonitorService::class.java)
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stopService(context: Context) {
            removeOverlay()
            hasPlayedStartupSound = false
            isCurrentlyPlugged = false
            val intent = Intent(context, ChargingMonitorService::class.java)
            context.stopService(intent)
        }

        fun showOverlay(context: Context) {
            Handler(Looper.getMainLooper()).post {
                if (overlayView != null) {
                    Log.d(TAG, "showOverlay: Vue déjà affichée dans WindowManager, ignoré.")
                    return@post
                }

                // Ecran verrouille : Android cache les fenetres TYPE_APPLICATION_OVERLAY sous le
                // keyguard. Seule une notification plein ecran peut afficher l'activite par-dessus.
                val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                if (keyguard?.isKeyguardLocked == true) {
                    Log.d(TAG, "showOverlay: écran verrouillé -> notification plein écran")
                    postFullScreenNotification(context)
                    return@post
                }

                if (!Settings.canDrawOverlays(context)) {
                    Log.w(TAG, "showOverlay: Permission overlay non accordée, fallback vers Activity")
                    launchChargingActivityFallback(context)
                    return@post
                }

                val appContext = context.applicationContext
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    ?: return@post

                windowManager = wm

                @Suppress("DEPRECATION")
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                )

                val playSound = !hasPlayedStartupSound
                if (playSound) {
                    hasPlayedStartupSound = true
                }

                val view = ProtonPackHybridView(appContext, playStartupSound = playSound) {
                    removeOverlay()
                }

                try {
                    wm.addView(view, params)
                    overlayView = view
                    Log.d(TAG, "showOverlay: Vue ajoutée au WindowManager avec succès (playSound=$playSound)")
                } catch (e: Exception) {
                    Log.e(TAG, "showOverlay: Erreur lors de l'ajout au WindowManager", e)
                    launchChargingActivityFallback(context)
                }
            }
        }

        private fun postFullScreenNotification(context: Context) {
            val intent = Intent(context, ChargingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pending = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, FULLSCREEN_CHANNEL_ID)
                .setContentTitle("Proton Pack")
                .setContentText("Recharge en cours")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pending, true)
                .setContentIntent(pending)
                .setAutoCancel(true)
                // PAS de setSilent(true) : AndroidX le simule avec un groupe GROUP_ALERT_SUMMARY,
                // ce qui fait bloquer le full-screen intent par SystemUI. Le canal est deja muet.
                .build()
            try {
                context.getSystemService(NotificationManager::class.java)
                    .notify(FULLSCREEN_NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                Log.e(TAG, "postFullScreenNotification: notifications refusées", e)
            }
        }

        fun cancelFullScreenNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .cancel(FULLSCREEN_NOTIFICATION_ID)
        }

        fun removeOverlay() {
            Handler(Looper.getMainLooper()).post {
                overlayView?.let { view ->
                    try {
                        windowManager?.removeView(view)
                        Log.d(TAG, "removeOverlay: Vue retirée du WindowManager")
                    } catch (e: Exception) {
                        Log.e(TAG, "removeOverlay: Erreur lors du retrait de la vue", e)
                    }
                    view.releaseResources()
                }
                overlayView = null
                windowManager = null
            }
        }

        private fun launchChargingActivityFallback(context: Context) {
            val intent = Intent(context, ChargingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "launchChargingActivityFallback: Erreur au lancement de ChargingActivity", e)
            }
        }
    }

    private var powerStateReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate du service")
        createNotificationChannels()
        startForegroundWithNotification()
        registerPowerReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand du service")
        checkInitialChargingState()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkInitialChargingState() {
        val isCharging = isDeviceCharging(this)
        Log.d(TAG, "checkInitialChargingState: isCharging = $isCharging")
        // Garde : sans elle, chaque retour dans l'app (onResume -> startService) relancait
        // l'animation tant que le telephone etait branche, meme apres l'avoir fermee.
        if (isCharging && !isCurrentlyPlugged) {
            isCurrentlyPlugged = true
            if (PreferencesManager.isServiceEnabled(this)) {
                showOverlay(this)
            }
        }
    }

    private fun isDeviceCharging(context: Context): Boolean {
        val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return isPluggedIn(batteryStatus)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val lowPriorityChannel = NotificationChannel(
                CHANNEL_ID,
                "Proton Pack Charging Monitor",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Moniteur de recharge Proton Pack"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            manager.createNotificationChannel(lowPriorityChannel)

            // Canal haute importance requis pour que le full-screen intent ouvre l'activite
            val fullScreenChannel = NotificationChannel(
                FULLSCREEN_CHANNEL_ID,
                "Animation de branchement",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Affiche le Proton Pack sur l'écran verrouillé"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            manager.createNotificationChannel(fullScreenChannel)
        }
    }

    private fun startForegroundWithNotification() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Proton Pack")
            .setContentText("Animation de recharge en cours")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun registerPowerReceiver() {
        if (powerStateReceiver != null) return

        powerStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null || intent == null) return
                val action = intent.action
                Log.d(TAG, "Event reçu: $action")

                val isPlugged: Boolean = when (action) {
                    Intent.ACTION_POWER_CONNECTED -> true
                    Intent.ACTION_POWER_DISCONNECTED -> false
                    Intent.ACTION_BATTERY_CHANGED -> isPluggedIn(intent)
                    else -> return
                }

                if (isPlugged) {
                    if (!isCurrentlyPlugged) {
                        isCurrentlyPlugged = true
                        Log.d(TAG, "Branchement détecté via $action (isCurrentlyPlugged = true)")
                        if (PreferencesManager.isServiceEnabled(context)) {
                            showOverlay(context)
                        }
                    }
                } else {
                    if (isCurrentlyPlugged || action == Intent.ACTION_POWER_DISCONNECTED) {
                        isCurrentlyPlugged = false
                        hasPlayedStartupSound = false
                        Log.d(TAG, "Débranchement détecté via $action (isCurrentlyPlugged = false)")
                        removeOverlay()
                        cancelFullScreenNotification(context)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }

        ContextCompat.registerReceiver(
            this,
            powerStateReceiver!!,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy du service")
        powerStateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        powerStateReceiver = null
        hasPlayedStartupSound = false
        isCurrentlyPlugged = false
        removeOverlay()
    }
}
