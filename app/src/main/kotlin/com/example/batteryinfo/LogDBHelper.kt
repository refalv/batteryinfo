package com.example.batteryinfo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LogDBHelper(context: Context) : SQLiteOpenHelper(context, "BatteryLogs.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        // Membuat tabel logs
        db.execSQL("CREATE TABLE logs (id INTEGER PRIMARY KEY AUTOINCREMENT, message TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Reset tabel jika versi database berubah
        db.execSQL("DROP TABLE IF EXISTS logs")
        onCreate(db)
    }

    // Fungsi Simpan Log (UNLIMITED - Tidak ada penghapusan otomatis)
    fun addLog(message: String) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("message", message)
        }
        
        // Masukkan data baru
        db.insert("logs", null, values)
        
        // Kita tidak melakukan penghapusan data lama di sini, 
        // jadi data akan tersimpan selamanya sampai user menekan tombol "Clear Logs".
        
        db.close() 
    }

    // Fungsi Ambil Semua Log
    // Menggunakan DESC agar log TERBARU muncul di URUTAN PERTAMA (Paling Atas)
    fun getAllLogs(): List<String> {
        val logs = mutableListOf<String>()
        val db = this.readableDatabase
        
        // Query: Ambil pesan, urutkan ID dari Besar ke Kecil (3, 2, 1)
        val cursor = db.rawQuery("SELECT message FROM logs ORDER BY id DESC", null)
        
        if (cursor.moveToFirst()) {
            do {
                logs.add(cursor.getString(0))
            } while (cursor.moveToNext())
        }
        
        cursor.close()
        db.close()
        return logs
    }

    // Fungsi Hapus Semua Log (Untuk Tombol Clear)
    fun deleteAllLogs() {
        val db = this.writableDatabase
        db.execSQL("DELETE FROM logs") // Menghapus seluruh isi tabel
        db.close()
    }
}
