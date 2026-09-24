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
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class BatteryService : Service() {

    private val channelId = "battery_monitor"
    private val notificationId = 1

    private val ACTION_BATTERY_LOG = "BATTERY_LOG_EVENT"

    private lateinit var dbHelper: LogDBHelper
    private val dbExecutor = Executors.newSingleThreadExecutor()
    

    // Data baterai terakhir
    private var lastBatteryPercent = 0
    private var lastChargingState = "Unknown"
    private var lastTemp = 0.0
    private var lastVolt = 0

    private var isServiceStarted = false
    private var channelCreated = false
    private var isReceiverRegistered = false

    // Cache icon untuk hindari Bitmap allocation berulang
    private var cachedIcon: IconCompat? = null
    private var cachedIconKey = ""

    // Cache notification — hanya rebuild kalau data berubah
    private var cachedNotification: Notification? = null
    private var cachedNotifKey = ""

    // Cache SimpleDateFormat untuk hindari object creation berulang
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Polling untuk realtime update notifikasi
    private val POLLING_INTERVAL = 1500L
    private val pollingHandler = Handler(Looper.getMainLooper())
    private val pollingRunnable = object : Runnable {
        override fun run() {
            updateFromSticky()
            pollingHandler.postDelayed(this, POLLING_INTERVAL)
        }
    }

    // Safety: notifikasi di-refresh saat layar dinyalakan

    override fun onCreate() {
        super.onCreate()
        dbHelper = LogDBHelper(this)

        // Safe: unregister dulu kalau service restart dari kill (tanpa onDestroy)
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver)
                unregisterReceiver(screenReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }

        // Receiver untuk perubahan baterai (selalu aktif)
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, batteryFilter)

        // Receiver untuk screen on/off (hemat baterai saat layar mati)
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, screenFilter)
        }
        isReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BatteryService", "onStartCommand() isServiceStarted=$isServiceStarted")

        // Ambil data baterai terkini LANGSUNG dari sticky broadcast
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            parseBatteryIntent(batteryIntent)
        }

        // Notifikasi langsung pakai data real dari sticky broadcast
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    createNotification(lastBatteryPercent, lastChargingState, lastTemp, lastVolt),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(notificationId, createNotification(lastBatteryPercent, lastChargingState, lastTemp, lastVolt))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Log "Service Start" LANGSUNG di sini, tidak menunggu receiver
        if (!isServiceStarted) {
            val time = timeFormat.format(Date())
            saveAndBroadcastLog("$time | ${String.format("%3d", lastBatteryPercent)}% | ${lastTemp}°C | 🚀 Service Start")
        }

        isServiceStarted = true
        startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceStarted = false
        isReceiverRegistered = false
        stopPolling()
        try {
            unregisterReceiver(batteryReceiver)
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        dbExecutor.shutdown()
        try {
            dbHelper.close()
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Dipanggil saat user swipe notification / hapus dari recent tasks
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("BatteryService", "onTaskRemoved() → scheduling restart")

        // Jadwalkan restart service segera
        val restartIntent = Intent(applicationContext, BatteryService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        // Pakai setExactAndAllowWhileIdle agar jalan di Doze mode (Android 6+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 500,
                pendingIntent
            )
        } else {
            alarmManager.set(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 500,
                pendingIntent
            )
        }
    }

    private fun renderIcon(value: String, bot: String): IconCompat? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

        val density = resources.displayMetrics.density
        val w = (28 * density).toInt()
        val bitmap = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint().apply {
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.FILL
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        // ── Baris atas: persen ──
        val percent = value.toIntOrNull() ?: 0
        paint.color = when {
            percent <= 20 -> Color.parseColor("#FF5252")
            percent <= 50 -> Color.parseColor("#FFD740")
            percent <= 90 -> Color.parseColor("#69F0AE")
            else          -> Color.parseColor("#33B5E5")
        }
        paint.textSize = if (value.length >= 3) 15f * density else 22f * density

        val fm1 = paint.fontMetrics
        val y1 = w * 0.3f - (fm1.ascent + fm1.descent) / 2f
        canvas.drawText(value, w / 2.2f, y1, paint)

        // ── Baris bawah: suhu ──
        val temp = bot.replace("°", "").toIntOrNull() ?: 0
        paint.color = if (temp <= 40) Color.GREEN else Color.RED
        paint.textSize = 14f * density

        val fm2 = paint.fontMetrics
        val y2 = w * 0.8f - (fm2.ascent + fm2.descent) / 2f
        canvas.drawText("$temp°", w / 2f, y2, paint)

        return IconCompat.createWithBitmap(bitmap)
    }

    private fun getCachedIcon(value: String, bot: String): IconCompat? {
        val key = "$value|$bot"
        if (key != cachedIconKey) {
            try {
                cachedIcon?.bitmap?.recycle()
            } catch (_: Exception) {}
            cachedIcon = renderIcon(value, bot)
            cachedIconKey = key
        }
        return cachedIcon
    }

    

    // ================= NOTIFICATION =================

    private fun createNotification(percent: Int, charging: String, temp: Double, volt: Int): Notification {
        // Cache: skip rebuild kalau data sama
        val key = "$percent|$charging|$temp|$volt"
        if (key == cachedNotifKey && cachedNotification != null) {
            return cachedNotification!!
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !channelCreated) {
            val channel = NotificationChannel(
                channelId,
                "Battery Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitoring battery state"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            channelCreated = true
        }

        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Battery: $percent%")
            .setContentText("$charging | ${temp}°C | ${volt}mV")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        val dynamicIcon = getCachedIcon("$percent","${temp.toInt()}°")

        if (dynamicIcon != null) {
            builder.setSmallIcon(dynamicIcon)
        } else {
            builder.setSmallIcon(android.R.drawable.ic_lock_idle_charging)
        }

        val notification = builder.build()
        cachedNotification = notification
        cachedNotifKey = key
        return notification
    }

    // Update notifikasi — pakai startForeground() agar selalu re-promote
    // nm.notify() tidak cukup karena Android suppress notif yang di-swipe
    private fun updateNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    createNotification(lastBatteryPercent, lastChargingState, lastTemp, lastVolt),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(notificationId, createNotification(lastBatteryPercent, lastChargingState, lastTemp, lastVolt))
            }
        } catch (e: Exception) {
            // Fallback ke nm.notify() kalau startForeground gagal
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notificationId, createNotification(lastBatteryPercent, lastChargingState, lastTemp, lastVolt))
        }
    }

    // ================= BROADCAST RECEIVERS =================

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { handleBatteryIntent(it) }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d("BatteryService", "Screen OFF → stop polling")
                    stopPolling()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d("BatteryService", "Screen ON → start polling")
                    startPolling()
                }
            }
        }
    }

    // ================= POLLING (REALTIME UPDATE) =================

    private fun startPolling() {
        stopPolling() // cegah double start
        pollingHandler.post(pollingRunnable)
        Log.d("BatteryService", "Polling started")
    }

    private fun stopPolling() {
        pollingHandler.removeCallbacks(pollingRunnable)
        Log.d("BatteryService", "Polling stopped")
    }

    // Re-promote notification setiap tick
    // Baca sticky broadcast → parse → buat notif
    // TAPI jangan update last* (hanya batteryReceiver yang update last*)
    private fun updateFromSticky() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return

        // Simpan last* sebelum parse
        val savedPercent = lastBatteryPercent
        val savedState = lastChargingState
        val savedTemp = lastTemp
        val savedVolt = lastVolt

        // Parse (ini update last*)
        if (!parseBatteryIntent(intent)) return

        // Ambil data terbaru untuk notifikasi
        val percent = lastBatteryPercent
        val charging = lastChargingState
        val temp = lastTemp
        val volt = lastVolt

        // Restore last* ke nilai sebelumnya (jangan ganggu log tracking)
        lastBatteryPercent = savedPercent
        lastChargingState = savedState
        lastTemp = savedTemp
        lastVolt = savedVolt

        // Update notifikasi dengan data terbaru dari sticky
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    createNotification(percent, charging, temp, volt),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(notificationId, createNotification(percent, charging, temp, volt))
            }
        } catch (e: Exception) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notificationId, createNotification(percent, charging, temp, volt))
        }
    }

    // ================= PARSING BATERAI (SHARED) =================

    // Parse battery intent → update last* variables
    // Return true kalau ada data valid
    private fun parseBatteryIntent(intent: Intent): Boolean {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (scale <= 0) return false

        lastBatteryPercent = (level * 100) / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        lastChargingState = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
            else -> "Unknown"
        }
        lastTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        lastVolt = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        return true
    }

    // ================= LOGIKA PEMROSESAN BATERAI =================

    private fun handleBatteryIntent(intent: Intent) {
        // Simpan nilai lama SEBELUM parse (parse akan mengubah last*)
        val oldState = lastChargingState
        val oldPercent = lastBatteryPercent

        if (!parseBatteryIntent(intent)) return

        // Bandingkan lama vs baru
        val isStateChanged = oldState != lastChargingState
        val isPercentChanged = oldPercent != lastBatteryPercent

        // Update notifikasi kalau ada perubahan
        if (isStateChanged || isPercentChanged) {
            updateNotification()
        }

        // Log hanya untuk perubahan signifikan
        val time = timeFormat.format(Date())
        if (isStateChanged) {
            val icon = if (lastChargingState == "Charging") "🔌" else "🔋"
            val event = if (lastChargingState == "Charging") "Connected" else "Disconnected"
            saveAndBroadcastLog("$time | ${String.format("%3d", lastBatteryPercent)}% | ${lastTemp}°C | $icon $event")
        }
        if (isPercentChanged) {
            val statusIcon = when (lastChargingState) {
                "Charging" -> "⚡"
                "Full" -> "✅"
                "Discharging" -> "🔻"
                else -> "•"
            }
            saveAndBroadcastLog("$time | ${String.format("%3d", lastBatteryPercent)}% | ${lastTemp}°C | $statusIcon $lastChargingState")
        }
    }

    private fun saveAndBroadcastLog(msg: String) {
        dbExecutor.execute {
            dbHelper.addLog(msg)
            val intent = Intent(ACTION_BATTERY_LOG).apply {
                setPackage(packageName)
                putExtra("log", msg)
            }
            sendBroadcast(intent)
        }
    }
}