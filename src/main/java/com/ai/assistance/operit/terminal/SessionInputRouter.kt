package com.ai.assistance.operit.terminal

import android.util.Log
import com.ai.assistance.operit.terminal.data.TerminalSessionData

/**
 * Outcome of writing terminal input to one explicitly named session.
 */
enum class SessionInputResult {
    /** The bytes were written and flushed to the requested session. */
    DELIVERED,

    /** No session carries the requested id; nothing was written anywhere. */
    SESSION_NOT_FOUND,

    /** The session exists but its PTY writer is not attached yet; nothing was written. */
    SESSION_NOT_READY,

    /** The session exists but the write itself failed; the input did not reach the PTY. */
    WRITE_FAILED,

    /** The PTY could not accept the input within its write deadline. */
    WRITE_TIMED_OUT
}

/**
 * Writes terminal input to the session the caller named, and to no other session.
 *
 * The target arrives as an already resolved session. Reading the active session here instead
 * would make an interrupt addressed to a background session depend on whatever the UI happens to
 * show when the write runs, which is the defect this router exists to prevent.
 */
internal object SessionInputRouter {
    private const val TAG = "SessionInputRouter"

    /** Ctrl+C as seen by the line discipline: a single 0x03 byte, turned into SIGINT by ISIG. */
    const val INTERRUPT_SEQUENCE = "\u0003"

    fun interrupt(session: TerminalSessionData?, sessionId: String): SessionInputResult {
        if (session == null) return SessionInputResult.SESSION_NOT_FOUND
        val pty = session.pty ?: return SessionInputResult.SESSION_NOT_READY
        return try {
            // Bypass the text encoder's monitor: a large paste may be waiting there. Keep the
            // actual Ctrl+C byte so raw terminals and SSH forward it with their existing semantics.
            pty.sendInterrupt()
            SessionInputResult.DELIVERED
        } catch (error: PtyWriteTimeoutException) {
            Log.w(TAG, "Interrupt input timed out for session $sessionId", error)
            SessionInputResult.WRITE_TIMED_OUT
        } catch (error: Exception) {
            Log.e(TAG, "Interrupt input failed for session $sessionId", error)
            SessionInputResult.WRITE_FAILED
        }
    }

    fun deliver(
        session: TerminalSessionData?,
        sessionId: String,
        input: String,
        source: String
    ): SessionInputResult {
        if (session == null) {
            Log.w(TAG, "Cannot deliver $source input: session $sessionId does not exist")
            return SessionInputResult.SESSION_NOT_FOUND
        }

        val writer = session.sessionWriter
        if (writer == null) {
            Log.w(TAG, "Cannot deliver $source input: session $sessionId has no PTY writer yet")
            return SessionInputResult.SESSION_NOT_READY
        }

        return try {
            writer.write(input)
            writer.flush()
            Log.d(
                TAG,
                "Delivered $source input to session $sessionId: '${escapeInputForLog(input)}'"
            )
            SessionInputResult.DELIVERED
        } catch (e: PtyWriteTimeoutException) {
            Log.w(TAG, "Input timed out for session $sessionId", e)
            SessionInputResult.WRITE_TIMED_OUT
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deliver $source input to session $sessionId", e)
            SessionInputResult.WRITE_FAILED
        }
    }

    /**
     * Renders control bytes readably so that logs distinguish an interrupt from literal text.
     */
    fun escapeInputForLog(input: String): String {
        return buildString(input.length) {
            input.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char.isISOControl()) {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
        }
    }
}
