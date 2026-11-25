package br.com.launcher.tv

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Compatibility bridge for TV firmware that ignores or overrides a third-party HOME app.
 *
 * This service deliberately does not retrieve window contents. It only:
 *  - intercepts the remote's HOME key when Android grants key-event filtering;
 *  - detects when a known stock TV launcher returns to the foreground (for example after
 *    wake/standby) and brings this launcher forward again.
 *
 * The user must explicitly enable this service in Android Accessibility settings.
 */
class LauncherAccessibilityService : AccessibilityService() {
    private var lastLaunch = 0L

    private val stockLauncherPackages = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx"
    )

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false

        // Consume both halves of the HOME press so the stock launcher does not race us.
        if (event.action == KeyEvent.ACTION_UP) {
            bringLauncherToFront()
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        val foreground = event.packageName?.toString() ?: return
        if (foreground !in stockLauncherPackages) return

        // Also covers firmware that reopens its own home screen after boot or standby.
        bringLauncherToFront()
    }

    private fun bringLauncherToFront() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 1800L) return

        val launch = android.content.Intent(this, MainActivity::class.java).apply {
            action = android.content.Intent.ACTION_MAIN
            addCategory(android.content.Intent.CATEGORY_HOME)
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        try {
            startActivity(launch)
            lastLaunch = now
        } catch (_: Exception) {
            // Some TV firmwares block activity starts from accessibility services.
        }
    }

    override fun onInterrupt() = Unit
}