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
    
    override val type = TerminalType.SSH
    
    private val jsch = JSch()
    private var session: Session? = null
    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()
    
    // 使用单个共享的SFTP文件系统提供者
    private var fileSystemProvider: SSHFileSystemProvider? = null
    
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
                sshSession.setConfig(config)
                
                // 连接
                sshSession.connect(30000) // 30秒超时
                session = sshSession
                
                // 创建SFTP文件系统提供者
                fileSystemProvider = SSHFileSystemProvider(sshSession)
                
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
            // 关闭所有通道
            activeSessions.keys.toList().forEach { sessionId ->
                closeSession(sessionId)
            }
            
            // 关闭SFTP文件系统提供者
            fileSystemProvider?.close()
            fileSystemProvider = null
            
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
        
        if (sshConfig.authType == SSHAuthType.PUBLIC_KEY && sshConfig.privateKeyPath != null) {
            // 注意：这里的路径是Android文件系统中的路径。
            // proot已将/storage/emulated/0挂载为/sdcard，因此如果密钥在外部存储中，路径需要相应调整。
            // 为简单起见，我们假设用户提供的路径在proot环境中是可访问的。
            cmd.append(" -i \"${sshConfig.privateKeyPath}\"")
        }
        
        cmd.append(" -o StrictHostKeyChecking=no") // 避免首次连接时的主机密钥检查提示
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
}
