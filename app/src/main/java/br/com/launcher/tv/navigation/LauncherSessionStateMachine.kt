package br.com.launcher.tv.navigation

/**
 * Observable launcher session state.
 *
 * This reducer deliberately records facts only. Losing window focus does not
 * prove that another Activity was launched: System UI panels and dialogs can
 * take input focus while this Activity remains resumed.
 */
enum class ActivityStage {
    NEW, CREATED, STARTED, RESUMED, PAUSED, STOPPED, DESTROYED
}

enum class WindowFocus {
    UNKNOWN, OWNED, NOT_OWNED
}

data class LauncherSessionSnapshot(
    val activityStage: ActivityStage = ActivityStage.NEW,
    val windowFocus: WindowFocus = WindowFocus.UNKNOWN,
    val lastTransitionAtMs: Long = 0L,
    val transitionCount: Long = 0L,
    val lastWindowFocusLossAtMs: Long? = null,
    val lastWindowFocusGainAtMs: Long? = null
) {
    val isInteractive: Boolean
        get() = activityStage == ActivityStage.RESUMED &&
            windowFocus == WindowFocus.OWNED
}

sealed interface LauncherSessionEvent {
    val atMs: Long

    data class Created(override val atMs: Long) : LauncherSessionEvent
    data class Started(override val atMs: Long) : LauncherSessionEvent
    data class Resumed(override val atMs: Long) : LauncherSessionEvent
    data class Paused(override val atMs: Long) : LauncherSessionEvent
    data class Stopped(override val atMs: Long) : LauncherSessionEvent
    data class Destroyed(override val atMs: Long) : LauncherSessionEvent
    data class WindowFocusChanged(
        val hasFocus: Boolean,
        override val atMs: Long
    ) : LauncherSessionEvent
}

/**
 * Deterministic state reducer. It does not launch Activities, request HOME,
 * alter accessibility settings, or infer which package owns another window.
 */
class LauncherSessionStateMachine(
    initial: LauncherSessionSnapshot = LauncherSessionSnapshot()
) {
    var snapshot: LauncherSessionSnapshot = initial
        private set

    fun accept(event: LauncherSessionEvent): LauncherSessionSnapshot {
        val previous = snapshot
        val next = when (event) {
            is LauncherSessionEvent.Created ->
                previous.copy(activityStage = ActivityStage.CREATED)
            is LauncherSessionEvent.Started ->
                previous.copy(activityStage = ActivityStage.STARTED)
            is LauncherSessionEvent.Resumed ->
                previous.copy(activityStage = ActivityStage.RESUMED)
            is LauncherSessionEvent.Paused ->
                previous.copy(activityStage = ActivityStage.PAUSED)
            is LauncherSessionEvent.Stopped ->
                previous.copy(activityStage = ActivityStage.STOPPED)
            is LauncherSessionEvent.Destroyed ->
                previous.copy(activityStage = ActivityStage.DESTROYED)
            is LauncherSessionEvent.WindowFocusChanged -> {
                if (event.hasFocus) {
                    previous.copy(
                        windowFocus = WindowFocus.OWNED,
                        lastWindowFocusGainAtMs = event.atMs
                    )
                } else {
                    previous.copy(
                        windowFocus = WindowFocus.NOT_OWNED,
                        lastWindowFocusLossAtMs = event.atMs
                    )
                }
            }
        }

        snapshot = next.copy(
            lastTransitionAtMs = event.atMs,
            transitionCount = previous.transitionCount + 1
        )
        return snapshot
    }
}
