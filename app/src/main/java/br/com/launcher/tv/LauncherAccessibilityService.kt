package br.com.launcher.tv

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import br.com.launcher.tv.core.HomeRoleController

/**
 * Experimental diagnostic observer.
 *
 * Key events are logged and always returned to the system. This service does
 * not launch activities, consume HOME, or reclaim foreground focus.
 */
class LauncherAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        val role = HomeRoleController(this).snapshot()
        Log.i(TAG, "Diagnostic observer connected; HOME state=${role.state}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == "com.android.systemui" ||
            packageName == "com.google.android.apps.tv.launcherx" ||
            packageName == "com.google.android.tvlauncher"
        ) {
            Log.d(TAG, "Observed window transition: $packageName (no foreground action)")
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.i(TAG, "Key event observed: action=${event.action}, keyCode=${event.keyCode}, repeat=${event.repeatCount}; passing through")
        return false
    }

    override fun onInterrupt() {
        Log.i(TAG, "Diagnostic observer interrupted")
    }

    private companion object {
        const val TAG = "LauncherLite.Accessibility"
    }
}
