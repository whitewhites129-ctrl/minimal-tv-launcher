package br.com.launcher.tv

import android.accessibilityservice.AccessibilityService
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Optional compatibility fallback. It only returns to Launcher Lite when this app
 * is already the system's selected HOME app. It never intercepts HOME key events.
 */
class LauncherAccessibilityService : AccessibilityService() {
    private var lastLaunch = 0L

    private val stockLauncherPackages = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx"
    )

    private fun isLauncherLiteDefaultHome(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)
                ?.isRoleHeld(RoleManager.ROLE_HOME) == true
        } else {
            packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                0
            )?.activityInfo?.packageName == packageName
        }
    } catch (_: Exception) {
        false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        // Never fight Android's HOME selection. The service is only a fallback
        // after Launcher Lite has already been selected as the default launcher.
        if (!isLauncherLiteDefaultHome()) return

        val foreground = event.packageName?.toString() ?: return
        if (foreground !in stockLauncherPackages) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 5000L) return

        try {
            val launch = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_HOME)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
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