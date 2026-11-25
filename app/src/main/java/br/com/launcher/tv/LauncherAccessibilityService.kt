package br.com.launcher.tv

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Optional compatibility service. It observes only window changes and never reads window content.
 * It does not request global key interception because some Android TV firmwares reject or disable
 * services that request key filtering for protected system keys such as HOME.
 */
class LauncherAccessibilityService : AccessibilityService() {
    private var lastLaunch = 0L

    private val stockLauncherPackages = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        val foreground = event.packageName?.toString() ?: return
        if (foreground !in stockLauncherPackages) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 2500L) return

        try {
            val launch = android.content.Intent(this, MainActivity::class.java).apply {
                action = android.content.Intent.ACTION_MAIN
                addCategory(android.content.Intent.CATEGORY_HOME)
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(launch)
            lastLaunch = now
        } catch (_: Exception) {
            // Some TV firmwares block activity starts from accessibility services.
        }
    }

    override fun onInterrupt() = Unit
}