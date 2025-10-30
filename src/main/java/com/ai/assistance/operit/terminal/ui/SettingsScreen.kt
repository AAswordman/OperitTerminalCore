package com.ai.assistance.operit.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.terminal.data.PackageManagerType
import com.ai.assistance.operit.terminal.data.SourceConfig

// 三色系主题配置
object SettingsTheme {
    // 蓝色系 - 主要操作和强调
    val primaryColor = Color(0xFF2196F3)        // 主色
    val primaryVariant = Color(0xFF1976D2)      // 深蓝变体
    
    // 灰色系 - 背景和文字
    val backgroundColor = Color(0xFF121212)     // 深色背景
    val surfaceColor = Color(0xFF1E1E1E)       // 卡片背景
    val onSurfaceColor = Color(0xFFE0E0E0)     // 主要文字
    val onSurfaceVariant = Color(0xFFB0B0B0)   // 次要文字
    
    // 红色系 - 危险操作和错误
    val errorColor = Color(0xFFE53E3E)         // 错误/危险色
    val errorVariant = Color(0xFFD32F2F)       // 深红变体
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel { SettingsViewModel(context.applicationContext as android.app.Application) }
    
    val cacheSize by viewModel.cacheSize.collectAsState()
    val updateStatus by viewModel.updateStatus.collectAsState()
    val isCalculatingCache by viewModel.isCalculatingCache.collectAsState()
    
    // FTP服务器相关状态
    val ftpServerStatus by viewModel.ftpServerStatus.collectAsState()
    val isFtpServerRunning by viewModel.isFtpServerRunning.collectAsState()
    val isManagingFtpServer by viewModel.isManagingFtpServer.collectAsState()
    
    // 更新相关状态
    val hasUpdateAvailable by viewModel.hasUpdateAvailable.collectAsState()
    
    // 源管理相关状态
    val sourceConfigs by viewModel.sourceConfigs.collectAsState()
    var showSourceDialogFor by remember { mutableStateOf<PackageManagerType?>(null) }
    
    // SSH配置相关状态（单一配置）
    val sshConfig by viewModel.sshConfig.collectAsState()
    val sshEnabled by viewModel.sshEnabled.collectAsState()
    
