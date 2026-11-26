package br.com.launcher.tv

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import br.com.launcher.tv.core.HomeRoleController

/**
 * Routes only the global HOME key to Launcher Lite.
 *
 * All other keys are passed through unchanged so the OEM settings key/panel
 * remains under system control. HOME is consumed only after the launch request
 * succeeds; repeated DOWN events never start duplicate activities.
 */
class LauncherAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        val role = HomeRoleController(this).snapshot()
        Log.i(TAG, "HOME router connected; HOME state=${role.state}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == "com.android.systemui" ||
            packageName == "com.google.android.apps.tv.launcherx" ||
            packageName == "com.google.android.tvlauncher"
        ) {
            Log.d(TAG, "Observed window transition: $packageName")
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false

        // Consume HOME's release and repeats, but launch only once on first DOWN.
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
            return true
        }

        return try {
            val launch = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(launch)
            Log.i(TAG, "HOME routed to Launcher Lite")
            true
        } catch (error: ActivityNotFoundException) {
            Log.e(TAG, "Unable to open Launcher Lite from HOME; passing HOME through", error)
            false
        } catch (error: SecurityException) {
            Log.e(TAG, "Security restriction while opening Launcher Lite; passing HOME through", error)
            false
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "HOME router interrupted")
    }

    private companion object {
        const val TAG = "LauncherLite.Accessibility"
    }
}
