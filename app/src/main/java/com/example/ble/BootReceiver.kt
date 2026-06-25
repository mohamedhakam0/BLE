/**
 * Receives BOOT_COMPLETED and starts the mesh foreground service automatically.
 *
 * This receiver ensures passive relay/receive behavior remains active after device reboot,
 * even before the user manually opens the app.
 */
package com.example.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ble.AppLogger
import com.example.ble.ui.OnboardingPrefs

/** Broadcast receiver that restores ForegroundMeshService after system boot. */
class BootReceiver : BroadcastReceiver() {
    /** Handles boot broadcasts and starts `ForegroundMeshService` using foreground mode on API 26+. */
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        if (!OnboardingPrefs.isDone(context)) {
            AppLogger.d("BootReceiver", "BOOT_COMPLETED: onboarding not complete — skipping service start")
            return
        }

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
