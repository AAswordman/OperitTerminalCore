package com.ai.assistance.operit.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import kotlinx.coroutines.launch
import android.util.Log

@Stable
class TerminalEnv(
    sessionsState: State<List<TerminalSessionData>>,
    currentSessionIdState: State<String?>,
    commandHistoryState: State<SnapshotStateList<CommandHistoryItem>>,
    currentDirectoryState: State<String>,
    isFullscreenState: State<Boolean>,
    screenContentState: State<String>,
    private val terminalManager: TerminalManager,
    val forceShowSetup: Boolean = false
) {
    val sessions by sessionsState
    val currentSessionId by currentSessionIdState
    val commandHistory by commandHistoryState
    val currentDirectory by currentDirectoryState
    val isFullscreen by isFullscreenState
    val screenContent by screenContentState

    var command by mutableStateOf("")

    fun onCommandChange(newCommand: String) {
        command = newCommand
    }

    fun onSendInput(inputText: String, isCommand: Boolean) {
        // 允许空输入（用于交互式场景发送回车）
        if (isCommand) {
            // 命令模式：也允许空命令（用于 SSH 等交互场景）
            terminalManager.coroutineScope.launch {
                terminalManager.sendCommand(inputText)
            }
            if (inputText == command) {
                command = ""
            }
        } else {
            // 输入模式：允许空输入（例如 ssh-keygen 直接回车使用默认路径）
            terminalManager.sendInput(inputText)
        }
    }

    fun onSetup(commands: List<String>) {
        val fullCommand = commands.joinToString(separator = " && ")
        terminalManager.coroutineScope.launch {
            terminalManager.sendCommand(fullCommand)
        }
    }

    fun onInterrupt() = terminalManager.sendInterruptSignal()
    fun onNewSession() {
        // 在terminalManager的协程作用域中异步创建会话
        terminalManager.coroutineScope.launch {
            try {
                terminalManager.createNewSession()
                Log.d("TerminalEnv", "New session created successfully")
            } catch (e: Exception) {
                Log.e("TerminalEnv", "Failed to create new session", e)
            }
        }
    }
    fun onSwitchSession(sessionId: String) = terminalManager.switchToSession(sessionId)
    fun onCloseSession(sessionId: String) = terminalManager.closeSession(sessionId)
}

@Composable
fun rememberTerminalEnv(terminalManager: TerminalManager, forceShowSetup: Boolean = false): TerminalEnv {
    val sessionsState = terminalManager.sessions.collectAsState(initial = emptyList())
    val currentSessionIdState = terminalManager.currentSessionId.collectAsState(initial = null)
    val commandHistoryState = terminalManager.commandHistory.collectAsState(initial = SnapshotStateList<CommandHistoryItem>())
    val currentDirectoryState = terminalManager.currentDirectory.collectAsState(initial = "$ ")
    val isFullscreenState = terminalManager.isFullscreen.collectAsState(initial = false)
    val screenContentState = terminalManager.screenContent.collectAsState(initial = "")

    return remember(terminalManager, forceShowSetup) {
        TerminalEnv(
            sessionsState = sessionsState,
            currentSessionIdState = currentSessionIdState,
            commandHistoryState = commandHistoryState,
            currentDirectoryState = currentDirectoryState,
            isFullscreenState = isFullscreenState,
            screenContentState = screenContentState,
            terminalManager = terminalManager,
            forceShowSetup = forceShowSetup
        )
    }
} 