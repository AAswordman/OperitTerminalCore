package com.ai.assistance.operit.terminal.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.view.canvas.CanvasTerminalOutput
import com.ai.assistance.operit.terminal.view.canvas.CanvasTerminalScreen
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ai.assistance.operit.terminal.TerminalEnv
import com.ai.assistance.operit.terminal.view.SyntaxColors
import com.ai.assistance.operit.terminal.view.SyntaxHighlightingVisualTransformation
import com.ai.assistance.operit.terminal.view.highlight
import androidx.compose.material.icons.filled.Settings
import com.ai.assistance.operit.terminal.view.canvas.CanvasTerminalScreen

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TerminalHome(
    env: TerminalEnv,
    onNavigateToSetup: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 语法高亮
    val visualTransformation = remember { SyntaxHighlightingVisualTransformation() }

    // 缩放状态
    var scaleFactor by remember { mutableStateOf(1f) }

    // 删除确认弹窗状态
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<String?>(null) }

    // 计算基于缩放因子的字体大小和间距
    val baseFontSize = 14.sp
    val fontSize = with(LocalDensity.current) {
        (baseFontSize.toPx() * scaleFactor).toSp()
    }
    val baseLineHeight = 1.2f
    val lineHeight = baseLineHeight * scaleFactor
    val basePadding = 8.dp
    val padding = basePadding * scaleFactor

    // 获取当前 session 的 PTY
    val currentPty = remember(env.currentSessionId, env.sessions) {
        env.sessions.find { it.id == env.currentSessionId }?.pty
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 会话标签页
        SessionTabBar(
            sessions = env.sessions,
            currentSessionId = env.currentSessionId,
            onSessionClick = env::onSwitchSession,
            onNewSession = env::onNewSession,
            onCloseSession = { sessionId ->
                sessionToDelete = sessionId
                showDeleteConfirmDialog = true
            }
        )

        if (env.isFullscreen) {
            CanvasTerminalScreen(
                emulator = env.terminalEmulator,
                modifier = Modifier.fillMaxSize(),
                pty = currentPty,
                onInput = { env.onSendInput(it, false) }
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Canvas输出区域（占满剩余空间）
                CanvasTerminalOutput(
                    emulator = env.terminalEmulator,
                    modifier = Modifier.weight(1f),
                    pty = currentPty
                )
                
                // 终端工具栏
                TerminalToolbar(
                    onInterrupt = env::onInterrupt,
                    onSendCommand = { env.onSendInput(it, true) },
                    fontSize = fontSize * 0.8f,
                    padding = padding,
                    onNavigateToSetup = onNavigateToSetup,
                    onNavigateToSettings = onNavigateToSettings
                )
                
                // 当前输入行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(padding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.padding(end = padding * 0.5f),
                        color = Color(0xFF006400), // DarkGreen
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = getTruncatedPrompt(env.currentDirectory.ifEmpty { "$ " }),
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize,
                            modifier = Modifier.padding(horizontal = padding * 0.5f, vertical = padding * 0.1f)
                        )
                    }
                    BasicTextField(
                        value = env.command,
                        onValueChange = env::onCommandChange,
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(
                            color = SyntaxColors.commandDefault,
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize
                        ),
                        cursorBrush = SolidColor(Color.Green),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            env.onSendInput(env.command, true)
                        })
                    )
                }
            }
        }
    }

    // 删除确认弹窗
    if (showDeleteConfirmDialog && sessionToDelete != null) {
        val context = LocalContext.current
        val sessionTitle = env.sessions.find { it.id == sessionToDelete }?.title ?: context.getString(com.ai.assistance.operit.terminal.R.string.unknown_session)

        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmDialog = false
                sessionToDelete = null
            },
            title = {
                Text(
                    text = context.getString(com.ai.assistance.operit.terminal.R.string.confirm_delete_session),
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = context.getString(com.ai.assistance.operit.terminal.R.string.delete_session_message, sessionTitle),
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sessionToDelete?.let { sessionId ->
                            env.onCloseSession(sessionId)
                        }
                        showDeleteConfirmDialog = false
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.delete),
                        color = Color.Red
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.cancel),
                        color = Color.White
                    )
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.Gray
        )
    }
}

private fun getTruncatedPrompt(prompt: String, maxLength: Int = 16): String {
    val trimmed = prompt.trimEnd()
    return if (trimmed.length > maxLength) {
        "..." + trimmed.takeLast(maxLength - 3)
    } else {
        trimmed
    }
}

@Composable
private fun SessionTabBar(
    sessions: List<TerminalSessionData>,
    currentSessionId: String?,
    onSessionClick: (String) -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF2D2D2D),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 会话标签页列表
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(sessions) { session ->
                    SessionTab(
                        session = session,
                        isActive = session.id == currentSessionId,
                        onClick = { onSessionClick(session.id) },
                        onClose = if (sessions.size > 1) {
                            { onCloseSession(session.id) }
                        } else null
                    )
                }
            }

            // 新建会话按钮
            IconButton(
                onClick = onNewSession,
                modifier = Modifier.size(32.dp)
            ) {
                val context = LocalContext.current
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = context.getString(com.ai.assistance.operit.terminal.R.string.new_session),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun SessionTab(
    session: TerminalSessionData,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: (() -> Unit)?
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        color = if (isActive) Color(0xFF4A4A4A) else Color(0xFF3A3A3A),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = session.title,
                color = if (isActive) Color.White else Color.Gray,
                fontSize = 12.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // 关闭按钮（只有多个会话时才显示）
            onClose?.let { closeAction ->
                val context = LocalContext.current
                IconButton(
                    onClick = closeAction,
                    modifier = Modifier.size(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = context.getString(com.ai.assistance.operit.terminal.R.string.close_session),
                        tint = Color.Gray,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalToolbar(
    onInterrupt: () -> Unit,
    onSendCommand: (String) -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp,
    onNavigateToSetup: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1A1A1A),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padding, vertical = padding * 0.5f),
            horizontalArrangement = Arrangement.spacedBy(padding * 0.5f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Ctrl+C 中断按钮
            Surface(
                modifier = Modifier.clickable { onInterrupt() },
                color = Color(0xFF4A4A4A),
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(padding * 0.3f)
                ) {
                    Text(
                        text = "Ctrl+C",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.interrupt),
                        color = Color.Gray,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize * 0.9f
                    )
                }
            }

            // 分隔线
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(padding * 1.5f)
                    .background(Color(0xFF3A3A3A))
            )

            Spacer(Modifier.weight(1f))

            // 环境配置按钮
            Surface(
                modifier = Modifier.clickable { onNavigateToSetup() },
                color = Color(0xFF4A4A4A),
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.environment_setup),
                        color = Color.White,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 设置按钮
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = context.getString(com.ai.assistance.operit.terminal.R.string.settings),
                tint = Color.Gray,
                modifier = Modifier
                    .clickable { onNavigateToSettings() }
                    .padding(start = padding)
                    .size(padding * 2.5f)
            )
        }
    }
} 