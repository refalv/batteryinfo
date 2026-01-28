package com.example.batteryinfo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Cek action: Boot Normal atau Quick Boot (Beberapa HP China/Lama)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            Log.d("BootReceiver", "Boot detected! Starting BatteryService...")

            val serviceIntent = Intent(context, BatteryService::class.java)
            
            // Start Service sesuai versi Android
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8+ wajib pakai startForegroundService di background
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
