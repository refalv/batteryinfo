package com.example.batteryinfo

import android.os.Handler
import android.os.Looper

class PeriodicTask(private val task: () -> Unit, private val intervalMs: Long) {
    private val handler = Handler(Looper.getMainLooper())
    private val runnable = object : Runnable {
        override fun run() {
            task()
            handler.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        stop() // Pastikan tidak double start
        handler.post(runnable)
    }

    fun stop() {
        handler.removeCallbacks(runnable)
    }
}
