package br.com.launcher.tv

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class LauncherNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val message: String,
    val postedAt: Long
)

class LauncherNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: LauncherNotificationListener? = null
        val items = mutableListOf<LauncherNotification>()
        @Volatile var onChanged: (() -> Unit)? = null

        @Synchronized
        private fun replace(list: List<LauncherNotification>) {
            items.clear()
            items.addAll(list.sortedByDescending { it.postedAt })
            onChanged?.invoke()
        }

        fun snapshot(): List<LauncherNotification> = synchronized(items) { items.toList() }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refreshFromSystem()
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = toItem(sbn) ?: return
        synchronized(items) {
            items.removeAll { it.key == notification.key }
            items.add(notification)
            items.sortByDescending { it.postedAt }
        }
        onChanged?.invoke()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(items) { items.removeAll { it.key == sbn.key } }
        onChanged?.invoke()
    }

    fun refreshFromSystem() {
        val active = try { activeNotifications?.mapNotNull { toItem(it) } ?: emptyList() }
        catch (_: Exception) { emptyList() }
        replace(active)
    }

    fun openNotification(key: String) {
        val item = try { activeNotifications?.firstOrNull { it.key == key } } catch (_: Exception) { null }
        try { item?.notification?.contentIntent?.send() } catch (_: Exception) { }
    }

    fun dismiss(key: String) {
        try { cancelNotification(key) } catch (_: Exception) { }
    }

    fun clearAll() {
        try { cancelAllNotifications() } catch (_: Exception) { }
    }

    private fun toItem(sbn: StatusBarNotification): LauncherNotification? {
        val extras = sbn.notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val message = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()?.trim().orEmpty()
        if (title.isBlank() && message.isBlank()) return null
        val appName = try {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) { sbn.packageName }
        return LauncherNotification(sbn.key, sbn.packageName, appName, title, message, sbn.postTime)
    }
}