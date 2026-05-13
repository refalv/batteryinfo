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
import android.os.PowerManager
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
    private val ACTION_BATTERY_STATUS = "BATTERY_STATUS_EVENT"
    private val ACTION_REQUEST_STATUS = "REQUEST_STATUS"

    private lateinit var dbHelper: LogDBHelper
    private val dbExecutor = Executors.newSingleThreadExecutor()
    

    // Data baterai terakhir
    private var lastBatteryPercent = 0
    private var lastChargingState = "Unknown"
    private var lastTemp = 0.0
    private var lastVolt = 0

    private var isFirstRun = true
    
    private val task = PeriodicTask({ 
        updateNotification() 
    }, 1_000L)

    // Cache untuk bitmap ikon dinamis (mencegah alokasi berulang)
    private val iconCache = mutableMapOf<String, Bitmap>()

    override fun onCreate() {
        super.onCreate()
        dbHelper = LogDBHelper(this)

        // Receiver untuk request status dari UI
        val filterStatus = IntentFilter(ACTION_REQUEST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(requestStatusReceiver, filterStatus, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(requestStatusReceiver, filterStatus)
        }

        // Receiver untuk perubahan baterai (selalu aktif)
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, batteryFilter)

        // Receiver untuk layar menyala/mati (hemat baterai)
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, screenFilter)

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BatteryService", "onStartCommand()")
        // Tampilkan notifikasi awal (data masih 0, akan segera diperbarui oleh receiver)
        try {
            startForeground(notificationId, createNotification(0, "Init...", 0.0, 0))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        task.start()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(batteryReceiver)
            unregisterReceiver(requestStatusReceiver)
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        dbExecutor.shutdown()
        task.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun renderIcon(value: String, bot: String): IconCompat? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

    iconCache["$value|$bot"]?.let { return IconCompat.createWithBitmap(it) }

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
    canvas.drawText("$temp°", w / 2f, y2, paint)  // pakai temp (sudah int bersih)

    iconCache["$value|$bot"] = bitmap
    return IconCompat.createWithBitmap(bitmap)
}

    // ================= NOTIFICATION =================

    private fun createNotification(percent: Int, charging: String, temp: Double, volt: Int): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Battery Monitor Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Monitoring battery state"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)//.apply {
              // flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
          // }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            // PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val dynamicIcon = renderIcon("$percent","${temp.toInt()}°")

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Battery: $percent%")
            .setContentText("$charging | ${temp}°C | ${volt}mV")
            .setContentIntent(pendingIntent)
            // .setOnlyAlertOnce(true)
            // .setOngoing(true)
            // .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // .setPriority(NotificationCompat.PRIORITY_HIGH)
            // .setShowWhen(true)

        if (dynamicIcon != null) {
            builder.setSmallIcon(dynamicIcon)
        } else {
            builder.setSmallIcon(android.R.drawable.ic_lock_idle_charging)
        }

        return builder.build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId, createNotification(lastBatteryPercent, lastChargingState, lastTemp, lastVolt))
    }

    // ================= BROADCAST RECEIVERS =================

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

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d("BatteryService", "Screen OFF")
                    task.stop()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d("BatteryService", "Screen ON")
                    updateNotification()   // refresh segera
                    task.start()
                }
            }
        }
    }

    // ================= LOGIKA PEMROSESAN BATERAI =================

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

        // Cek perubahan
        val isStateChanged = chargingState != lastChargingState
        val isPercentChanged = percent != lastBatteryPercent
        val isTempChanged = kotlin.math.abs(temp - lastTemp) >= 0.5
        val isVoltChanged = kotlin.math.abs(volt - lastVolt) >= 50

        // Update nilai terbaru
        val oldPercent = lastBatteryPercent
        val oldState = lastChargingState
        lastBatteryPercent = percent
        lastChargingState = chargingState
        lastTemp = temp
        lastVolt = volt

        // Jika ada perubahan signifikan, update notifikasi dan log
        if (isStateChanged || isPercentChanged || isTempChanged || isVoltChanged) {
            updateNotification()

            // val time = timeFormat.get().format(Date())
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (!isFirstRun) {
                if (isStateChanged) {
                    val icon = if (chargingState == "Charging") "🔌" else "🔋"
                    val event = if (chargingState == "Charging") "Connected" else "Disconnected"
                    saveAndBroadcastLog("$time | ${String.format("%3d", percent)}% | ${temp}°C | $icon $event")
                }
                if (isPercentChanged) {
                    val statusIcon = when (chargingState) {
                        "Charging" -> "⚡"
                        "Full" -> "✅"
                        "Discharging" -> "🔻"
                        else -> "•"
                    }
                    saveAndBroadcastLog("$time | ${String.format("%3d", percent)}% | ${temp}°C | $statusIcon $chargingState")
                }
            } else {
                isFirstRun = false
                saveAndBroadcastLog("$time | ${String.format("%3d", percent)}% | ${temp}°C | 🚀 Service Start")
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
        dbExecutor.execute {
            dbHelper.addLog(msg)
        }
        val intent = Intent(ACTION_BATTERY_LOG).apply {
            setPackage(packageName)
            putExtra("log", msg)
        }
        sendBroadcast(intent)
    }
}