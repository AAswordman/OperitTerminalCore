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

    // 自动滚动到底部
    LaunchedEffect(env.commandHistory.size, env.commandHistory.lastOrNull()?.output) {
        if (env.commandHistory.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(index = env.commandHistory.size)
            }
        }
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
            FullscreenContent(
                screenContent = env.screenContent,
                fontSize = fontSize,
                lineHeight = lineHeight,
                padding = padding,
                onSendInput = { env.onSendInput(it, false) },
                onGesture = { zoom ->
                    scaleFactor = max(0.5f, min(3f, scaleFactor * zoom))
                }
            )
        } else {
            TerminalContent(
                commandHistory = env.commandHistory,
                currentDirectory = env.currentDirectory,
                command = env.command,
                onCommandChange = env::onCommandChange,
                onSendCommand = { env.onSendInput(it, true) },
                onInterrupt = env::onInterrupt,
                listState = listState,
                fontSize = fontSize,
                lineHeight = lineHeight,
                padding = padding,
                onGesture = { zoom ->
                    scaleFactor = max(0.5f, min(3f, scaleFactor * zoom))
                },
                onNavigateToSetup = onNavigateToSetup,
                onNavigateToSettings = onNavigateToSettings
            )
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

@Composable
private fun FullscreenContent(
    screenContent: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: Float,
    padding: androidx.compose.ui.unit.Dp,
    onSendInput: (String) -> Unit,
    onGesture: (zoom: Float) -> Unit
) {
    var textFieldValue by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        if (event.changes.size >= 2) {
                            val zoom = event.calculateZoom()
                            if (abs(zoom - 1f) > 0.01f) {
                                onGesture(zoom)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .padding(padding)
    ) {
        // This text displays the actual terminal content from the PTY
        SelectionContainer {
            Text(
                text = screenContent,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                lineHeight = fontSize * lineHeight,
                modifier = Modifier.fillMaxSize()
            )
        }

        // This is an invisible text field that captures all keyboard input
        BasicTextField(
            value = textFieldValue,
            onValueChange = {
                // This is a simplified approach. A more robust solution
                // would compare the old and new text to determine what was
                // typed (e.g., handling backspace, pasting text).
                val typedText = if (it.length > textFieldValue.length) {
                    it.substring(textFieldValue.length)
                } else {
                    // Handle backspace. The ASCII backspace character is 8.
                    // Another common one is DEL (127). Vim uses BS.
                    "\u0008"
                }
                onSendInput(typedText)

                // We keep the text field's internal state, but it could also be cleared.
                // For vim, it's better to keep it to handle multi-character sequences,
                // but for raw input, clearing it after sending might be an option.
                textFieldValue = it
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            textStyle = TextStyle(
                // Make the text transparent so it's not visible
                color = Color.Transparent,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize
            ),
            cursorBrush = SolidColor(Color.Transparent), // Hide the cursor
            keyboardOptions = KeyboardOptions.Default.copy(
                // No specific action, we want raw input
                imeAction = ImeAction.None
            )
        )
    }
}

@Composable
private fun TerminalContent(
    commandHistory: SnapshotStateList<CommandHistoryItem>,
    currentDirectory: String,
    command: String,
    onCommandChange: (String) -> Unit,
    onSendCommand: (String) -> Unit,
    onInterrupt: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: Float,
    padding: androidx.compose.ui.unit.Dp,
    onGesture: (zoom: Float) -> Unit,
    onNavigateToSetup: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    // 终端内容
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)

                        // 只在有多个手指时处理缩放
                        if (event.changes.size >= 2) {
                            val zoom = event.calculateZoom()
                            if (abs(zoom - 1f) > 0.01f) {
                                onGesture(zoom)
                                // 消费事件以防止传递给LazyColumn
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 历史输出 - 占满全屏
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(padding)
            ) {
                items(commandHistory, key = { it.id }) { historyItem ->
                    SelectionContainer {
                        Column {
                            if (historyItem.prompt.isNotBlank() || historyItem.command.isNotBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Prompt as a label
                                    if (historyItem.prompt.isNotBlank()) {
                                        Surface(
                                            modifier = Modifier.padding(end = padding * 0.5f),
                                            color = Color(0xFF006400), // DarkGreen
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = getTruncatedPrompt(historyItem.prompt),
                                                color = Color.White,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = fontSize,
                                                modifier = Modifier.padding(horizontal = padding * 0.5f, vertical = padding * 0.1f)
                                            )
                                        }
                                    }
                                    // Command
                                    Text(
                                        text = highlight(historyItem.command, isCommand = true),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = fontSize,
                                        lineHeight = fontSize * lineHeight
                                    )
                                }
                                // Spacer
                                Spacer(modifier = Modifier.height(padding * 0.25f))
                            }

                            // 渲染已完成的输出页面
                            historyItem.outputPages.forEach { page ->
                                if (page.isNotEmpty()) {
                                    Text(
                                        text = highlight(page),
                                        color = Color.White,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = fontSize,
                                        lineHeight = fontSize * lineHeight,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 0.dp) // 页面之间没有内边距
                                    )
                                }
                            }

                            // 渲染当前实时输出页面
                            if (historyItem.output.isNotEmpty()) {
                                Text(
                                    text = highlight(historyItem.output),
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = fontSize,
                                    lineHeight = fontSize * lineHeight,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = padding * 0.5f) // 所有输出末尾的内边距
                                )
                            }
                        }
                    }

                    // Show a progress indicator for executing commands
                    if (historyItem.isExecuting) {
                        val context = LocalContext.current
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = context.getString(com.ai.assistance.operit.terminal.R.string.executing),
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }

            // 终端工具栏
            TerminalToolbar(
                onInterrupt = onInterrupt,
                onSendCommand = onSendCommand,
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
                        text = getTruncatedPrompt(currentDirectory.ifEmpty { "$ " }),
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize,
                        modifier = Modifier.padding(horizontal = padding * 0.5f, vertical = padding * 0.1f)
                    )
                }
                BasicTextField(
                    value = command,
                    onValueChange = onCommandChange,
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
                        if (command.isNotBlank()) {
                            onSendCommand(command)
                        }
                    })
                )
            }
        }
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