    var showClearCacheDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(com.ai.assistance.operit.terminal.R.string.settings_title), color = SettingsTheme.onSurfaceColor) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = context.getString(com.ai.assistance.operit.terminal.R.string.back), tint = SettingsTheme.onSurfaceColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SettingsTheme.surfaceColor
                ),
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
        containerColor = SettingsTheme.backgroundColor
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // FTP服务器管理区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SettingsTheme.surfaceColor)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.ftp_server_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SettingsTheme.onSurfaceColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = ftpServerStatus,
                        color = if (isFtpServerRunning) SettingsTheme.primaryColor else SettingsTheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isFtpServerRunning) {
                            Button(
                                onClick = { viewModel.stopFtpServer() },
                                enabled = !isManagingFtpServer,
                                colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.errorColor),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isManagingFtpServer) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = SettingsTheme.onSurfaceColor
                                    )
                                } else {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isManagingFtpServer) context.getString(com.ai.assistance.operit.terminal.R.string.ftp_server_stopping) else context.getString(com.ai.assistance.operit.terminal.R.string.ftp_server_stop))
                            }
                        } else {
                            Button(
                                onClick = { viewModel.startFtpServer() },
                                enabled = !isManagingFtpServer,
                                colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.primaryColor),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isManagingFtpServer) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = SettingsTheme.onSurfaceColor
                                    )
                                } else {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isManagingFtpServer) context.getString(com.ai.assistance.operit.terminal.R.string.ftp_server_starting) else context.getString(com.ai.assistance.operit.terminal.R.string.ftp_server_start))
                            }
                        }
                    }
                    
                    if (isFtpServerRunning) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = context.getString(com.ai.assistance.operit.terminal.R.string.ftp_server_tip),
                            color = SettingsTheme.primaryColor,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = context.getString(com.ai.assistance.operit.terminal.R.string.ftp_server_suggestion),
                            color = SettingsTheme.primaryColor,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            
            // 缓存管理区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SettingsTheme.surfaceColor)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.storage_management_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SettingsTheme.onSurfaceColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.ubuntu_environment_size, cacheSize),
                        color = SettingsTheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.getCacheSize() },
                            enabled = !isCalculatingCache,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = SettingsTheme.primaryColor
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SettingsTheme.primaryColor)
                        ) {
                            if (isCalculatingCache) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = SettingsTheme.primaryColor
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isCalculatingCache) context.getString(com.ai.assistance.operit.terminal.R.string.refresh_size_calculating) else context.getString(com.ai.assistance.operit.terminal.R.string.refresh_size))
                        }
                        
                        Button(
                            onClick = { showClearCacheDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.errorColor),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(context.getString(com.ai.assistance.operit.terminal.R.string.reset_environment))
                        }
                    }
                }
            }
            
            // 项目地址和更新检查区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SettingsTheme.surfaceColor)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.project_address_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SettingsTheme.onSurfaceColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.project_name),
                        color = SettingsTheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = updateStatus,
                        color = if (hasUpdateAvailable) SettingsTheme.primaryColor else SettingsTheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.openGitHubRepo() },
                            enabled = true,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = SettingsTheme.primaryColor
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SettingsTheme.primaryColor)
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(context.getString(com.ai.assistance.operit.terminal.R.string.visit_project))
                        }
                        
                        Button(
                            onClick = { 
                                if (hasUpdateAvailable) {
                                    viewModel.openGitHubReleases()
                                } else {
                                    viewModel.checkForUpdates()
                                }
                            },
                            enabled = true,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hasUpdateAvailable) SettingsTheme.primaryColor else SettingsTheme.surfaceColor
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (hasUpdateAvailable) {
                                Icon(Icons.Default.GetApp, contentDescription = null, modifier = Modifier.size(16.dp))
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (hasUpdateAvailable) context.getString(com.ai.assistance.operit.terminal.R.string.update_now) else context.getString(com.ai.assistance.operit.terminal.R.string.check_updates))
                        }
                    }
                }
            }
            
            // SSH配置区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SettingsTheme.surfaceColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // SSH 启用开关
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用 SSH 连接",
                                style = MaterialTheme.typography.titleMedium,
                                color = SettingsTheme.onSurfaceColor
                            )
                            if (sshConfig != null) {
                                Text(
                                    text = if (sshEnabled) "使用远程 SSH 终端" else "使用本地终端",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SettingsTheme.onSurfaceColor.copy(alpha = 0.6f)
                                )
                            }
                        }
                        Switch(
                            checked = sshEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setSSHEnabled(enabled)
                            },
                            enabled = sshConfig != null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SettingsTheme.primaryColor,
                                checkedTrackColor = SettingsTheme.primaryColor.copy(alpha = 0.5f)
                            )
                        )
                    }
                    
                    if (sshConfig == null) {
                        Text(
                            text = "请先配置 SSH 连接信息",
                            style = MaterialTheme.typography.bodySmall,
                            color = SettingsTheme.onSurfaceColor.copy(alpha = 0.6f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = SettingsTheme.onSurfaceColor.copy(alpha = 0.1f)
                    )
                    
                    // SSH 配置表单
                    SSHConfigScreen(
                        config = sshConfig,
                        onSave = { config ->
                            viewModel.saveSSHConfig(config)
                        },
                        onDelete = {
                            viewModel.deleteSSHConfig()
                        }
                    )
                }
            }
            
            // 源管理区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SettingsTheme.surfaceColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "软件源管理",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SettingsTheme.onSurfaceColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    sourceConfigs.forEach { (pm, config) ->
                        SettingsItem(
                            title = pm.displayName,
                            subtitle = "当前源: ${config.sources.find { it.id == config.selectedSourceId }?.name ?: "N/A"}",
                            onClick = { showSourceDialogFor = pm },
                            icon = Icons.Default.Source
                        )
                        HorizontalDivider(color = SettingsTheme.backgroundColor)
                    }
                }
            }
            HorizontalDivider(color = SettingsTheme.surfaceColor)
        }
    }
    
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { 
                Text(context.getString(com.ai.assistance.operit.terminal.R.string.reset_dialog_title), color = SettingsTheme.errorColor, fontWeight = FontWeight.Bold) 
            },
            text = { 
                Column {
                    Text(
                        context.getString(com.ai.assistance.operit.terminal.R.string.reset_dialog_description),
                        color = SettingsTheme.onSurfaceColor,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(context.getString(com.ai.assistance.operit.terminal.R.string.reset_dialog_item1), color = SettingsTheme.onSurfaceColor)
                    Text(context.getString(com.ai.assistance.operit.terminal.R.string.reset_dialog_item2), color = SettingsTheme.onSurfaceColor) 
                    Text(context.getString(com.ai.assistance.operit.terminal.R.string.reset_dialog_item3), color = SettingsTheme.onSurfaceColor)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        context.getString(com.ai.assistance.operit.terminal.R.string.reset_dialog_warning),
                        color = SettingsTheme.errorColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        context.getString(com.ai.assistance.operit.terminal.R.string.reset_dialog_ftp_warning),
                        color = SettingsTheme.errorColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearCache()
                        showClearCacheDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.errorColor)
                ) {
                    Text(context.getString(com.ai.assistance.operit.terminal.R.string.reset_confirm), color = SettingsTheme.onSurfaceColor)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showClearCacheDialog = false },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SettingsTheme.onSurfaceVariant
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SettingsTheme.onSurfaceVariant)
                ) {
                    Text(context.getString(com.ai.assistance.operit.terminal.R.string.dialog_cancel), color = SettingsTheme.onSurfaceVariant)
                }
            },
            containerColor = SettingsTheme.surfaceColor
        )
    }
    
    // 源选择弹窗
    showSourceDialogFor?.let { pm ->
        val config = sourceConfigs[pm]
        if (config != null) {
            SourceSelectionDialog(
                packageManager = pm,
                config = config,
                onDismiss = { showSourceDialogFor = null },
                onSourceSelected = { sourceId ->
                    viewModel.updateSource(pm, sourceId)
                    showSourceDialogFor = null
                },
                onAddCustomSource = { name, url, isHttps ->
                    viewModel.addCustomSource(pm, name, url, isHttps)
                },
                onDeleteCustomSource = { sourceId ->
                    viewModel.deleteCustomSource(pm, sourceId)
                }
            )
        }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.ChevronRight
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = SettingsTheme.onSurfaceColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = subtitle, color = SettingsTheme.onSurfaceVariant, fontSize = 14.sp)
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SettingsTheme.primaryColor
        )
    }
}

