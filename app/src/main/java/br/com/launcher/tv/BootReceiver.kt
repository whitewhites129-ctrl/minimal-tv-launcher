package br.com.launcher.tv

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val elapsed = SystemClock.elapsedRealtime()
        Log.i(TAG, "onReceive action=$action; elapsedRealtime=$elapsed")

        // This receiver is directBootAware. Record only a tiny diagnostic marker
        // in device-protected storage; do not touch credential-protected prefs or UI.
        val deviceContext = context.createDeviceProtectedStorageContext()
        val bootDiagnostics = deviceContext.getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE)

        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            val saved = bootDiagnostics.edit()
                .putBoolean(KEY_LOCKED_BOOT_RECEIVED, true)
                .putLong(KEY_LOCKED_BOOT_ELAPSED, elapsed)
                .commit()
            Log.i(TAG, "LOCKED_BOOT_COMPLETED recorded in device-protected storage; saved=$saved; elapsedRealtime=$elapsed; no UI launch attempted")
            return
        }

        if (action != Intent.ACTION_BOOT_COMPLETED) {
            Log.w(TAG, "Ignoring unexpected boot broadcast")
            return
        }

        val lockedBootReceived = bootDiagnostics.getBoolean(KEY_LOCKED_BOOT_RECEIVED, false)
        val lockedBootElapsed = bootDiagnostics.getLong(KEY_LOCKED_BOOT_ELAPSED, -1L)
        Log.i(TAG, "BOOT_COMPLETED diagnostic: lockedBootReceived=$lockedBootReceived; lockedBootElapsed=$lockedBootElapsed; currentElapsed=$elapsed")

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        try {
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= 34) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                pendingIntent.send(context, 0, null, null, null, null, options.toBundle())
            } else {
                pendingIntent.send()
            }
            Log.i(TAG, "Launcher Lite launch PendingIntent sent; elapsedRealtime=${SystemClock.elapsedRealtime()}")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to request Launcher Lite launch during boot", error)
        }
    }

    private companion object {
        const val TAG = "LauncherLite.Boot"
        const val BOOT_PREFS = "boot_diagnostics_device_protected"
        const val KEY_LOCKED_BOOT_RECEIVED = "locked_boot_received"
        const val KEY_LOCKED_BOOT_ELAPSED = "locked_boot_elapsed"
    }
}
