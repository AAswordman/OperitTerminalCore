package com.ai.assistance.operit.terminal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PtyChildProcessTest {
    @Test
    fun runningChildCannotBeReportedAsExitedSuccessfully() {
        val finish = CountDownLatch(1)
        val process = PtyChildProcess(42, { finish.await(); 3 }, {})
        try {
            assertTrue(process.isAlive())
            assertFalse(process.waitFor(0, TimeUnit.MILLISECONDS))
            try {
                process.exitValue()
                fail("A running process must not return exit code 0")
            } catch (_: IllegalThreadStateException) {
                // The Process contract requires this until the child actually exits.
            }
        } finally {
            finish.countDown()
        }
        assertTrue(process.waitFor(1, TimeUnit.SECONDS))
        assertEquals(3, process.exitValue())
        assertFalse(process.isAlive())
    }

    @Test
    fun repeatedObserversReadTheSameExitCodeWithoutReapingTwice() {
        val reaped = AtomicInteger()
        val process = PtyChildProcess(42, { reaped.incrementAndGet(); 137 }, {})
        assertTrue(process.waitFor(1, TimeUnit.SECONDS))
        assertEquals(137, process.waitFor())
        assertEquals(137, process.waitFor())
        assertEquals(137, process.exitValue())
        assertEquals(1, reaped.get())
    }

    @Test
    fun destructionSignalsALiveChildOnlyOnce() {
        val finish = CountDownLatch(1)
        val signals = mutableListOf<Int>()
        val process = PtyChildProcess(42, { finish.await(); 137 }, { signal ->
            signals.add(signal)
            if (signal == 9) finish.countDown()
        })
        try {
            process.destroy()
            process.destroy()
            assertTrue(process.waitFor(1, TimeUnit.SECONDS))
            assertEquals(listOf(1, 9), signals)
        } finally {
            finish.countDown()
        }
    }
}
