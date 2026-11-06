package com.ai.assistance.operit.terminal

import com.ai.assistance.operit.terminal.data.TerminalSessionData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionInputRouterTest {
    @Test
    fun interruptIsWrittenToTheNamedSessionAsASingleCtrlCByte() {
        val target = RecordingSession("target")
        val other = RecordingSession("other")

        val result = interrupt(target.session, "target")

        assertEquals(SessionInputResult.DELIVERED, result)
        assertEquals(byteArrayOf(0x03).toList(), target.bytes().toList())
        assertEquals(emptyList<Byte>(), other.bytes().toList())
    }

    @Test
    fun interruptNeverFallsBackToAnotherSessionWhenTheIdIsUnknown() {
        val present = RecordingSession("present")

        val result = interrupt(null, "missing")

        assertEquals(SessionInputResult.SESSION_NOT_FOUND, result)
        assertEquals(emptyList<Byte>(), present.bytes().toList())
    }

    @Test
    fun interruptOnASessionWithoutAWriterReportsNotReadyAndWritesNothing() {
        val session = TerminalSessionData(title = "not-ready")

        val result = interrupt(session, session.id)

        assertEquals(SessionInputResult.SESSION_NOT_READY, result)
    }

    @Test
    fun failingWriterIsReportedAsWriteFailed() {
        val failing =
            TerminalSessionData(
                title = "failing",
                pty = testPty(FailingOutputStream())
            )

        val result = interrupt(failing, failing.id)

        assertEquals(SessionInputResult.WRITE_FAILED, result)
    }

    @Test
    fun literalInputReachesOnlyTheNamedSession() {
        val first = RecordingSession("first")
        val second = RecordingSession("second")

        val result =
            SessionInputRouter.deliver(first.session, first.session.id, "ls -la\r", "session-input")

        assertEquals(SessionInputResult.DELIVERED, result)
        assertEquals("ls -la\r", first.bytes().toString(Charsets.UTF_8))
        assertEquals(emptyList<Byte>(), second.bytes().toList())
    }

    @Test
    fun interruptSequenceIsTheSingleControlByteTheLineDisciplineTurnsIntoSigint() {
        assertEquals(1, SessionInputRouter.INTERRUPT_SEQUENCE.length)
        assertEquals('\u0003', SessionInputRouter.INTERRUPT_SEQUENCE[0])
    }

    @Test
    fun logEscapingKeepsControlBytesDistinguishableFromLiteralText() {
        assertEquals("\\u0003", SessionInputRouter.escapeInputForLog("\u0003"))
        assertEquals("\\r", SessionInputRouter.escapeInputForLog("\r"))
        assertEquals("a\\u0003b", SessionInputRouter.escapeInputForLog("a\u0003b"))
        assertTrue(SessionInputRouter.escapeInputForLog("ls -la").contains("ls -la"))
    }

    @Test
    fun interruptDoesNotWaitForTheTextWriterMonitor() {
        val target = RecordingSession("target")
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            executor.submit {
                synchronized(requireNotNull(target.session.sessionWriter)) {
                    locked.countDown()
                    release.await()
                }
            }
            assertTrue(locked.await(1, TimeUnit.SECONDS))
            val result = executor.submit<SessionInputResult> { interrupt(target.session, target.session.id) }
            assertEquals(SessionInputResult.DELIVERED, result.get(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
        assertEquals(listOf(0x03.toByte()), target.bytes().toList())
    }

    @Test
    fun interruptReportsTimeoutWithoutWritingToAnotherSession() {
        val other = RecordingSession("other")
        val target = TerminalSessionData(title = "blocked", pty = testPty(object : OutputStream() {
            override fun write(value: Int) { throw PtyWriteTimeoutException(0) }
        }))
        assertEquals(SessionInputResult.WRITE_TIMED_OUT, interrupt(target, target.id))
        assertTrue(other.bytes().isEmpty())
    }

    private fun interrupt(session: TerminalSessionData?, sessionId: String): SessionInputResult =
        SessionInputRouter.interrupt(session, sessionId)

    private class RecordingSession(title: String) {
        private val sink = ByteArrayOutputStream()
        val session =
            TerminalSessionData(
                title = title,
                pty = testPty(sink),
                sessionWriter = OutputStreamWriter(sink, Charsets.UTF_8)
            )

        fun bytes(): ByteArray {
            session.sessionWriter?.flush()
            return sink.toByteArray()
        }
    }

    private class FailingOutputStream : OutputStream() {
        override fun write(b: Int) {
            throw IOException("injected write failure")
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            throw IOException("injected write failure")
        }
    }

    private companion object {
        fun testPty(sink: OutputStream): Pty {
            val process = object : Process() {
                override fun getOutputStream() = sink
                override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
                override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                override fun waitFor() = 0
                override fun exitValue() = 0
                override fun destroy() {}
            }
            return Pty(process, null, -1, process.inputStream, sink)
        }
    }
}
