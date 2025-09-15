package com.ai.assistance.operit.terminal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.assistance.operit.terminal.utils.CacheManager
import com.ai.assistance.operit.terminal.utils.UpdateChecker
import com.ai.assistance.operit.terminal.utils.FtpServerManager
import com.ai.assistance.operit.terminal.TerminalManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    application: Application,
    private val terminalManager: TerminalManager? = null
) : AndroidViewModel(application) {
    private val cacheManager = CacheManager(application)
    private val updateChecker = UpdateChecker(application)
    private val ftpServerManager = FtpServerManager(application)

    private val _cacheSize = MutableStateFlow("点击刷新计算")
    val cacheSize = _cacheSize.asStateFlow()

    private val _updateStatus = MutableStateFlow("点击检查更新")
    val updateStatus = _updateStatus.asStateFlow()
    
    private val _isCalculatingCache = MutableStateFlow(false)
    val isCalculatingCache = _isCalculatingCache.asStateFlow()
    
    private val _isClearingCache = MutableStateFlow(false)
    val isClearingCache = _isClearingCache.asStateFlow()

    // FTP服务器相关状态
    private val _ftpServerStatus = MutableStateFlow("FTP服务器未运行")
    val ftpServerStatus = _ftpServerStatus.asStateFlow()
    
    private val _isFtpServerRunning = MutableStateFlow(false)
    val isFtpServerRunning = _isFtpServerRunning.asStateFlow()
    
    private val _isManagingFtpServer = MutableStateFlow(false)
    val isManagingFtpServer = _isManagingFtpServer.asStateFlow()

    // 自动检测更新，但不自动计算缓存大小
    init {
        checkForUpdates()
        updateFtpServerStatus()
    }

    fun getCacheSize() {
        viewModelScope.launch {
            _isCalculatingCache.value = true
            _cacheSize.value = "计算中..."
            try {
                val size = cacheManager.getCacheSize()
                _cacheSize.value = cacheManager.formatSize(size)
            } catch (e: Exception) {
                _cacheSize.value = "计算失败"
            } finally {
                _isCalculatingCache.value = false
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            _isClearingCache.value = true
            _cacheSize.value = "正在重置环境..."
            try {
                // 在清理缓存前停止FTP服务器
                if (ftpServerManager.isFtpServerRunning()) {
                    ftpServerManager.stopFtpServer()
                    updateFtpServerStatus()
                }
                
                cacheManager.clearCache(terminalManager)
                _cacheSize.value = "环境已重置 (需要重启应用)"
            } catch (e: Exception) {
                _cacheSize.value = "重置失败: ${e.message}"
            } finally {
                _isClearingCache.value = false
            }
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _updateStatus.value = "检查更新中..."
            when (val result = updateChecker.checkForUpdates(showToast = true)) {
                is UpdateChecker.UpdateResult.UpdateAvailable -> {
                    _updateStatus.value = "发现新版本: ${result.latestVersion} (当前: ${result.currentVersion})"
                }
                is UpdateChecker.UpdateResult.UpToDate -> {
                    _updateStatus.value = "已是最新版本 (${result.currentVersion})"
                }
                is UpdateChecker.UpdateResult.Error -> {
                    _updateStatus.value = "检查失败: ${result.message}"
                }
            }
        }
    }
    
    fun startFtpServer() {
        viewModelScope.launch {
            _isManagingFtpServer.value = true
            _ftpServerStatus.value = "正在启动FTP服务器..."
            try {
                val success = ftpServerManager.startFtpServer()
                if (success) {
                    _isFtpServerRunning.value = true
                    _ftpServerStatus.value = ftpServerManager.getFtpServerInfo()
                } else {
                    _ftpServerStatus.value = "启动失败 (请确保Ubuntu环境已初始化)"
                }
            } catch (e: Exception) {
                _ftpServerStatus.value = "启动失败: ${e.message}"
            } finally {
                _isManagingFtpServer.value = false
            }
        }
    }
    
    fun stopFtpServer() {
        viewModelScope.launch {
            _isManagingFtpServer.value = true
            _ftpServerStatus.value = "正在停止FTP服务器..."
            try {
                val success = ftpServerManager.stopFtpServer()
                if (success) {
                    _isFtpServerRunning.value = false
                    _ftpServerStatus.value = "FTP服务器已停止"
                } else {
                    _ftpServerStatus.value = "停止失败"
                }
            } catch (e: Exception) {
                _ftpServerStatus.value = "停止失败: ${e.message}"
            } finally {
                _isManagingFtpServer.value = false
            }
        }
    }
    
    private fun updateFtpServerStatus() {
        _isFtpServerRunning.value = ftpServerManager.isFtpServerRunning()
        _ftpServerStatus.value = ftpServerManager.getFtpServerInfo()
    }
} 