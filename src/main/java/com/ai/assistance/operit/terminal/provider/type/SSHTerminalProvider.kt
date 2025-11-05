package com.ai.assistance.operit.terminal.provider.type

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.terminal.Pty
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.TerminalSession
import com.ai.assistance.operit.terminal.data.SSHAuthType
import com.ai.assistance.operit.terminal.data.SSHConfig
import com.ai.assistance.operit.terminal.provider.filesystem.FileSystemProvider
import com.ai.assistance.operit.terminal.provider.filesystem.SSHFileSystemProvider
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * SSH 远程终端提供者
 * 
 * 通过启动一个本地终端，然后自动执行ssh命令来连接到远程服务器
 */
class SSHTerminalProvider(
    private val context: Context,
    private val sshConfig: SSHConfig,
    private val terminalManager: TerminalManager
) : TerminalProvider {
    
    private val jsch = JSch()
    private var session: Session? = null
    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()
    
    // 使用单个共享的SFTP文件系统提供者
    private var fileSystemProvider: SSHFileSystemProvider? = null
    
    // 记录我们实际挂载的路径，只卸载我们自己挂载的
    private val mountedPaths = mutableSetOf<String>()
    
    // 记录端口转发状态
    private var portForwardingActive = false
    
    // 记录反向隧道状态
    private var reverseTunnelActive = false
    
    companion object {
        private const val TAG = "SSHTerminalProvider"
    }
    
    override suspend fun isConnected(): Boolean {
        return session?.isConnected == true
    }
    
    override suspend fun connect(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Connecting to SSH server for SFTP: ${sshConfig.host}:${sshConfig.port}")
                
                // 配置认证
                when (sshConfig.authType) {
                    SSHAuthType.PUBLIC_KEY -> {
                        sshConfig.privateKeyPath?.let { keyPath ->
                            if (sshConfig.passphrase != null) {
                                jsch.addIdentity(keyPath, sshConfig.passphrase)
                            } else {
                                jsch.addIdentity(keyPath)
                            }
                        }
                    }
                    SSHAuthType.PASSWORD -> {
                        // 密码会在创建session时设置
                    }
                }
                
                // 创建会话
                val sshSession = jsch.getSession(sshConfig.username, sshConfig.host, sshConfig.port)
                
                // 设置密码（如果使用密码认证）
                if (sshConfig.authType == SSHAuthType.PASSWORD && sshConfig.password != null) {
                    sshSession.setPassword(sshConfig.password)
                }
                
                // 配置会话
                val config = Properties()
                config["StrictHostKeyChecking"] = "no" // 不验证主机密钥（生产环境应该验证）
                
                // 配置心跳包（Keep-Alive）
                if (sshConfig.enableKeepAlive) {
                    // ServerAliveInterval: 客户端向服务器发送心跳包的间隔（秒）
                    config["ServerAliveInterval"] = sshConfig.keepAliveInterval.toString()
                    // ServerAliveCountMax: 在断开连接之前发送心跳包的最大次数
                    config["ServerAliveCountMax"] = "3"
                    Log.d(TAG, "Keep-Alive enabled: interval=${sshConfig.keepAliveInterval}s, max_count=3")
                }
                
                sshSession.setConfig(config)
                
                // 连接
                sshSession.connect(30000) // 30秒超时
                session = sshSession
                
                // 创建SFTP文件系统提供者
                fileSystemProvider = SSHFileSystemProvider(sshSession)
                
                // 设置本地端口转发（用于MCP Bridge）
                if (sshConfig.enablePortForwarding) {
                    setupPortForwarding(sshSession)
                }
                
                // 设置反向隧道（用于sshfs挂载本地存储）
                if (sshConfig.enableReverseTunnel) {
                    // 启动本地SSHD服务器
                    val started = terminalManager.getSSHDServerManager().startServer(sshConfig)
                    if (!started) {
                        Log.w(TAG, "Failed to start SSHD server for reverse tunnel")
                    } else {
                        // 在JSch Session层面配置反向端口转发
                        setupReverseTunnel(sshSession)
                    }
                }
                
                Log.d(TAG, "SSH connection for SFTP established successfully")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to SSH server for SFTP", e)
                Result.failure(e)
            }
        }
    }
    
    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            // 关闭端口转发
            teardownPortForwarding()
            
            // 关闭反向隧道
            teardownReverseTunnel()
            
            // 卸载存储
            unmountStorage()
            
            // 关闭所有通道
            activeSessions.keys.toList().forEach { sessionId ->
                closeSession(sessionId)
            }
            
            // 关闭SFTP文件系统提供者
            fileSystemProvider?.close()
            fileSystemProvider = null
            
            // 停止SSHD服务器
            terminalManager.getSSHDServerManager().stopServer()
            
            // 断开SSH会话
            session?.disconnect()
            session = null
            
            Log.d(TAG, "SSH connection closed")
        }
    }
    
    override suspend fun startSession(sessionId: String): Result<Pair<TerminalSession, Pty>> {
        return withContext(Dispatchers.IO) {
            try {
                if (session?.isConnected != true) {
                    connect().getOrThrow()
                }

                val filesDir: File = context.filesDir
                val binDir: File = File(filesDir, "usr/bin")
                val bash = File(binDir, "bash").absolutePath
                val startScript = "source \$HOME/common.sh && ssh_shell"
                val command = arrayOf(bash, "-c", startScript)
                
                val env = buildEnvironment()
                
                Log.d(TAG, "Starting local terminal session for SSH with command: ${command.joinToString(" ")}")
                Log.d(TAG, "Environment: $env")
                
                val pty = Pty.start(command, env, filesDir)
                
                val terminalSession = TerminalSession(
                    process = pty.process,
                    stdout = pty.stdout,
                    stdin = pty.stdin
                )
                
                activeSessions[sessionId] = terminalSession
                
                // 如果启用了反向隧道，挂载存储（只在第一次时挂载）
                if (sshConfig.enableReverseTunnel && mountedPaths.isEmpty()) {
                    mountStorage()
                }
                
                Log.d(TAG, "SSH terminal session started via local pty: $sessionId")
                Result.success(Pair(terminalSession, pty))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start SSH terminal session", e)
                Result.failure(e)
            }
        }
    }
    
    override suspend fun closeSession(sessionId: String) {
        activeSessions[sessionId]?.let { session ->
            session.process.destroy()
            activeSessions.remove(sessionId)
            Log.d(TAG, "Closed SSH terminal session (process): $sessionId")
        }
    }
    
    override fun getFileSystemProvider(): FileSystemProvider {
        // 返回共享的SFTP文件系统提供者
        return fileSystemProvider 
            ?: throw IllegalStateException("SSH connection not established or SFTP provider not available")
    }
    
    override suspend fun getWorkingDirectory(): String {
        // SSH默认工作目录通常是用户主目录
        return "~"
    }
    
    override fun getEnvironment(): Map<String, String> {
        // SSH环境变量由远程服务器决定
        return mapOf(
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8"
        )
    }

    private fun buildSshCommand(): String {
        val cmd = StringBuilder()
        
        // 如果是密码认证，使用sshpass自动输入密码
        if (sshConfig.authType == SSHAuthType.PASSWORD && sshConfig.password != null) {
            cmd.append("sshpass -p '${sshConfig.password}' ")
        }
        
        cmd.append("ssh")
        cmd.append(" -p ${sshConfig.port}")
        
        // 注意：反向隧道现在通过JSch Session API配置（setupReverseTunnel），不再需要ssh命令参数
        
        if (sshConfig.authType == SSHAuthType.PUBLIC_KEY && sshConfig.privateKeyPath != null) {
            // 注意：这里的路径是Android文件系统中的路径。
            // proot已将/storage/emulated/0挂载为/sdcard，因此如果密钥在外部存储中，路径需要相应调整。
            // 为简单起见，我们假设用户提供的路径在proot环境中是可访问的。
            cmd.append(" -i \"${sshConfig.privateKeyPath}\"")
        }
        
        cmd.append(" -o StrictHostKeyChecking=no") // 避免首次连接时的主机密钥检查提示
        
        // 配置心跳包（Keep-Alive）
        if (sshConfig.enableKeepAlive) {
            cmd.append(" -o ServerAliveInterval=${sshConfig.keepAliveInterval}")
            cmd.append(" -o ServerAliveCountMax=3")
        }
        
        cmd.append(" ${sshConfig.username}@${sshConfig.host}")

        return cmd.toString()
    }

    private fun buildEnvironment(): Map<String, String> {
        val filesDir: File = context.filesDir
        val usrDir: File = File(filesDir, "usr")
        val binDir: File = File(usrDir, "bin")
        val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
        
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
        env["SSH_COMMAND"] = buildSshCommand()
        return env
    }
    
    /**
     * 挂载本地存储到远程服务器
     * 通过反向SSH隧道使用 sshfs 将本地的 /sdcard 挂载到远程 ~/storage 和 ~/sdcard
     */
    private suspend fun mountStorage() {
        if (!sshConfig.enableReverseTunnel) {
            Log.d(TAG, "Reverse tunnel not enabled, skipping storage mount")
            return
        }
        
        val sshSession = session ?: run {
            Log.w(TAG, "SSH session not available, cannot mount storage")
            return
        }
        
        try {
            Log.d(TAG, "Attempting to mount local storage to remote server...")
            
            // 执行挂载命令
            val mountCommands = """
                # 检查 sshfs 是否安装
                if ! command -v sshfs &> /dev/null; then
                    echo "sshfs not installed, skipping mount"
                    exit 0
                fi
                
                # 创建挂载点目录（如果不存在）
                mkdir -p ~/storage ~/sdcard 2>/dev/null || true
                
                # 检查 ~/storage 是否已经挂载
                if ! mountpoint -q ~/storage 2>/dev/null; then
                    echo "Mounting local /sdcard to ~/storage..."
                    sshfs -p ${sshConfig.remoteTunnelPort} \
                        ${sshConfig.localSshUsername}@localhost:/ \
                        ~/storage \
                        -o password_stdin \
                        -o StrictHostKeyChecking=no \
                        -o UserKnownHostsFile=/dev/null \
                        -o reconnect \
                        -o ServerAliveInterval=15 \
                        -o ServerAliveCountMax=3 <<< "${sshConfig.localSshPassword}"
                    echo "MOUNT_SUCCESS:~/storage"
                else
                    echo "~/storage already mounted, skipping"
                fi
                
                # 检查 ~/sdcard 是否已经挂载
                if ! mountpoint -q ~/sdcard 2>/dev/null; then
                    echo "Mounting local /sdcard to ~/sdcard..."
                    sshfs -p ${sshConfig.remoteTunnelPort} \
                        ${sshConfig.localSshUsername}@localhost:/ \
                        ~/sdcard \
                        -o password_stdin \
                        -o StrictHostKeyChecking=no \
                        -o UserKnownHostsFile=/dev/null \
                        -o reconnect \
                        -o ServerAliveInterval=15 \
                        -o ServerAliveCountMax=3 <<< "${sshConfig.localSshPassword}"
                    echo "MOUNT_SUCCESS:~/sdcard"
                else
                    echo "~/sdcard already mounted, skipping"
                fi
            """.trimIndent()
            
            val channel = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
            channel.setCommand(mountCommands)
            channel.connect()
            
            // 读取输出
            val output = channel.inputStream.bufferedReader().readText()
            val errorOutput = channel.errStream.bufferedReader().readText()
            
            channel.disconnect()
            
            Log.d(TAG, "Mount command output: $output")
            if (errorOutput.isNotEmpty()) {
                Log.d(TAG, "Mount command errors: $errorOutput")
            }
            
            // 只记录我们成功挂载的路径
            output.lines().forEach { line ->
                if (line.startsWith("MOUNT_SUCCESS:")) {
                    val path = line.substringAfter("MOUNT_SUCCESS:")
                    mountedPaths.add(path)
                    Log.d(TAG, "Recorded mounted path: $path")
                }
            }
            
            Log.d(TAG, "Storage mount process completed, mounted paths: $mountedPaths")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mount storage", e)
            // 不抛出异常，允许继续使用SSH（即使挂载失败）
        }
    }
    
    /**
     * 卸载远程服务器上的存储挂载
     * 只卸载我们自己挂载的路径，避免误删其他进程的挂载
     */
    private suspend fun unmountStorage() {
        if (mountedPaths.isEmpty()) {
            Log.d(TAG, "No paths mounted by us, skipping unmount")
            return
        }
        
        val sshSession = session ?: return
        
        try {
            Log.d(TAG, "Unmounting remote storage paths: $mountedPaths")
            
            // 构建卸载命令，只卸载我们记录的路径
            val unmountCommandsList = mountedPaths.map { path ->
                """
                if mountpoint -q $path 2>/dev/null; then
                    echo "Unmounting $path..."
                    fusermount -u $path 2>/dev/null || umount $path 2>/dev/null || true
                    echo "UNMOUNT_SUCCESS:$path"
                fi
                """.trimIndent()
            }
            
            val unmountCommands = unmountCommandsList.joinToString("\n")
            
            val channel = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
            channel.setCommand(unmountCommands)
            channel.connect()
            
            // 读取输出
            val output = channel.inputStream.bufferedReader().readText()
            val errorOutput = channel.errStream.bufferedReader().readText()
            
            channel.disconnect()
            
            Log.d(TAG, "Unmount command output: $output")
            if (errorOutput.isNotEmpty()) {
                Log.d(TAG, "Unmount command errors: $errorOutput")
            }
            
            // 清空已挂载路径列表
            mountedPaths.clear()
            Log.d(TAG, "Storage unmounted successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unmount storage", e)
            // 不抛出异常，允许继续断开连接
        }
    }
    
    /**
     * 设置本地端口转发
     * 将本地端口转发到远程服务器的指定端口（用于MCP Bridge）
     */
    private fun setupPortForwarding(sshSession: Session) {
        try {
            val localPort = sshConfig.localForwardPort
            val remotePort = sshConfig.remoteForwardPort
            
            Log.d(TAG, "Setting up port forwarding: localhost:$localPort -> remote:$remotePort")
            
            // 使用JSch的setPortForwardingL方法设置本地端口转发
            // 格式：setPortForwardingL(本地端口, 远程主机, 远程端口)
            // 这里远程主机使用localhost，因为目标是SSH服务器本身的端口
            sshSession.setPortForwardingL(localPort, "localhost", remotePort)
            
            portForwardingActive = true
            Log.d(TAG, "Port forwarding established successfully: localhost:$localPort -> remote:$remotePort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup port forwarding", e)
            // 不抛出异常，允许继续使用SSH（即使端口转发失败）
        }
    }
    
    /**
     * 关闭端口转发
     */
    private fun teardownPortForwarding() {
        if (!portForwardingActive) {
            return
        }
        
        val sshSession = session ?: return
        
        try {
            val localPort = sshConfig.localForwardPort
            Log.d(TAG, "Tearing down port forwarding on port $localPort")
            
            // 删除端口转发
            sshSession.delPortForwardingL(localPort)
            
            portForwardingActive = false
            Log.d(TAG, "Port forwarding closed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to teardown port forwarding", e)
            // 不抛出异常，允许继续断开连接
        }
    }
    
    /**
     * 设置反向端口转发（反向隧道）
     * 将远程服务器的端口转发到本地SSHD服务器（用于sshfs挂载本地存储）
     */
    private fun setupReverseTunnel(sshSession: Session) {
        try {
            val remoteTunnelPort = sshConfig.remoteTunnelPort
            val localSshPort = sshConfig.localSshPort
            
            Log.d(TAG, "Setting up reverse tunnel: remote:$remoteTunnelPort -> localhost:$localSshPort")
            
            // 使用JSch的setPortForwardingR方法设置反向端口转发
            // 格式：setPortForwardingR(远程端口, 本地主机, 本地端口)
            // 远程服务器的remoteTunnelPort会转发到本地的localSshPort
            sshSession.setPortForwardingR(remoteTunnelPort, "localhost", localSshPort)
            
            reverseTunnelActive = true
            Log.d(TAG, "Reverse tunnel established successfully: remote:$remoteTunnelPort -> localhost:$localSshPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup reverse tunnel", e)
            // 不抛出异常，允许继续使用SSH（即使反向隧道失败）
        }
    }
    
    /**
     * 关闭反向端口转发
     */
    private fun teardownReverseTunnel() {
        if (!reverseTunnelActive) {
            return
        }
        
        val sshSession = session ?: return
        
        try {
            val remoteTunnelPort = sshConfig.remoteTunnelPort
            Log.d(TAG, "Tearing down reverse tunnel on remote port $remoteTunnelPort")
            
            // 删除反向端口转发
            sshSession.delPortForwardingR(remoteTunnelPort)
            
            reverseTunnelActive = false
            Log.d(TAG, "Reverse tunnel closed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to teardown reverse tunnel", e)
            // 不抛出异常，允许继续断开连接
        }
    }
}
