package com.example.ghbprontoncharge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ghbprontoncharge.service.ChargingMonitorService
import com.example.ghbprontoncharge.util.PreferencesManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || context == null) return

        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (PreferencesManager.isServiceEnabled(context)) {
                ChargingMonitorService.startService(context)
            }
        }
    }
}
