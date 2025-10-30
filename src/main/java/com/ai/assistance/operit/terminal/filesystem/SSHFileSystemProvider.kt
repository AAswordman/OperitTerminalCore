package com.ai.assistance.operit.terminal.filesystem

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * SSH远程文件系统提供者
 * 使用SSH会话执行命令操作远程文件系统
 * 
 * @param sshSession JSch SSH会话实例，用于执行远程命令
 * @param sessionId 终端会话ID，用于日志标识
 */
class SSHFileSystemProvider(
    private val sshSession: Session,
    private val sessionId: String
) : FileSystemProvider {
    
    companion object {
        private const val TAG = "SSHFileSystemProvider"
    }
    
    /**
     * 执行远程Shell命令并获取结果
     * 使用JSch ChannelExec执行命令并同步等待结果
     */
    private suspend fun executeCommand(command: String): CommandResult {
        Log.d(TAG, "[executeCommand] Session: $sessionId, Command: $command")
        return withContext(Dispatchers.IO) {
            var channel: ChannelExec? = null
            try {
                // 检查SSH会话是否连接
                if (!sshSession.isConnected) {
                    Log.e(TAG, "[executeCommand] SSH session is not connected")
                    return@withContext CommandResult(
                        success = false,
                        stdout = "",
                        stderr = "SSH session is not connected",
                        exitCode = -1
                    )
                }
                
                // 创建执行通道
                channel = sshSession.openChannel("exec") as ChannelExec
                channel.setCommand(command)
                
                // 准备输出流
                val stdoutStream = ByteArrayOutputStream()
                val stderrStream = ByteArrayOutputStream()
                channel.outputStream = stdoutStream
                channel.setErrStream(stderrStream)
                
                // 连接并执行
                channel.connect(10000) // 10秒超时
                
                // 读取输出（ChannelExec会在命令执行完成后自动关闭）
                val inputStream: InputStream = channel.inputStream
                val buffer = ByteArray(4096)
                while (true) {
                    while (inputStream.available() > 0) {
                        val len = inputStream.read(buffer)
                        if (len < 0) break
                        stdoutStream.write(buffer, 0, len)
                    }
                    if (channel.isClosed) {
                        if (inputStream.available() > 0) continue
                        break
                    }
                    Thread.sleep(100)
                }
                
                val exitCode = channel.exitStatus
                val stdout = stdoutStream.toString("UTF-8")
                val stderr = stderrStream.toString("UTF-8")
                
                Log.d(TAG, "[executeCommand] Command completed with exit code: $exitCode")
                Log.d(TAG, "[executeCommand] stdout length: ${stdout.length}, stderr length: ${stderr.length}")
                
                CommandResult(
                    success = exitCode == 0,
                    stdout = stdout,
                    stderr = stderr,
                    exitCode = exitCode
                )
            } catch (e: Exception) {
                Log.e(TAG, "[executeCommand] Error executing command: $command", e)
                CommandResult(
                    success = false,
                    stdout = "",
                    stderr = e.message ?: "Unknown error",
                    exitCode = -1
                )
            } finally {
                channel?.disconnect()
            }
        }
    }
    
    /**
     * 命令执行结果
     */
    private data class CommandResult(
        val success: Boolean,
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )
    
    // ==================== 文件读取操作 ====================
    
    override suspend fun readFile(path: String): String? {
        Log.d(TAG, "[readFile] Reading file: $path")
        val result = executeCommand("cat '$path'")
        Log.d(TAG, "[readFile] Result: success=${result.success}, stdout length=${result.stdout.length}")
        return if (result.success) result.stdout else null
    }
    
    override suspend fun readFileWithLimit(path: String, maxBytes: Int): String? {
        val result = executeCommand("head -c $maxBytes '$path'")
        return if (result.success) result.stdout else null
    }
    
    override suspend fun readFileLines(path: String, startLine: Int, endLine: Int): String? {
        // 使用sed命令提取指定行范围
        val result = executeCommand("sed -n '${startLine},${endLine}p' '$path'")
        return if (result.success) result.stdout else null
    }
    
    override suspend fun readFileSample(path: String, sampleSize: Int): ByteArray? {
        val result = executeCommand("head -c $sampleSize '$path'")
        return if (result.success) result.stdout.toByteArray() else null
    }
    
    // ==================== 文件写入操作 ====================
    
    override suspend fun writeFile(path: String, content: String, append: Boolean): FileSystemProvider.OperationResult {
        Log.d(TAG, "[writeFile] Path: $path, content length: ${content.length}, append: $append")
        
        // 先创建父目录
        val parentDir = path.substringBeforeLast('/')
        if (parentDir.isNotEmpty() && parentDir != path) {
            Log.d(TAG, "[writeFile] Creating parent directory: $parentDir")
            executeCommand("mkdir -p '$parentDir'")
        }
        
        // 对内容进行base64编码以避免特殊字符问题
        val contentBase64 = android.util.Base64.encodeToString(
            content.toByteArray(),
            android.util.Base64.NO_WRAP
        )
        
        val redirectOperator = if (append) ">>" else ">"
        val result = executeCommand("echo '$contentBase64' | base64 -d $redirectOperator '$path'")
        
        if (!result.success) {
            Log.w(TAG, "[writeFile] Base64 write failed, trying fallback method")
            // 尝试备用方法
            val fallbackResult = executeCommand("printf '%s' '$content' $redirectOperator '$path'")
            if (!fallbackResult.success) {
                Log.e(TAG, "[writeFile] Fallback write also failed: ${fallbackResult.stderr}")
                return FileSystemProvider.OperationResult(
                    success = false,
                    message = "Failed to write to file: ${fallbackResult.stderr}"
                )
            }
        }
        
        // 验证文件是否存在
        val verifyResult = executeCommand("test -f '$path' && echo 'exists' || echo 'not exists'")
        if (verifyResult.stdout.trim() != "exists") {
            Log.e(TAG, "[writeFile] File verification failed, file does not exist after write")
            return FileSystemProvider.OperationResult(
                success = false,
                message = "Write command completed but file does not exist. Possible permission issue."
            )
        }
        
        // 检查文件大小
        val sizeResult = executeCommand("stat -c %s '$path' 2>/dev/null || echo '0'")
        val size = sizeResult.stdout.trim().toLongOrNull() ?: 0
        if (size == 0L && content.isNotEmpty()) {
            Log.e(TAG, "[writeFile] File size is 0 but content was not empty")
            return FileSystemProvider.OperationResult(
                success = false,
                message = "File was created but appears to be empty. Possible write failure."
            )
        }
        
        Log.d(TAG, "[writeFile] Write successful, file size: $size bytes")
        return FileSystemProvider.OperationResult(
            success = true,
            message = if (append) "Content appended to $path" else "Content written to $path"
        )
    }
    
    override suspend fun writeFileBytes(path: String, bytes: ByteArray): FileSystemProvider.OperationResult {
        // 先创建父目录
        val parentDir = path.substringBeforeLast('/')
        if (parentDir.isNotEmpty() && parentDir != path) {
            executeCommand("mkdir -p '$parentDir'")
        }
        
        // 使用base64编码传输二进制数据
        val base64Content = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val result = executeCommand("echo '$base64Content' | base64 -d > '$path'")
        
        if (!result.success) {
            return FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to write binary file: ${result.stderr}"
            )
        }
        
        return FileSystemProvider.OperationResult(
            success = true,
            message = "Binary content written to $path"
        )
    }
    
    // ==================== 文件/目录管理操作 ====================
    
    override suspend fun listDirectory(path: String): List<FileSystemProvider.FileInfo>? {
        Log.d(TAG, "[listDirectory] Listing directory: $path")
        val normalizedPath = if (path.endsWith("/")) path else "$path/"
        val result = executeCommand("ls -la '$normalizedPath'")
        
        if (!result.success) {
            Log.w(TAG, "[listDirectory] Failed to list directory: ${result.stderr}")
            return null
        }
        
        val entries = parseDirectoryListing(result.stdout)
        Log.d(TAG, "[listDirectory] Found ${entries.size} entries in $path")
        return entries
    }
    
    override suspend fun exists(path: String): Boolean {
        val result = executeCommand("test -e '$path' && echo 'exists' || echo 'not exists'")
        return result.success && result.stdout.trim() == "exists"
    }
    
    override suspend fun isDirectory(path: String): Boolean {
        val result = executeCommand("test -d '$path' && echo 'true' || echo 'false'")
        return result.success && result.stdout.trim() == "true"
    }
    
    override suspend fun isFile(path: String): Boolean {
        val result = executeCommand("test -f '$path' && echo 'true' || echo 'false'")
        return result.success && result.stdout.trim() == "true"
    }
    
    override suspend fun getFileSize(path: String): Long {
        val result = executeCommand("stat -c %s '$path' 2>/dev/null || echo '0'")
        return result.stdout.trim().toLongOrNull() ?: 0
    }
    
    override suspend fun getLineCount(path: String): Int {
        val result = executeCommand("cat '$path' | wc -l")
        if (!result.success) return 0
        
        return result.stdout.trim().split(" ")[0].toIntOrNull() ?: 0
    }
    
    override suspend fun createDirectory(path: String, createParents: Boolean): FileSystemProvider.OperationResult {
        Log.d(TAG, "[createDirectory] Path: $path, createParents: $createParents")
        
        // 先检查目录是否已存在
        val checkResult = executeCommand("test -d '$path' && echo 'exists' || echo 'not exists'")
        if (checkResult.success && checkResult.stdout.trim() == "exists") {
            Log.d(TAG, "[createDirectory] Directory already exists: $path")
            return FileSystemProvider.OperationResult(
                success = true,
                message = "Directory already exists: $path"
            )
        }
        
        val mkdirCommand = if (createParents) "mkdir -p '$path'" else "mkdir '$path'"
        val result = executeCommand(mkdirCommand)
        
        if (!result.success) {
            Log.w(TAG, "[createDirectory] mkdir command failed, rechecking if directory exists")
            // 再次检查是否已存在（可能在执行过程中被创建）
            val recheckResult = executeCommand("test -d '$path' && echo 'exists' || echo 'not exists'")
            if (recheckResult.success && recheckResult.stdout.trim() == "exists") {
                Log.d(TAG, "[createDirectory] Directory exists after recheck")
                return FileSystemProvider.OperationResult(
                    success = true,
                    message = "Directory already exists: $path"
                )
            }
            
            Log.e(TAG, "[createDirectory] Failed to create directory: ${result.stderr}")
            return FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to create directory: ${result.stderr}"
            )
        }
        
        Log.d(TAG, "[createDirectory] Successfully created directory: $path")
        return FileSystemProvider.OperationResult(
            success = true,
            message = "Successfully created directory $path"
        )
    }
    
    override suspend fun delete(path: String, recursive: Boolean): FileSystemProvider.OperationResult {
        Log.d(TAG, "[delete] Path: $path, recursive: $recursive")
        val deleteCommand = if (recursive) "rm -rf '$path'" else "rm -f '$path'"
        val result = executeCommand(deleteCommand)
        
        return if (result.success) {
            Log.d(TAG, "[delete] Successfully deleted: $path")
            FileSystemProvider.OperationResult(
                success = true,
                message = "Successfully deleted $path"
            )
        } else {
            Log.e(TAG, "[delete] Failed to delete: ${result.stderr}")
            FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to delete: ${result.stderr}"
            )
        }
    }
    
    override suspend fun move(sourcePath: String, destPath: String): FileSystemProvider.OperationResult {
        val result = executeCommand("mv '$sourcePath' '$destPath'")
        
        return if (result.success) {
            FileSystemProvider.OperationResult(
                success = true,
                message = "Successfully moved $sourcePath to $destPath"
            )
        } else {
            FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to move file: ${result.stderr}"
            )
        }
    }
    
    override suspend fun copy(sourcePath: String, destPath: String, recursive: Boolean): FileSystemProvider.OperationResult {
        // 检查源路径是否存在
        val existsResult = executeCommand("test -e '$sourcePath' && echo 'exists' || echo 'not exists'")
        if (existsResult.stdout.trim() != "exists") {
            return FileSystemProvider.OperationResult(
                success = false,
                message = "Source path does not exist: $sourcePath"
            )
        }
        
        // 检查是否为目录
        val isDirResult = executeCommand("test -d '$sourcePath' && echo 'true' || echo 'false'")
        val isDirectory = isDirResult.stdout.trim() == "true"
        
        // 确保目标父目录存在
        val destParentDir = destPath.substringBeforeLast('/')
        if (destParentDir.isNotEmpty()) {
            executeCommand("mkdir -p '$destParentDir'")
        }
        
        // 执行复制
        val copyCommand = if (isDirectory && recursive) {
            "cp -r '$sourcePath' '$destPath'"
        } else if (!isDirectory) {
            "cp '$sourcePath' '$destPath'"
        } else {
            return FileSystemProvider.OperationResult(
                success = false,
                message = "Cannot copy directory without recursive flag"
            )
        }
        
        val result = executeCommand(copyCommand)
        
        if (!result.success) {
            return FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to copy: ${result.stderr}"
            )
        }
        
        // 验证复制是否成功
        val verifyResult = executeCommand("test -e '$destPath' && echo 'exists' || echo 'not exists'")
        if (verifyResult.stdout.trim() != "exists") {
            return FileSystemProvider.OperationResult(
                success = false,
                message = "Copy command completed but destination does not exist"
            )
        }
        
        return FileSystemProvider.OperationResult(
            success = true,
            message = "Successfully copied ${if (isDirectory) "directory" else "file"} $sourcePath to $destPath"
        )
    }
    
    // ==================== 文件搜索操作 ====================
    
    override suspend fun findFiles(
        basePath: String,
        pattern: String,
        maxDepth: Int,
        caseInsensitive: Boolean
    ): List<String> {
        val searchOption = if (caseInsensitive) "-iname" else "-name"
        val depthOption = if (maxDepth >= 0) "-maxdepth $maxDepth" else ""
        
        // 正确转义模式中的单引号
        val escapedPattern = pattern.replace("'", "'\\''")
        
        val command = "find '${if(basePath.endsWith("/")) basePath else "$basePath/"}' $depthOption $searchOption '$escapedPattern'"
        val result = executeCommand(command)
        
        val fileList = result.stdout.trim()
        
        return if (fileList.isBlank()) {
            emptyList()
        } else {
            fileList.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
    
    // ==================== 文件信息操作 ====================
    
    override suspend fun getFileInfo(path: String): FileSystemProvider.FileInfo? {
        // 检查文件是否存在
        val existsResult = executeCommand("test -e '$path' && echo 'exists' || echo 'not exists'")
        if (existsResult.stdout.trim() != "exists") {
            return null
        }
        
        // 获取文件名
        val name = path.substringAfterLast('/')
        
        // 获取文件类型
        val fileTypeResult = executeCommand("test -d '$path' && echo 'directory' || (test -f '$path' && echo 'file' || echo 'other')")
        val isDirectory = fileTypeResult.stdout.trim() == "directory"
        
        // 获取文件大小
        val sizeResult = executeCommand("stat -c %s '$path' 2>/dev/null || echo '0'")
        val size = sizeResult.stdout.trim().toLongOrNull() ?: 0
        
        // 获取权限
        val permissionsResult = executeCommand("stat -c %A '$path' 2>/dev/null || echo ''")
        val permissions = permissionsResult.stdout.trim()
        
        // 获取最后修改时间
        val modifiedResult = executeCommand("stat -c %y '$path' 2>/dev/null || echo ''")
        val lastModified = modifiedResult.stdout.trim()
        
        return FileSystemProvider.FileInfo(
            name = name,
            isDirectory = isDirectory,
            size = size,
            permissions = permissions,
            lastModified = lastModified
        )
    }
    
    override suspend fun getPermissions(path: String): String {
        val result = executeCommand("stat -c %A '$path' 2>/dev/null || echo ''")
        return result.stdout.trim()
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 解析ls -la命令的输出
     */
    private fun parseDirectoryListing(output: String): List<FileSystemProvider.FileInfo> {
        val lines = output.trim().split("\n")
        val entries = mutableListOf<FileSystemProvider.FileInfo>()
        
        // 跳过第一行总计行
        val startIndex = if (lines.isNotEmpty() && lines[0].startsWith("total")) 1 else 0
        
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        
        for (i in startIndex until lines.size) {
            try {
                val line = lines[i]
                if (line.isBlank()) continue
                
                // Android上ls -la输出格式: crwxrw--- 2 u0_a425 media_rw 4056 2025-03-14 06:04 Android
                val androidRegex = """^(\S+)\s+(\d+)\s+(\S+\s*\S*)\s+(\S+)\s+(\d+)\s+(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})\s+(.+)$""".toRegex()
                val androidMatch = androidRegex.find(line)
                
                if (androidMatch != null) {
                    val permissions = androidMatch.groupValues[1]
                    val size = androidMatch.groupValues[5].toLongOrNull() ?: 0
                    val date = androidMatch.groupValues[6]
                    val time = androidMatch.groupValues[7]
                    var name = androidMatch.groupValues[8]
                    val isDirectory = permissions.startsWith("d") || permissions.startsWith("c")
                    val isSymlink = permissions.startsWith("l")
                    
                    // 处理符号链接格式
                    if (isSymlink && name.contains(" -> ")) {
                        name = name.substringBefore(" -> ")
                    }
                    
                    // 跳过 . 和 .. 条目
                    if (name == "." || name == "..") continue
                    
                    // 将日期和时间转换为时间戳字符串
                    val dateTimeStr = "$date $time"
                    val timestamp = try {
                        val parsedDate = dateFormat.parse(dateTimeStr)
                        parsedDate?.time?.toString() ?: dateTimeStr
                    } catch (e: Exception) {
                        dateTimeStr
                    }
                    
                    entries.add(
                        FileSystemProvider.FileInfo(
                            name = name,
                            isDirectory = isDirectory,
                            size = size,
                            permissions = permissions,
                            lastModified = timestamp
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing directory entry: ${lines[i]}", e)
            }
        }
        
        return entries
    }
}

