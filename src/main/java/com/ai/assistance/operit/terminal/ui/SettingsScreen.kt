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
    
    var showClearCacheDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A1A)
                )
            )
        },
        containerColor = Color(0xFF1A1A1A)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            // FTP服务器管理区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "FTP文件服务器",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = ftpServerStatus,
                        color = if (isFtpServerRunning) Color.Green else Color.Gray,
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
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isManagingFtpServer) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                } else {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isManagingFtpServer) "停止中..." else "停止服务")
                            }
                        } else {
                            Button(
                                onClick = { viewModel.startFtpServer() },
                                enabled = !isManagingFtpServer,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isManagingFtpServer) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                } else {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isManagingFtpServer) "启动中..." else "启动服务")
                            }
                        }
                    }
                    
                    if (isFtpServerRunning) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "💡 提示：使用FTP客户端连接到上述地址即可访问Ubuntu文件系统",
                            color = Color(0xFFFFEB3B),
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
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "存储管理",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Ubuntu环境大小: $cacheSize",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.getCacheSize() },
                            enabled = !isCalculatingCache,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isCalculatingCache) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isCalculatingCache) "计算中..." else "刷新大小")
                        }
                        
                        Button(
                            onClick = { showClearCacheDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("重置环境")
                        }
                    }
                }
            }
            
            SettingsItem(
                title = "更新检查",
                subtitle = updateStatus,
                onClick = { viewModel.checkForUpdates() },
                icon = Icons.Default.Refresh
            )
            HorizontalDivider(color = Color(0xFF2D2D2D))
        }
    }
    
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { 
                Text("⚠️ 危险操作", color = Color.Red, fontWeight = FontWeight.Bold) 
            },
            text = { 
                Column {
                    Text(
                        "此操作将完全删除Ubuntu虚拟环境，包括：",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("• 所有已安装的软件包", color = Color.White)
                    Text("• 用户数据和配置文件", color = Color.White) 
                    Text("• 系统文件和环境设置", color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "操作后需要重新初始化Ubuntu环境，这可能需要几分钟时间。",
                        color = Color.Yellow
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "FTP服务器也将被停止。确定要继续吗？",
                        color = Color.Red,
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("确定重置", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearCacheDialog = false }) {
                    Text("取消", color = Color.White)
                }
            },
            containerColor = Color(0xFF2D2D2D)
        )
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
            Text(text = title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = subtitle, color = Color.Gray, fontSize = 14.sp)
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.Gray
        )
    }
} 