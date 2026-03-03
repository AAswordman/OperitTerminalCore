package com.ai.assistance.operit.terminal.view.canvas

/**
 * Lightweight tab model used by CanvasTerminalView for in-surface tab rendering.
 */
data class TerminalTabRenderItem(
    val id: String,
    val title: String,
    val canClose: Boolean
)
