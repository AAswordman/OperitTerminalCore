package com.ai.assistance.operit.terminal.view.canvas

import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache

/**
 * 文本测量工具
 * 缓存字符宽高以提高性能
 */
class TextMetrics(
    private val paint: Paint,
    config: RenderConfig
) {
    private val charWidthCache = LruCache<Char, Float>(256)
    private var currentTypeface: Typeface = config.typeface
    private var nerdTypeface: Typeface? = null // Nerd Font 字体
    
    var charWidth: Float = 0f
        private set
    var charHeight: Float = 0f
        private set
    var charBaseline: Float = 0f
        private set
    
    init {
        updateFromRenderConfig(config)
    }

    /**
     * 根据 RenderConfig 更新字体大小和类型，并重新计算指标
     */
    fun updateFromRenderConfig(config: RenderConfig) {
        paint.textSize = config.fontSize
        currentTypeface = config.typeface
        paint.typeface = currentTypeface

        // 测量标准字符 'M' 来确定单元格大小
        charWidth = paint.measureText("M")

        val fontMetrics = paint.fontMetrics
        charHeight = fontMetrics.descent - fontMetrics.ascent
        charBaseline = -fontMetrics.ascent

        // 清除缓存
        charWidthCache.evictAll()
    }
    
    /**
     * 设置 Nerd Font 字体
     */
    fun setNerdTypeface(typeface: Typeface?) {
        nerdTypeface = typeface
        // 清除缓存，因为字体改变会影响字符宽度
        charWidthCache.evictAll()
    }
    
    /**
     * 为给定的字符选择合适的字体（主字体或Nerd字体）并设置到Paint对象上。
     * @param char 要渲染的字符
     * @return 如果字符在任何一个字体中都不可渲染，则返回 false
     */
    fun selectTypefaceForChar(char: Char): Boolean {
        // 默认使用当前字体
        val defaultTypeface = currentTypeface
        paint.typeface = defaultTypeface
        
        // 检查当前字体是否支持
        if (paint.hasGlyph(char.toString())) {
            return true
        }
        
        // 如果当前字体不支持，且有Nerd字体，则尝试使用Nerd字体
        nerdTypeface?.let {
            paint.typeface = it
            if (paint.hasGlyph(char.toString())) {
                return true
            }
        }
        
        // 如果两个字体都不支持，恢复为默认字体并返回 false
        paint.typeface = defaultTypeface
        return false
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
     * 判断字符是否为宽字符（全角字符，如中文、日文、韩文等）
     * 宽字符在终端中占用2个单元格
     */
    fun isWideChar(char: Char): Boolean {
        val code = char.code
        return when {
            // CJK统一表意文字（中文、日文、韩文汉字）
            code in 0x4E00..0x9FFF -> true
            // CJK扩展A
            code in 0x3400..0x4DBF -> true
            // CJK扩展B
            code in 0x20000..0x2A6DF -> true
            // CJK扩展C
            code in 0x2A700..0x2B73F -> true
            // CJK扩展D
            code in 0x2B740..0x2B81F -> true
            // CJK扩展E
            code in 0x2B820..0x2CEAF -> true
            // 平假名和片假名（日文）
            code in 0x3040..0x309F -> true
            code in 0x30A0..0x30FF -> true
            // 韩文音节
            code in 0xAC00..0xD7AF -> true
            // 全角字符范围
            code in 0xFF00..0xFFEF -> true
            // 其他全角符号
            code in 0x3000..0x303F -> true
            else -> false
        }
    }
    
    /**
     * 获取字符的单元格宽度（1或2）
     * 宽字符返回2，普通字符返回1
     */
    fun getCellWidth(char: Char): Int {
        return if (isWideChar(char)) 2 else 1
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
        // 基于当前字体创建样式变体
        // 注意：这里我们暂时不处理 Nerd Font 的粗体/斜体，以简化逻辑
        // selectTypefaceForChar 会在绘制时选择正确的原始字体
        val baseTypeface = currentTypeface
        paint.typeface = Typeface.create(baseTypeface, style)
    }
    
    /**
     * 重置样式为默认
     */
    fun resetStyle() {
        paint.typeface = currentTypeface
    }
}

