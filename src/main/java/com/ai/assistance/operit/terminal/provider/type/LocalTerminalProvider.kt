package com.ai.assistance.operit.terminal.provider.type

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.ai.assistance.operit.terminal.Pty
import com.ai.assistance.operit.terminal.TerminalSession
import com.ai.assistance.operit.terminal.provider.filesystem.FileSystemProvider
import com.ai.assistance.operit.terminal.provider.filesystem.LocalFileSystemProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

/**
 * 本地终端提供者
 *
 * 使用 proot + Ubuntu 环境提供本地 Linux 终端。
 * 可见终端继续走 PTY，不可见执行走后台复用 shell。
 */
class LocalTerminalProvider private constructor(
    private val filesDir: File,
    private val nativeLibDir: String,
    private val fileSystemProvider: FileSystemProvider,
    private val startProcess: (ProcessBuilder) -> Process
) : TerminalProvider {

    constructor(context: Context) : this(
        filesDir = context.filesDir,
        nativeLibDir = context.applicationInfo.nativeLibraryDir,
        fileSystemProvider = LocalFileSystemProvider(context),
        startProcess = { it.start() }
    )

    internal constructor(
        filesDir: File,
        nativeLibDir: String,
        startProcess: (ProcessBuilder) -> Process
    ) : this(
        filesDir = filesDir,
        nativeLibDir = nativeLibDir,
        fileSystemProvider = LocalFileSystemProvider(),
        startProcess = startProcess
    )

    private data class HiddenExecShell(
        val key: String,
        val process: Process,
        val writer: java.io.BufferedWriter,
        val outputChannel: Channel<String>,
        val readJob: kotlinx.coroutines.Job,
        val exitJob: kotlinx.coroutines.Job,
        val mutex: Mutex = Mutex(),
        val closed: AtomicBoolean = AtomicBoolean(false)
    )

    private val usrDir: File = File(filesDir, "usr")
    private val binDir: File = File(usrDir, "bin")

    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()
    private val hiddenExecShells = ConcurrentHashMap<String, HiddenExecShell>()
    private val hiddenExecShellMutex = Mutex()
    private val hiddenExecScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "LocalTerminalProvider"
        private const val READY_MARKER = "TERMINAL_READY"
        private const val BEGIN_MARKER_PREFIX = "__OPERIT_HIDDEN_BEGIN__:"
        private const val END_MARKER_PREFIX = "__OPERIT_HIDDEN_END__:"
        private const val PID_MARKER_PREFIX = "__OPERIT_HIDDEN_PID__:"
    }

    override suspend fun isConnected(): Boolean {
        return true
    }

    override suspend fun connect(): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        hiddenExecScope.cancel()
        hiddenExecShells.values.toList().forEach { shell ->
            closeHiddenExecShell(shell)
        }
        activeSessions.keys.toList().forEach { sessionId ->
            closeSession(sessionId)
        }
    }

    override suspend fun startSession(sessionId: String): Result<Pair<TerminalSession, Pty>> {
        return withContext(Dispatchers.IO) {
            try {
                val command = buildVisibleSessionCommand()
                val env = buildEnvironment()

                Log.d(TAG, "Starting local terminal session with command: ${command.joinToString(" ")}")
                Log.d(TAG, "Environment: $env")

                val pty = Pty.start(command, env, filesDir)

                val session = TerminalSession(
                    process = pty.process,
                    stdout = pty.stdout,
                    stdin = pty.stdin
                )

                activeSessions[sessionId] = session
                Result.success(Pair(session, pty))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start local terminal session", e)
                Result.failure(e)
            }
        }
    }

    override suspend fun closeSession(sessionId: String) {
        activeSessions[sessionId]?.let { session ->
            session.process.destroy()
            activeSessions.remove(sessionId)
            Log.d(TAG, "Closed local terminal session: $sessionId")
        }
    }

    override suspend fun executeHiddenCommand(
        command: String,
        executorKey: String,
        timeoutMs: Long
    ): HiddenExecResult {
        var phase = "prepare"
        val token = UUID.randomUUID().toString()
        val output = StringBuilder()
        Log.d(TAG, "Hidden exec requested: key=$executorKey token=$token timeoutMs=$timeoutMs")
        return try {
            withTimeoutOrNull<HiddenExecResult>(timeoutMs) {
                check(hiddenExecScope.isActive) { "Hidden exec provider is disconnected" }
                while (true) {
                    phase = "prepare"
                    val shell = getOrCreateHiddenExecShell(executorKey)
                    phase = "queue"
                    val result: HiddenExecResult? = shell.mutex.withLock {
                        // The previous owner can retire this shell while this call waits for the key.
                        // Re-resolve the key instead of failing a still-live queued call against stale state.
                        if (shell.closed.get() || !shell.process.isAlive) {
                            return@withLock null
                        }
                        var completed = false
                        try {
                            phase = "write"
                            val wrappedCommand = buildHiddenExecEnvelope(command, token)
                            // A blocked pipe write must not make the caller wait for an IO child to finish.
                            val writeJob = hiddenExecScope.async {
                                shell.writer.write(wrappedCommand)
                                shell.writer.flush()
                            }
                            try {
                                writeJob.await()
                            } finally {
                                writeJob.cancel()
                            }
                            phase = "read"
                            val commandResult = collectHiddenExecResult(shell, token, output)
                            completed = commandResult.isOk
                            commandResult
                        } finally {
                            if (!completed) {
                                closeHiddenExecShell(shell, output.toString(), token)
                            }
                        }
                    }
                    if (result != null) {
                        return@withTimeoutOrNull result
                    }
                    closeHiddenExecShell(shell)
                }
                error("Hidden exec lifecycle loop exited unexpectedly")
            } ?: HiddenExecResult(
                output = extractHiddenExecOutput(output.toString(), token),
                exitCode = -1,
                state = HiddenExecResult.State.TIMEOUT,
                error = "Hidden exec timed out after ${timeoutMs}ms during $phase",
                rawOutputPreview = output.toString().takeLast(1200)
            ).also {
                Log.w(TAG, "Hidden exec timed out: key=$executorKey token=$token phase=$phase")
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Hidden exec cancelled: key=$executorKey token=$token phase=$phase")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Hidden exec failed: key=$executorKey token=$token phase=$phase", e)
            HiddenExecResult(
                output = extractHiddenExecOutput(output.toString(), token),
                exitCode = -1,
                state = if (phase == "prepare") HiddenExecResult.State.SHELL_START_FAILED else HiddenExecResult.State.EXECUTION_ERROR,
                error = "Hidden exec failed during $phase: ${e.message.orEmpty()}",
                rawOutputPreview = output.toString().takeLast(1200)
            )
        }
    }

    override fun getFileSystemProvider(): FileSystemProvider {
        return fileSystemProvider
    }

    override suspend fun getWorkingDirectory(): String {
        return filesDir.absolutePath
    }

    override fun getEnvironment(): Map<String, String> {
        return buildEnvironment()
    }

    private suspend fun getOrCreateHiddenExecShell(executorKey: String): HiddenExecShell {
        hiddenExecShells[executorKey]?.let { existing ->
            if (!existing.closed.get() && existing.process.isAlive) {
                return existing
            }
            closeHiddenExecShell(existing)
        }

        return hiddenExecShellMutex.withLock {
            check(hiddenExecScope.isActive) { "Hidden exec provider is disconnected" }
            hiddenExecShells[executorKey]?.let { existing ->
                if (!existing.closed.get() && existing.process.isAlive) {
                    return@withLock existing
                }
                closeHiddenExecShell(existing)
            }

            val created = createHiddenExecShell(executorKey)
            hiddenExecShells[executorKey] = created
            created
        }
    }

    private suspend fun createHiddenExecShell(executorKey: String): HiddenExecShell {
        val process = suspendCancellableCoroutine<Process> { continuation ->
            val startJob = hiddenExecScope.launch {
                try {
                    val process = startProcess(
                        buildProcessBuilder(buildHiddenExecStartupCommand(), redirectErrorStream = true)
                    )
                    // Cancellation can win after native process creation but before ownership is transferred.
                    continuation.resume(process) { _, unclaimed, _ -> destroyHiddenExecProcess(unclaimed) }
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
            startJob.invokeOnCompletion { cause ->
                if (cause != null) continuation.cancel(cause)
            }
            continuation.invokeOnCancellation { startJob.cancel() }
        }
        val outputChannel = Channel<String>(Channel.UNLIMITED)
        val readJob = hiddenExecScope.launch {
            try {
                val buffer = ByteArray(4096)
                while (isActive) {
                    val count = process.inputStream.read(buffer)
                    if (count < 0) break
                    if (count > 0) outputChannel.send(String(buffer, 0, count, Charsets.UTF_8))
                }
                outputChannel.close()
            } catch (e: Exception) {
                outputChannel.close(e)
                if (isActive) Log.e(TAG, "Hidden exec reader failed for $executorKey", e)
            }
        }
        val exitJob = hiddenExecScope.launch {
            runInterruptible { process.waitFor() }
            // Descendants can keep stdout open after the shell itself has exited.
            outputChannel.close()
        }
        val shell = HiddenExecShell(
            key = executorKey,
            process = process,
            writer = process.outputStream.bufferedWriter(Charsets.UTF_8),
            outputChannel = outputChannel,
            readJob = readJob,
            exitJob = exitJob
        )
        try {
            awaitHiddenExecReady(shell)
            currentCoroutineContext().ensureActive()
            check(hiddenExecScope.isActive) { "Hidden exec provider is disconnected" }
            Log.d(TAG, "Created hidden exec shell: $executorKey")
            return shell
        } catch (e: Exception) {
            closeHiddenExecShell(shell)
            throw e
        }
    }

    private suspend fun awaitHiddenExecReady(shell: HiddenExecShell) {
        val output = StringBuilder()
        while (true) {
            currentCoroutineContext().ensureActive()
            val chunk = shell.outputChannel.receiveCatching().getOrThrow()
            logHiddenExecChunk(shell.key, "ready", chunk)
            output.append(chunk)
            if (output.indexOf(READY_MARKER) >= 0) return
        }
    }

    private suspend fun collectHiddenExecResult(
        shell: HiddenExecShell,
        token: String,
        output: StringBuilder
    ): HiddenExecResult {
        val endMarkerPrefix = "$END_MARKER_PREFIX$token:"
        while (true) {
            currentCoroutineContext().ensureActive()
            val received = shell.outputChannel.receiveCatching()
            received.exceptionOrNull()?.let { throw it }
            if (received.isClosed) {
                return HiddenExecResult(
                    output = extractHiddenExecOutput(output.toString(), token),
                    exitCode = -1,
                    state = HiddenExecResult.State.PROCESS_EXITED,
                    error = "Hidden exec shell output closed before command completion",
                    rawOutputPreview = output.toString().takeLast(1200)
                )
            }
            val chunk = received.getOrThrow()
            logHiddenExecChunk(shell.key, token, chunk)
            output.append(chunk)
            val endIndex = output.indexOf(endMarkerPrefix)
            if (endIndex >= 0 && output.indexOf("\n", endIndex + endMarkerPrefix.length) >= 0) {
                return parseHiddenExecOutput(output.toString(), token)
            }
        }
    }

    private fun parseHiddenExecOutput(
        rawOutput: String,
        token: String
    ): HiddenExecResult {
        val beginMarker = "$BEGIN_MARKER_PREFIX$token"
        val endMarkerPrefix = "$END_MARKER_PREFIX$token:"
        val beginIndex = rawOutput.indexOf(beginMarker)
        if (beginIndex < 0) {
            return HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.MISSING_BEGIN_MARKER,
                error = "Hidden exec output missing begin marker",
                rawOutputPreview = rawOutput.takeLast(1200)
            )
        }

        val payloadStart = beginIndex + beginMarker.length
        val endIndex = rawOutput.indexOf(endMarkerPrefix, payloadStart)
        if (endIndex < 0) {
            return HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.MISSING_END_MARKER,
                error = "Hidden exec output missing end marker",
                rawOutputPreview = rawOutput.takeLast(1200)
            )
        }

        val commandOutput = extractHiddenExecOutput(rawOutput, token)

        val exitCodeText =
            rawOutput
                .substring(endIndex + endMarkerPrefix.length)
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: return HiddenExecResult(
                    output = commandOutput,
                    exitCode = -1,
                    state = HiddenExecResult.State.INVALID_EXIT_CODE,
                    error = "Hidden exec output missing exit code",
                    rawOutputPreview = rawOutput.takeLast(1200)
                )

        val exitCode =
            exitCodeText.toIntOrNull()
                ?: return HiddenExecResult(
                    output = commandOutput,
                    exitCode = -1,
                    state = HiddenExecResult.State.INVALID_EXIT_CODE,
                    error = "Invalid hidden exec exit code: $exitCodeText",
                    rawOutputPreview = rawOutput.takeLast(1200)
                )

        return HiddenExecResult(
            output = commandOutput,
            exitCode = exitCode
        )
    }

    private fun cancelHiddenExecCommand(rawOutput: String, token: String) {
        val pid = extractHiddenExecPid(rawOutput, token) ?: return
        runCatching {
            Os.kill((-pid).toInt(), OsConstants.SIGTERM)
        }.onFailure { error ->
            Log.w(TAG, "Failed to send SIGTERM to hidden exec process group $pid", error)
        }
        Thread.sleep(100)
        runCatching {
            Os.kill((-pid).toInt(), OsConstants.SIGKILL)
        }.onFailure { error ->
            Log.w(TAG, "Failed to send SIGKILL to hidden exec process group $pid", error)
        }
    }

    private fun extractHiddenExecPid(rawOutput: String, token: String): Long? {
        val pidMarkerPrefix = "$PID_MARKER_PREFIX$token:"
        val markerIndex = rawOutput.indexOf(pidMarkerPrefix)
        if (markerIndex < 0) {
            return null
        }
        val pidText =
            rawOutput
                .substring(markerIndex + pidMarkerPrefix.length)
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
        return pidText?.toLongOrNull()
    }

    private fun extractHiddenExecOutput(rawOutput: String, token: String): String {
        val beginMarker = "$BEGIN_MARKER_PREFIX$token"
        val beginIndex = rawOutput.indexOf(beginMarker)
        if (beginIndex < 0) {
            return ""
        }
        val payloadStart = beginIndex + beginMarker.length
        val endMarkerPrefix = "$END_MARKER_PREFIX$token:"
        val endIndex = rawOutput.indexOf(endMarkerPrefix, payloadStart).let { index ->
            if (index >= 0) index else rawOutput.length
        }
        return rawOutput
            .substring(payloadStart, endIndex)
            .lineSequence()
            .filterNot { it.startsWith("$PID_MARKER_PREFIX$token:") }
            .joinToString("\n")
            .trimStart('\n', '\r')
            .trimEnd('\n', '\r')
    }

    private fun closeHiddenExecShell(shell: HiddenExecShell, rawOutput: String = "", token: String = "") {
        if (!shell.closed.compareAndSet(false, true)) return
        hiddenExecShells.remove(shell.key, shell)
        shell.outputChannel.cancel()
        shell.readJob.cancel()
        shell.exitJob.cancel()
        hiddenExecScope.launch(NonCancellable) {
            try {
                cancelHiddenExecCommand(rawOutput, token)
            } finally {
                // Destroy before closing the buffered writer: close can flush into the same blocked pipe.
                runCatching { shell.process.destroyForcibly() }
                    .onFailure { Log.w(TAG, "Failed to destroy hidden exec shell: ${shell.key}", it) }
                runCatching { shell.writer.close() }
                    .onFailure { Log.w(TAG, "Failed to close hidden exec writer: ${shell.key}", it) }
                runCatching { shell.process.inputStream.close() }
                    .onFailure { Log.w(TAG, "Failed to close hidden exec output: ${shell.key}", it) }
            }
            Log.d(TAG, "Closed hidden exec shell: ${shell.key}")
        }
    }

    private fun destroyHiddenExecProcess(process: Process) {
        hiddenExecScope.launch(NonCancellable) {
            runCatching { process.destroyForcibly() }
                .onFailure { Log.w(TAG, "Failed to destroy unclaimed hidden exec process", it) }
        }
    }

    private fun buildVisibleSessionCommand(): Array<String> {
        val bash = File(binDir, "bash").absolutePath
        val startScript = "source \$HOME/common.sh && start_shell"
        return arrayOf(bash, "-c", startScript)
    }

    private fun buildHiddenExecStartupCommand(): Array<String> {
        val bash = File(binDir, "bash").absolutePath
        val startScript = "source \$HOME/common.sh && login_ubuntu '/bin/bash --noprofile --norc'"
        return arrayOf(bash, "-c", startScript)
    }

    private fun buildHiddenExecEnvelope(command: String, token: String): String {
        val normalizedCommand = command.replace("\r\n", "\n").replace("\r", "\n")
        val tokenSuffix = token.replace("-", "")
        val heredocMarker = "__OPERIT_HIDDEN_CMD_${tokenSuffix}__"
        return buildString {
            append("printf '%s\\n' '$BEGIN_MARKER_PREFIX$token'\n")
            append("__operit_hidden_script=\"\${TMPDIR:-/tmp}/operit_hidden_$tokenSuffix.sh\"\n")
            append("cat >\"\$__operit_hidden_script\" <<'$heredocMarker'\n")
            append(normalizedCommand)
            if (!normalizedCommand.endsWith("\n")) {
                append('\n')
            }
            append("$heredocMarker\n")
            append("setsid /bin/bash --noprofile --norc \"\$__operit_hidden_script\" </dev/null &\n")
            append("__operit_hidden_pid=\$!\n")
            append("printf '%s:%s\\n' '$PID_MARKER_PREFIX$token' \"\$__operit_hidden_pid\"\n")
            append("wait \"\$__operit_hidden_pid\"\n")
            append("__operit_hidden_rc=\$?\n")
            append("rm -f \"\$__operit_hidden_script\"\n")
            append("printf '%s:%s\\n' '$END_MARKER_PREFIX$token' \"\$__operit_hidden_rc\"\n")
        }
    }

    private fun logHiddenExecChunk(
        executorKey: String,
        token: String,
        chunk: String
    ) {
        chunk.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                line.chunked(3000).forEach { part ->
                    Log.d(TAG, "Hidden exec output [$executorKey][$token]: $part")
                }
            }
    }

    private fun buildProcessBuilder(
        command: Array<String>,
        redirectErrorStream: Boolean
    ): ProcessBuilder {
        val processBuilder = ProcessBuilder(*command)
        processBuilder.directory(filesDir)
        processBuilder.redirectErrorStream(redirectErrorStream)
        val environment = processBuilder.environment()
        environment.clear()
        environment.putAll(buildEnvironment())
        return processBuilder
    }

    private fun buildEnvironment(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["PATH"] = "${binDir.absolutePath}:${System.getenv("PATH")}"
        env["HOME"] = filesDir.absolutePath
        env["PREFIX"] = usrDir.absolutePath
        env["TERMUX_PREFIX"] = usrDir.absolutePath
        env["LD_LIBRARY_PATH"] = "${nativeLibDir}:${binDir.absolutePath}"
        env["PROOT_LOADER"] = File(binDir, "loader").absolutePath
        env["TMPDIR"] = File(filesDir, "tmp").absolutePath
        env["PROOT_TMP_DIR"] = File(filesDir, "tmp").absolutePath
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
        return env
    }
}
