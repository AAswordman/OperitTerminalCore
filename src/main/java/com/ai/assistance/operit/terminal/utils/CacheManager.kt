package com.ai.assistance.operit.terminal.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

class CacheManager(private val context: Context) {

    private val filesDir: File = context.filesDir
    private val usrDir: File = File(filesDir, "usr")
    private val tmpDir: File = File(filesDir, "tmp")

    suspend fun getCacheSize(): Long = withContext(Dispatchers.IO) {
        val usrSize = getDirectorySize(usrDir)
        val tmpSize = getDirectorySize(tmpDir)
        usrSize + tmpSize
    }

    suspend fun clearCache(terminalManager: com.ai.assistance.operit.terminal.TerminalManager? = null) = withContext(Dispatchers.IO) {
        // 首先停止所有终端会话
        terminalManager?.cleanup()
        
        // 等待一下确保进程完全停止
        kotlinx.coroutines.delay(1000)
        
        // 清理文件系统
        usrDir.deleteRecursively()
        tmpDir.deleteRecursively()
        
        // 清理其他相关文件
        val filesToClean = listOf(
            "common.sh",
            "proot-distro.zip", 
            "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
        )
        
        filesToClean.forEach { fileName ->
            val file = File(filesDir, fileName)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun getDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L

        val visitedInodes = mutableSetOf<Any>()
        
        return dir.walkTopDown()
            .mapNotNull { file ->
                if (!file.isFile) return@mapNotNull null

                try {
                    val path = file.toPath()
                    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                    val fileKey = attrs.fileKey()

                    if (fileKey != null) {
                        if (visitedInodes.add(fileKey)) {
                            attrs.size()
                        } else {
                            0L // Already counted
                        }
                    } else {
                        // Fallback for file systems without fileKey support
                        file.length()
                    }
                } catch (e: Exception) {
                    // Fallback for broken symlinks or other errors
                    file.length()
                }
            }
            .sum()
    }

    fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
} 