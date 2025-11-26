package com.ai.assistance.operit.terminal.view.canvas

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.content.ClipData
import android.content.ClipboardManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator
import com.ai.assistance.operit.terminal.view.domain.ansi.TerminalChar
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.abs
import java.io.File
import com.ai.assistance.operit.terminal.R

/**
 * 基于Canvas的高性能终端视图
 * 使用SurfaceView + 独立渲染线程实现
 */
class CanvasTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {
    
    // 渲染配置
    private var config = RenderConfig()
    
    // 暂停控制
    private val pauseLock = Object()
    @Volatile
    private var isPaused = false
    
    // Paint对象（复用以提高性能）
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
        textSize = config.fontSize
    }
    
    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    
    private val cursorPaint = Paint().apply {
        color = Color.GREEN
        alpha = 180
        style = Paint.Style.FILL
    }
    
    private val selectionPaint = Paint().apply {
        color = Color.argb(100, 100, 149, 237) // 半透明蓝色
        style = Paint.Style.FILL
    }
    
    // 文本测量工具
    private val textMetrics: TextMetrics
    
    // 终端模拟器
    private var emulator: AnsiTerminalEmulator? = null
    private var emulatorChangeListener: (() -> Unit)? = null
    private var emulatorNewOutputListener: (() -> Unit)? = null
    
    // 是否自动滚动到底部（当新内容到达时）
    private var autoScrollToBottom = true
    // 用户是否正在手动滚动
    private var isUserScrolling = false
    // 是否需要滚动到底部（在渲染时执行）
    private var needScrollToBottom = false
    
    // PTY 引用（用于窗口大小同步）
    private var pty: com.ai.assistance.operit.terminal.Pty? = null
    
    // 渲染线程
    private var renderThread: RenderThread? = null
    private val renderLock = ReentrantLock()
    private val renderCondition = renderLock.newCondition()
    private var isDirty = true // 是否需要重绘
    
    // 手势处理
    private lateinit var gestureHandler: GestureHandler
    private val selectionManager = TextSelectionManager()
    
    // 缩放因子
    private var scaleFactor = 1f
        set(value) {
            field = value.coerceIn(0.5f, 3f)
            updateFontSize()
        }
    
    // 滚动偏移
    private var scrollOffsetY = 0f
    
    // 输入回调
    private var inputCallback: ((String) -> Unit)? = null
    
    // 会话ID和滚动位置回调
    private var sessionId: String? = null
    private var onScrollOffsetChanged: ((String, Float) -> Unit)? = null
    private var getScrollOffset: ((String) -> Float)? = null
    
    // 缓存终端尺寸，避免重复调用
    private var cachedRows = 0
    private var cachedCols = 0
    
    // 临时字符缓冲区，用于避免 drawChar 中的 String 分配
    private val tempCharBuffer = CharArray(1)
    
    // 文本选择ActionMode
    private var actionMode: ActionMode? = null
    
    // 全屏模式标记
    private var isFullscreenMode = true
    
    // 方向键长按处理
    private val handler = Handler(Looper.getMainLooper())
    private var currentArrowKey: Int? = null
    private var arrowKeyRepeatRunnable: Runnable? = null
    private var isArrowKeyPressed = false
    private val longPressDelay = 500L // 长按延迟时间（毫秒）
    private val repeatInterval = 200L // 重复发送间隔（毫秒）
    
    init {
        holder.addCallback(this)
        setWillNotDraw(false)
        
        // 使视图可以获得焦点以接收输入法输入
        isFocusable = true
        isFocusableInTouchMode = true
        
        // 初始化文本指标
        textMetrics = TextMetrics(textPaint, config)
        
        // 加载并应用字体
        loadAndApplyFont()
        
        // 初始化手势处理器
        initGestureHandler()
    }
    
    /**
     * 加载并应用字体
     */
    private fun loadAndApplyFont() {
        // 主字体已由 config.typeface 提供，直接应用
        textMetrics.updateFromRenderConfig(config)

        // 加载 Nerd Font (如果路径在 config 中未指定，则使用默认资源)
        val nerdFontResId = R.font.jetbrains_mono_nerd_font_regular
        val nerdTypeface = try {
            // 优先从 config 的 nerdFontPath 加载
            config.nerdFontPath?.let { path ->
                val file = File(path)
                if (file.exists() && file.isFile) {
                    Typeface.createFromFile(file)
                } else {
                    // 如果路径无效，尝试加载默认资源
                    resources.getFont(nerdFontResId)
                }
            } ?: resources.getFont(nerdFontResId) // 如果路径为空，直接加载默认资源
        } catch (e: Exception) {
            // 加载失败
            null
        }
        textMetrics.setNerdTypeface(nerdTypeface)
    }
    
    private fun initGestureHandler() {
        gestureHandler = GestureHandler(
            context = context,
            onScale = { scale ->
                scaleFactor *= scale
                requestRender()
            },
            onScroll = { _, distanceY ->
                if (!selectionManager.hasSelection()) {
                    scrollOffsetY += distanceY
                    scrollOffsetY = scrollOffsetY.coerceAtLeast(0f)
                    
                    // 保存滚动位置
                    sessionId?.let { id ->
                        onScrollOffsetChanged?.invoke(id, scrollOffsetY)
                    }
                    
                    // 用户手动滚动，检测是否在底部
                    if (scrollOffsetY > 0f) {
                        isUserScrolling = true
                    } else {
                        // 滚动到底部，恢复自动滚动
                        isUserScrolling = false
                    }
                    
                    requestRender()
                }
            },
            onDoubleTap = { x, y ->
                // 双击选择单词（可选实现）
                // 也可以用来切换输入法（仅全屏模式）
                if (isFullscreenMode) {
                    if (!hasFocus()) {
                        requestFocus()
                    }
                    showSoftKeyboard()
                }
            },
            onLongPress = { x, y ->
                startTextSelection(x, y)
            }
        )
    }
    
    /**
     * 设置终端模拟器
     */
    fun setEmulator(emulator: AnsiTerminalEmulator) {
        // 保存当前模拟器的滚动位置
        sessionId?.let { id ->
            onScrollOffsetChanged?.invoke(id, scrollOffsetY)
        }

        // 移除旧的监听器
        this.emulator?.let { oldEmulator ->
            emulatorChangeListener?.let { listener ->
                oldEmulator.removeChangeListener(listener)
            }
            emulatorNewOutputListener?.let { listener ->
                oldEmulator.removeNewOutputListener(listener)
            }
        }
        
        this.emulator = emulator
        // 恢复新模拟器的滚动位置
        sessionId?.let { id ->
            getScrollOffset?.invoke(id)?.let { offset ->
                scrollOffsetY = offset
            }
        }
        
        // 添加新的监听器
        emulatorChangeListener = {
            isDirty = true
            requestRender()
        }
        emulator.addChangeListener(emulatorChangeListener!!)
        
        // 添加新输出监听器（用于自动滚动到底部）
        emulatorNewOutputListener = {
            if (autoScrollToBottom) {
                scrollToBottom()
            }
        }
        emulator.addNewOutputListener {
            post {
                emulatorNewOutputListener?.invoke()
            }
        }
        
        // 如果 Surface 已经创建，立即同步终端大小
        if (width > 0 && height > 0) {
            updateTerminalSize(width, height)
        }
        
        requestRender()
    }
    
    /**
     * 设置滚动偏移（用于恢复会话滚动位置）
     */
    fun setScrollOffset(offset: Float) {
        scrollOffsetY = offset.coerceAtLeast(0f)
        requestRender()
    }
    
    /**
     * 获取当前滚动偏移（用于保存会话滚动位置）
     */
    fun getScrollOffset(): Float = scrollOffsetY
    
    /**
     * 滚动到底部
     */
    fun scrollToBottom() {
        needScrollToBottom = true
        isUserScrolling = false // 恢复自动滚动
        isDirty = true
        requestRender()
    }
    
    /**
     * 设置 PTY（用于窗口大小同步）
     */
    fun setPty(pty: com.ai.assistance.operit.terminal.Pty?) {
        this.pty = pty
        
        // 如果 Surface 已经创建且有 emulator，立即同步终端大小
        if (width > 0 && height > 0 && emulator != null) {
            updateTerminalSize(width, height)
        }
    }
    
    /**
     * 设置全屏模式
     */
    fun setFullscreenMode(isFullscreen: Boolean) {
        isFullscreenMode = isFullscreen
        // 非全屏模式下禁用焦点和输入法
        isFocusable = isFullscreen
        isFocusableInTouchMode = isFullscreen
    }
    
    /**
     * 设置输入回调
     */
    fun setInputCallback(callback: (String) -> Unit) {
        this.inputCallback = callback
    }
    
    /**
     * 设置会话ID和滚动位置回调
     */
    fun setSessionScrollCallbacks(
        sessionId: String?,
        onScrollOffsetChanged: ((String, Float) -> Unit)?,
        getScrollOffset: ((String) -> Float)?
    ) {
        this.sessionId = sessionId
        this.onScrollOffsetChanged = onScrollOffsetChanged
        this.getScrollOffset = getScrollOffset
        
        // 如果设置了sessionId，立即恢复滚动位置
        sessionId?.let { id ->
            getScrollOffset?.invoke(id)?.let { offset ->
                scrollOffsetY = offset
                requestRender()
            }
        }
    }
    
    /**
     * 设置缩放回调
     */
    fun setScaleCallback(callback: (Float) -> Unit) {
        // 当缩放因子变化时调用
        gestureHandler = GestureHandler(
            context = context,
            onScale = { scale ->
                scaleFactor *= scale
                callback(scaleFactor)
                // 更新字体指标和终端大小
                updateTerminalSize(holder.surfaceFrame.width(), holder.surfaceFrame.height())
                requestRender()
            },
            onScroll = { _, distanceY ->
                if (!selectionManager.hasSelection()) {
                    scrollOffsetY += distanceY
                    scrollOffsetY = scrollOffsetY.coerceAtLeast(0f)
                    
                    // 保存滚动位置
                    sessionId?.let { id ->
                        onScrollOffsetChanged?.invoke(id, scrollOffsetY)
                    }
                    
                    // 用户手动滚动，检测是否在底部
                    if (scrollOffsetY > 0f) {
                        isUserScrolling = true
                    } else {
                        // 滚动到底部，恢复自动滚动
                        isUserScrolling = false
                    }
                    
                    requestRender()
                }
            },
            onDoubleTap = { x, y ->
                // 双击选择单词（可选实现）
            },
            onLongPress = { x, y ->
                startTextSelection(x, y)
            }
        )
    }
    
    /**
     * 设置性能监控回调
     */
    fun setPerformanceCallback(callback: (fps: Float, frameTime: Long) -> Unit) {
        // 性能监控逻辑可以在RenderThread中实现
        // 这里暂时留空，可以后续扩展
    }
    
    /**
     * 设置渲染配置
     */
    fun setConfig(newConfig: RenderConfig) {
        val oldTypeface = config.typeface
        val oldNerdFontPath = config.nerdFontPath
        val oldFontSize = config.fontSize

        // 更新配置
        config = newConfig

        // 如果字体或字体大小改变了，重新加载并更新指标
        if (oldTypeface != newConfig.typeface ||
            oldNerdFontPath != newConfig.nerdFontPath ||
            oldFontSize != newConfig.fontSize
        ) {
            loadAndApplyFont()
            updateTerminalSize(holder.surfaceFrame.width(), holder.surfaceFrame.height())
            requestRender()
        } else {
            // 其他配置改变，也需要重新渲染
            requestRender()
        }
    }
    
    /**
     * 更新字体大小
     */
    private fun updateFontSize() {
        // 字体大小的更新现在由 textMetrics.updateFromRenderConfig 处理
        // 这里可以保留用于缩放手势等场景
        textMetrics.updateFromRenderConfig(config.copy(fontSize = config.fontSize * scaleFactor))
        updateTerminalSize(holder.surfaceFrame.width(), holder.surfaceFrame.height())
        requestRender()
    }
    
    /**
     * 请求渲染
     */
    private fun requestRender() {
        isDirty = true
    }
    
    // === SurfaceHolder.Callback 实现 ===
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d("CanvasTerminalView", "onSizeChanged: ${w}x${h}")
        if (w <= 0 || h <= 0) {
            synchronized(pauseLock) { isPaused = true }
        } else {
            synchronized(pauseLock) { 
                isPaused = false
                pauseLock.notifyAll()
            }
        }
    }
    
    override fun surfaceCreated(holder: SurfaceHolder) {
        startRenderThread()
    }
    
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d("CanvasTerminalView", "surfaceChanged: ${width}x${height}")
        // 如果尺寸无效，暂停渲染
        if (width <= 0 || height <= 0) {
            Log.d("CanvasTerminalView", "Invalid size, pausing render thread")
            synchronized(pauseLock) {
                isPaused = true
            }
            return
        }
        
        // 恢复渲染
        synchronized(pauseLock) {
            if (isPaused) {
                Log.d("CanvasTerminalView", "Valid size, resuming render thread")
                isPaused = false
                pauseLock.notifyAll()
            }
        }

        // 更新终端窗口大小
        updateTerminalSize(width, height)
        
        requestRender()
    }
    
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRenderThread()
        // 清理方向键长按回调，避免内存泄漏
        handleArrowKeyUp()
    }
    
    // === 渲染线程 ===
    
    private fun startRenderThread() {
        stopRenderThread()
        renderThread = RenderThread(holder).apply {
            start()
        }
    }
    
    /**
     * 停止渲染线程
     * 在视图被销毁或移除时调用，避免SurfaceView锁竞争导致ANR
     */
    fun stopRenderThread() {
        renderThread?.let { thread ->
            thread.stopRendering()
            thread.interrupt()
            try {
                thread.join(1000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
        renderThread = null
    }
    
    private inner class RenderThread(private val surfaceHolder: SurfaceHolder) : Thread("TerminalRenderThread") {
        @Volatile
        private var running = false
        
        override fun start() {
            running = true
            super.start()
        }
        
        fun stopRendering() {
            running = false
        }
        
        override fun run() {
            var lastFrameTime = System.currentTimeMillis()
            val targetFrameTime = 1000L / config.targetFps
            
            while (running) {
                // 如果视图尺寸无效，直接休眠（不依赖 isPaused 状态的兜底逻辑）
                if (width <= 0 || height <= 0) {
                    try {
                        sleep(200)
                    } catch (e: InterruptedException) {
                        // Ignore
                    }
                    continue
                }

                // 检查是否暂停
                if (isPaused) {
                    Log.d("CanvasTerminalView", "RenderThread: Loop paused")
                    synchronized(pauseLock) {
                        while (isPaused && running) {
                            try {
                                Log.d("CanvasTerminalView", "RenderThread: Waiting...")
                                pauseLock.wait()
                            } catch (e: InterruptedException) {
                                // Ignore
                            }
                        }
                    }
                    Log.d("CanvasTerminalView", "RenderThread: Waking up")
                    // 唤醒后重置时间，避免 huge delta
                    lastFrameTime = System.currentTimeMillis()
                    continue
                }

                val currentTime = System.currentTimeMillis()
                val deltaTime = currentTime - lastFrameTime
                
                // 如果没有变化且距离上次渲染时间较短，则跳过渲染（省电）
                if (!isDirty && deltaTime < targetFrameTime) {
                    try {
                        sleep(5)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }
                
                var canvas: Canvas? = null
                try {
                    canvas = surfaceHolder.lockCanvas()
                    canvas?.let {
                        synchronized(surfaceHolder) {
                            drawTerminal(it)
                            isDirty = false
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    canvas?.let {
                        try {
                            surfaceHolder.unlockCanvasAndPost(it)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                // 如果未能获取Canvas（例如Surface无效），暂停一会避免忙轮询
                if (canvas == null) {
                    Log.w("CanvasTerminalView", "RenderThread: Failed to lock canvas, sleeping 10ms")
                    try {
                        sleep(10)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }
                
                lastFrameTime = currentTime
                
                // 控制帧率
                val frameTime = System.currentTimeMillis() - currentTime
                val sleepTime = targetFrameTime - frameTime
                if (sleepTime > 0) {
                    try {
                        sleep(sleepTime)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }
    }
    
    // === 核心渲染方法 ===
    
    private fun drawTerminal(canvas: Canvas) {
        val em = emulator ?: return
        
        // 使用完整内容（历史 + 屏幕缓冲）
        val fullContent = em.getFullContent()
        val historySize = em.getHistorySize()
        
        val charWidth = textMetrics.charWidth
        val charHeight = textMetrics.charHeight
        val baseline = textMetrics.charBaseline
        
        // 限制最大滚动偏移（不能超过内容高度）
        val maxScrollOffset = max(0f, fullContent.size * charHeight - canvas.height)
        
        // 如果需要滚动到底部，在渲染时执行（使用正确的 canvas.height）
        if (needScrollToBottom) {
            scrollOffsetY = maxScrollOffset
            needScrollToBottom = false
        }
        
        scrollOffsetY = scrollOffsetY.coerceIn(0f, maxScrollOffset)
        
        // 计算可见区域
        val visibleRows = (canvas.height / charHeight).toInt() + 1
        val startRow = (scrollOffsetY / charHeight).toInt()
        val endRow = min(startRow + visibleRows, fullContent.size)
        
        // 绘制每一行（包括背景）
        for (row in startRow until endRow) {
            if (row >= fullContent.size) break
            
            val line = fullContent[row]
            val y = (row - startRow) * charHeight - (scrollOffsetY % charHeight)
            
            // 先绘制整行的默认背景
            bgPaint.color = config.backgroundColor
            canvas.drawRect(0f, y, canvas.width.toFloat(), y + charHeight, bgPaint)
            
            // 绘制该行的所有字符
            drawLine(canvas, line, row, 0f, y, charWidth, charHeight, baseline)
        }
        
        // 绘制底部剩余区域的背景（如果有）
        val lastY = (endRow - startRow) * charHeight - (scrollOffsetY % charHeight)
        if (lastY < canvas.height) {
            bgPaint.color = config.backgroundColor
            canvas.drawRect(0f, lastY, canvas.width.toFloat(), canvas.height.toFloat(), bgPaint)
        }
        
        // 绘制选择区域
        if (selectionManager.hasSelection()) {
            drawSelection(canvas, charWidth, charHeight)
        }
        
        // 绘制光标（光标只在可见屏幕部分显示，需要考虑历史缓冲区偏移）
        if (em.isCursorVisible()) {
            val cursorRow = historySize + em.getCursorY()
            val cursorCol = em.getCursorX()
            
            // 只有当光标在可见区域内时才绘制
            if (cursorRow >= startRow && cursorRow < endRow) {
                val cursorY = (cursorRow - startRow) * charHeight - (scrollOffsetY % charHeight)
                
                // 计算光标的 x 坐标，考虑宽字符
                val line = fullContent.getOrNull(cursorRow) ?: arrayOf()
                var cursorX = 0f
                
                // 遍历到光标列，累加每个字符的宽度
                for (col in 0 until cursorCol.coerceAtMost(line.size)) {
                    val cellWidth = textMetrics.getCellWidth(line[col].char)
                    cursorX += charWidth * cellWidth
                }
                
                // 获取光标所在字符的宽度
                val cursorCharWidth = if (cursorCol < line.size) {
                    val cellWidth = textMetrics.getCellWidth(line[cursorCol].char)
                    charWidth * cellWidth
                } else {
                    charWidth
                }
                
                cursorPaint.color = Color.GREEN
                cursorPaint.alpha = 180
                canvas.drawRect(
                    cursorX,
                    cursorY,
                    cursorX + cursorCharWidth,
                    cursorY + charHeight,
                    cursorPaint
                )
            }
        }
    }
    
    private fun drawLine(
        canvas: Canvas,
        line: Array<TerminalChar>,
        row: Int,
        startX: Float,
        y: Float,
        charWidth: Float,
        charHeight: Float,
        baseline: Float
    ) {
        var x = startX
        var currentBgColor: Int? = null
        var bgStartX = x
        
        for (col in line.indices) {
            val termChar = line[col]
            
            // 获取字符的单元格宽度（宽字符为2，普通字符为1）
            val cellWidth = textMetrics.getCellWidth(termChar.char)
            val actualCharWidth = charWidth * cellWidth
            
            // 批量绘制相同背景色
            if (termChar.bgColor != config.backgroundColor) {
                if (currentBgColor != termChar.bgColor) {
                    // 绘制之前的背景
                    currentBgColor?.let {
                        bgPaint.color = it
                        canvas.drawRect(bgStartX, y, x, y + charHeight, bgPaint)
                    }
                    currentBgColor = termChar.bgColor
                    bgStartX = x
                }
            } else {
                // 绘制累积的背景
                currentBgColor?.let {
                    bgPaint.color = it
                    canvas.drawRect(bgStartX, y, x, y + charHeight, bgPaint)
                }
                currentBgColor = null
                bgStartX = x + actualCharWidth
            }
            
            // 绘制字符（宽字符居中显示）
            if (termChar.char != ' ' && !termChar.isHidden) {
                val charX = if (cellWidth == 2) {
                    // 宽字符居中显示
                    x + charWidth / 2
                } else {
                    x
                }
                drawChar(canvas, termChar, charX, y + baseline, actualCharWidth)
            }
            
            x += actualCharWidth
        }
        
        // 绘制行尾的背景
        currentBgColor?.let {
            bgPaint.color = it
            canvas.drawRect(bgStartX, y, x, y + charHeight, bgPaint)
        }
    }
    
    private fun drawChar(canvas: Canvas, termChar: TerminalChar, x: Float, y: Float, charWidth: Float = textMetrics.charWidth) {
        // 检查字符是否可被渲染，如果不行就用替换字符
        val charToDraw = if (textMetrics.selectTypefaceForChar(termChar.char)) {
            termChar.char
        } else {
            '\uFFFD' // Unicode 替换字符
        }
        
        // 应用粗体/斜体等样式（这会设置一个基础字体）
        textMetrics.applyStyle(termChar.isBold, termChar.isItalic)
        
        // 再次选择正确的字体（主字体或Nerd字体），因为applyStyle可能会覆盖它
        textMetrics.selectTypefaceForChar(charToDraw)
        
        // 设置颜色
        var fgColor = termChar.fgColor
        if (termChar.isDim) {
            // 使颜色变暗
            fgColor = Color.argb(
                180,
                Color.red(fgColor),
                Color.green(fgColor),
                Color.blue(fgColor)
            )
        }
        
        if (termChar.isInverse) {
            // 反转前景和背景色（这里只反转文字颜色）
            fgColor = termChar.bgColor
        }
        
        textPaint.color = fgColor
        
        // 绘制下划线
        if (termChar.isUnderline) {
            val underlineY = y + 2
            canvas.drawLine(x, underlineY, x + charWidth, underlineY, textPaint)
        }
        
        // 绘制删除线
        if (termChar.isStrikethrough) {
            val strikeY = y - textMetrics.charHeight / 2
            canvas.drawLine(x, strikeY, x + charWidth, strikeY, textPaint)
        }
        
        // 绘制字符
        tempCharBuffer[0] = charToDraw
        canvas.drawText(tempCharBuffer, 0, 1, x, y, textPaint)
        
        // 重置样式
        textMetrics.resetStyle()
    }
    
    private fun drawSelection(canvas: Canvas, charWidth: Float, charHeight: Float) {
        val selection = selectionManager.selection?.normalize() ?: return
        val em = emulator ?: return
        val fullContent = em.getFullContent()
        val historySize = em.getHistorySize()
        
        val startRow = (scrollOffsetY / charHeight).toInt()
        
        for (row in selection.startRow..selection.endRow) {
            val y = (row - startRow) * charHeight - (scrollOffsetY % charHeight)
            
            val startCol = if (row == selection.startRow) selection.startCol else 0
            val endCol = if (row == selection.endRow) {
                selection.endCol
            } else {
                fullContent.getOrNull(row)?.size ?: 0
            }
            
            // 计算选择区域的 x 坐标，考虑宽字符
            val line = fullContent.getOrNull(row) ?: continue
            var x1 = 0f
            var x2 = 0f
            
            // 计算起始 x 坐标
            for (col in 0 until startCol.coerceAtMost(line.size)) {
                val cellWidth = textMetrics.getCellWidth(line[col].char)
                x1 += charWidth * cellWidth
            }
            
            // 计算结束 x 坐标
            for (col in 0..endCol.coerceAtMost(line.size - 1)) {
                val cellWidth = textMetrics.getCellWidth(line[col].char)
                x2 += charWidth * cellWidth
            }
            
            canvas.drawRect(x1, y, x2, y + charHeight, selectionPaint)
        }
    }
    
    // === 触摸事件处理 ===
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureHandler.onTouchEvent(event)
        
        // 单击时请求焦点并显示输入法（全屏模式下）
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (!hasFocus()) {
                requestFocus()
            }
            // 全屏模式下，单击即显示输入法
            if (isFullscreenMode && !selectionManager.hasSelection()) {
                postDelayed({
                    showSoftKeyboard()
                }, 100) // 延迟100ms确保焦点已获取
            }
        }
        
        // 处理选择移动
        if (selectionManager.hasSelection() && event.action == MotionEvent.ACTION_MOVE) {
            val (row, col) = screenToTerminalCoords(event.x, event.y)
            selectionManager.updateSelection(row, col)
            requestRender()
        }
        
        if (event.action == MotionEvent.ACTION_UP && selectionManager.hasSelection()) {
            showTextSelectionMenu()
        }
        
        return handled || super.onTouchEvent(event)
    }
    
    /**
     * 屏幕坐标转换为终端坐标
     * 考虑宽字符的影响
     */
    private fun screenToTerminalCoords(x: Float, y: Float): Pair<Int, Int> {
        val em = emulator ?: return Pair(0, 0)
        val fullContent = em.getFullContent()
        val row = ((y + scrollOffsetY) / textMetrics.charHeight).toInt().coerceIn(0, fullContent.size - 1)
        
        // 获取该行的内容
        val line = fullContent.getOrNull(row) ?: return Pair(row, 0)
        
        // 遍历该行的字符，找到对应的列
        var currentX = 0f
        var col = 0
        val charWidth = textMetrics.charWidth
        
        for (i in line.indices) {
            val cellWidth = textMetrics.getCellWidth(line[i].char)
            val actualCharWidth = charWidth * cellWidth
            
            if (x < currentX + actualCharWidth / 2) {
                // 点击位置在这个字符的前半部分
                col = i
                break
            } else if (x < currentX + actualCharWidth) {
                // 点击位置在这个字符的后半部分（宽字符）
                col = i
                break
            }
            
            currentX += actualCharWidth
            col = i + 1
        }
        
        return Pair(row, col.coerceIn(0, line.size - 1))
    }
    
    /**
     * 开始文本选择
     */
    private fun startTextSelection(x: Float, y: Float) {
        val (row, col) = screenToTerminalCoords(x, y)
        selectionManager.startSelection(row, col)
        requestRender()
    }
    
    /**
     * 显示文本选择菜单
     */
    private fun showTextSelectionMenu() {
        if (actionMode != null) return
        
        actionMode = startActionMode(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, 1, 0, "复制")
                return true
            }
            
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                return false
            }
            
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    1 -> {
                        copySelectedText()
                        mode.finish()
                        return true
                    }
                }
                return false
            }
            
            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                selectionManager.clearSelection()
                requestRender()
            }
        })
    }
    
    /**
     * 复制选中的文本
     */
    private fun copySelectedText() {
        val selection = selectionManager.selection?.normalize() ?: return
        val buffer = emulator?.getScreenContent() ?: return
        
        val text = buildString {
            for (row in selection.startRow..selection.endRow) {
                if (row >= buffer.size) break
                
                val line = buffer[row]
                val startCol = if (row == selection.startRow) selection.startCol else 0
                val endCol = if (row == selection.endRow) selection.endCol else line.size - 1
                
                for (col in startCol..endCol) {
                    if (col < line.size) {
                        append(line[col].char)
                    }
                }
                
                if (row < selection.endRow) {
                    append('\n')
                }
            }
        }
        
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }
    
    // === 输入法支持 ===
    
    /**
     * 显示软键盘
     */
    private fun showSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
    
    /**
     * 隐藏软键盘
     */
    fun hideSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }
    
    /**
     * 创建输入连接
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection {
        outAttrs?.apply {
            inputType = EditorInfo.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        }
        
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.let {
                    inputCallback?.invoke(it.toString())
                }
                return true
            }
            
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    // 发送退格键
                    inputCallback?.invoke("\u007F") // DEL character
                }
                return true
            }
            
            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                event?.let {
                    when (it.action) {
                        KeyEvent.ACTION_DOWN -> {
                            when (it.keyCode) {
                                KeyEvent.KEYCODE_DEL -> {
                                    inputCallback?.invoke("\u007F")
                                    return true
                                }
                                KeyEvent.KEYCODE_ENTER -> {
                                    inputCallback?.invoke("\n")
                                    return true
                                }
                                KeyEvent.KEYCODE_DPAD_UP,
                                KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT,
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    // 全屏模式下处理方向键长按
                                    if (isFullscreenMode) {
                                        handleArrowKeyDown(it.keyCode)
                                        return true
                                    }
                                }
                            }
                        }
                        KeyEvent.ACTION_UP -> {
                            when (it.keyCode) {
                                KeyEvent.KEYCODE_DPAD_UP,
                                KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT,
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    // 全屏模式下处理方向键释放
                                    if (isFullscreenMode) {
                                        handleArrowKeyUp()
                                        return true
                                    }
                                }
                            }
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }
    
    /**
     * 处理方向键按下
     */
    private fun handleArrowKeyDown(keyCode: Int) {
        // 如果已经有其他方向键被按下，先释放
        if (isArrowKeyPressed && currentArrowKey != keyCode) {
            handleArrowKeyUp()
        }
        
        currentArrowKey = keyCode
        isArrowKeyPressed = true
        
        // 立即发送一次方向键
        sendArrowKey(keyCode)
        
        // 延迟后开始重复发送
        arrowKeyRepeatRunnable = object : Runnable {
            override fun run() {
                if (isArrowKeyPressed && currentArrowKey == keyCode) {
                    sendArrowKey(keyCode)
                    handler.postDelayed(this, repeatInterval)
                }
            }
        }
        handler.postDelayed(arrowKeyRepeatRunnable!!, longPressDelay)
    }
    
    /**
     * 处理方向键释放
     */
    private fun handleArrowKeyUp() {
        isArrowKeyPressed = false
        currentArrowKey = null
        arrowKeyRepeatRunnable?.let {
            handler.removeCallbacks(it)
            arrowKeyRepeatRunnable = null
        }
    }
    
    /**
     * 发送方向键的 ANSI 转义序列
     */
    private fun sendArrowKey(keyCode: Int) {
        val escapeSequence = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"    // ESC [ A
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"  // ESC [ B
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C" // ESC [ C
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"   // ESC [ D
            else -> return
        }
        inputCallback?.invoke(escapeSequence)
    }
    
    /**
     * 检查是否可以显示输入法
     */
    override fun onCheckIsTextEditor(): Boolean {
        return isFullscreenMode
    }
    
    /**
     * 更新终端窗口大小
     */
    private fun updateTerminalSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        // 确保字体指标已更新（基于当前缩放因子）
        textMetrics.updateFromRenderConfig(config.copy(fontSize = config.fontSize * scaleFactor))

        // 计算终端尺寸（行和列）
        val cols = (width / textMetrics.charWidth).toInt().coerceAtLeast(1)
        val rows = (height / textMetrics.charHeight).toInt().coerceAtLeast(1)
        
        // 只有当尺寸真正发生变化时才更新
        if (rows == cachedRows && cols == cachedCols) {
            return
        }
        
        cachedRows = rows
        cachedCols = cols
        
        // 更新模拟器尺寸
        emulator?.resize(cols, rows)
        
        // 同步 PTY 窗口尺寸
        pty?.setWindowSize(rows, cols)
    }
}

