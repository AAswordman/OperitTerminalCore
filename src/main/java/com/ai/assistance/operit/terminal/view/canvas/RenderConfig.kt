package com.ai.assistance.operit.terminal.view.canvas

import android.graphics.Color
import android.graphics.Typeface

/**
 * 渲染配置
 */
data class RenderConfig(
    val fontSize: Float = 42f, // 默认字体大小（像素）
    val textSize: Float = fontSize, // 别名，保持兼容性
    val fontFamily: Typeface = Typeface.MONOSPACE,
    val backgroundColor: Int = Color.BLACK,
    val foregroundColor: Int = Color.WHITE, // 添加前景色
    val defaultForegroundColor: Int = foregroundColor,
    val cursorColor: Int = Color.GREEN,
    val cursorBlinkRate: Long = 500L, // 光标闪烁频率（毫秒）
    val lineSpacing: Float = 0.1f, // 行间距（相对于字符高度的比例）
    val charSpacing: Float = 0f, // 字符间距（像素）
    val targetFps: Int = 60, // 目标帧率
    val enableCharCache: Boolean = true, // 启用字符缓存
    val enableDirtyTracking: Boolean = true, // 启用脏区域追踪
    val enableFrameRateAdaptation: Boolean = true, // 启用帧率自适应
    val paddingLeft: Float = 16f,
    val paddingTop: Float = 16f,
    val paddingRight: Float = 16f,
    val paddingBottom: Float = 16f
) {
    fun withTextSize(newSize: Float): RenderConfig {
        return copy(fontSize = newSize, textSize = newSize)
    }
    
    fun getFrameDelay(): Long {
        return 1000L / targetFps
    }
}

