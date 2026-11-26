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
        Log.i(TAG, "onReceive action=${intent.action}; elapsedRealtime=${SystemClock.elapsedRealtime()}")
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            Log.w(TAG, "Ignoring unexpected boot broadcast")
            return
        }

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
    }
}
