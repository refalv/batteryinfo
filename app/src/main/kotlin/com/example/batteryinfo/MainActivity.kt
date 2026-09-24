package com.example.batteryinfo

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    // Komponen UI
    private lateinit var txtLevel: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtTemp: TextView
    private lateinit var txtVolt: TextView
    private lateinit var txtLog: TextView
    private lateinit var btnClearLogs: Button
    private lateinit var scrollViewLog: ScrollView

    private lateinit var dbHelper: LogDBHelper
    private val dbExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var isLogLoading = false

    // 1. Receiver LOG (Menangani log baru saat aplikasi terbuka)
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadLogsDirectly()
        }
    }

    // 2. Receiver STATUS (Real-time update dari Sistem Android)
    private val systemBatteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { updateBatteryUI(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbHelper = LogDBHelper(this)
        initViews()
        startBatteryService()
    }

    private fun initViews() {
        txtLevel = findViewById(R.id.txtLevel)
        txtStatus = findViewById(R.id.txtStatus)
        txtTemp = findViewById(R.id.txtTemp)
        txtVolt = findViewById(R.id.txtVolt)
        txtLog = findViewById(R.id.txtLog)

        btnClearLogs = findViewById(R.id.btnClearLogs)

        btnClearLogs.setOnClickListener {
            btnClearLogs.isEnabled = false
            dbExecutor.execute {
                dbHelper.deleteAllLogs()
                runOnUiThread {
                    txtLog.text = ""
                    Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
                    btnClearLogs.isEnabled = true
                }
            }
        }

        try {
            scrollViewLog = findViewById(R.id.scrollViewLog)
        } catch (e: Exception) {
            if (txtLog.parent is ScrollView) scrollViewLog = txtLog.parent as ScrollView
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiverSafe("BATTERY_LOG_EVENT", logReceiver)
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(systemBatteryReceiver, batteryFilter)

        loadLogsDirectly()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(logReceiver)
            unregisterReceiver(systemBatteryReceiver)
        } catch (_: Exception) {}
    }

    private fun loadLogsDirectly() {
        if (isLogLoading) return
        isLogLoading = true
        dbExecutor.execute {
            try {
                val logs = dbHelper.getAllLogs()
                val sb = StringBuilder()
                for (log in logs) {
                    sb.append(log).append("\n")
                }
                runOnUiThread {
                    txtLog.text = sb.toString()
                    scrollToTop()
                }
            } finally {
                isLogLoading = false
            }
        }
    }

    private fun scrollToTop() {
        scrollViewLog.post {
            scrollViewLog.fullScroll(ScrollView.FOCUS_UP)
        }
    }

    private fun updateBatteryUI(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (scale > 0) (level * 100) / scale else -1

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
            else -> "Unknown"
        }

        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val volt = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)

        txtLevel.text = "Battery Level: $percent%"
        txtStatus.text = "Status: $isCharging"
        txtTemp.text = "Temperature: $temp °C"
        txtVolt.text = "Voltage: $volt mV"
    }

    private fun startBatteryService() {
        val intent = Intent(this, BatteryService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun registerReceiverSafe(action: String, receiver: BroadcastReceiver) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }
}
