package br.com.launcher.tv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherLifecycleCoordinatorTest {

    @Test
    fun recordsPauseAndResumeGapWithoutChangingPolicy() {
        val coordinator = LauncherLifecycleCoordinator()
        coordinator.onCreated(10L)
        coordinator.onStarted(20L)
        coordinator.onResumed(30L)
        coordinator.onPaused(100L)

        val resumed = coordinator.onResumed(145L)

        assertEquals(LauncherActivityStage.RESUMED, resumed.stage)
        assertEquals(45L, resumed.lastResumeGapMs)
        assertEquals(2, resumed.resumeCount)
        assertEquals(5, resumed.transitionCount)
    }

    @Test
    fun firstResumeHasNoPauseGap() {
        val resumed = LauncherLifecycleCoordinator().onResumed(50L)
        assertEquals(LauncherActivityStage.RESUMED, resumed.stage)
        assertNull(resumed.lastResumeGapMs)
        assertEquals(1, resumed.resumeCount)
    }

    @Test
    fun destroyedStageIsRecorded() {
        val coordinator = LauncherLifecycleCoordinator()
        coordinator.onCreated(1L)

        val destroyed = coordinator.onDestroyed(2L)

        assertEquals(LauncherActivityStage.DESTROYED, destroyed.stage)
    }
}
