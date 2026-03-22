package com.example.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ble.AppLogger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        AppLogger.d("BootReceiver", "BOOT_COMPLETED received, starting ForegroundMeshService")

        // Start service without a compile-time reference. Ensure ForegroundMeshService exists and
        // is in package com.example.ble with name ".ForegroundMeshService".
        val serviceIntent = Intent().apply {
            setClassName(context.packageName, "${context.packageName}.ForegroundMeshService")
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            AppLogger.e("BootReceiver", "Failed to start ForegroundMeshService: ${e.message}", e)
        }
    }
}

// Manifest snippet (add to AndroidManifest.xml):
// <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
//
// <application>
//   <receiver
//       android:name=".BootReceiver"
//       android:enabled="true"
//       android:exported="true">
//       <intent-filter>
//           <action android:name="android.intent.action.BOOT_COMPLETED" />
//       </intent-filter>
//   </receiver>
//
//   <service
//       android:name=".ForegroundMeshService"
//       android:exported="false"
//       android:foregroundServiceType="connectedDevice" />
// </application>
