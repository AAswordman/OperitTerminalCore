package com.ai.assistance.operit.terminal.provider

import android.util.Log
import com.ai.assistance.operit.terminal.Pty
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.TerminalSession
import com.ai.assistance.operit.terminal.data.SSHAuthType
import com.ai.assistance.operit.terminal.data.SSHConfig
import com.ai.assistance.operit.terminal.filesystem.FileSystemProvider
import com.ai.assistance.operit.terminal.filesystem.SSHFileSystemProvider
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * SSH 远程终端提供者
 * 
 * 通过 SSH 连接到远程服务器
 */
class SSHTerminalProvider(
    private val sshConfig: SSHConfig,
    private val terminalManager: TerminalManager
) : TerminalProvider {
    
    override val type = TerminalType.SSH
    
    private val jsch = JSch()
    private var session: Session? = null
    private val activeChannels = ConcurrentHashMap<String, ChannelShell>()
    
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
                Log.d(TAG, "Connecting to SSH server: ${sshConfig.host}:${sshConfig.port}")
                
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
                
                Log.d(TAG, "SSH connection established successfully")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to SSH server", e)
                Result.failure(e)
            }
        }
    }
    
    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            // 关闭所有通道
            activeChannels.keys.toList().forEach { sessionId ->
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
                val sshSession = session ?: return@withContext Result.failure(
                    IllegalStateException("SSH session not connected")
                )
                
                // 创建shell通道
                val channel = sshSession.openChannel("shell") as ChannelShell
                
                // 配置终端
                channel.setPtyType("xterm-256color")
                channel.setPtySize(80, 24, 640, 480) // 列数、行数、宽度像素、高度像素
                
                // 连接通道
                channel.connect(10000) // 10秒超时
                
                // 创建SSH Process
                val process = object : Process() {
                    override fun waitFor(): Int {
                        while (channel.isConnected) {
                            Thread.sleep(100)
                        }
                        return channel.exitStatus
                    }
                    
                    override fun exitValue(): Int {
                        if (channel.isConnected) {
                            throw IllegalThreadStateException("process hasn't exited")
                        }
                        return channel.exitStatus
                    }
                    
                    override fun destroy() {
                        channel.disconnect()
                    }
                    
                    override fun getOutputStream(): OutputStream = channel.outputStream
                    override fun getInputStream(): InputStream = channel.inputStream
                    override fun getErrorStream(): InputStream = channel.extInputStream
                }
                
                // 创建SSH Pty
                val pty = object : Pty(
                    process = process,
                    masterFd = null,
                    ptyMaster = 0,
                    stdout = channel.inputStream,
                    stdin = channel.outputStream
                ) {
                    override fun setWindowSize(rows: Int, cols: Int): Boolean {
                        return try {
                            channel.setPtySize(cols, rows, cols * 8, rows * 16)
                            true
                        } catch (e: Exception) {
                            Log.e("SSHPty", "Failed to resize PTY", e)
                            false
                        }
                    }
                }
                
                // 创建终端会话
                val terminalSession = TerminalSession(
                    process = process,
                    stdout = channel.inputStream,
                    stdin = channel.outputStream
                )
                
                activeChannels[sessionId] = channel
                
                Log.d(TAG, "SSH terminal session started: $sessionId")
                Result.success(Pair(terminalSession, pty))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start SSH terminal session", e)
                Result.failure(e)
            }
        }
    }
    
    override suspend fun closeSession(sessionId: String) {
        activeChannels[sessionId]?.let { channel ->
            channel.disconnect()
            activeChannels.remove(sessionId)
            Log.d(TAG, "Closed SSH terminal session: $sessionId")
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
}
