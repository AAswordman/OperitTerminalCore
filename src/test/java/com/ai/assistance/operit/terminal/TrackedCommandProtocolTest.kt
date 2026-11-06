package com.ai.assistance.operit.terminal

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class TrackedCommandProtocolTest {
    @Test
    fun recordsAreDecodedAcrossEveryPossibleReadBoundary() {
        val wire = "before" + TrackedCommandProtocol.PREFIX + "run-a;start\u0007" +
            "payload#\n" + TrackedCommandProtocol.PREFIX + "run-a;7\u0007" + "after"
        for (split in 0..wire.length) {
            val decoder = TrackedCommandProtocol.Decoder()
            val frames = decoder.append(wire.take(split)) + decoder.append(wire.drop(split))
            assertEquals("beforepayload#\nafter", frames.filterIsInstance<TrackedCommandProtocol.Frame.Text>().joinToString("") { it.value })
            assertEquals(listOf(TrackedCommandProtocol.Frame.Started("run-a")), frames.filterIsInstance<TrackedCommandProtocol.Frame.Started>())
            assertEquals(listOf(TrackedCommandProtocol.Frame.Completed("run-a", 7)), frames.filterIsInstance<TrackedCommandProtocol.Frame.Completed>())
        }
    }

    @Test
    fun promptLookingTextCannotBecomeACompletionRecord() {
        val frames = TrackedCommandProtocol.Decoder().append("root@host:~#\nvalue$ ")
        assertEquals("root@host:~#\nvalue$ ", frames.filterIsInstance<TrackedCommandProtocol.Frame.Text>().joinToString("") { it.value })
        assertTrue(frames.none { it is TrackedCommandProtocol.Frame.Completed })
    }

    @Test
    fun invalidExitStatusesRemainUnknownInsteadOfSuccessful() {
        for (value in listOf("", "oops", "-1", "256")) {
            val frames = TrackedCommandProtocol.Decoder().append(TrackedCommandProtocol.PREFIX + "run-a;$value\u0007")
            assertEquals(TrackedCommandProtocol.Frame.Completed("run-a", null), frames.single())
        }
    }

    @Test
    fun unrelatedControlLikeOutputDoesNotStopTheReader() {
        val decoder = TrackedCommandProtocol.Decoder()
        val text = TrackedCommandProtocol.PREFIX + "not;a;record\u0007"
        assertEquals(listOf(TrackedCommandProtocol.Frame.Text(text)), decoder.append(text))
        assertEquals(listOf(TrackedCommandProtocol.Frame.Completed("real", 3)),
            decoder.append(TrackedCommandProtocol.PREFIX + "real;3\u0007"))
    }

    @Test
    fun shellReportsFailuresWhileRetainingDirectoryVariablesAndUserHooks() {
        val output = bash(
            "PROMPT_COMMAND=('OPERIT_PROMPT_RAN=yes')",
            TrackedCommandProtocol.wrap("cd /tmp; export OPERIT_OUTCOME_TEST=kept; false", "first"),
            TrackedCommandProtocol.wrap("printf '%s:%s:%s\\n' \"\$PWD\" \"\$OPERIT_OUTCOME_TEST\" \"\$OPERIT_PROMPT_RAN\"; (exit 7)", "second"),
            "exit 0"
        )
        val frames = TrackedCommandProtocol.Decoder().append(output)
        assertEquals(listOf(1, 7), frames.filterIsInstance<TrackedCommandProtocol.Frame.Completed>().map { it.exitCode })
        assertTrue(output.contains("/tmp:kept:yes"))
    }

    @Test
    fun shellQuotingPreservesMultilineCommandsAndLiteralPromptText() {
        val output = bash(
            TrackedCommandProtocol.wrap("printf '%s\\n' \"literal ' quote #\"\nprintf '%s\\n' 'second line'", "quoted"),
            "exit 0"
        )
        val frames = TrackedCommandProtocol.Decoder().append(output)
        assertEquals(listOf(TrackedCommandProtocol.Frame.Completed("quoted", 0)), frames.filterIsInstance<TrackedCommandProtocol.Frame.Completed>())
        assertTrue(output.contains("literal ' quote #"))
        assertTrue(output.contains("second line"))
    }

    private fun bash(vararg lines: String): String {
        assumeTrue("Shell integration cases require /bin/bash", File("/bin/bash").canExecute())
        val process = ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-i")
            .redirectErrorStream(true).start()
        try {
            process.outputStream.bufferedWriter().use { writer ->
                for (line in lines) { writer.write(line); writer.newLine() }
            }
            assertTrue("Shell must finish", process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            return process.inputStream.bufferedReader().readText()
        } finally {
            process.destroyForcibly()
        }
    }
}
