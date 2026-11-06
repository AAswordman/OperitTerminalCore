package com.ai.assistance.operit.terminal

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Reap a PTY child exactly once and retain its actual waitpid result for every observer. */
internal class PtyChildProcess(
    pid: Int,
    waitForExit: () -> Int,
    private val sendSignal: (Int) -> Unit
) : Process() {
    private val exit = FutureTask { waitForExit() }
    private val destroyRequested = AtomicBoolean(false)

    init {
        Thread(exit, "operit-pty-wait-$pid").apply { isDaemon = true }.start()
    }

    override fun waitFor(): Int = exit.get()

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        return try {
            exit.get(timeout, unit)
            true
        } catch (_: java.util.concurrent.TimeoutException) {
            false
        }
    }

    override fun exitValue(): Int {
        if (!exit.isDone) throw IllegalThreadStateException("PTY process has not exited")
        return exit.get()
    }

    override fun isAlive(): Boolean = !exit.isDone

    override fun destroy() {
        if (isAlive() && destroyRequested.compareAndSet(false, true)) {
            try {
                sendSignal(1)
            } finally {
                if (isAlive()) sendSignal(9)
            }
        }
    }

    override fun getInputStream(): InputStream? = null
    override fun getOutputStream(): OutputStream? = null
    override fun getErrorStream(): InputStream? = null
}
