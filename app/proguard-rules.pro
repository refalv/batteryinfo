# Add project specific ProGuard rules here.

# Keep all four app classes — registered in Manifest or used via reflection
-keep class com.example.batteryinfo.BatteryService { *; }
-keep class com.example.batteryinfo.BootReceiver { *; }
-keep class com.example.batteryinfo.MainActivity { *; }
-keep class com.example.batteryinfo.LogDBHelper { *; }

# Keep BroadcastReceiver inner classes (anonymous receivers registered at runtime)
-keep class com.example.batteryinfo.BatteryService$* { *; }
-keep class com.example.batteryinfo.MainActivity$* { *; }

# Preserve line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
