package com.ai.assistance.operit.terminal

import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual JNI and PTY I/O, including on Android 8-10 without Os.fcntlInt. */
@RunWith(AndroidJUnit4::class)
class PtyIoCompatibilityTest {
    @Test
    fun startsAndExchangesInputWithShell() {
        val pty = startShell("printf 'ready\\n'; IFS= read -r value; printf 'received:%s\\n' \"\$value\"")
        val reader = pty.stdout.bufferedReader()
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertEquals("ready", worker.submit<String> { reader.readLine() }.get(5, TimeUnit.SECONDS))
            pty.stdin.write("compatibility-check\n".toByteArray())
            val output = worker.submit<String> { reader.readText() }.get(5, TimeUnit.SECONDS)
            assertTrue("Shell must receive the input and reply: $output", output.contains("received:compatibility-check"))
            assertTrue(pty.process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, pty.process.exitValue())
        } finally {
            pty.destroy()
            worker.shutdownNow()
        }
    }

    @Test
    fun fullInputQueueTimesOutInsteadOfBlocking() {
        val pty = startShell("printf 'ready\\n'; sleep 30")
        val reader = pty.stdout.bufferedReader()
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertEquals("ready", worker.submit<String> { reader.readLine() }.get(5, TimeUnit.SECONDS))
            val written = worker.submit<Int> {
                try {
                    // Complete lines fill a canonical PTY queue without needing stty.
                    // Android 10's system stty uses TCSETSF, which its app SELinux rules deny.
                    pty.stdin.write(ByteArray(1024 * 1024) { if (it % 2 == 0) 'x'.code.toByte() else 10 })
                    throw AssertionError("A full PTY input queue must time out")
                } catch (error: PtyWriteTimeoutException) {
                    error.bytesTransferred
                }
            }.get(8, TimeUnit.SECONDS)
            assertTrue("Should report a partial write", written > 0 && written < 1024 * 1024)
        } finally {
            pty.destroy()
            worker.shutdownNow()
        }
    }

    @Test
    fun invalidDescriptorReportsErrnoAsIOException() {
        try {
            Pty.configureNonBlocking(-1)
            fail("Invalid descriptor must fail")
        } catch (error: IOException) {
            assertEquals(OsConstants.EBADF, (error.cause as ErrnoException).errno)
        }
    }

    private fun startShell(script: String): Pty =
        Pty.start(arrayOf("/system/bin/sh", "-c", script), mapOf("PATH" to "/system/bin"), File("/"))
}
