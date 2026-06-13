package xyz.aprildown.timer.presentation.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerMachineTest {

    @Test
    fun `countdown count does not read zero`() {
        assertFalse(shouldReadCountdown(remainingSeconds = 4, times = 3))
        listOf(3L, 2L, 1L).forEach { remainingSeconds ->
            assertTrue(shouldReadCountdown(remainingSeconds = remainingSeconds, times = 3))
        }
        assertFalse(shouldReadCountdown(remainingSeconds = 0, times = 3))
        assertFalse(shouldReadCountdown(remainingSeconds = -1, times = 3))
        assertFalse(shouldReadCountdown(remainingSeconds = 1, times = 0))
    }
}
