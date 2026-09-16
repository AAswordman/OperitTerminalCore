package com.ai.assistance.operit.terminal.provider.type

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class LocalTerminalProviderTest {
    @Test(timeout = 15_000)
    fun successfulCommandsReuseTheSameKeyWithoutMixingOutput() {
        val process = FakeProcess { shell, token ->
            shell.complete(token, "reply-${shell.commandCount.get()}")
        }
        withProvider(process) {
            assertOk(command(text = "printf first").await(), "reply-1")
            assertOk(command(text = "printf second").await(), "reply-2")
            assertEquals(1, starts.get())
            assertEquals(2, process.commandCount.get())
            assertTrue(process.isAlive)
        }
    }

    @Test(timeout = 15_000)
    fun processStartFailureDoesNotPoisonTheNextCall() {
        val recovered = FakeProcess()
        withProvider(recovered, start = { attempt ->
            if (attempt == 0) throw IOException("injected start failure")
            recovered
        }) {
            val failed = command().await()
            assertEquals(HiddenExecResult.State.SHELL_START_FAILED, failed.state)
            assertTrue(failed.error.contains("injected start failure"))
            assertOk(command().await())
            assertEquals(2, starts.get())
        }
    }

    @Test(timeout = 15_000)
    fun readinessDeadlineDestroysUnpublishedProcessAndAllowsBothKeys() {
        val unready = FakeProcess(ready = false)
        val sameKey = FakeProcess()
        val otherKey = FakeProcess()
        withProvider(unready, sameKey, otherKey) {
            assertEquals(
                HiddenExecResult.State.TIMEOUT,
                command(timeoutMs = DEADLINE_MS).await().state
            )
            assertRetired(unready)
            assertEquals(0, unready.commandCount.get())
            assertOk(command().await())
            assertOk(command(key = "other").await())
            assertEquals(3, starts.get())
        }
    }

    @Test(timeout = 15_000)
    fun processStartDeadlineReturnsBeforeTheBlockedStartAndRetiresItsProcess() {
        val delayed = FakeProcess()
        val sameKey = FakeProcess()
        val otherKey = FakeProcess()
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        withProvider(delayed, sameKey, otherKey, start = { attempt ->
            if (attempt == 0) {
                startEntered.countDown()
                while (releaseStart.count != 0L) {
                    try {
                        releaseStart.await()
                    } catch (_: InterruptedException) {
                        // Model ProcessBuilder.start() remaining blocked after coroutine cancellation.
                    }
                }
                delayed
            } else {
                listOf(sameKey, otherKey)[attempt - 1]
            }
        }) {
            val call = command(timeoutMs = DEADLINE_MS)
            try {
                startEntered.awaitBounded("blocked process start")
                assertEquals(HiddenExecResult.State.TIMEOUT, call.await().state)
            } finally {
                releaseStart.countDown()
            }
            assertRetired(delayed)
            assertOk(command().await())
            assertOk(command(key = "other").await())
            assertEquals(3, starts.get())
        }
    }

    @Test(timeout = 15_000)
    fun waitingForCreationMutexUsesTheCallDeadline() {
        val first = FakeProcess()
        val second = FakeProcess()
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        withProvider(first, second, start = { attempt ->
            if (attempt == 0) {
                startEntered.countDown()
                releaseStart.await()
                first
            } else {
                second
            }
        }) {
            val owner = command(key = "owner")
            try {
                startEntered.awaitBounded("first process start")
                assertEquals(
                    HiddenExecResult.State.TIMEOUT,
                    command(key = "waiting", timeoutMs = DEADLINE_MS).await().state
                )
                assertEquals("waiter must not reach process creation", 1, starts.get())
                assertTrue(first.isAlive)
            } finally {
                releaseStart.countDown()
            }
            assertOk(owner.await())
            assertOk(command(key = "waiting").await())
            assertEquals(2, starts.get())
        }
    }

    @Test(timeout = 15_000)
    fun queuedSameKeyTimeoutDoesNotTerminateTheActiveOwner() {
        val process = FakeProcess { _, _ -> }
        withProvider(process) {
            val owner = command()
            val ownerToken = process.awaitCommand()
            assertEquals(
                HiddenExecResult.State.TIMEOUT,
                command(timeoutMs = DEADLINE_MS).await().state
            )
            owner.assertPending("queued timeout ended the active owner's call")
            assertTrue("queued timeout must not destroy the owner's shell", process.isAlive)
            assertEquals(1L, process.destroyed.count)
            assertEquals(1, process.commandCount.get())
            process.complete(ownerToken, "owner finished")
            assertOk(owner.await(), "owner finished")

            val next = command()
            process.complete(process.awaitCommand(), "next finished")
            assertOk(next.await(), "next finished")
            assertEquals(1, starts.get())
        }
    }

    @Test(timeout = 15_000)
    fun queuedSameKeyCallContinuesOnANewShellAfterTheOwnerTimesOut() {
        val timedOut = FakeProcess { _, _ -> }
        val recovered = FakeProcess()
        withProvider(timedOut, recovered) {
            val owner = command(timeoutMs = DEADLINE_MS)
            timedOut.awaitCommand()
            val queued = command()

            assertEquals(HiddenExecResult.State.TIMEOUT, owner.await().state)
            assertRetired(timedOut)
            assertOk(queued.await())
            assertEquals(2, starts.get())
        }
    }

    @Test(timeout = 15_000)
    fun stalledWriterIsDeadlineBoundedAndRetiredBeforeTheNextCall() {
        val stalled = FakeProcess(stallWrites = true)
        val recovered = FakeProcess()
        withProvider(stalled, recovered) {
            val call = command(timeoutMs = DEADLINE_MS)
            stalled.writeEntered.awaitBounded("blocked output write")
            assertEquals(HiddenExecResult.State.TIMEOUT, call.await().state)
            assertRetired(stalled)
            stalled.writeReleased.awaitBounded("destroy must release the blocked write")
            assertEquals(0, stalled.commandCount.get())
            assertOk(command().await())
            assertEquals(2, starts.get())
        }
    }

    @Test(timeout = 15_000)
    fun cancellationRetiresTheActiveShellAndAllowsTheSameKeyAgain() {
        val active = FakeProcess { _, _ -> }
        val recovered = FakeProcess()
        withProvider(active, recovered) {
            val cancellation = Job()
            val call = command(cancellation = cancellation)
            active.awaitCommand()
            cancellation.cancel()
            try {
                call.await()
                throw AssertionError("caller cancellation must propagate")
            } catch (_: CancellationException) {
                // Cancellation must finish cleanup, not become an execution result.
            }
            assertRetired(active)
            assertOk(command().await())
            assertEquals(2, starts.get())
        }
    }

    @Test(timeout = 15_000)
    fun processExitEndsTheCallWithoutWaitingForItsDeadline() {
        val exited = FakeProcess { _, _ -> }
        val recovered = FakeProcess()
        withProvider(exited, recovered) {
            val call = command()
            exited.awaitCommand()
            exited.exit(17, keepStdoutOpen = true)
            assertEquals(HiddenExecResult.State.PROCESS_EXITED, call.await().state)
            assertOk(command().await())
            assertEquals(2, starts.get())
        }
    }

    @Test(timeout = 15_000)
    fun readerFailureEndsTheCallAndRetiresTheShell() {
        val broken = FakeProcess { _, _ -> }
        val recovered = FakeProcess()
        withProvider(broken, recovered) {
            val call = command()
            broken.awaitCommand()
            broken.stdout.fail(IOException("injected reader failure"))
            assertEquals(HiddenExecResult.State.EXECUTION_ERROR, call.await().state)
            assertRetired(broken)
            assertOk(command().await())
            assertEquals(2, starts.get())
        }
    }

    @Test(timeout = 15_000)
    fun splitEndMarkerWaitsForTheCompleteExitCodeLine() {
        val process = FakeProcess { _, _ -> }
        withProvider(process) {
            val call = command()
            val token = process.awaitCommand()
            process.stdout.emit("payload\n__OPERIT_HIDDEN_END__:$token:")
                .awaitBounded("END prefix read")
            call.assertPending()
            process.stdout.emit("2").awaitBounded("partial exit code read")
            call.assertPending()
            process.stdout.emit("3\n")
            assertOk(call.await(), "payload", exitCode = 23)
        }
    }

    @Test(timeout = 15_000)
    fun disconnectRejectsFurtherCallsPromptlyWithoutStartingProcesses() {
        val process = FakeProcess()
        withProvider(process) {
            assertOk(command().await())
            submit { provider.disconnect() }.await()
            assertRetired(process)
            for (key in listOf("same", "other")) {
                val result = command(key = key).await()
                assertFalse(result.isOk)
                assertFalse("disconnected calls must not wait for readiness", result.state == HiddenExecResult.State.TIMEOUT)
            }
            assertEquals(1, starts.get())
        }
    }

    private fun withProvider(
        vararg processes: FakeProcess,
        start: (Int) -> Process = { processes[it] },
        block: Harness.() -> Unit
    ) {
        val harness = Harness(processes.toList(), start)
        try {
            harness.block()
        } finally {
            harness.close()
        }
    }

    private class Harness(
        private val processes: List<FakeProcess>,
        start: (Int) -> Process
    ) {
        val starts = AtomicInteger()
        private val closed = AtomicBoolean()
        private val futures = CopyOnWriteArrayList<Future<*>>()
        private val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "local-terminal-test").apply { isDaemon = true }
        }
        private val directory = Files.createTempDirectory("operit-local-terminal-test-").toFile()
        val provider = LocalTerminalProvider(
            filesDir = directory,
            nativeLibDir = File(directory, "lib").absolutePath
        ) { _ ->
            check(!closed.get()) { "Process started after fixture cleanup" }
            start(starts.getAndIncrement())
        }

        fun command(
            key: String = "same",
            timeoutMs: Long = CALL_DEADLINE_MS,
            text: String = "printf test",
            cancellation: Job = Job()
        ): Future<HiddenExecResult> = submit(cancellation) {
            provider.executeHiddenCommand(text, key, timeoutMs)
        }

        fun <T> submit(
            context: CoroutineContext = EmptyCoroutineContext,
            block: suspend CoroutineScope.() -> T
        ): Future<T> = executor.submit(Callable { runBlocking(context, block) }).also {
            futures.add(it)
        }

        fun close() {
            closed.set(true)
            // Release fake I/O first: regressed writer-close ordering must not hang teardown.
            processes.forEach { it.destroyForcibly() }
            try {
                submit { provider.disconnect() }.await()
            } finally {
                futures.forEach { it.cancel(true) }
                executor.shutdownNow()
                assertTrue("test workers did not stop", executor.awaitTermination(HARD_BOUND_MS, TimeUnit.MILLISECONDS))
                directory.deleteRecursively()
            }
        }
    }

    private class FakeProcess(
        ready: Boolean = true,
        private val stallWrites: Boolean = false,
        private val respond: (FakeProcess, String) -> Unit = { process, token ->
            process.complete(token, "ok")
        }
    ) : Process() {
        val stdout = BlockingInput()
        val destroyed = CountDownLatch(1)
        val writeEntered = CountDownLatch(1)
        val writeReleased = CountDownLatch(1)
        val commandCount = AtomicInteger()
        private val terminated = CountDownLatch(1)
        private val releaseWrite = CountDownLatch(1)
        private val commands = LinkedBlockingQueue<String>()
        private val alive = AtomicBoolean(true)
        @Volatile private var code = 0
        private val written = ByteArrayOutputStream()
        private val stdin = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (stallWrites) {
                    writeEntered.countDown()
                    try {
                        // Match native pipe writes that ignore coroutine/thread interruption.
                        while (releaseWrite.count != 0L) {
                            try {
                                releaseWrite.await()
                            } catch (_: InterruptedException) {
                                // Only destroying this process releases the write.
                            }
                        }
                    } finally {
                        writeReleased.countDown()
                    }
                }
                if (!alive.get()) throw IOException("process stdin closed")
                written.write(bytes, offset, length)
            }

            override fun flush() {
                if (!alive.get()) throw IOException("process stdin closed")
                val envelope = written.toString(Charsets.UTF_8.name())
                if (envelope.isEmpty()) return
                val token = Regex("__OPERIT_HIDDEN_BEGIN__:([^'\\r\\n]+)")
                    .find(envelope)?.groupValues?.get(1)
                    ?: throw IOException("Missing BEGIN token in command envelope")
                check(envelope.contains("__OPERIT_HIDDEN_END__:$token")) {
                    "Missing matching END token in command envelope"
                }
                written.reset()
                commandCount.incrementAndGet()
                // A nonnumeric PID exercises marker filtering without issuing real OS signals.
                stdout.emit("__OPERIT_HIDDEN_BEGIN__:$token\n__OPERIT_HIDDEN_PID__:$token:not-a-pid\n")
                commands.add(token)
                respond(this@FakeProcess, token)
            }
        }

        init {
            if (ready) stdout.emit("TERMINAL_READY\n")
        }

        fun awaitCommand(): String = commands.poll(HARD_BOUND_MS, TimeUnit.MILLISECONDS)
            ?: throw AssertionError("command envelope was not written")

        fun complete(token: String, output: String, exitCode: Int = 0) {
            stdout.emit("$output\n__OPERIT_HIDDEN_END__:$token:$exitCode\n")
        }

        @Synchronized
        fun exit(exitCode: Int, keepStdoutOpen: Boolean = false) {
            if (alive.get()) {
                code = exitCode
                alive.set(false)
            }
            if (!keepStdoutOpen) stdout.close()
            terminated.countDown()
        }

        override fun getInputStream(): InputStream = stdout
        override fun getOutputStream(): OutputStream = stdin
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun isAlive(): Boolean = alive.get()

        override fun exitValue(): Int {
            if (isAlive) throw IllegalThreadStateException("process still alive")
            return code
        }

        override fun waitFor(): Int {
            terminated.await()
            return code
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = terminated.await(timeout, unit)

        override fun destroy() {
            exit(143)
            releaseWrite.countDown()
            destroyed.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    private class BlockingInput : InputStream() {
        private sealed class Event {
            class Bytes(val data: ByteArray, val consumed: CountDownLatch) : Event()
            class Failure(val error: IOException) : Event()
            object End : Event()
        }

        private val events = LinkedBlockingQueue<Event>()
        private var current: Event.Bytes? = null
        private var offset = 0
        private var ended = false
        private val closed = AtomicBoolean()

        fun emit(text: String): CountDownLatch = CountDownLatch(1).also {
            check(!closed.get()) { "Output emitted after process exit" }
            events.add(Event.Bytes(text.toByteArray(Charsets.UTF_8), it))
        }

        fun fail(error: IOException) {
            events.add(Event.Failure(error))
        }

        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
        }

        override fun read(bytes: ByteArray, destination: Int, length: Int): Int {
            if (length == 0) return 0
            if (ended) return -1
            if (current == null) {
                when (val event = events.take()) {
                    is Event.Bytes -> {
                        current = event
                        offset = 0
                    }
                    is Event.Failure -> throw event.error
                    Event.End -> {
                        ended = true
                        return -1
                    }
                }
            }
            val chunk = checkNotNull(current)
            val count = minOf(length, chunk.data.size - offset)
            chunk.data.copyInto(bytes, destination, offset, offset + count)
            offset += count
            if (offset == chunk.data.size) {
                current = null
                chunk.consumed.countDown()
            }
            return count
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) events.add(Event.End)
        }
    }

    private companion object {
        const val DEADLINE_MS = 400L
        const val HARD_BOUND_MS = 2_500L
        const val CALL_DEADLINE_MS = 6_000L

        fun assertOk(result: HiddenExecResult, output: String = "ok", exitCode: Int = 0) {
            assertEquals(result.toString(), HiddenExecResult.State.OK, result.state)
            assertEquals(output, result.output)
            assertEquals(exitCode, result.exitCode)
        }

        fun assertRetired(process: FakeProcess) {
            process.destroyed.awaitBounded("retired process was not destroyed")
            assertFalse(process.isAlive)
        }

        fun CountDownLatch.awaitBounded(description: String) {
            assertTrue(description, await(HARD_BOUND_MS, TimeUnit.MILLISECONDS))
        }

        fun <T> Future<T>.await(): T = try {
            // This wall-clock bound still fires when coroutine cancellation cannot unblock I/O.
            get(HARD_BOUND_MS, TimeUnit.MILLISECONDS)
        } catch (failure: ExecutionException) {
            throw checkNotNull(failure.cause)
        }

        fun Future<*>.assertPending(
            message: String = "call completed before the exit-code line was complete"
        ) {
            try {
                get(150, TimeUnit.MILLISECONDS)
                throw AssertionError(message)
            } catch (_: TimeoutException) {
                // Observe that the call remains pending without using coroutine timeouts.
            }
        }
    }
}
