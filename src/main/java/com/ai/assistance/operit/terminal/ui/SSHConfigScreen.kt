package com.ai.assistance.operit.terminal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.terminal.data.SSHAuthType
import com.ai.assistance.operit.terminal.data.SSHConfig

/**
 * SSH 配置界面（单一配置）
 */
@Composable
fun SSHConfigScreen(
    config: SSHConfig?,
    onSave: (SSHConfig) -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "SSH 连接配置",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = SettingsTheme.onSurfaceColor
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (config == null) {
            // 无配置，显示添加按钮
            Text(
                text = "暂无SSH配置",
                color = SettingsTheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            var showDialog by remember { mutableStateOf(false) }
            
            Button(
                onClick = { showDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SettingsTheme.primaryColor
                )
            ) {
                Text("设置 SSH 配置")
            }
            
            if (showDialog) {
                SSHConfigEditDialog(
                    config = null,
                    onDismiss = { showDialog = false },
                    onConfirm = { newConfig ->
                        onSave(newConfig)
                        showDialog = false
                    }
                )
            }
        } else {
            // 显示当前配置
            SSHConfigCard(
                config = config,
                onEdit = { newConfig -> onSave(newConfig) },
                onDelete = onDelete
            )
        }
    }
}

/**
 * SSH 配置卡片
 */
@Composable
private fun SSHConfigCard(
    config: SSHConfig,
    onEdit: (SSHConfig) -> Unit,
    onDelete: () -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = SettingsTheme.surfaceColor
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 配置信息
            Text(
                text = "${config.username}@${config.host}:${config.port}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = SettingsTheme.onSurfaceColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "认证方式: ${if (config.authType == SSHAuthType.PASSWORD) "密码" else "公钥"}",
                fontSize = 14.sp,
                color = SettingsTheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 操作按钮
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showEditDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SettingsTheme.primaryColor
                    )
                ) {
                    Icon(Icons.Default.Edit, "编辑", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("编辑")
                }
                
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SettingsTheme.errorColor
                    )
                ) {
                    Icon(Icons.Default.Delete, "删除", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除")
                }
            }
        }
    }
    
    // 编辑对话框
    if (showEditDialog) {
        SSHConfigEditDialog(
            config = config,
            onDismiss = { showEditDialog = false },
            onConfirm = { newConfig ->
                onEdit(newConfig)
                showEditDialog = false
            }
        )
    }
    
    // 删除确认对话框
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除", color = SettingsTheme.onSurfaceColor) },
            text = { Text("确定要删除此SSH配置吗？", color = SettingsTheme.onSurfaceColor) },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SettingsTheme.errorColor
                    )
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消", color = SettingsTheme.primaryColor)
                }
            },
            containerColor = SettingsTheme.surfaceColor
        )
    }
}

/**
 * SSH 配置编辑对话框
 */
@Composable
fun SSHConfigEditDialog(
    config: SSHConfig? = null,
    onDismiss: () -> Unit,
    onConfirm: (SSHConfig) -> Unit
) {
    var host by remember { mutableStateOf(config?.host ?: "") }
    var port by remember { mutableStateOf(config?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(config?.username ?: "") }
    var authType by remember { mutableStateOf(config?.authType ?: SSHAuthType.PASSWORD) }
    var password by remember { mutableStateOf(config?.password ?: "") }
    var privateKeyPath by remember { mutableStateOf(config?.privateKeyPath ?: "") }
    var passphrase by remember { mutableStateOf(config?.passphrase ?: "") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (config == null) "添加 SSH 配置" else "编辑 SSH 配置",
                color = SettingsTheme.onSurfaceColor
            )
        },
        text = {
            LazyColumn {
                item {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("主机地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SettingsTheme.onSurfaceColor,
                            unfocusedTextColor = SettingsTheme.onSurfaceColor,
                            focusedBorderColor = SettingsTheme.primaryColor,
                            unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                            focusedLabelColor = SettingsTheme.primaryColor,
                            unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                        )
                    )
                }
                
                item { Spacer(Modifier.height(8.dp)) }
                
                item {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SettingsTheme.onSurfaceColor,
                            unfocusedTextColor = SettingsTheme.onSurfaceColor,
                            focusedBorderColor = SettingsTheme.primaryColor,
                            unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                            focusedLabelColor = SettingsTheme.primaryColor,
                            unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                        )
                    )
                }
                
                item { Spacer(Modifier.height(8.dp)) }
                
                item {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SettingsTheme.onSurfaceColor,
                            unfocusedTextColor = SettingsTheme.onSurfaceColor,
                            focusedBorderColor = SettingsTheme.primaryColor,
                            unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                            focusedLabelColor = SettingsTheme.primaryColor,
                            unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                        )
                    )
                }
                
                item { Spacer(Modifier.height(8.dp)) }
                
                // 认证方式选择
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = authType == SSHAuthType.PASSWORD,
                            onClick = { authType = SSHAuthType.PASSWORD },
                            label = { Text("密码认证") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = authType == SSHAuthType.PUBLIC_KEY,
                            onClick = { authType = SSHAuthType.PUBLIC_KEY },
                            label = { Text("公钥认证") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                
                item { Spacer(Modifier.height(8.dp)) }
                
                // 根据认证类型显示不同字段
                if (authType == SSHAuthType.PASSWORD) {
                    item {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SettingsTheme.onSurfaceColor,
                                unfocusedTextColor = SettingsTheme.onSurfaceColor,
                                focusedBorderColor = SettingsTheme.primaryColor,
                                unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                                focusedLabelColor = SettingsTheme.primaryColor,
                                unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                            )
                        )
                    }
                } else {
                    item {
                        OutlinedTextField(
                            value = privateKeyPath,
                            onValueChange = { privateKeyPath = it },
                            label = { Text("私钥路径") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SettingsTheme.onSurfaceColor,
                                unfocusedTextColor = SettingsTheme.onSurfaceColor,
                                focusedBorderColor = SettingsTheme.primaryColor,
                                unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                                focusedLabelColor = SettingsTheme.primaryColor,
                                unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                            )
                        )
                    }
                    
                    item { Spacer(Modifier.height(8.dp)) }
                    
                    item {
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("密钥密码（可选）") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SettingsTheme.onSurfaceColor,
                                unfocusedTextColor = SettingsTheme.onSurfaceColor,
                                focusedBorderColor = SettingsTheme.primaryColor,
                                unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                                focusedLabelColor = SettingsTheme.primaryColor,
                                unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newConfig = SSHConfig(
                        host = host,
                        port = port.toIntOrNull() ?: 22,
                        username = username,
                        authType = authType,
                        password = if (authType == SSHAuthType.PASSWORD) password else null,
                        privateKeyPath = if (authType == SSHAuthType.PUBLIC_KEY) privateKeyPath else null,
                        passphrase = if (authType == SSHAuthType.PUBLIC_KEY && passphrase.isNotEmpty()) passphrase else null
                    )
                    onConfirm(newConfig)
                },
                enabled = host.isNotBlank() && username.isNotBlank() &&
                        (authType == SSHAuthType.PUBLIC_KEY && privateKeyPath.isNotBlank() ||
                         authType == SSHAuthType.PASSWORD && password.isNotBlank()),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SettingsTheme.primaryColor
                )
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = SettingsTheme.primaryColor)
            }
        },
        containerColor = SettingsTheme.surfaceColor
    )
}
