package com.ai.assistance.operit.terminal

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.launch
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import com.ai.assistance.operit.terminal.data.TerminalState
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.QueuedCommand
import com.ai.assistance.operit.terminal.view.domain.OutputProcessor
import java.util.UUID
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import com.ai.assistance.operit.terminal.data.PackageManagerType
import com.ai.assistance.operit.terminal.data.SessionInitState
import com.ai.assistance.operit.terminal.utils.SourceManager
import com.ai.assistance.operit.terminal.utils.SSHConfigManager
import com.ai.assistance.operit.terminal.provider.filesystem.FileSystemProvider
import com.ai.assistance.operit.terminal.provider.filesystem.LocalFileSystemProvider
import com.ai.assistance.operit.terminal.provider.type.TerminalProvider
import com.ai.assistance.operit.terminal.provider.type.TerminalType
import com.ai.assistance.operit.terminal.provider.type.LocalTerminalProvider
import com.ai.assistance.operit.terminal.provider.type.SSHTerminalProvider
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator

class TerminalManager private constructor(
    private val context: Context
) {
    internal val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val envInitMutex = Mutex()
    private var isEnvInitialized = false

    private val filesDir: File = context.filesDir
    private val usrDir: File = File(filesDir, "usr")
    private val binDir: File = File(usrDir, "bin")
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()

    // 核心组件
    private val sessionManager = SessionManager(this)
    private val outputProcessor = OutputProcessor(
        onCommandExecutionEvent = { event ->
            coroutineScope.launch {
                _commandExecutionEvents.emit(event)
            }
        },
        onDirectoryChangeEvent = { event ->
            coroutineScope.launch {
                _directoryChangeEvents.emit(event)
            }
        },
        onCommandCompleted = { sessionId ->
            coroutineScope.launch {
                processNextQueuedCommand(sessionId)
            }
        }
    )
    private val sourceManager = SourceManager(context)
    private val sshConfigManager = SSHConfigManager(context)
    
    // 单例的 TerminalProvider
    private var terminalProvider: TerminalProvider? = null
    private val providerMutex = Mutex()

    // 状态和事件流
    private val _commandExecutionEvents = MutableSharedFlow<CommandExecutionEvent>()
    val commandExecutionEvents: SharedFlow<CommandExecutionEvent> = _commandExecutionEvents.asSharedFlow()

    private val _directoryChangeEvents = MutableSharedFlow<SessionDirectoryEvent>()
    val directoryChangeEvents: SharedFlow<SessionDirectoryEvent> = _directoryChangeEvents.asSharedFlow()

    // 暴露会话管理器的状态
    val terminalState: StateFlow<TerminalState> = sessionManager.state

    // 为了向后兼容，提供单独的状态流
    val sessions = terminalState.map { it.sessions }
    val currentSessionId = terminalState.map { it.currentSessionId }
    val currentDirectory = terminalState.map { it.currentSession?.currentDirectory ?: "$ " }
    val isInteractiveMode = terminalState.map { it.currentSession?.isInteractiveMode ?: false }
    val interactivePrompt = terminalState.map { it.currentSession?.interactivePrompt ?: "" }
    val isFullscreen = terminalState.map { it.currentSession?.isFullscreen ?: false }
    val terminalEmulator = terminalState.map { it.currentSession?.ansiParser ?: AnsiTerminalEmulator() }

    companion object {
        @Volatile
        private var INSTANCE: TerminalManager? = null

        fun getInstance(context: Context): TerminalManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TerminalManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val TAG = "TerminalManager"
        private const val UBUNTU_FILENAME = "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
        private const val MAX_HISTORY_ITEMS = 500
        private const val MAX_OUTPUT_LINES_PER_ITEM = 1000
    }

    init {
        // 在初始化时异步创建默认session
        coroutineScope.launch {
            try {
                Log.d(TAG, "Creating default session...")
                // 自动检测：如果有SSH配置则创建SSH会话，否则创建本地会话
                val sshConfig = sshConfigManager.getConfig()
                val isEnabled = sshConfigManager.isEnabled()
                if (sshConfig != null && isEnabled) {
                    Log.d(TAG, "Found SSH config, creating SSH session")
                    createNewSession("SSH")
                } else {
                    Log.d(TAG, "No SSH config, creating local session")
                    createNewSession("Local")
                }
                Log.d(TAG, "Default session created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create default session", e)
            }
        }
    }

    /**
     * 创建新会话 - 同步等待初始化完成
     * 自动检测终端类型：如果配置了SSH则使用SSH，否则使用本地终端
     * 
     * @param title 会话标题
     */
    suspend fun createNewSession(
        title: String? = null
    ): TerminalSessionData {
        // 自动检测终端类型
        val terminalType = if (sshConfigManager.getConfig() != null && sshConfigManager.isEnabled()) {
            TerminalType.SSH
        } else {
            TerminalType.LOCAL
        }
        
        val newSession = sessionManager.createNewSession(title, terminalType)

        // 异步初始化会话
        coroutineScope.launch {
            initializeSession(newSession.id)
        }

        // 等待会话初始化完成
        val success = withTimeoutOrNull(30000) { // 30秒超时
            terminalState.first { state ->
                val session = state.sessions.find { it.id == newSession.id }
                session?.initState == com.ai.assistance.operit.terminal.data.SessionInitState.READY
            }
        }

        if (success == null) {
            Log.e(TAG, "Session initialization timeout for session: ${newSession.id}")
            // 初始化失败，移除会话
            sessionManager.closeSession(newSession.id)
            throw Exception("Session initialization timeout")
        }

        Log.d(TAG, "Session ${newSession.id} initialized successfully")
        return sessionManager.getSession(newSession.id) ?: newSession
    }

    /**
     * 切换到会话
     */
    fun switchToSession(sessionId: String) {
        sessionManager.switchToSession(sessionId)
    }

    /**
     * 关闭会话
     */
    fun closeSession(sessionId: String) {
        sessionManager.closeSession(sessionId)
    }

    private fun createBusyboxSymlinks() {
        val links = listOf(
            "awk", "ash", "basename", "bzip2", "curl", "cp", "chmod", "cut", "cat", "du", "dd",
            "find", "grep", "gzip", "hexdump", "head", "id", "lscpu", "mkdir", "realpath", "rm",
            "sed", "stat", "sh", "tr", "tar", "uname", "xargs", "xz", "xxd"
        )
        val busybox = File(binDir, "busybox")
        for (linkName in links) {
            try {
                createSymbolicLink(busybox, linkName, binDir, true)
                Log.d(TAG, "Created busybox link for '$linkName'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create link for '$linkName'", e)
            }
        }
        try {
            val fileLink = File(binDir, "file")
            if (!fileLink.exists()) {
                Files.createSymbolicLink(fileLink.toPath(), File("/system/bin/file").toPath())
                Log.d(TAG, "Created symlink for 'file'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create symlink for 'file'", e)
        }
    }

    /**
     * 发送命令
     */
    suspend fun sendCommand(command: String, commandId: String? = null): String {
        val actualCommandId = commandId ?: UUID.randomUUID().toString()
        val session = sessionManager.getCurrentSession() ?: return actualCommandId

        // 如果会话在交互模式，直接发送输入（不创建命令历史）
        if (session.isInteractiveMode) {
            Log.d(TAG, "Session in interactive mode, sending as input: $command")
            sendInput(command + "\n")
            return actualCommandId
        }

        session.commandMutex.withLock {
            if (session.currentExecutingCommand?.isExecuting == true) {
                // 有命令正在执行，将新命令加入队列
                session.commandQueue.add(QueuedCommand(actualCommandId, command))
                Log.d(TAG, "Command queued: $command (id: $actualCommandId). Queue size: ${session.commandQueue.size}")
            } else {
                // 没有命令在执行，直接执行
                executeCommandInternal(command, session, actualCommandId)
            }
        }
        return actualCommandId
    }

    /**
     * 向指定会话发送命令（不切换当前会话）
     */
    suspend fun sendCommandToSession(sessionId: String, command: String, commandId: String? = null): String {
        val actualCommandId = commandId ?: UUID.randomUUID().toString()
        val session = sessionManager.getSession(sessionId) ?: return actualCommandId

        // 如果会话在交互模式，直接发送输入（不创建命令历史）
        if (session.isInteractiveMode) {
            Log.d(TAG, "Session $sessionId in interactive mode, sending as input: $command")
            try {
                session.sessionWriter?.write(command + "\n")
                session.sessionWriter?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending input to session $sessionId", e)
            }
            return actualCommandId
        }

        session.commandMutex.withLock {
            if (session.currentExecutingCommand?.isExecuting == true) {
                // 有命令正在执行，将新命令加入队列
                session.commandQueue.add(QueuedCommand(actualCommandId, command))
                Log.d(TAG, "Command queued for session $sessionId: $command (id: $actualCommandId). Queue size: ${session.commandQueue.size}")
            } else {
                // 没有命令在执行，直接执行
                executeCommandInternal(command, session, actualCommandId)
            }
        }
        return actualCommandId
    }

    /**
     * 处理队列中的下一个命令
     */
    private suspend fun processNextQueuedCommand(sessionId: String) {
        val session = sessionManager.getSession(sessionId) ?: return

        session.commandMutex.withLock {
            if (session.currentExecutingCommand?.isExecuting == true) {
                Log.w(TAG, "processNextQueuedCommand called, but a command is still executing. This should not happen.")
                return@withLock
            }

            if (session.commandQueue.isNotEmpty()) {
                val nextCommand = session.commandQueue.removeAt(0)
                Log.d(TAG, "Processing next queued command: ${nextCommand.command} (id: ${nextCommand.id}). Queue size: ${session.commandQueue.size}")
                executeCommandInternal(nextCommand.command, session, nextCommand.id)
            }
        }
    }

    /**
     * 内部执行命令的函数, 必须在 commandMutex 锁内部调用
     */
    private suspend fun executeCommandInternal(command: String, session: TerminalSessionData, commandId: String) {
        if (command.trim() == "clear") {
            try {
                session.sessionWriter?.write("clear\n")
                session.sessionWriter?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending 'clear' command", e)
            }
        } else {
            handleRegularCommand(command, session, commandId)
            try {
                val fullInput = "$command\n"
                session.sessionWriter?.write(fullInput)
                session.sessionWriter?.flush()
                Log.d(TAG, "Sent command to PTY: $command")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending command", e)
            }
        }
    }

    /**
     * 发送输入
     */
    fun sendInput(input: String) {
        coroutineScope.launch(Dispatchers.IO) {
            val session = sessionManager.getCurrentSession() ?: return@launch

            try {
                session.sessionWriter?.write(input)
                session.sessionWriter?.flush()
                Log.d(TAG, "Sent input: '$input'")

                // 如果用户提供了交互式输入，则重置等待状态
                if (session.isWaitingForInteractiveInput) {
                    sessionManager.updateSession(session.id) {
                        it.copy(isWaitingForInteractiveInput = false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending input", e)
            }
        }
    }

    /**
     * 发送中断信号
     */
    fun sendInterruptSignal() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val currentSession = sessionManager.getCurrentSession()
                currentSession?.sessionWriter?.apply {
                    write(3) // ETX character (Ctrl+C)
                    flush()
                    Log.d(TAG, "Sent interrupt signal (Ctrl+C) to session ${currentSession.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending interrupt signal", e)
                val currentSession = sessionManager.getCurrentSession()
            }
        }
    }

    private fun initializeSession(sessionId: String) {
        coroutineScope.launch {
            val success = initializeEnvironment()
            if (success) {
                startSession(sessionId)
            }
        }
    }

    private fun startSession(sessionId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting session...")

                // 获取单例的终端提供者
                val provider = getTerminalProvider()

                // 启动终端会话
                val result = provider.startSession(sessionId)
                val (terminalSession, pty) = result.getOrThrow()
                val sessionWriter = terminalSession.stdin.writer()

                // 启动读取协程
                val readJob = launch {
                    try {
                        terminalSession.stdout.use { inputStream ->
                            val buffer = ByteArray(4096)
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                val chunk = String(buffer, 0, bytesRead)
                                Log.d(TAG, "Read chunk: '$chunk'")
                                outputProcessor.processOutput(sessionId, chunk, sessionManager)
                            }
                        }
                    } catch (e: java.io.InterruptedIOException) {
                        Log.i(TAG, "Read job interrupted for session $sessionId.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in read job for session $sessionId", e)
                    }
                }

                // 更新会话信息
                sessionManager.updateSession(sessionId) { session ->
                    session.copy(
                        terminalSession = terminalSession,
                        pty = pty,
                        sessionWriter = sessionWriter,
                        readJob = readJob
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting session", e)
            }
        }
    }
    
    /**
     * 获取或创建单例的终端提供者
     */
    private suspend fun getTerminalProvider(): TerminalProvider {
        providerMutex.withLock {
            if (terminalProvider == null) {
                val sshConfig = sshConfigManager.getConfig()
                val provider = if (sshConfig != null && sshConfigManager.isEnabled()) {
                    Log.d(TAG, "Creating singleton SSH terminal provider")
                    SSHTerminalProvider(context, sshConfig, this)
                } else {
                    Log.d(TAG, "Creating singleton local terminal provider")
                    LocalTerminalProvider(context)
                }
                provider.connect().getOrThrow()
                terminalProvider = provider
            }
        }
        return terminalProvider!!
    }

    suspend fun initializeEnvironment(): Boolean {
        if (isEnvInitialized) return true

        envInitMutex.lock()
        try {
            if (isEnvInitialized) {
                return true
            }

            val success = withContext(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Starting environment initialization...")

                    // 1. Create necessary directories
                    createDirectories()

                    // 2. Link native libraries
                    linkNativeLibs()
                    createBusyboxSymlinks()

                    // 3. Extract assets
                    extractAssets()

                    // 4. Generate and write startup script
                    val startScript = generateStartScript()
                    File(filesDir, "common.sh").writeText(startScript)


                    Log.d(TAG, "Environment initialization completed successfully.")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Environment initialization failed", e)
                    false
                }
            }
            if (success) {
                isEnvInitialized = true
            }
            return success
        } finally {
            envInitMutex.unlock()
        }
    }

    private fun createDirectories() {
        if (!usrDir.exists()) {
            usrDir.mkdirs()
        }
        if (!binDir.exists()) {
            binDir.mkdirs()
            Log.d(TAG, "Created bin directory at: ${binDir.absolutePath}")
        }
        File(filesDir, "tmp").mkdirs()
    }

    private fun linkNativeLibs() {
        Log.d(TAG, "Linking native libraries from: $nativeLibDir")

        val nativeLibDirFile = File(nativeLibDir)
        if (!nativeLibDirFile.exists() || !nativeLibDirFile.isDirectory) {
            Log.e(TAG, "Native library directory not found or is not a directory.")
            return
        }

        Log.d(TAG, "Native lib directory contents:")
        nativeLibDirFile.listFiles()?.forEach { file ->
            Log.d(TAG, "  - ${file.name} (file ${file.length()} bytes)")
        }

        val busybox = File(binDir, "busybox")

        // First, we need to link busybox itself so we can use it.
        val busyboxSo = File(nativeLibDir, "libbusybox.so")
        Log.d(TAG, "Checking busybox: libbusybox.so exists = ${busyboxSo.exists()}, busybox exists = ${busybox.exists()}")

        if (!busyboxSo.exists()) {
            Log.e(TAG, "libbusybox.so not found, cannot create busybox link")
            return
        }

        // Always ensure proper busybox link - remove any existing file/broken link first
        try {
            val link = busybox.toPath()
            val target = busyboxSo.toPath()

            // Delete existing file/broken link if it exists to prevent FileAlreadyExistsException
            Files.deleteIfExists(link)

            // CRITICAL: Set execute permission on the target .so file before creating symlink
            busyboxSo.setExecutable(true, false)

            // Create the symbolic link
            Files.createSymbolicLink(link, target)
            Log.d(TAG, "Created busybox symbolic link using Java NIO")

            // Verify the link was created successfully and is functional
            if (busybox.exists() && busybox.canExecute()) {
                Log.d(TAG, "Verification: busybox link exists and is executable at ${busybox.absolutePath}")
            } else {
                Log.e(TAG, "Verification failed: busybox link not functional after creation")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create busybox link using Java NIO", e)
            return
        }

        // Symlink other binaries
        val libraries = mapOf(
            "libproot.so" to "proot",
            "libloader.so" to "loader",
            "liblibtalloc.so.2.so" to "libtalloc.so.2", // Keep .so extension for libs
            "libbash.so" to "bash",
            "libsudo.so" to "sudo"
        )

        libraries.forEach { (libName, linkName) ->
            val libFile = File(nativeLibDir, libName)
            val linkFile = File(binDir, linkName)

            Log.d(TAG, "Checking $libName at ${libFile.absolutePath}, exists: ${libFile.exists()}")

            if (!libFile.exists()) {
                Log.w(TAG, "Native library not found: $libName")
                return@forEach
            }

            // Always ensure proper link - remove any existing file/broken link first
            try {
                val link = linkFile.toPath()
                val target = libFile.toPath()

                // Delete existing file/broken link if it exists to prevent FileAlreadyExistsException
                Files.deleteIfExists(link)

                // CRITICAL: Set execute permission on the target .so file before creating symlink
                libFile.setExecutable(true, false)

                // Create the symbolic link
                Files.createSymbolicLink(link, target)
                Log.d(TAG, "Created $linkName symbolic link using Java NIO")

                // Verify the link was created successfully and is executable
                if (linkFile.exists() && linkFile.canExecute()) {
                    Log.d(TAG, "Verification: $linkName link exists and is executable at ${linkFile.absolutePath}")
                } else {
                    Log.w(TAG, "Verification failed: $linkName link not executable after creation")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create $linkName link using Java NIO", e)
            }
        }
    }

    @Throws(IOException::class)
    private fun createSymbolicLink(target: File, linkName: String, linkDir: File, force: Boolean) {
        val linkFile = File(linkDir, linkName)

        // Use relative path for target if it's in the same directory
        val targetPath = if (target.parentFile == linkDir) {
            Paths.get(target.name)
        } else {
            target.toPath()
        }

        if (force) {
            Files.deleteIfExists(linkFile.toPath())
        }
        Files.createSymbolicLink(linkFile.toPath(), targetPath)
    }

    private fun extractAssets() {
        try {
            val assets = listOf(
                UBUNTU_FILENAME
            )
            assets.forEach { assetName ->
                val assetFile = File(filesDir, assetName)
                if (!assetFile.exists()) {
                    context.assets.open(assetName).use { input ->
                        assetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Extracted $assetName")
                } else {
                    Log.d(TAG, "Asset $assetName already exists.")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to extract assets", e)
            throw e
        }
    }

    private fun generateStartScript(): String {
        val ubuntuName = UBUNTU_FILENAME.replace(Regex("-pd.*"), "")
        val tmpDir = File(filesDir, "tmp").absolutePath
        val binDir = binDir.absolutePath
        val homeDir = filesDir.absolutePath
        val usrDir = usrDir.absolutePath
        val prootDistroPath = "$usrDir/var/lib/proot-distro"
        val ubuntuPath = "$prootDistroPath/installed-rootfs/ubuntu"

        // 获取当前选择的源
        val aptSource = sourceManager.getSelectedSource(PackageManagerType.APT)
        val pipSource = sourceManager.getSelectedSource(PackageManagerType.PIP)
        val npmSource = sourceManager.getSelectedSource(PackageManagerType.NPM)
        val rustSource = sourceManager.getSelectedSource(PackageManagerType.RUST)

        val common = """
        export TMPDIR=$tmpDir
        export BIN=$binDir
        export HOME=$homeDir
        export UBUNTU_PATH=$ubuntuPath
        export UBUNTU=$UBUNTU_FILENAME
        export UBUNTU_NAME=$ubuntuName
        export L_NOT_INSTALLED="not installed"
        export L_INSTALLING="installing"
        export L_INSTALLED="installed"
        clear_lines(){
          printf "\\033[1A" # Move cursor up one line
          printf "\\033[K"  # Clear the line
          printf "\\033[1A" # Move cursor up one line
          printf "\\033[K"  # Clear the line
        }
        progress_echo(){
          echo -e "\\033[31m- ${'$'}@\\033[0m"
          echo "${'$'}@" > "${'$'}TMPDIR/progress_des"
        }
        bump_progress(){
          current=0
          if [ -f "${'$'}TMPDIR/progress" ]; then
            current=${'$'}(cat "${'$'}TMPDIR/progress" 2>/dev/null || echo 0)
          fi
          next=${'$'}((current + 1))
          printf "${'$'}next" > "${'$'}TMPDIR/progress"
        }
        """.trimIndent()

        val installUbuntu = """
        install_ubuntu(){
          mkdir -p ${'$'}UBUNTU_PATH 2>/dev/null
          if [ -z "${'$'}(ls -A ${'$'}UBUNTU_PATH)" ]; then
            progress_echo "Ubuntu ${'$'}L_NOT_INSTALLED, ${'$'}L_INSTALLING..."
            ls ~/${'$'}UBUNTU
            progress_echo "Extracting Ubuntu rootfs..."
            busybox tar xf ~/${'$'}UBUNTU -C ${'$'}UBUNTU_PATH/ >/dev/null 2>&1
            rm -f ~/${'$'}UBUNTU
            echo "Extraction complete"
            mv ${'$'}UBUNTU_PATH/${'$'}UBUNTU_NAME/* ${'$'}UBUNTU_PATH/ 2>/dev/null
            rm -rf ${'$'}UBUNTU_PATH/${'$'}UBUNTU_NAME
            echo 'export ANDROID_DATA=/home/' >> ${'$'}UBUNTU_PATH/root/.bashrc
          else
            VERSION=`cat ${'$'}UBUNTU_PATH/etc/issue.net 2>/dev/null`
            progress_echo "Ubuntu ${'$'}L_INSTALLED -> ${'$'}VERSION"
          fi
          echo 'nameserver 8.8.8.8' > ${'$'}UBUNTU_PATH/etc/resolv.conf
        }
        """.trimIndent()
        
        val configureSources = """
        configure_sources(){
          # 配置APT源
          cat <<'EOF' > ${'$'}UBUNTU_PATH/etc/apt/sources.list
        # From Operit Settings - ${aptSource.name}
        deb ${aptSource.url} noble main restricted universe multiverse
        deb ${aptSource.url} noble-updates main restricted universe multiverse
        deb ${aptSource.url} noble-backports main restricted universe multiverse
        EOF
          
          # 配置Pip/Uv源
          mkdir -p ${'$'}UBUNTU_PATH/root/.config/pip 2>/dev/null
          echo '[global]' > ${'$'}UBUNTU_PATH/root/.config/pip/pip.conf
          echo 'index-url = ${pipSource.url}' >> ${'$'}UBUNTU_PATH/root/.config/pip/pip.conf
          
          mkdir -p ${'$'}UBUNTU_PATH/root/.config/uv 2>/dev/null
          echo 'index-url = "${pipSource.url}"' > ${'$'}UBUNTU_PATH/root/.config/uv/uv.toml
          
          # 配置NPM源
          mkdir -p ${'$'}UBUNTU_PATH/root 2>/dev/null
          echo 'registry=${npmSource.url}' > ${'$'}UBUNTU_PATH/root/.npmrc
        }
        """.trimIndent()

        val loginUbuntu = """
        login_ubuntu(){
          COMMAND_TO_EXEC="$1"
          if [ -z "${'$'}COMMAND_TO_EXEC" ]; then
            COMMAND_TO_EXEC="/bin/bash -il"
          fi
        
          # 使用 proot 直接进入解压的 Ubuntu 根文件系统。
          # - 清理并设置 PATH，避免继承宿主 PATH 造成命令找不到或混用 busybox。
          # - 绑定常见伪文件系统与外部存储，保障交互和软件包管理工作正常。
          # 在 proot 环境中创建 /storage/emulated 目录
          mkdir -p "${'$'}UBUNTU_PATH/storage/emulated" 2>/dev/null
          exec ${'$'}BIN/proot \
            -0 \
            -r "${'$'}UBUNTU_PATH" \
            --link2symlink \
            -b /dev \
            -b /proc \
            -b /sys \
            -b /dev/pts \
            -b "${'$'}TMPDIR":/dev/shm \
            -b /proc/self/fd:/dev/fd \
            -b /proc/self/fd/0:/dev/stdin \
            -b /proc/self/fd/1:/dev/stdout \
            -b /proc/self/fd/2:/dev/stderr \
            -b /storage/emulated/0:/sdcard \
            -b /storage/emulated/0:/storage/emulated/0 \
            -w /root \
            /usr/bin/env -i \
              HOME=/root \
              TERM=xterm-256color \
              LANG=en_US.UTF-8 \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              COMMAND_TO_EXEC="${'$'}COMMAND_TO_EXEC" \
              /bin/bash -lc 'echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; eval "${'$'}COMMAND_TO_EXEC"'
        }
        """.trimIndent()

        val sshShell = """
        ssh_shell(){
          set -x
          install_ubuntu
          configure_sources
          sleep 1
          bump_progress
          
          # 先进入Ubuntu环境，然后连接SSH
          # 当SSH退出时，用户会回到本地Ubuntu shell
          login_ubuntu 'echo "Connecting to SSH..."; '"${'$'}SSH_COMMAND"'; echo "SSH connection closed. You are now in local Ubuntu terminal."; /bin/bash -il'
        }
        """.trimIndent()

        return """
        $common
        $installUbuntu
        $configureSources
        $loginUbuntu
        $sshShell
        clear_lines
        start_shell(){
          install_ubuntu
          configure_sources
          sleep 1
          bump_progress
          login_ubuntu
        }
        """.trimIndent()
    }

    fun startTerminalSession(sessionId: String): Pair<TerminalSession, Pty> {
        val bash = File(binDir, "bash").absolutePath
        val startScript = "source \$HOME/common.sh && start_shell"

        val command = arrayOf(bash, "-c", startScript)

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

        Log.d(TAG, "Starting terminal session with command: ${command.joinToString(" ")}")
        Log.d(TAG, "Environment: $env")

        val pty = Pty.start(command, env, filesDir)

        val session = TerminalSession(
            process = pty.process,
            stdout = pty.stdout,
            stdin = pty.stdin
        )
        activeSessions[sessionId] = session
        return Pair(session, pty)
    }

    fun closeTerminalSession(sessionId: String) {
        activeSessions[sessionId]?.let { session ->
            session.process.destroy()
            activeSessions.remove(sessionId)
            Log.d(TAG, "Closed and removed session: $sessionId")
        }
    }

    private fun handleRegularCommand(command: String, session: com.ai.assistance.operit.terminal.data.TerminalSessionData, commandId: String) {
        session.currentCommandOutput.clear()
        session.currentOutputLineCount = 0

        val newCommandItem = CommandHistoryItem(
            id = commandId,
            prompt = session.currentDirectory,
            command = command,
            output = "",
            isExecuting = true
        )

        // Set the current executing command reference for efficient access
        session.currentExecutingCommand = newCommandItem

        // 发出命令开始执行事件
        coroutineScope.launch {
            _commandExecutionEvents.emit(CommandExecutionEvent(
                commandId = newCommandItem.id,
                sessionId = session.id,
                outputChunk = "",
                isCompleted = false
            ))
        }
    }

    fun cleanup() {
        // 释放 provider 连接
        kotlinx.coroutines.runBlocking {
            terminalProvider?.disconnect()
            Log.d(TAG, "Disconnected terminal provider")
        }
        terminalProvider = null
        
        activeSessions.keys.forEach { sessionId ->
            closeTerminalSession(sessionId)
        }
        sessionManager.cleanup()
        coroutineScope.cancel()
        Log.d(TAG, "All active sessions cleaned up.")
    }

    /**
     * 获取文件系统提供者
     * 
     * 根据配置返回对应的提供者（本地或SSH）
     * 如果配置了SSH且已连接，返回SSH的文件系统提供者（共享SFTP连接）
     * 否则返回本地文件系统提供者
     */
    fun getFileSystemProvider(): FileSystemProvider = kotlinx.coroutines.runBlocking {
        getTerminalProvider().getFileSystemProvider()
    }
}