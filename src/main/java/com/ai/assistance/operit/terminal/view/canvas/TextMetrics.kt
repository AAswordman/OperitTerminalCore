package com.ai.assistance.operit.terminal.view.canvas

import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache

/**
 * 文本测量工具
 * 缓存字符宽高以提高性能
 */
class TextMetrics(private val paint: Paint) {
    private val charWidthCache = LruCache<Char, Float>(256)
    
    var charWidth: Float = 0f
        private set
    var charHeight: Float = 0f
        private set
    var charBaseline: Float = 0f
        private set
    
    /**
     * 更新字体大小并重新计算指标
     */
    fun updateFontSize(fontSize: Float) {
        paint.textSize = fontSize
        paint.typeface = Typeface.MONOSPACE
        
        // 测量标准字符 'M' 来确定单元格大小
        charWidth = paint.measureText("M")
        
        val fontMetrics = paint.fontMetrics
        charHeight = fontMetrics.descent - fontMetrics.ascent
        charBaseline = -fontMetrics.ascent
        
        // 清除缓存
        charWidthCache.evictAll()
    }
    
    /**
     * 获取字符宽度（带缓存）
     */
    fun getCharWidth(char: Char): Float {
        return charWidthCache.get(char) ?: run {
            val width = paint.measureText(char.toString())
            charWidthCache.put(char, width)
            width
        }
    }
    
    /**
     * 应用文本样式
     */
    fun applyStyle(isBold: Boolean, isItalic: Boolean) {
        val style = when {
            isBold && isItalic -> Typeface.BOLD_ITALIC
            isBold -> Typeface.BOLD
            isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        paint.typeface = Typeface.create(Typeface.MONOSPACE, style)
    }
    
    /**
     * 重置样式为默认
     */
    fun resetStyle() {
        paint.typeface = Typeface.MONOSPACE
    }
}

