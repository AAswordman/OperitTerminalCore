package com.ai.assistance.operit.terminal.filesystem

import java.io.File

/**
 * 文件系统操作的抽象接口
 * 提供统一的文件系统操作接口，支持本地和远程（SSH）两种实现
 */
interface FileSystemProvider {
    
    /**
     * 文件信息数据类
     */
    data class FileInfo(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val permissions: String,
        val lastModified: String
    )
    
    /**
     * 操作结果
     */
    data class OperationResult(
        val success: Boolean,
        val message: String = "",
        val data: Any? = null
    )
    
    // ==================== 文件读取操作 ====================
    
    /**
     * 读取文件完整内容
     * @param path 文件路径
     * @return 文件内容，失败返回null
     */
    suspend fun readFile(path: String): String?
    
    /**
     * 读取文件指定字节数
     * @param path 文件路径
     * @param maxBytes 最大字节数
     * @return 文件内容，失败返回null
     */
    suspend fun readFileWithLimit(path: String, maxBytes: Int): String?
    
    /**
     * 读取文件指定行范围
     * @param path 文件路径
     * @param startLine 起始行（从1开始）
     * @param endLine 结束行（包含）
     * @return 文件内容，失败返回null
     */
    suspend fun readFileLines(path: String, startLine: Int, endLine: Int): String?
    
    /**
     * 读取文件前N个字节作为样本（用于检测文件类型）
     * @param path 文件路径
     * @param sampleSize 样本大小（字节）
     * @return 样本字节数组，失败返回null
     */
    suspend fun readFileSample(path: String, sampleSize: Int = 512): ByteArray?
    
    // ==================== 文件写入操作 ====================
    
    /**
     * 写入文件内容
     * @param path 文件路径
     * @param content 文件内容
     * @param append 是否追加模式
     * @return 操作结果
     */
    suspend fun writeFile(path: String, content: String, append: Boolean = false): OperationResult
    
    /**
     * 写入二进制文件
     * @param path 文件路径
     * @param bytes 二进制内容
     * @return 操作结果
     */
    suspend fun writeFileBytes(path: String, bytes: ByteArray): OperationResult
    
    // ==================== 文件/目录管理操作 ====================
    
    /**
     * 列出目录内容
     * @param path 目录路径
     * @return 文件信息列表，失败返回null
     */
    suspend fun listDirectory(path: String): List<FileInfo>?
    
    /**
     * 检查文件/目录是否存在
     * @param path 路径
     * @return 是否存在
     */
    suspend fun exists(path: String): Boolean
    
    /**
     * 检查是否为目录
     * @param path 路径
     * @return 是否为目录，如果不存在返回false
     */
    suspend fun isDirectory(path: String): Boolean
    
    /**
     * 检查是否为文件
     * @param path 路径
     * @return 是否为文件，如果不存在返回false
     */
    suspend fun isFile(path: String): Boolean
    
    /**
     * 获取文件大小
     * @param path 文件路径
     * @return 文件大小（字节），失败返回0
     */
    suspend fun getFileSize(path: String): Long
    
    /**
     * 获取文件行数
     * @param path 文件路径
     * @return 文件行数，失败返回0
     */
    suspend fun getLineCount(path: String): Int
    
    /**
     * 创建目录
     * @param path 目录路径
     * @param createParents 是否创建父目录
     * @return 操作结果
     */
    suspend fun createDirectory(path: String, createParents: Boolean = false): OperationResult
    
    /**
     * 删除文件或目录
     * @param path 路径
     * @param recursive 是否递归删除（用于目录）
     * @return 操作结果
     */
    suspend fun delete(path: String, recursive: Boolean = false): OperationResult
    
    /**
     * 移动/重命名文件或目录
     * @param sourcePath 源路径
     * @param destPath 目标路径
     * @return 操作结果
     */
    suspend fun move(sourcePath: String, destPath: String): OperationResult
    
    /**
     * 复制文件或目录
     * @param sourcePath 源路径
     * @param destPath 目标路径
     * @param recursive 是否递归复制（用于目录）
     * @return 操作结果
     */
    suspend fun copy(sourcePath: String, destPath: String, recursive: Boolean = true): OperationResult
    
    // ==================== 文件搜索操作 ====================
    
    /**
     * 查找匹配模式的文件
     * @param basePath 搜索基础路径
     * @param pattern 文件名模式（glob）
     * @param maxDepth 最大搜索深度，-1表示无限制
     * @param caseInsensitive 是否忽略大小写
     * @return 匹配的文件路径列表，失败返回空列表
     */
    suspend fun findFiles(
        basePath: String,
        pattern: String,
        maxDepth: Int = -1,
        caseInsensitive: Boolean = false
    ): List<String>
    
    // ==================== 文件信息操作 ====================
    
    /**
     * 获取文件详细信息
     * @param path 文件路径
     * @return 文件信息，失败返回null
     */
    suspend fun getFileInfo(path: String): FileInfo?
    
    /**
     * 获取文件权限字符串
     * @param path 文件路径
     * @return 权限字符串（如"rwxr-xr-x"），失败返回空字符串
     */
    suspend fun getPermissions(path: String): String
}

