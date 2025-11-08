package com.ai.assistance.operit.terminal.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import com.ai.assistance.operit.terminal.view.canvas.RenderConfig
import java.io.File

/**
 * 终端字体配置管理器
 * 管理终端字体的设置，包括字体大小、字体路径、字体名称等
 */
class TerminalFontConfigManager private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "terminal_font_prefs",
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_FONT_PATH = "font_path"
        private const val KEY_FONT_NAME = "font_name"
        
        private const val DEFAULT_FONT_SIZE = 42f
        
        @Volatile
        private var INSTANCE: TerminalFontConfigManager? = null
        
        fun getInstance(context: Context): TerminalFontConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TerminalFontConfigManager(context.applicationContext).also { 
                    INSTANCE = it 
                }
            }
        }
    }

    /**
     * 加载完整的渲染配置
     */
    fun loadRenderConfig(): RenderConfig {
        return RenderConfig(
            fontSize = getFontSize(),
            typeface = loadTypeface()
            // nerdFontPath 和其他参数可以使用 RenderConfig 的默认值
        )
    }

    /**
     * 根据保存的路径或名称加载字体
     */
    private fun loadTypeface(): Typeface {
        val fontPath = getFontPath()
        val fontName = getFontName()

        return try {
            fontPath?.let { path ->
                val file = File(path)
                if (file.exists() && file.isFile) {
                    return Typeface.createFromFile(file)
                }
            }

            fontName?.let { name ->
                return when (name.lowercase()) {
                    "monospace", "mono" -> Typeface.MONOSPACE
                    "serif" -> Typeface.SERIF
                    "sans-serif", "sans" -> Typeface.SANS_SERIF
                    else -> Typeface.create(name, Typeface.NORMAL)
                }
            }

            // 默认回退
            Typeface.MONOSPACE
        } catch (e: Exception) {
            // 加载失败回退
            Typeface.MONOSPACE
        }
    }

    /**
     * 获取字体大小
     */
    fun getFontSize(): Float {
        return prefs.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
    }
    
    /**
     * 设置字体大小
     */
    fun setFontSize(size: Float) {
        prefs.edit().putFloat(KEY_FONT_SIZE, size).apply()
    }
    
    /**
     * 获取字体文件路径
     */
    fun getFontPath(): String? {
        val path = prefs.getString(KEY_FONT_PATH, null)
        return if (path.isNullOrBlank()) null else path
    }
    
    /**
     * 设置字体文件路径
     */
    fun setFontPath(path: String?) {
        prefs.edit().putString(KEY_FONT_PATH, path).apply()
    }
    
    /**
     * 获取系统字体名称
     */
    fun getFontName(): String? {
        val name = prefs.getString(KEY_FONT_NAME, null)
        return if (name.isNullOrBlank()) null else name
    }
    
    /**
     * 设置系统字体名称（如 "monospace", "serif", "sans-serif"）
     */
    fun setFontName(name: String?) {
        prefs.edit().putString(KEY_FONT_NAME, name).apply()
    }
    
    /**
     * 清除所有字体设置，恢复默认
     */
    fun resetToDefault() {
        prefs.edit()
            .remove(KEY_FONT_SIZE)
            .remove(KEY_FONT_PATH)
            .remove(KEY_FONT_NAME)
            .apply()
    }
}

