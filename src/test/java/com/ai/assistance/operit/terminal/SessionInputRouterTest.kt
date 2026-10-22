package com.ai.assistance.operit.terminal

import com.ai.assistance.operit.terminal.data.TerminalSessionData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter

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
                sessionWriter = OutputStreamWriter(FailingOutputStream())
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

    private fun interrupt(session: TerminalSessionData?, sessionId: String): SessionInputResult =
        SessionInputRouter.deliver(
            session,
            sessionId,
            SessionInputRouter.INTERRUPT_SEQUENCE,
            "interrupt"
        )

    private class RecordingSession(title: String) {
        private val sink = ByteArrayOutputStream()
        val session =
            TerminalSessionData(
                title = title,
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
}
