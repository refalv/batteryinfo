package com.example.batteryinfo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import java.text.SimpleDateFormat
import java.util.*

class BatteryService : Service() {

    private val channelId = "battery_monitor"
    private val notificationId = 1

    private val ACTION_BATTERY_LOG = "BATTERY_LOG_EVENT"
    private val ACTION_BATTERY_STATUS = "BATTERY_STATUS_EVENT"
    private val ACTION_REQUEST_STATUS = "REQUEST_STATUS"

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    private lateinit var dbHelper: LogDBHelper

    private var lastBatteryPercent = -1
    private var lastChargingState = "Unknown"
    private var lastTemp = 0.0
    private var lastVolt = 0

    private var isFirstRun = true

    // Timer 1.25 Detik (Menggunakan Class PeriodicTask dari file sebelah)
    private val task = PeriodicTask({ 
        updateNotification() 
    }, 1_250L)

    override fun onCreate() {
        super.onCreate()
        dbHelper = LogDBHelper(this)

        val filterStatus = IntentFilter(ACTION_REQUEST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(requestStatusReceiver, filterStatus, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(requestStatusReceiver, filterStatus)
        }

        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, batteryFilter)

        // 🔥 FITUR HEMAT BATERAI: Deteksi Layar Mati/Nyala
        // Agar Timer berhenti saat HP dikunci (Deep Sleep aman)
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, screenFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BatteryService", "onStartCommand()")
        super.onStartCommand(intent, flags, startId)

        try {
            startForeground(notificationId, createNotification(0, "Init..."))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Jalankan Timer saat service mulai
        task.start()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(batteryReceiver)
            unregisterReceiver(requestStatusReceiver)
            // 🔥 Jangan lupa unregister screen receiver juga
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Matikan timer saat service hancur
        task.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ================= 📺 SCREEN RECEIVER (LOGIKA HEMAT BATERAI) =================
    
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                // Layar Mati -> Stop Timer (Agar Foreground Usage tidak 90%)
                Log.d("BatteryService", "Screen OFF: Pausing Timer")
                task.stop()
            } else if (intent.action == Intent.ACTION_SCREEN_ON) {
                // Layar Nyala -> Refresh sekali & Jalankan Timer Lagi
                Log.d("BatteryService", "Screen ON: Resuming Timer")
                updateNotification()
                task.start()
            }
        }
    }

    // ================= 🎨 FITUR IKON ANGKA BERWARNA =================
    
    private fun renderIcon(value: String): IconCompat? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

        val density = resources.displayMetrics.density
        val w = (24 * density).toInt() 
        val bitmap = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888) 
        val canvas = Canvas(bitmap)

        val paint = Paint()
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.isAntiAlias = true
        
        val percent = value.toIntOrNull() ?: 0
        paint.color = when {
            percent <= 20 -> Color.parseColor("#FF5252") 
            percent <= 50 -> Color.parseColor("#FFD740") 
            percent <= 90 -> Color.parseColor("#69F0AE") 
            else -> Color.parseColor("#33B5E5")          
        }

        if (value.length >= 3) {
            paint.textSize = 15f * density 
        } else {
            paint.textSize = 20f * density
        }

        canvas.drawText(value, w / 2f, w / 1.7f + (3 * density), paint)

        return IconCompat.createWithBitmap(bitmap)
    }

    // ================= NOTIFICATION =================

    private fun createNotification(percent: Int, charging: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Battery Monitor Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Monitoring battery state"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val dynamicIcon = renderIcon(percent.toString())

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Battery Level: $percent%")
            .setContentText("Status: $charging")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setShowWhen(false)

        if (dynamicIcon != null) {
            builder.setSmallIcon(dynamicIcon)
        } else {
            builder.setSmallIcon(android.R.drawable.ic_lock_idle_charging)
        }

        return builder.build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId, createNotification(lastBatteryPercent, lastChargingState))
    }

    // ================= RECEIVERS =================

    private val requestStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            sendStatus()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { handleBatteryIntent(it) }
        }
    }

    private fun handleBatteryIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (scale <= 0) return

        val percent = (level * 100) / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        
        val chargingState = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
            else -> "Unknown"
        }

        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val volt = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)

        val isStateChanged = chargingState != lastChargingState
        val isPercentChanged = percent != lastBatteryPercent
        
        lastTemp = temp
        lastVolt = volt

        if (isStateChanged || isPercentChanged) {
            val oldState = lastChargingState
            
            lastBatteryPercent = percent
            lastChargingState = chargingState
            
            updateNotification()

            val time = timeFormat.format(Date())
            val percent = String.format("%3d", percent)

            if (!isFirstRun) {
                if (isStateChanged) {
                    val icon = if (chargingState == "Charging") "🔌" else "🔋"
                    val event = if (chargingState == "Charging") "Connected" else "Disconnected"
                    saveAndBroadcastLog("$time | $percent% | $temp°C | $icon $event")
                }

                if (isPercentChanged) {
                    val statusIcon = when(chargingState) {
                        "Charging" -> "⚡"
                        "Full" -> "✅"
                        "Discharging" -> "🔻"
                        else -> "•"
                    }
                    saveAndBroadcastLog("$time | $percent% | $temp°C | $statusIcon $chargingState")
                }
            } else {
                isFirstRun = false
                saveAndBroadcastLog("$time | $percent% | $temp°C | 🚀 Service Start")
            }
            
            sendStatus()
        }
    }

    private fun sendStatus() {
        val intent = Intent(ACTION_BATTERY_STATUS).apply {
            setPackage(packageName)
            putExtra("percent", lastBatteryPercent)
            putExtra("charging", lastChargingState)
            putExtra("temp", lastTemp)
            putExtra("volt", lastVolt)
        }
        sendBroadcast(intent)
    }

    private fun saveAndBroadcastLog(msg: String) {
        dbHelper.addLog(msg)
        val intent = Intent(ACTION_BATTERY_LOG).apply {
            setPackage(packageName)
            putExtra("log", msg)
        }
        sendBroadcast(intent)
    }
}
