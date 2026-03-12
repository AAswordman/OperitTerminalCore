package com.ai.assistance.operit.terminal.provider.type

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.terminal.Pty
import com.ai.assistance.operit.terminal.TerminalSession
import com.ai.assistance.operit.terminal.provider.filesystem.FileSystemProvider
import com.ai.assistance.operit.terminal.provider.filesystem.LocalFileSystemProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地终端提供者
 *
 * 使用 proot + Ubuntu 环境提供本地 Linux 终端。
 * 可见终端继续走 PTY，不可见执行走后台复用 shell。
 */
class LocalTerminalProvider(
    private val context: Context
) : TerminalProvider {

    private data class HiddenExecShell(
        val key: String,
        val process: Process,
        val writer: java.io.BufferedWriter,
        val outputChannel: Channel<String>,
        val readJob: kotlinx.coroutines.Job,
        val mutex: Mutex = Mutex()
    )

    private val filesDir: File = context.filesDir
    private val usrDir: File = File(filesDir, "usr")
    private val binDir: File = File(usrDir, "bin")
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir

    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()
    private val hiddenExecShells = ConcurrentHashMap<String, HiddenExecShell>()
    private val hiddenExecShellMutex = Mutex()
    private val hiddenExecScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fileSystemProvider = LocalFileSystemProvider(context)

    companion object {
        private const val TAG = "LocalTerminalProvider"
        private const val READY_MARKER = "TERMINAL_READY"
        private const val BEGIN_MARKER_PREFIX = "__OPERIT_HIDDEN_BEGIN__:"
        private const val END_MARKER_PREFIX = "__OPERIT_HIDDEN_END__:"
    }

    override suspend fun isConnected(): Boolean {
        return true
    }

    override suspend fun connect(): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        activeSessions.keys.toList().forEach { sessionId ->
            closeSession(sessionId)
        }
        hiddenExecShells.keys.toList().forEach { key ->
            closeHiddenExecShell(key)
        }
        hiddenExecScope.cancel()
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
        val shell =
            try {
                getOrCreateHiddenExecShell(executorKey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare hidden exec shell: $executorKey", e)
                return HiddenExecResult(
                    output = "",
                    exitCode = -1,
                    state = HiddenExecResult.State.SHELL_START_FAILED,
                    error = e.message ?: "Failed to prepare hidden exec shell"
                )
            }

        return shell.mutex.withLock {
            val token = UUID.randomUUID().toString()
            val wrappedCommand = buildHiddenExecEnvelope(command, token)
            return@withLock try {
                withContext(Dispatchers.IO) {
                    shell.writer.write(wrappedCommand)
                    shell.writer.flush()
                }
                collectHiddenExecResult(shell, token, timeoutMs)
            } catch (e: TimeoutCancellationException) {
                closeHiddenExecShell(executorKey)
                HiddenExecResult(
                    output = "",
                    exitCode = -1,
                    state = HiddenExecResult.State.TIMEOUT,
                    error = "Hidden exec command timed out after ${timeoutMs}ms"
                )
            } catch (e: Exception) {
                closeHiddenExecShell(executorKey)
                Log.e(TAG, "Failed to execute hidden command in shell: $executorKey", e)
                HiddenExecResult(
                    output = "",
                    exitCode = -1,
                    state = HiddenExecResult.State.EXECUTION_ERROR,
                    error = e.message ?: "Failed to execute hidden command"
                )
            }
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
            if (existing.process.isAlive) {
                return existing
            }
            closeHiddenExecShell(executorKey)
        }

        return hiddenExecShellMutex.withLock {
            hiddenExecShells[executorKey]?.let { existing ->
                if (existing.process.isAlive) {
                    return@withLock existing
                }
                closeHiddenExecShell(executorKey)
            }

            val created = createHiddenExecShell(executorKey)
            hiddenExecShells[executorKey] = created
            created
        }
    }

    private suspend fun createHiddenExecShell(executorKey: String): HiddenExecShell {
        val process =
            withContext(Dispatchers.IO) {
                buildProcessBuilder(
                    command = buildHiddenExecStartupCommand(),
                    redirectErrorStream = true
                ).start()
            }
        val outputChannel = Channel<String>(Channel.UNLIMITED)
        val readJob =
            hiddenExecScope.launch {
                val input = process.inputStream
                val buffer = ByteArray(4096)
                try {
                    while (isActive) {
                        val count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        if (count > 0) {
                            outputChannel.send(String(buffer, 0, count, Charsets.UTF_8))
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Hidden exec reader failed for $executorKey", e)
                    }
                } finally {
                    outputChannel.close()
                }
            }

        val shell =
            HiddenExecShell(
                key = executorKey,
                process = process,
                writer = process.outputStream.bufferedWriter(Charsets.UTF_8),
                outputChannel = outputChannel,
                readJob = readJob
            )

        val readyResult = awaitHiddenExecReady(shell)
        if (!readyResult.isOk) {
            closeHiddenExecShell(executorKey)
            throw IllegalStateException(
                readyResult.error.ifBlank { "Hidden exec shell did not become ready" }
            )
        }

        Log.d(TAG, "Created hidden exec shell: $executorKey")
        return shell
    }

    private suspend fun awaitHiddenExecReady(shell: HiddenExecShell): HiddenExecResult {
        return try {
            val rawOutput: String =
                withTimeout(30000L) {
                    val builder = StringBuilder()
                    while (true) {
                        val chunk =
                            shell.outputChannel.receiveCatching().getOrNull()
                                ?: break
                        builder.append(chunk)
                        if (builder.indexOf(READY_MARKER) >= 0) {
                            break
                        }
                    }
                    builder.toString()
                }

            if (rawOutput.contains(READY_MARKER)) {
                HiddenExecResult(output = "", exitCode = 0)
            } else if (!shell.process.isAlive) {
                HiddenExecResult(
                    output = "",
                    exitCode = -1,
                    state = HiddenExecResult.State.PROCESS_EXITED,
                    error = "Hidden exec shell exited before ready",
                    rawOutputPreview = rawOutput.takeLast(1200)
                )
            } else {
                HiddenExecResult(
                    output = "",
                    exitCode = -1,
                    state = HiddenExecResult.State.SHELL_NOT_READY,
                    error = "Hidden exec shell did not emit ready marker",
                    rawOutputPreview = rawOutput.takeLast(1200)
                )
            }
        } catch (e: TimeoutCancellationException) {
            HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.TIMEOUT,
                error = "Timed out while waiting for hidden exec shell to become ready"
            )
        }
    }

    private suspend fun collectHiddenExecResult(
        shell: HiddenExecShell,
        token: String,
        timeoutMs: Long
    ): HiddenExecResult {
        val endMarkerPrefix = "$END_MARKER_PREFIX$token:"
        val rawOutput: String =
            withTimeout(timeoutMs) {
                val builder = StringBuilder()
                while (true) {
                    val chunk =
                        shell.outputChannel.receiveCatching().getOrNull()
                            ?: break
                    builder.append(chunk)
                    if (builder.indexOf(endMarkerPrefix) >= 0) {
                        break
                    }
                }
                builder.toString()
            }
        if (rawOutput.indexOf(endMarkerPrefix) < 0 && !shell.process.isAlive) {
            return HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.PROCESS_EXITED,
                error = "Hidden exec shell exited while executing command",
                rawOutputPreview = rawOutput.takeLast(1200)
            )
        }
        return parseHiddenExecOutput(rawOutput, token)
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

        val commandOutput =
            rawOutput
                .substring(payloadStart, endIndex)
                .trimStart('\n', '\r')
                .trimEnd('\n', '\r')

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

    private suspend fun closeHiddenExecShell(executorKey: String) {
        hiddenExecShells.remove(executorKey)?.let { shell ->
            withContext(Dispatchers.IO) {
                runCatching { shell.writer.close() }
                runCatching { shell.process.destroy() }
                runCatching { shell.readJob.cancel() }
                runCatching { shell.outputChannel.close() }
            }
            Log.d(TAG, "Closed hidden exec shell: $executorKey")
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
            append("/bin/bash --noprofile --norc \"\$__operit_hidden_script\" </dev/null\n")
            append("__operit_hidden_rc=\$?\n")
            append("rm -f \"\$__operit_hidden_script\"\n")
            append("printf '%s:%s\\n' '$END_MARKER_PREFIX$token' \"\$__operit_hidden_rc\"\n")
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
