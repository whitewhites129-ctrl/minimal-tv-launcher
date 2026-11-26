package br.com.launcher.tv.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherSessionStateMachineTest {

    @Test
    fun windowFocusLossDoesNotPretendActivityWasPaused() {
        val machine = LauncherSessionStateMachine()
        machine.accept(LauncherSessionEvent.Resumed(10L))

        val state = machine.accept(
            LauncherSessionEvent.WindowFocusChanged(false, 20L)
        )

        assertEquals(ActivityStage.RESUMED, state.activityStage)
        assertEquals(WindowFocus.NOT_OWNED, state.windowFocus)
        assertFalse(state.isInteractive)
    }

    @Test
    fun systemWindowCanTakeFocusWhileActivityRemainsResumed() {
        val machine = LauncherSessionStateMachine()
        machine.accept(LauncherSessionEvent.Created(1L))
        machine.accept(LauncherSessionEvent.Started(2L))
        machine.accept(LauncherSessionEvent.Resumed(3L))
        machine.accept(LauncherSessionEvent.WindowFocusChanged(true, 4L))

        val lost = machine.accept(
            LauncherSessionEvent.WindowFocusChanged(false, 5L)
        )

        assertEquals(ActivityStage.RESUMED, lost.activityStage)
        assertEquals(5L, lost.lastWindowFocusLossAtMs)
        assertEquals(4L, lost.lastWindowFocusGainAtMs)
    }

    @Test
    fun regainingFocusMakesResumedActivityInteractiveAgain() {
        val machine = LauncherSessionStateMachine()
        machine.accept(LauncherSessionEvent.Resumed(10L))
        machine.accept(LauncherSessionEvent.WindowFocusChanged(false, 20L))

        val state = machine.accept(
            LauncherSessionEvent.WindowFocusChanged(true, 30L)
        )

        assertTrue(state.isInteractive)
        assertEquals(WindowFocus.OWNED, state.windowFocus)
    }

    @Test
    fun everyEventIsRecordedExactlyOnce() {
        val machine = LauncherSessionStateMachine()
        machine.accept(LauncherSessionEvent.Created(1L))
        machine.accept(LauncherSessionEvent.Started(2L))
        val state = machine.accept(LauncherSessionEvent.Resumed(3L))

        assertEquals(3L, state.transitionCount)
        assertEquals(3L, state.lastTransitionAtMs)
    }
}
