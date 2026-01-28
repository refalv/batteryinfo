package com.example.batteryinfo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    // Komponen UI
    private lateinit var txtLevel: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtTemp: TextView
    private lateinit var txtVolt: TextView
    private lateinit var txtLog: TextView
    private lateinit var btnClearLogs: Button
    private lateinit var scrollViewLog: ScrollView

    private val NOTIFICATION_PERMISSION_CODE = 1001
    private lateinit var dbHelper: LogDBHelper

    // 1. Receiver LOG (Menangani log baru saat aplikasi terbuka)
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val log = intent?.getStringExtra("log") ?: return
            
            // Tampilkan log baru di bagian PALING ATAS
            val currentText = txtLog.text.toString()
            txtLog.text = "$log\n$currentText"
            
            // Scroll ke posisi paling atas agar user melihat update terbaru
            scrollToTop()
        }
    }

    // 2. Receiver STATUS (Real-time update dari Sistem Android)
    private val systemBatteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { updateBatteryUI(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Atur padding untuk Edge-to-Edge (Status bar & Nav bar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainContainer)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        dbHelper = LogDBHelper(this)
        initViews()
        checkPermissionAndStartService()
    }

    private fun initViews() {
        txtLevel = findViewById(R.id.txtLevel)
        txtStatus = findViewById(R.id.txtStatus)
        txtTemp = findViewById(R.id.txtTemp)
        txtVolt = findViewById(R.id.txtVolt)
        txtLog = findViewById(R.id.txtLog)
        
        // Inisialisasi Tombol Clear
        btnClearLogs = findViewById(R.id.btnClearLogs)
        
        // Logika Tombol Hapus
        btnClearLogs.setOnClickListener {
            // 1. Hapus data di Database
            dbHelper.deleteAllLogs()
            
            // 2. Hapus tampilan di Layar
            txtLog.text = ""
            
            // 3. Notifikasi ke user
            Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
        }
        
        try {
            scrollViewLog = findViewById(R.id.scrollViewLog)
        } catch (e: Exception) {
            // Fallback safety jika ID salah
            if (txtLog.parent is ScrollView) scrollViewLog = txtLog.parent as ScrollView
        }
    }

    override fun onResume() {
        super.onResume()
        // Register receiver saat aplikasi aktif
        registerReceiverSafe("BATTERY_LOG_EVENT", logReceiver)
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(systemBatteryReceiver, batteryFilter)
        
        // Muat ulang log dari database setiap kali aplikasi dibuka/resume
        loadLogsDirectly()
    }

    override fun onPause() {
        super.onPause()
        // Lepas receiver saat aplikasi tidak aktif (minimize)
        try {
            unregisterReceiver(logReceiver)
            unregisterReceiver(systemBatteryReceiver)
        } catch (_: Exception) {}
    }

    private fun loadLogsDirectly() {
        txtLog.text = ""
        
        // Ambil semua log (LogDBHelper sudah mengurutkan DESC/Terbaru di atas)
        val logs = dbHelper.getAllLogs()

        val sb = StringBuilder()
        for (log in logs) {
            sb.append(log).append("\n")
        }

        txtLog.text = sb.toString()
        scrollToTop()
    }

    private fun scrollToTop() {
        scrollViewLog.post { 
            scrollViewLog.fullScroll(ScrollView.FOCUS_UP) // Fokus ke ATAS
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

    private fun checkPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_CODE
                )
            } else {
                startBatteryService()
            }
        } else {
            startBatteryService()
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startBatteryService()
        }
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
