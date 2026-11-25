package br.com.launcher.tv

import android.accessibilityservice.AccessibilityService
import android.app.role.RoleManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Optional compatibility helper for TV firmware that returns to its own launcher.
 * It only redirects HOME when Launcher Lite is already the selected HOME role.
 */
class LauncherAccessibilityService : AccessibilityService() {
    private var lastRedirect = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val foreground = event.packageName?.toString() ?: return
        if (foreground != "com.google.android.tvlauncher" &&
            foreground != "com.google.android.googlequicksearchbox") return

        val now = SystemClock.elapsedRealtime()
        if (now - lastRedirect < 2500L) return

        val isDefaultHome = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        } else {
            false
        }
        if (isDefaultHome) {
            lastRedirect = now
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    override fun onInterrupt() = Unit
}
