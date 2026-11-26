package br.com.launcher.tv.core

/** Passive Activity lifecycle recorder; it never launches or foregrounds the app. */
enum class LauncherActivityStage {
    CREATED, STARTED, RESUMED, PAUSED, STOPPED, DESTROYED
}

data class LauncherLifecycleSnapshot(
    val stage: LauncherActivityStage,
    val transitionCount: Int,
    val lastTransitionAtMs: Long,
    val lastPauseAtMs: Long?,
    val lastResumeGapMs: Long?,
    val resumeCount: Int
)

class LauncherLifecycleCoordinator {
    private var current = LauncherLifecycleSnapshot(
        stage = LauncherActivityStage.CREATED,
        transitionCount = 0,
        lastTransitionAtMs = 0L,
        lastPauseAtMs = null,
        lastResumeGapMs = null,
        resumeCount = 0
    )

    fun onCreated(atMs: Long) = transition(LauncherActivityStage.CREATED, atMs)
    fun onStarted(atMs: Long) = transition(LauncherActivityStage.STARTED, atMs)

    fun onResumed(atMs: Long): LauncherLifecycleSnapshot {
        val gap = current.lastPauseAtMs?.let { (atMs - it).coerceAtLeast(0L) }
        current = current.copy(
            stage = LauncherActivityStage.RESUMED,
            transitionCount = current.transitionCount + 1,
            lastTransitionAtMs = atMs,
            lastResumeGapMs = gap,
            resumeCount = current.resumeCount + 1
        )
        return current
    }

    fun onPaused(atMs: Long): LauncherLifecycleSnapshot {
        current = current.copy(
            stage = LauncherActivityStage.PAUSED,
            transitionCount = current.transitionCount + 1,
            lastTransitionAtMs = atMs,
            lastPauseAtMs = atMs
        )
        return current
    }

    fun onStopped(atMs: Long) = transition(LauncherActivityStage.STOPPED, atMs)
    fun onDestroyed(atMs: Long) = transition(LauncherActivityStage.DESTROYED, atMs)
    fun snapshot(): LauncherLifecycleSnapshot = current

    private fun transition(
        stage: LauncherActivityStage,
        atMs: Long
    ): LauncherLifecycleSnapshot {
        current = current.copy(
            stage = stage,
            transitionCount = current.transitionCount + 1,
            lastTransitionAtMs = atMs
        )
        return current
    }
}
