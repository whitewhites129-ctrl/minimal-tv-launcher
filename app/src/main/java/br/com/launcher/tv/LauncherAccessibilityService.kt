package br.com.launcher.tv

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import br.com.launcher.tv.core.HomeRoleController

/**
 * Optional diagnostic observer only.
 *
 * Accessibility window events also occur while Android System UI, quick
 * settings and OEM side panels are opening. This service deliberately never
 * starts MainActivity or tries to reclaim foreground focus.
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

    override fun onInterrupt() {
        Log.i(TAG, "Diagnostic observer interrupted")
    }

    private companion object {
        const val TAG = "LauncherLite.Accessibility"
    }
}