@Composable
private fun SourceSelectionDialog(
    packageManager: PackageManagerType,
    config: SourceConfig,
    onDismiss: () -> Unit,
    onSourceSelected: (String) -> Unit,
    onAddCustomSource: (name: String, url: String, isHttps: Boolean) -> Unit,
    onDeleteCustomSource: (String) -> Unit
) {
    var selectedId by remember { mutableStateOf(config.selectedSourceId) }
    var showAddCustomDialog by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("选择 ${packageManager.displayName} 源", color = SettingsTheme.onSurfaceColor)
                IconButton(onClick = { showAddCustomDialog = true }) {
                    Icon(Icons.Default.Add, "添加自定义源", tint = SettingsTheme.primaryColor)
                }
            }
        },
        text = {
            LazyColumn {
                items(config.sources) { source ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selectedId = source.id }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedId == source.id,
                            onClick = { selectedId = source.id },
                            colors = RadioButtonDefaults.colors(selectedColor = SettingsTheme.primaryColor)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(source.name, color = SettingsTheme.onSurfaceColor)
                            Text(
                                source.url, 
                                color = SettingsTheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                        // 只有自定义源才显示删除按钮
                        if (source.id.startsWith("custom_")) {
                            IconButton(onClick = { onDeleteCustomSource(source.id) }) {
                                Icon(Icons.Default.Delete, "删除", tint = SettingsTheme.errorColor)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSourceSelected(selectedId) },
                colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.primaryColor)
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        containerColor = SettingsTheme.surfaceColor
    )
    
    // 添加自定义源弹窗
    if (showAddCustomDialog) {
        AddCustomSourceDialog(
            packageManager = packageManager,
            onDismiss = { showAddCustomDialog = false },
            onConfirm = { name, url, isHttps ->
                onAddCustomSource(name, url, isHttps)
                showAddCustomDialog = false
            }
        )
    }
}

@Composable
private fun AddCustomSourceDialog(
    packageManager: PackageManagerType,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, isHttps: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var isHttps by remember { mutableStateOf(true) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自定义 ${packageManager.displayName} 源", color = SettingsTheme.onSurfaceColor) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("源名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SettingsTheme.primaryColor,
                        focusedLabelColor = SettingsTheme.primaryColor
                    )
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("源地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SettingsTheme.primaryColor,
                        focusedLabelColor = SettingsTheme.primaryColor
                    )
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isHttps,
                        onCheckedChange = { isHttps = it },
                        colors = CheckboxDefaults.colors(checkedColor = SettingsTheme.primaryColor)
                    )
                    Text("HTTPS", color = SettingsTheme.onSurfaceColor)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    if (name.isNotBlank() && url.isNotBlank()) {
                        onConfirm(name, url, isHttps)
                    }
                },
                enabled = name.isNotBlank() && url.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.primaryColor)
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        containerColor = SettingsTheme.surfaceColor
    )
} 