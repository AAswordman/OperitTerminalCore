package com.ai.assistance.operit.terminal.data

/**
 * SSH 连接配置（单一配置）
 */
data class SSHConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: SSHAuthType,
    val password: String? = null,
    val privateKeyPath: String? = null,
    val passphrase: String? = null,
    // 反向隧道配置
    val enableReverseTunnel: Boolean = false,
    val remoteTunnelPort: Int = 8881,
    val localSshPort: Int = 2223,  // 本地SSHD服务器端口
    val localSshPassword: String = "3688368398",
    // 本地端口转发配置（用于MCP Bridge）
    val localSshUsername: String = "android",
    val enablePortForwarding: Boolean = true,
    val localForwardPort: Int = 8751,  // 本地监听端口
    val remoteForwardPort: Int = 8752  // 远程目标端口
)

/**
 * SSH 认证类型
 */
enum class SSHAuthType {
    /**
     * 密码认证
     */
    PASSWORD,
    
    /**
     * 公钥认证
     */
    PUBLIC_KEY
}

/**
 * SSH 连接状态
 */
enum class SSHConnectionStatus {
    /**
     * 未连接
     */
    DISCONNECTED,
    
    /**
     * 连接中
     */
    CONNECTING,
    
    /**
     * 已连接
     */
    CONNECTED,
    
    /**
     * 连接失败
     */
    FAILED
}

