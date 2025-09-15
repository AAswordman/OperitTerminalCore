package com.ai.assistance.operit.terminal.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

class UpdateChecker(private val context: Context) {

    companion object {
        private const val GITHUB_API_URL = "https://api.github.com/repos/AAswordman/OperitTerminal/tags"
    }

    sealed class UpdateResult {
        data class UpdateAvailable(val latestVersion: String, val currentVersion: String) : UpdateResult()
        data class UpToDate(val currentVersion: String) : UpdateResult()
        data class Error(val message: String) : UpdateResult()
    }

    suspend fun checkForUpdates(showToast: Boolean = false): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val currentVersion = getCurrentAppVersion()
            
            val url = URL(GITHUB_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            
            val tags = JSONArray(response)
            val latestVersion = findLatestVersion(tags)

            val result = if (latestVersion != null && isNewer(latestVersion, currentVersion)) {
                UpdateResult.UpdateAvailable(latestVersion, currentVersion)
            } else {
                UpdateResult.UpToDate(currentVersion)
            }
            
            // 如果需要显示 Toast，切换到主线程显示
            if (showToast) {
                withContext(Dispatchers.Main) {
                    when (result) {
                        is UpdateResult.UpdateAvailable -> {
                            Toast.makeText(context, "发现新版本: ${result.latestVersion}", Toast.LENGTH_LONG).show()
                        }
                        is UpdateResult.UpToDate -> {
                            Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                        }
                        is UpdateResult.Error -> {
                            Toast.makeText(context, "检查更新失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            
            result
        } catch (e: Exception) {
            val result = UpdateResult.Error(e.message ?: "Unknown error")
            
            if (showToast) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "检查更新失败", Toast.LENGTH_SHORT).show()
                }
            }
            
            result
        }
    }

    private fun findLatestVersion(tags: JSONArray): String? {
        val versionPattern = Pattern.compile("^v(\\d+\\.\\d+)$")
        var latestTag: String? = null
        for (i in 0 until tags.length()) {
            val tagObject = tags.getJSONObject(i)
            val tagName = tagObject.getString("name")
            val matcher = versionPattern.matcher(tagName)
            if (matcher.matches()) {
                if (latestTag == null || isNewer(tagName, latestTag)) {
                    latestTag = tagName
                }
            }
        }
        return latestTag
    }

    private fun isNewer(newVersion: String, oldVersion: String): Boolean {
        // Simple version comparison, assumes vX.Y format
        val newVersionValue = newVersion.replace("v", "").toFloatOrNull() ?: 0f
        val oldVersionValue = oldVersion.replace("v", "").toFloatOrNull() ?: 0f
        return newVersionValue > oldVersionValue
    }

    private fun getCurrentAppVersion(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            "v" + packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            "v0.0" // Should not happen
        }
    }
} 