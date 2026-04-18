package com.example.crtui

import android.app.NotificationManager
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.content.res.ResourcesCompat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val sessions = CopyOnWriteArrayList<TerminalSession>()
    private var activeTabIndex = 0

    private fun activeSession(): TerminalSession = sessions[activeTabIndex]

    // --- THEME ENGINE ---
    enum class TerminalTheme { GREEN, LUMON, AMBER }

    private var currentTheme = TerminalTheme.LUMON
    private var currentTextColor = Color.parseColor("#00E5FF")
    private var currentTextSize = 37f
    private var currentGlowIntensity = 0.6f
    private var isScanlinesEnabled = true

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = currentTextColor
        textSize = currentTextSize
        typeface = ResourcesCompat.getFont(context, R.font.glass_tty)
    }

    private val boldTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = currentTextColor
        textSize = currentTextSize
        typeface = ResourcesCompat.getFont(context, R.font.glass_tty)
        isFakeBoldText = true
    }

    private val promptTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = currentTextColor
        textSize = currentTextSize
        typeface = ResourcesCompat.getFont(context, R.font.glass_tty)
        isFakeBoldText = true
    }

    private val tabFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = currentTextColor
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val invertedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = currentTextColor
        style = Paint.Style.FILL
    }

    private val invertedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#05101D")
        textSize = currentTextSize
        typeface = ResourcesCompat.getFont(context, R.font.glass_tty)
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private enum class AppState { TERMINAL, SETTINGS }
    private var currentState = AppState.TERMINAL

    private enum class CommandMode { NORMAL, APPS, ALIASES }
    private var currentCommandMode = CommandMode.NORMAL

    private enum class OverlayState { NONE, FAVORITES, NEW_TAB_PROMPT, SSH_HOSTS, MANUAL, ADD_SSH_HOST }
    @Volatile private var currentOverlay = OverlayState.NONE

    // --- TUI FORM STATE ---
    private val sshFormLabels = listOf("Name: ", "User: ", "Host/IP: ", "Port: ", "Pass: ")
    private var sshFormValues = MutableList(5) { "" }
    private var sshFormActiveField = 0

    @Volatile private var tabHitboxes: Map<Int, RectF> = emptyMap()
    @Volatile private var settingsTabHitbox: RectF? = null
    @Volatile private var newTabHitbox: RectF? = null
    @Volatile private var commandHitboxes: Map<String, RectF> = emptyMap()
    @Volatile private var extraKeyHitboxes: Map<String, RectF> = emptyMap()
    @Volatile private var settingsHitboxes: Map<String, RectF> = emptyMap()
    @Volatile private var overlayHitboxes: Map<String, RectF> = emptyMap()

    data class AppInfo(val name: String, val packageName: String)
    private val installedApps = mutableListOf<AppInfo>()

    private val favoriteApps = CopyOnWriteArrayList<String>()
    private val aliases = ConcurrentHashMap<String, String>().apply {
        put("grind", "dnd")
        put("home", "cd ..")
    }

    private val savedSshHosts = CopyOnWriteArrayList<SshConfig>()

    private val manualLines = listOf(
        "<bold>INTERFACE & NAVIGATION</bold>",
        "• Tabs: Double-tap to close. Tap [+] for new session.",
        "• Overlays: Tap [Favs] for Favorites, [*] for Settings.",
        "• Keys: Use [Tab] to autocomplete Apps and Favs.",
        " ",
        "<bold>CORE COMMANDS</bold>",
        "• s [query]: System-wide web search.",
        "• fav -add/rm: Manage the Favorite Apps overlay.",
        "• alias x=y: Map custom execution macros.",
        "• dnd: Toggle Android Do-Not-Disturb mode.",
        "• wifi: Open native Network panel.",
        "• changelog: View system update history.",
        " ",
        "<bold>FILE SYSTEM & UTILS</bold>",
        "• ls, cd, pwd, mkdir, rm: Local file navigation.",
        "• note [text]: Append strings to local note buffer.",
        "• read: Display local note buffer.",
        "• ping [ip]: Test ICMP packet latency.",
        " ",
        "<bold>CHANGELOG v0.6.0</bold>",
        "• SSH Engine: JSch integration with interactive auth.",
        "• SSH UI: Add/Edit/Delete hosts, masked passwords.",
        "• Terminal: ANSI escape code scrubbing.",
        "• Visuals: True Gaussian bloom (aspect-ratio corrected).",
        "• UX: Auto-launch apps via standard prompt."
    )

    private var scrollOffset = 0f
    private var forceScrollToBottom = true

    private var lastTabTapTime = 0L
    private var lastTabTapIndex = -1

    private var crtSurfaceView: GLSurfaceView? = null
    private var shaderRenderer: CrtShaderRenderer? = null

    private val cursorPulseRunnable = object : Runnable {
        override fun run() {
            if (currentState == AppState.TERMINAL) requestUpdate()
            postDelayed(this, 32)
        }
    }

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val formattedLine = intent?.getStringExtra("formattedLine") ?: return
            activeSession().history.add(formattedLine)
            scrollToBottom()
            requestUpdate()
        }
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            performClick()
            if (currentOverlay != OverlayState.NONE) {
                handleOverlayTap(e.x, e.y)
            } else if (currentState == AppState.SETTINGS) {
                handleSettingsTap(e.x, e.y)
            } else {
                handleTap(e.x, e.y)
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentState == AppState.TERMINAL && currentOverlay == OverlayState.NONE) {
                for ((index, rect) in tabHitboxes) {
                    if (rect.contains(e.x, e.y)) {
                        val deadSession = sessions[index]
                        if (deadSession.type == SessionType.SSH) deadSession.disconnectSsh()

                        if (sessions.size > 1) {
                            sessions.removeAt(index)
                            activeTabIndex = min(activeTabIndex, sessions.size - 1)
                        } else {
                            sessions.clear()
                            sessions.add(TerminalSession("Local", SessionType.LOCAL))
                            activeTabIndex = 0
                        }
                        scrollToBottom()
                        requestUpdate()
                        return true
                    }
                }
            }
            return false
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (currentState == AppState.TERMINAL && currentOverlay == OverlayState.NONE) {
                val fontMetrics = textPaint.fontMetrics
                val textHeight = fontMetrics.descent - fontMetrics.ascent
                val maxWidth = width - paddingLeft - paddingRight - 80f
                val visibleArea = (height - paddingBottom - 40f) - (textHeight * 3) - 75f - (paddingTop + 80f)

                val totalHeight = getTotalTextHeight(maxWidth)
                val maxScroll = max(0f, totalHeight - visibleArea)

                scrollOffset = (scrollOffset + distanceY).coerceIn(0f, maxScroll)
                requestUpdate()
            }
            return true
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true

        sessions.add(TerminalSession("Local", SessionType.LOCAL))

        savedSshHosts.add(SshConfig("Pi-Hole", "root", "192.168.1.10", 22, ""))
        savedSshHosts.add(SshConfig("Web-Server", "admin", "104.22.45.1", 2222, "admin123"))

        loadInstalledApps()
        applyTheme(TerminalTheme.LUMON)
        post(cursorPulseRunnable)
    }

    override fun onCheckIsTextEditor(): Boolean = true
    override fun performClick(): Boolean = super.performClick()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (currentState == AppState.TERMINAL) {
                requestFocus()
            }
            return true
        }
        return handled || super.onTouchEvent(event)
    }

    private fun applyTheme(theme: TerminalTheme) {
        currentTheme = theme
        val bgColorArray: FloatArray
        val textColorArray: FloatArray

        when (theme) {
            TerminalTheme.GREEN -> {
                currentTextColor = Color.parseColor("#33FF00")
                promptTextPaint.color = Color.parseColor("#E0FFE0")
                bgColorArray = floatArrayOf(0.04f, 0.12f, 0.04f)
                textColorArray = floatArrayOf(0.2f, 1.0f, 0.0f)
                invertedTextPaint.color = Color.parseColor("#0A1F0A")
            }
            TerminalTheme.LUMON -> {
                currentTextColor = Color.parseColor("#00E5FF")
                promptTextPaint.color = Color.parseColor("#E0FFFF")
                bgColorArray = floatArrayOf(0.02f, 0.06f, 0.11f)
                textColorArray = floatArrayOf(0.0f, 0.9f, 1.0f)
                invertedTextPaint.color = Color.parseColor("#05101D")
            }
            TerminalTheme.AMBER -> {
                currentTextColor = Color.parseColor("#FFB000")
                promptTextPaint.color = Color.parseColor("#FFE8B2")
                bgColorArray = floatArrayOf(0.12f, 0.06f, 0.0f)
                textColorArray = floatArrayOf(1.0f, 0.7f, 0.0f)
                invertedTextPaint.color = Color.parseColor("#1F0F00")
            }
        }

        textPaint.color = currentTextColor
        boldTextPaint.color = currentTextColor
        tabFramePaint.color = currentTextColor
        invertedPaint.color = currentTextColor

        shaderRenderer?.let {
            it.currentBgColor = bgColorArray
            it.currentTextColor = textColorArray
        }
        requestUpdate()
    }

    fun setCrtSurface(glView: GLSurfaceView, renderer: CrtShaderRenderer) {
        this.crtSurfaceView = glView
        this.shaderRenderer = renderer
        applyTheme(currentTheme)
        renderer.glowIntensity = currentGlowIntensity
        renderer.isScanlinesEnabled = isScanlinesEnabled
    }

    private fun requestUpdate() {
        shaderRenderer?.isDirty = true
    }

    private fun updatePaintSizes() {
        textPaint.textSize = currentTextSize
        boldTextPaint.textSize = currentTextSize
        promptTextPaint.textSize = currentTextSize
        invertedTextPaint.textSize = currentTextSize
    }

    fun onKeyboardToggled(isOpen: Boolean) {
        if (isOpen) scrollToBottom()
    }

    private fun scrollToBottom() {
        forceScrollToBottom = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter("com.example.crtui.NOTIFICATION_EVENT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(notificationReceiver, filter)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.unregisterReceiver(notificationReceiver)
        removeCallbacks(cursorPulseRunnable)
    }

    private fun loadInstalledApps() {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = pm.queryIntentActivities(intent, 0)
        for (resolveInfo in apps) {
            val appName = resolveInfo.loadLabel(pm).toString()
            val packageName = resolveInfo.activityInfo.packageName
            if (packageName != context.packageName) installedApps.add(AppInfo(appName, packageName))
        }
        installedApps.sortBy { it.name.lowercase() }
    }

    data class TextMetrics(val nextY: Float, val endX: Float, val cursorCoords: Pair<Float, Float>?)

    private fun drawAndMeasureWrappedText(
        canvas: Canvas?, text: String, startX: Float, startY: Float,
        maxWidth: Float, bottomLimit: Float = Float.MAX_VALUE, maxLines: Int = Int.MAX_VALUE,
        targetCharIndex: Int = -1
    ): TextMetrics {
        var tempX = startX
        var currentY = startY
        val buffer = StringBuilder()
        var isBold = false
        var isPrompt = false
        val fontMetrics = textPaint.fontMetrics
        val lineHeight = fontMetrics.descent - fontMetrics.ascent + 5f
        var lineCount = 1
        var cursorCoords: Pair<Float, Float>? = null

        fun flushBuffer(appendEllipsis: Boolean = false) {
            if (buffer.isNotEmpty() || appendEllipsis) {
                val paintToUse = if (isPrompt) promptTextPaint else if (isBold) boldTextPaint else textPaint
                val textToDraw = if (appendEllipsis) buffer.toString() + "..." else buffer.toString()

                if (canvas != null && currentY > paddingTop && currentY < bottomLimit + 50f) {
                    canvas.drawText(textToDraw, tempX, currentY, paintToUse)
                }
                tempX += paintToUse.measureText(textToDraw)
                buffer.clear()
            }
        }

        var i = 0
        while (i <= text.length) {
            if (i == targetCharIndex) {
                flushBuffer()
                cursorCoords = Pair(currentY, tempX)
            }
            if (i == text.length) break

            if (text.startsWith("<bold>", i)) { flushBuffer(); isBold = true; i += 6; continue }
            if (text.startsWith("</bold>", i)) { flushBuffer(); isBold = false; i += 7; continue }
            if (text.startsWith("<prompt>", i)) { flushBuffer(); isPrompt = true; i += 8; continue }
            if (text.startsWith("</prompt>", i)) { flushBuffer(); isPrompt = false; i += 9; continue }

            val charStr = text[i].toString()
            val paintToUse = if (isPrompt) promptTextPaint else if (isBold) boldTextPaint else textPaint
            val charWidth = paintToUse.measureText(charStr)
            val bufferWidth = paintToUse.measureText(buffer.toString())

            if ((tempX - startX) + bufferWidth + charWidth > maxWidth) {
                if (lineCount >= maxLines) {
                    flushBuffer(appendEllipsis = true)
                    return TextMetrics(currentY + lineHeight + 10f, tempX, cursorCoords)
                }
                flushBuffer()
                tempX = startX
                currentY += lineHeight
                lineCount++

                if (i == targetCharIndex && cursorCoords != null && cursorCoords!!.first != currentY) {
                    cursorCoords = Pair(currentY, tempX)
                }
            }

            buffer.append(text[i])
            i++
        }
        flushBuffer()
        return TextMetrics(currentY + lineHeight + 10f, tempX, cursorCoords)
    }

    private fun getTotalTextHeight(maxWidth: Float): Float {
        var totalHeight = 0f
        val session = activeSession()
        for (line in session.history) {
            val maxL = if (line.startsWith("<bold>![")) 2 else Int.MAX_VALUE
            totalHeight = drawAndMeasureWrappedText(null, line, 0f, totalHeight, maxWidth, maxLines = maxL).nextY
        }
        totalHeight = drawAndMeasureWrappedText(null, "<prompt>${session.getPrompt()}</prompt>${session.buffer}", 0f, totalHeight, maxWidth).nextY
        return totalHeight
    }

    override fun onDraw(canvas: Canvas) {}

    fun renderContentForTexture(canvas: Canvas) {
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent

        val tabsY = height - paddingBottom - 40f
        val extraKeysY = tabsY - textHeight - 25f
        val commandBarY = extraKeysY - textHeight - 25f

        val textTopLimit = paddingTop + 80f
        val textBottomLimit = commandBarY - textHeight - 20f

        if (currentState == AppState.SETTINGS) {
            renderSettings(canvas)
            if (currentOverlay != OverlayState.NONE) {
                drawOverlayWindow(canvas, textBottomLimit)
            }
            return
        }

        val session = activeSession()
        val startX = paddingLeft + 40f
        val maxWidth = width - paddingLeft - paddingRight - 80f
        val visibleHeight = textBottomLimit - textTopLimit

        val totalHeight = getTotalTextHeight(maxWidth)
        val maxScroll = max(0f, totalHeight - visibleHeight)

        if (forceScrollToBottom) {
            scrollOffset = maxScroll
            forceScrollToBottom = false
        } else {
            scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
        }

        var currentY = textTopLimit - scrollOffset

        val timeStr = SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(Date())
        canvas.drawText(timeStr, startX, paddingTop + 60f, promptTextPaint)

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batStr = "PWR: $batteryPct%"
        val batWidth = promptTextPaint.measureText(batStr)
        canvas.drawText(batStr, width - paddingRight - 40f - batWidth, paddingTop + 60f, promptTextPaint)

        canvas.save()
        canvas.clipRect(0f, textTopLimit, width.toFloat(), textBottomLimit + fontMetrics.descent)

        for (line in session.history) {
            val maxL = if (line.startsWith("<bold>![")) 2 else Int.MAX_VALUE
            currentY = drawAndMeasureWrappedText(canvas, line, startX, currentY, maxWidth, textBottomLimit, maxL).nextY
        }

        val fullPrompt = "<prompt>${session.getPrompt()}</prompt>${session.buffer}"
        val targetIndex = "<prompt>${session.getPrompt()}</prompt>".length + session.cursor
        val metrics = drawAndMeasureWrappedText(canvas, fullPrompt, startX, currentY, maxWidth, textBottomLimit, targetCharIndex = targetIndex)

        if (metrics.cursorCoords != null) {
            val cY = metrics.cursorCoords.first
            val cX = metrics.cursorCoords.second

            val time = System.currentTimeMillis()
            val pulseAlpha = ((Math.sin(time / 300.0) + 1.0) / 2.0 * 255).toInt()
            textPaint.alpha = pulseAlpha
            canvas.drawRect(cX + 2f, cY + fontMetrics.ascent + 5f, cX + 22f, cY + fontMetrics.descent - 5f, textPaint)
            textPaint.alpha = 255
        }
        canvas.restore()

        if (currentOverlay != OverlayState.NONE) {
            drawOverlayWindow(canvas, textBottomLimit)
        }

        drawTabs(canvas, tabsY)
        drawExtraKeysBar(canvas, extraKeysY)
        drawCommandBar(canvas, commandBarY)
    }

    private fun drawOverlayWindow(canvas: Canvas, maxBottomY: Float) {
        val newHitboxes = mutableMapOf<String, RectF>()
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val touchPad = 25f

        val leftX = paddingLeft + 60f
        val rightX = width - paddingRight - 60f
        val topY = paddingTop + 150f
        var currentY = topY + textHeight + 20f

        val itemCount = when (currentOverlay) {
            OverlayState.FAVORITES -> max(1, favoriteApps.size)
            OverlayState.NEW_TAB_PROMPT -> 2
            OverlayState.SSH_HOSTS -> max(1, savedSshHosts.size) + 2
            OverlayState.ADD_SSH_HOST -> 6
            else -> 1
        }

        val neededHeight = when (currentOverlay) {
            OverlayState.MANUAL -> (maxBottomY - 20f) - topY
            else -> (textHeight * 1.5f) * (itemCount + 3)
        }

        val bottomY = min(topY + neededHeight, maxBottomY - 20f)
        val windowRect = RectF(leftX, topY, rightX, bottomY)

        canvas.drawRect(windowRect, clearPaint)
        canvas.drawRect(windowRect, tabFramePaint)

        val title = when (currentOverlay) {
            OverlayState.FAVORITES -> " FAVORITE APPS "
            OverlayState.NEW_TAB_PROMPT -> " ESTABLISH NEW CONNECTION "
            OverlayState.SSH_HOSTS -> " SECURE SHELL HOSTS "
            OverlayState.ADD_SSH_HOST -> " CONFIGURE NEW HOST "
            OverlayState.MANUAL -> " USER MANUAL v0.6.0 "
            else -> " OVERLAY "
        }
        canvas.drawText(title, leftX + 20f, currentY, boldTextPaint)

        val closeText = "[X]"
        val closeWidth = textPaint.measureText(closeText)
        val closeX = rightX - closeWidth - 20f
        canvas.drawText(closeText, closeX, currentY, boldTextPaint)

        newHitboxes["close"] = RectF(closeX - touchPad, currentY - textHeight - touchPad, closeX + closeWidth + touchPad, currentY + fontMetrics.descent + touchPad)

        canvas.drawLine(leftX, currentY + fontMetrics.descent, rightX, currentY + fontMetrics.descent, textPaint)
        currentY += textHeight * 1.5f

        when (currentOverlay) {
            OverlayState.FAVORITES -> {
                if (favoriteApps.isEmpty()) {
                    canvas.drawText(" (No favorites. Use 'fav -add <app>')", leftX + 20f, currentY, textPaint)
                } else {
                    for (app in favoriteApps) {
                        if (currentY > bottomY - textHeight) break
                        canvas.drawText("  $app", leftX + 20f, currentY, textPaint)
                        newHitboxes["launch_$app"] = RectF(leftX, currentY - textHeight, rightX, currentY + fontMetrics.descent)
                        currentY += textHeight * 1.5f
                    }
                }
            }
            OverlayState.NEW_TAB_PROMPT -> {
                val optLocal = "  [ LOCAL TERMINAL ]"
                canvas.drawText(optLocal, leftX + 20f, currentY, textPaint)
                newHitboxes["new_local"] = RectF(leftX, currentY - textHeight, rightX, currentY + fontMetrics.descent)
                currentY += textHeight * 1.5f

                val optRemote = "  [ REMOTE (SSH) TERMINAL ]"
                canvas.drawText(optRemote, leftX + 20f, currentY, textPaint)
                newHitboxes["new_remote"] = RectF(leftX, currentY - textHeight, rightX, currentY + fontMetrics.descent)
            }
            OverlayState.SSH_HOSTS -> {
                if (savedSshHosts.isEmpty()) {
                    canvas.drawText("  (No saved hosts)", leftX + 20f, currentY, textPaint)
                    currentY += textHeight * 1.5f
                } else {
                    for ((index, host) in savedSshHosts.withIndex()) {
                        if (currentY > bottomY - (textHeight*3)) break

                        val hostText = "  ${host.name} (${host.user}@${host.host})"
                        canvas.drawText(hostText, leftX + 20f, currentY, textPaint)

                        val delBtn = "[X]"
                        val editBtn = "[EDIT]"

                        val delX = rightX - textPaint.measureText(delBtn) - 20f
                        val editX = delX - textPaint.measureText(editBtn) - 20f

                        canvas.drawText(delBtn, delX, currentY, boldTextPaint)
                        canvas.drawText(editBtn, editX, currentY, boldTextPaint)

                        newHitboxes["del_ssh_$index"] = RectF(delX - touchPad, currentY - textHeight - touchPad, delX + textPaint.measureText(delBtn) + touchPad, currentY + fontMetrics.descent + touchPad)
                        newHitboxes["edit_ssh_$index"] = RectF(editX - touchPad, currentY - textHeight - touchPad, editX + textPaint.measureText(editBtn) + touchPad, currentY + fontMetrics.descent + touchPad)
                        newHitboxes["connect_ssh_$index"] = RectF(leftX, currentY - textHeight, editX - 10f, currentY + fontMetrics.descent)

                        currentY += textHeight * 1.5f
                    }
                }

                currentY += textHeight * 0.5f
                val addText = "  [+ ADD NEW HOST]"
                canvas.drawText(addText, leftX + 20f, currentY, boldTextPaint)
                newHitboxes["add_ssh"] = RectF(leftX, currentY - textHeight, rightX, currentY + fontMetrics.descent)
            }
            OverlayState.ADD_SSH_HOST -> {
                for (i in sshFormLabels.indices) {
                    val label = sshFormLabels[i]
                    val isPasswordField = (i == 4)
                    val isActiveField = (i == sshFormActiveField)

                    val value = if (isPasswordField && !isActiveField && sshFormValues[i].isNotEmpty()) {
                        "*".repeat(sshFormValues[i].length)
                    } else {
                        sshFormValues[i]
                    }

                    val cursorChar = if (isActiveField && (System.currentTimeMillis() / 300 % 2 == 0L)) "_" else ""
                    val text = "  $label$value$cursorChar"

                    val paintToUse = if (isActiveField) boldTextPaint else textPaint
                    canvas.drawText(text, leftX + 20f, currentY, paintToUse)
                    newHitboxes["ssh_field_$i"] = RectF(leftX, currentY - textHeight, rightX, currentY + fontMetrics.descent)
                    currentY += textHeight * 1.5f
                }

                currentY += textHeight * 0.5f
                val saveBtn = "  [ SAVE ]"
                canvas.drawText(saveBtn, leftX + 20f, currentY, boldTextPaint)
                newHitboxes["ssh_save"] = RectF(leftX, currentY - textHeight, leftX + 40f + textPaint.measureText(saveBtn), currentY + fontMetrics.descent)

                val cancelBtn = "  [ CANCEL ]"
                val cancelX = leftX + 60f + textPaint.measureText(saveBtn)
                canvas.drawText(cancelBtn, cancelX, currentY, boldTextPaint)
                newHitboxes["ssh_cancel"] = RectF(cancelX - 20f, currentY - textHeight, rightX, currentY + fontMetrics.descent)
            }
            OverlayState.MANUAL -> {
                val manualMaxWidth = rightX - leftX - 40f
                canvas.save()
                canvas.clipRect(leftX + 5f, currentY - textHeight, rightX - 5f, bottomY - 5f)
                for (line in manualLines) {
                    if (currentY > bottomY - textHeight) break
                    val metrics = drawAndMeasureWrappedText(canvas, line, leftX + 20f, currentY, manualMaxWidth, bottomY - 10f)
                    currentY = metrics.nextY - (textHeight * 0.5f)
                }
                canvas.restore()
            }
            else -> {}
        }

        overlayHitboxes = newHitboxes
    }

    private fun renderSettings(canvas: Canvas) {
        val newSettings = mutableMapOf<String, RectF>()
        val startX = paddingLeft + 40f
        var currentY = paddingTop + 150f
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val touchPad = 25f

        canvas.drawText("MACRODATA REFINEMENT :: PREFERENCES", startX, currentY, boldTextPaint)
        currentY += textHeight * 2f

        val themeLabel = "Color Theme: [${currentTheme.name}]  "
        canvas.drawText(themeLabel, startX, currentY, textPaint)
        val cycleBtn = "[ CYCLE ]"
        val cycleX = startX + textPaint.measureText(themeLabel)
        canvas.drawText(cycleBtn, cycleX, currentY, boldTextPaint)
        newSettings["theme_cycle"] = RectF(cycleX - touchPad, currentY - textHeight - touchPad, cycleX + textPaint.measureText(cycleBtn) + touchPad, currentY + fontMetrics.descent + touchPad)
        currentY += textHeight * 1.5f

        val glowLabel = String.format(Locale.US, "Glow Intensity: %.1f  ", currentGlowIntensity)
        canvas.drawText(glowLabel, startX, currentY, textPaint)
        var tempX = startX + textPaint.measureText(glowLabel)

        val gMinusBtn = "[-] "
        canvas.drawText(gMinusBtn, tempX, currentY, boldTextPaint)
        newSettings["glow_minus"] = RectF(tempX - touchPad, currentY - textHeight - touchPad, tempX + textPaint.measureText(gMinusBtn) + touchPad, currentY + fontMetrics.descent + touchPad)
        tempX += textPaint.measureText(gMinusBtn)

        val gPlusBtn = "[+]"
        canvas.drawText(gPlusBtn, tempX, currentY, boldTextPaint)
        newSettings["glow_plus"] = RectF(tempX - touchPad, currentY - textHeight - touchPad, tempX + textPaint.measureText(gPlusBtn) + touchPad, currentY + fontMetrics.descent + touchPad)
        currentY += textHeight * 1.5f

        val scanlineLabel = "Scanlines: "
        canvas.drawText(scanlineLabel, startX, currentY, textPaint)
        val scanBtn = if (isScanlinesEnabled) "[ ENABLED ]" else "[ DISABLED ]"
        val scanX = startX + textPaint.measureText(scanlineLabel)
        canvas.drawText(scanBtn, scanX, currentY, boldTextPaint)
        newSettings["toggle_scanlines"] = RectF(scanX - touchPad, currentY - textHeight - touchPad, scanX + textPaint.measureText(scanBtn) + touchPad, currentY + fontMetrics.descent + touchPad)
        currentY += textHeight * 1.5f

        val fontLabel = "Font Size: ${currentTextSize.toInt()}  "
        canvas.drawText(fontLabel, startX, currentY, textPaint)
        tempX = startX + textPaint.measureText(fontLabel)

        val minusBtn = "[-] "
        canvas.drawText(minusBtn, tempX, currentY, boldTextPaint)
        newSettings["font_minus"] = RectF(tempX - touchPad, currentY - textHeight - touchPad, tempX + textPaint.measureText(minusBtn) + touchPad, currentY + fontMetrics.descent + touchPad)
        tempX += textPaint.measureText(minusBtn)

        val plusBtn = "[+]"
        canvas.drawText(plusBtn, tempX, currentY, boldTextPaint)
        newSettings["font_plus"] = RectF(tempX - touchPad, currentY - textHeight - touchPad, tempX + textPaint.measureText(plusBtn) + touchPad, currentY + fontMetrics.descent + touchPad)
        currentY += textHeight * 2.5f

        val manualBtn = "[ VIEW SYSTEM MANUAL ]"
        canvas.drawText(manualBtn, startX, currentY, boldTextPaint)
        newSettings["open_manual"] = RectF(startX - touchPad, currentY - textHeight - touchPad, startX + textPaint.measureText(manualBtn) + touchPad, currentY + fontMetrics.descent + touchPad)
        currentY += textHeight * 1.5f

        val exportBtn = "[ EXPORT SETTINGS ]"
        canvas.drawText(exportBtn, startX, currentY, boldTextPaint)
        newSettings["export"] = RectF(startX - touchPad, currentY - textHeight - touchPad, startX + textPaint.measureText(exportBtn) + touchPad, currentY + fontMetrics.descent + touchPad)
        currentY += textHeight * 3f

        canvas.drawText("--- MACRO ALIASES ---", startX, currentY, boldTextPaint)
        currentY += textHeight * 1.5f

        if (aliases.isEmpty()) {
            canvas.drawText("(No aliases defined)", startX, currentY, textPaint)
            currentY += textHeight * 1.5f
        } else {
            for ((key, cmd) in aliases) {
                val aliasText = "$key -> $cmd  "
                canvas.drawText(aliasText, startX, currentY, textPaint)

                val delX = startX + textPaint.measureText(aliasText)
                val delBtn = "[X]"
                canvas.drawText(delBtn, delX, currentY, boldTextPaint)
                newSettings["delete_alias_$key"] = RectF(delX - touchPad, currentY - textHeight - touchPad, delX + textPaint.measureText(delBtn) + touchPad, currentY + fontMetrics.descent + touchPad)

                currentY += textHeight * 1.5f
            }
        }

        val addBtn = "[+ ADD ALIAS]"
        canvas.drawText(addBtn, startX, currentY, boldTextPaint)
        newSettings["add_alias"] = RectF(startX - touchPad, currentY - textHeight - touchPad, startX + textPaint.measureText(addBtn) + touchPad, currentY + fontMetrics.descent + touchPad)
        currentY += textHeight * 3f

        val repoText = "VERSION v0.6.0 :: [ GitHub: exolon/CRTUI ]"
        canvas.drawText(repoText, startX, currentY, promptTextPaint)
        newSettings["github_link"] = RectF(startX - touchPad, currentY - textHeight - touchPad, startX + textPaint.measureText(repoText) + touchPad, currentY + fontMetrics.descent + touchPad)
        currentY += textHeight * 3f

        val exitBtn = "[ EXIT SETTINGS ]"
        canvas.drawText(exitBtn, startX, currentY, boldTextPaint)
        newSettings["exit"] = RectF(startX - touchPad, currentY - textHeight - touchPad, startX + textPaint.measureText(exitBtn) + touchPad, currentY + fontMetrics.descent + touchPad)

        settingsHitboxes = newSettings
    }

    private fun drawTabs(canvas: Canvas, bottomY: Float) {
        val newTabs = mutableMapOf<Int, RectF>()
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        var currentX = paddingLeft + 40f
        val touchPad = 15f

        for ((index, session) in sessions.withIndex()) {
            val displayText = " ${session.name} "
            val textWidth = textPaint.measureText(displayText)
            val rect = RectF(currentX - touchPad, bottomY - textHeight - touchPad, currentX + textWidth + touchPad, bottomY + fontMetrics.descent + touchPad)
            newTabs[index] = rect

            if (index == activeTabIndex) {
                val visualRect = RectF(currentX, bottomY - textHeight, currentX + textWidth, bottomY + fontMetrics.descent)
                canvas.drawRect(visualRect, tabFramePaint)
            }
            canvas.drawText(displayText, currentX, bottomY, textPaint)

            currentX += textWidth + 20f
        }

        val plusText = " + "
        val plusWidth = textPaint.measureText(plusText)
        newTabHitbox = RectF(currentX - touchPad, bottomY - textHeight - touchPad, currentX + plusWidth + touchPad, bottomY + fontMetrics.descent + touchPad)
        canvas.drawText(plusText, currentX, bottomY, boldTextPaint)

        val cogText = " [ * ] "
        val cogWidth = textPaint.measureText(cogText)
        val cogX = width - paddingRight - 40f - cogWidth
        settingsTabHitbox = RectF(cogX - touchPad, bottomY - textHeight - touchPad, cogX + cogWidth + touchPad, bottomY + fontMetrics.descent + touchPad)
        canvas.drawText(cogText, cogX, bottomY, promptTextPaint)

        tabHitboxes = newTabs
    }

    private fun drawExtraKeysBar(canvas: Canvas, yPos: Float) {
        val newKeys = mutableMapOf<String, RectF>()
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        var currentX = paddingLeft + 40f
        val touchPad = 10f

        val keys = listOf("Tab", "CTRL+C", "<", ">", "^", "v", "/", "-", "=")

        for (key in keys) {
            val displayText = " [$key] "
            val textWidth = textPaint.measureText(displayText)
            val rect = RectF(currentX - touchPad, yPos - textHeight - touchPad, currentX + textWidth + touchPad, yPos + fontMetrics.descent + touchPad)

            newKeys[key] = rect
            canvas.drawText(displayText, currentX, yPos, textPaint)
            currentX += textWidth + 15f
        }
        extraKeyHitboxes = newKeys
    }

    private fun drawCommandBar(canvas: Canvas, commandBarY: Float) {
        val newCmds = mutableMapOf<String, RectF>()
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        var currentX = paddingLeft + 40f
        val touchPad = 10f

        val session = activeSession()
        val itemsToDraw = when (currentCommandMode) {
            CommandMode.NORMAL -> listOf("Apps", "Favs", "Alias", "Sys", "Net")
            CommandMode.APPS -> {
                val filterText = session.buffer.lowercase()
                installedApps.filter { it.name.lowercase().contains(filterText) }.take(3).map { it.name }
            }
            CommandMode.ALIASES -> {
                val filterText = session.buffer.lowercase()
                aliases.keys.filter { it.lowercase().contains(filterText) }.take(4).toList()
            }
            else -> listOf()
        }

        val rowPrefix = when (currentCommandMode) {
            CommandMode.NORMAL -> "CMD: "
            CommandMode.APPS -> "APP: "
            else -> "ALS: "
        }

        canvas.drawText(rowPrefix, currentX, commandBarY, promptTextPaint)
        currentX += textPaint.measureText(rowPrefix)

        for (item in itemsToDraw) {
            val displayText = "[$item]"
            val textWidth = textPaint.measureText(displayText)
            val rect = RectF(currentX - touchPad, commandBarY - textHeight - touchPad, currentX + textWidth + touchPad, commandBarY + fontMetrics.descent + touchPad)

            newCmds[item] = rect
            canvas.drawText(displayText, currentX, commandBarY, textPaint)
            currentX += textWidth + 25f
        }
        commandHitboxes = newCmds
    }

    private fun handleTabCompletion() {
        val session = activeSession()
        val bufferLower = session.buffer.lowercase()

        if (bufferLower.startsWith("fav -add ")) {
            val query = bufferLower.substringAfter("fav -add ")
            val match = installedApps.find { it.name.lowercase().startsWith(query) }
            if (match != null) {
                session.buffer = "fav -add ${match.name}"
                session.cursor = session.buffer.length
            }
        } else if (bufferLower.startsWith("fav -rm ")) {
            val query = bufferLower.substringAfter("fav -rm ")
            val match = favoriteApps.find { it.lowercase().startsWith(query) }
            if (match != null) {
                session.buffer = "fav -rm $match"
                session.cursor = session.buffer.length
            }
        } else if (!bufferLower.contains(" ") && bufferLower.isNotEmpty()) {
            val matches = installedApps.filter { it.name.lowercase().startsWith(bufferLower) }
            if (matches.size == 1) {
                session.history.add(session.getPrompt() + session.buffer)
                launchApp(matches[0].name)
            } else if (matches.size > 1) {
                session.history.add(session.getPrompt() + session.buffer)
                session.history.add(matches.joinToString("  ") { "<bold>${it.name}</bold>" })

                var prefix = matches[0].name
                for (i in 1 until matches.size) {
                    while (!matches[i].name.lowercase().startsWith(prefix.lowercase())) {
                        prefix = prefix.substring(0, prefix.length - 1)
                        if (prefix.isEmpty()) break
                    }
                }
                if (prefix.length > session.buffer.length) {
                    session.buffer = prefix
                    session.cursor = session.buffer.length
                }
            } else {
                val left = session.buffer.substring(0, session.cursor)
                val right = session.buffer.substring(session.cursor)
                session.buffer = left + "    " + right
                session.cursor += 4
            }
        } else {
            val left = session.buffer.substring(0, session.cursor)
            val right = session.buffer.substring(session.cursor)
            session.buffer = left + "    " + right
            session.cursor += 4
        }
        scrollToBottom()
        requestUpdate()
    }

    private fun launchApp(appName: String) {
        val app = installedApps.find { it.name == appName }
        if (app != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            if (intent != null) {
                val session = activeSession()
                session.history.add("> Launching $appName...")
                context.startActivity(intent)
                session.buffer = ""
                session.cursor = 0
                currentCommandMode = CommandMode.NORMAL
            } else {
                activeSession().history.add("> Error: Cannot launch $appName")
            }
        }
    }

    private fun exportSettings() {
        // Modern permission bypass vs Legacy Storage request
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            (context as? android.app.Activity)?.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 112)
            activeSession().history.add("> Requesting storage permission. Please accept and try again.")
            scrollToBottom()
            requestUpdate()
            return
        }

        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val exportFile = File(downloadsDir, "lumon_settings_export.txt")

            val data = StringBuilder()
            data.appendLine("LUMON MACRODATA EXPORT")
            data.appendLine("Theme: ${currentTheme.name}")
            data.appendLine("FontSize: $currentTextSize")
            data.appendLine("GlowIntensity: $currentGlowIntensity")
            data.appendLine("Scanlines: $isScanlinesEnabled")
            data.appendLine("--- ALIASES ---")
            for ((key, value) in aliases) {
                data.appendLine("$key=$value")
            }
            data.appendLine("--- FAVORITES ---")
            for (app in favoriteApps) {
                data.appendLine(app)
            }

            exportFile.writeText(data.toString())
            activeSession().history.add("> Exported to Downloads/lumon_settings_export.txt")
            scrollToBottom()
        } catch (e: Exception) {
            activeSession().history.add("> Export Failed. Details: ${e.message}")
            scrollToBottom()
        }
    }

    private fun handleOverlayTap(touchX: Float, touchY: Float) {
        var windowActionTaken = false
        for ((action, rect) in overlayHitboxes) {
            if (rect.contains(touchX, touchY)) {
                when (currentOverlay) {
                    OverlayState.FAVORITES -> {
                        if (action == "close") currentOverlay = OverlayState.NONE
                        else if (action.startsWith("launch_")) {
                            launchApp(action.removePrefix("launch_"))
                            currentOverlay = OverlayState.NONE
                            currentState = AppState.TERMINAL
                        }
                    }
                    OverlayState.NEW_TAB_PROMPT -> {
                        if (action == "close") currentOverlay = OverlayState.NONE
                        else if (action == "new_local") {
                            sessions.add(TerminalSession("Local", SessionType.LOCAL))
                            activeTabIndex = sessions.size - 1
                            currentOverlay = OverlayState.NONE
                        }
                        else if (action == "new_remote") {
                            currentOverlay = OverlayState.SSH_HOSTS
                        }
                    }
                    OverlayState.SSH_HOSTS -> {
                        if (action == "close") currentOverlay = OverlayState.NONE
                        else if (action == "add_ssh") {
                            sshFormValues = MutableList(5) { "" }
                            sshFormValues[3] = "22"
                            sshFormActiveField = 0
                            currentOverlay = OverlayState.ADD_SSH_HOST
                            post {
                                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                imm.showSoftInput(this, 0)
                            }
                        }
                        else if (action.startsWith("del_ssh_")) {
                            val hostIndex = action.removePrefix("del_ssh_").toIntOrNull()
                            if (hostIndex != null && hostIndex < savedSshHosts.size) {
                                savedSshHosts.removeAt(hostIndex)
                            }
                        }
                        else if (action.startsWith("edit_ssh_")) {
                            val hostIndex = action.removePrefix("edit_ssh_").toIntOrNull()
                            if (hostIndex != null && hostIndex < savedSshHosts.size) {
                                val h = savedSshHosts[hostIndex]
                                sshFormValues = mutableListOf(h.name, h.user, h.host, h.port.toString(), h.pass)
                                sshFormActiveField = 0
                                savedSshHosts.removeAt(hostIndex)
                                currentOverlay = OverlayState.ADD_SSH_HOST
                                post {
                                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    imm.showSoftInput(this, 0)
                                }
                            }
                        }
                        else if (action.startsWith("connect_ssh_")) {
                            val hostIndex = action.removePrefix("connect_ssh_").toIntOrNull()
                            if (hostIndex != null && hostIndex < savedSshHosts.size) {
                                val config = savedSshHosts[hostIndex]
                                val newSession = TerminalSession(config.name, SessionType.SSH, config)
                                sessions.add(newSession)
                                activeTabIndex = sessions.size - 1

                                newSession.connectSsh {
                                    post {
                                        scrollToBottom()
                                        requestUpdate()
                                    }
                                }
                            }
                            currentOverlay = OverlayState.NONE
                        }
                    }
                    OverlayState.ADD_SSH_HOST -> {
                        if (action == "close" || action == "ssh_cancel") {
                            currentOverlay = OverlayState.SSH_HOSTS
                        } else if (action.startsWith("ssh_field_")) {
                            sshFormActiveField = action.removePrefix("ssh_field_").toInt()
                            post {
                                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                imm.showSoftInput(this, 0)
                            }
                        } else if (action == "ssh_save") {
                            val name = sshFormValues[0].takeIf { it.isNotBlank() } ?: "Unnamed"
                            val user = sshFormValues[1].takeIf { it.isNotBlank() } ?: "root"
                            val host = sshFormValues[2].takeIf { it.isNotBlank() } ?: "127.0.0.1"
                            val port = sshFormValues[3].toIntOrNull() ?: 22
                            val pass = sshFormValues[4]

                            savedSshHosts.add(SshConfig(name, user, host, port, pass))
                            currentOverlay = OverlayState.SSH_HOSTS
                        }
                    }
                    OverlayState.MANUAL -> {
                        if (action == "close") currentOverlay = OverlayState.NONE
                    }
                    else -> {}
                }
                windowActionTaken = true
                break
            }
        }
        if (windowActionTaken) {
            scrollToBottom()
            requestUpdate()
        }
    }

    private fun handleSettingsTap(touchX: Float, touchY: Float) {
        for ((action, rect) in settingsHitboxes) {
            if (rect.contains(touchX, touchY)) {
                when {
                    action == "theme_cycle" -> {
                        val nextTheme = TerminalTheme.values()[(currentTheme.ordinal + 1) % TerminalTheme.values().size]
                        applyTheme(nextTheme)
                    }
                    action == "glow_minus" -> {
                        if (currentGlowIntensity > 0.0f) {
                            currentGlowIntensity = max(0f, currentGlowIntensity - 0.1f)
                            shaderRenderer?.glowIntensity = currentGlowIntensity
                        }
                    }
                    action == "glow_plus" -> {
                        if (currentGlowIntensity < 2.0f) {
                            currentGlowIntensity = min(2.0f, currentGlowIntensity + 0.1f)
                            shaderRenderer?.glowIntensity = currentGlowIntensity
                        }
                    }
                    action == "toggle_scanlines" -> {
                        isScanlinesEnabled = !isScanlinesEnabled
                        shaderRenderer?.isScanlinesEnabled = isScanlinesEnabled
                    }
                    action == "font_minus" -> {
                        if (currentTextSize > 20f) {
                            currentTextSize -= 2f
                            updatePaintSizes()
                        }
                    }
                    action == "font_plus" -> {
                        if (currentTextSize < 80f) {
                            currentTextSize += 2f
                            updatePaintSizes()
                        }
                    }
                    action == "open_manual" -> {
                        currentOverlay = OverlayState.MANUAL
                    }
                    action == "export" -> {
                        exportSettings()
                        currentState = AppState.TERMINAL
                    }
                    action == "github_link" -> {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/exolon/CRTUI"))
                        context.startActivity(intent)
                    }
                    action.startsWith("delete_alias_") -> {
                        val key = action.removePrefix("delete_alias_")
                        aliases.remove(key)
                    }
                    action == "add_alias" -> {
                        currentState = AppState.TERMINAL
                        val session = activeSession()
                        session.buffer = "alias "
                        session.cursor = 6
                        scrollToBottom()
                        requestFocus()
                        post {
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.showSoftInput(this, 0)
                        }
                    }
                    action == "exit" -> {
                        currentState = AppState.TERMINAL
                        activeSession().history.add("> Settings saved.")
                        scrollToBottom()
                    }
                }
                requestUpdate()
                break
            }
        }
    }

    private fun handleTap(touchX: Float, touchY: Float) {
        var actionTaken = false

        if (settingsTabHitbox?.contains(touchX, touchY) == true) {
            currentState = AppState.SETTINGS
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(windowToken, 0)
            requestUpdate()
            return
        }

        if (newTabHitbox?.contains(touchX, touchY) == true) {
            currentOverlay = OverlayState.NEW_TAB_PROMPT
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(windowToken, 0)
            requestUpdate()
            return
        }

        val session = activeSession()
        for ((key, rect) in extraKeyHitboxes) {
            if (rect.contains(touchX, touchY)) {
                when (key) {
                    "Tab" -> handleTabCompletion()
                    "CTRL+C" -> {
                        session.history.add(session.getPrompt() + session.buffer + "^C")
                        session.buffer = ""
                        session.cursor = 0
                        currentCommandMode = CommandMode.NORMAL
                        scrollToBottom()
                    }
                    "<" -> session.cursor = max(0, session.cursor - 1)
                    ">" -> session.cursor = min(session.buffer.length, session.cursor + 1)
                    "^" -> {
                        val fontMetrics = textPaint.fontMetrics
                        val textHeight = fontMetrics.descent - fontMetrics.ascent
                        val visibleArea = (height - paddingBottom - 40f) - (textHeight * 3) - 75f - (paddingTop + 80f)
                        val maxWidth = width - paddingLeft - paddingRight - 80f
                        val totalHeight = getTotalTextHeight(maxWidth)
                        val maxScroll = max(0f, totalHeight - visibleArea)

                        scrollOffset = (scrollOffset + 200f).coerceIn(0f, maxScroll)
                    }
                    "v" -> scrollOffset = (scrollOffset - 200f).coerceAtLeast(0f)
                    else -> {
                        val left = session.buffer.substring(0, session.cursor)
                        val right = session.buffer.substring(session.cursor)
                        session.buffer = left + key + right
                        session.cursor += key.length
                    }
                }
                actionTaken = true
                break
            }
        }

        if (!actionTaken) {
            for ((index, rect) in tabHitboxes) {
                if (rect.contains(touchX, touchY)) {
                    activeTabIndex = index
                    actionTaken = true
                    scrollToBottom()
                    break
                }
            }
        }

        if (!actionTaken) {
            for ((commandString, rect) in commandHitboxes) {
                if (rect.contains(touchX, touchY)) {
                    when {
                        currentCommandMode == CommandMode.NORMAL && commandString == "Apps" -> {
                            currentCommandMode = CommandMode.APPS
                        }
                        currentCommandMode == CommandMode.NORMAL && commandString == "Alias" -> {
                            currentCommandMode = CommandMode.ALIASES
                        }
                        currentCommandMode == CommandMode.NORMAL && commandString == "Favs" -> {
                            currentOverlay = OverlayState.FAVORITES
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.hideSoftInputFromWindow(windowToken, 0)
                        }
                        currentCommandMode == CommandMode.APPS -> {
                            launchApp(commandString)
                        }
                        currentCommandMode == CommandMode.ALIASES -> {
                            val mappedCmd = aliases[commandString]
                            if (mappedCmd != null) {
                                session.history.add(session.getPrompt() + commandString)
                                processCommand(mappedCmd)
                                currentCommandMode = CommandMode.NORMAL
                            }
                        }
                    }
                    actionTaken = true
                    break
                }
            }
        }

        if (!actionTaken) {
            currentCommandMode = CommandMode.NORMAL
            requestFocus()
            post {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(this, 0)
            }
        }

        requestUpdate()
    }

    private fun processCommand(input: String) {
        val session = activeSession()
        val args = input.trim().split("\\s+".toRegex())
        val cmd = args[0].lowercase()

        if (cmd != "alias" && aliases.containsKey(cmd)) {
            val mappedCmd = aliases[cmd]!!
            if (session.type == SessionType.LOCAL) {
                session.history.add("> executing alias: $cmd -> $mappedCmd")
            }
            processCommand(mappedCmd)
            return
        }

        if (session.type == SessionType.SSH) {
            if (cmd == "clear" || cmd == "settings" || cmd == "alias" || cmd == "exit") {
                if (cmd == "exit") {
                    session.disconnectSsh()
                    session.history.add("> Terminated remote connection.")
                }
            } else {
                session.sendCommand(input) {
                    post {
                        scrollToBottom()
                        requestUpdate()
                    }
                }
                return
            }
        }

        when (cmd) {
            "clear" -> session.history.clear()

            "changelog" -> {
                session.history.add("> CHANGELOG v0.6.0")
                session.history.add("• Added JSch SSH backend with TUI form.")
                session.history.add("• Added ANSI scrubbing for clean NAS output.")
                session.history.add("• Upgraded to true Gaussian bloom rendering.")
                session.history.add("• Enabled instant app launch from CLI root.")
                session.history.add("• Restored double-tap to close tabs.")
            }

            "settings" -> {
                currentState = AppState.SETTINGS
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(windowToken, 0)
            }

            "s" -> {
                val query = input.substringAfter("s ").trim()
                if (query.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra(SearchManager.QUERY, query) }
                    try {
                        context.startActivity(intent)
                        session.history.add("> Searching: $query")
                    } catch (e: Exception) {
                        val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        session.history.add("> Searching (Browser fallback): $query")
                    }
                } else {
                    session.history.add("Syntax error. Use: s [query]")
                }
            }

            "fav" -> {
                if (args.size >= 3) {
                    val action = args[1].lowercase()
                    val target = input.substringAfter(args[1]).trim()

                    if (action == "-add") {
                        val app = installedApps.find { it.name.equals(target, ignoreCase = true) }
                        if (app != null && !favoriteApps.contains(app.name)) {
                            favoriteApps.add(app.name)
                            session.history.add("Added to favorites: ${app.name}")
                        } else if (app == null) {
                            session.history.add("App not found: $target")
                        } else {
                            session.history.add("Already in favorites: ${app.name}")
                        }
                    } else if (action == "-rm") {
                        val removed = favoriteApps.remove(favoriteApps.find { it.equals(target, ignoreCase = true) })
                        if (removed) {
                            session.history.add("Removed from favorites: $target")
                        } else {
                            session.history.add("Favorite not found: $target")
                        }
                    } else {
                        session.history.add("Syntax: fav -add [app] | fav -rm [app]")
                    }
                } else {
                    session.history.add("Syntax: fav -add [app] | fav -rm [app]")
                }
            }

            "alias" -> {
                if (args.size > 1 && input.contains("=")) {
                    val mapping = input.substringAfter("alias ").trim()
                    val key = mapping.substringBefore("=").trim()
                    val value = mapping.substringAfter("=").trim()

                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        aliases[key] = value
                        session.history.add("Alias saved: $key -> $value")
                    } else {
                        session.history.add("Syntax error. Use: alias name=command")
                    }
                } else {
                    session.history.add("Syntax error. Use: alias name=command")
                }
            }

            "pwd" -> session.history.add(session.cwd.absolutePath)

            "ls" -> {
                val files = session.cwd.listFiles()
                if (files != null && files.isNotEmpty()) {
                    val output = files.joinToString("  ") { if (it.isDirectory) "<bold>${it.name}/</bold>" else it.name }
                    session.history.add(output)
                } else {
                    session.history.add(" (empty)")
                }
            }

            "cd" -> {
                if (args.size < 2) {
                    session.cwd = Environment.getExternalStorageDirectory()
                } else {
                    val target = args[1]
                    val newDir = if (target == "..") {
                        session.cwd.parentFile ?: session.cwd
                    } else if (target.startsWith("/")) {
                        File(target)
                    } else {
                        File(session.cwd, target)
                    }

                    if (newDir.exists() && newDir.isDirectory) {
                        session.cwd = newDir
                    } else {
                        session.history.add("cd: $target: No such file or directory")
                    }
                }
            }

            "mkdir" -> {
                if (args.size > 1) {
                    val newDir = File(session.cwd, args[1])
                    if (newDir.mkdirs()) session.history.add("Created directory: ${args[1]}")
                    else session.history.add("mkdir: cannot create directory '${args[1]}'")
                }
            }

            "rm" -> {
                if (args.size > 1) {
                    val target = File(session.cwd, args[1])
                    if (target.exists()) {
                        val success = if (target.isDirectory) target.deleteRecursively() else target.delete()
                        if (success) session.history.add("Removed: ${args[1]}")
                        else session.history.add("rm: cannot remove '${args[1]}'")
                    } else {
                        session.history.add("rm: ${args[1]}: No such file or directory")
                    }
                }
            }

            "note" -> {
                if (args.size > 1) {
                    val noteText = input.substringAfter("note ").trim()
                    val file = File(context.filesDir, "lumon_notes.txt")
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                    file.appendText("[$timestamp] $noteText\n")
                    session.history.add("Saved to local buffer.")
                }
            }

            "read" -> {
                val file = File(context.filesDir, "lumon_notes.txt")
                if (file.exists()) {
                    session.history.addAll(file.readLines())
                } else {
                    session.history.add("Local buffer is empty.")
                }
            }

            "dnd" -> {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.isNotificationPolicyAccessGranted) {
                    val isDndOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                    val newFilter = if (isDndOn) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_NONE
                    nm.setInterruptionFilter(newFilter)
                    session.history.add("DND Mode: ${if (!isDndOn) "ENGAGED" else "DISABLED"}")
                } else {
                    session.history.add("Permission denied. Grant DND access in OS settings.")
                }
            }

            "wifi" -> {
                session.history.add("Opening OS Network Panel...")
                context.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            }

            "ping" -> {
                if (args.size > 1) {
                    val ip = args[1]
                    session.history.add("Pinging $ip...")
                    Thread {
                        try {
                            val process = Runtime.getRuntime().exec("ping -c 4 $ip")
                            val reader = BufferedReader(InputStreamReader(process.inputStream))
                            reader.forEachLine { line ->
                                post {
                                    session.history.add(line)
                                    scrollToBottom()
                                    requestUpdate()
                                }
                            }
                        } catch (e: Exception) {
                            post {
                                session.history.add("Ping failed: ${e.message}")
                                requestUpdate()
                            }
                        }
                    }.start()
                } else {
                    session.history.add("Usage: ping [address]")
                }
            }

            else -> {
                val targetApp = input.trim().lowercase()
                val appMatch = installedApps.find {
                    it.name.lowercase() == targetApp || it.name.lowercase().contains(targetApp)
                }

                if (appMatch != null) {
                    launchApp(appMatch.name)
                } else {
                    session.history.add("Command not found: $cmd")
                }
            }
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE

        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text != null) {
                    if (currentOverlay == OverlayState.ADD_SSH_HOST) {
                        sshFormValues[sshFormActiveField] += text
                    } else {
                        val session = activeSession()
                        val left = session.buffer.substring(0, session.cursor)
                        val right = session.buffer.substring(session.cursor)
                        session.buffer = left + text + right
                        session.cursor += text.length
                        scrollToBottom()
                    }
                    requestUpdate()
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_TAB -> {
                            handleTabCompletion()
                            return true
                        }
                        KeyEvent.KEYCODE_DEL -> {
                            if (currentOverlay == OverlayState.ADD_SSH_HOST) {
                                val v = sshFormValues[sshFormActiveField]
                                if (v.isNotEmpty()) {
                                    sshFormValues[sshFormActiveField] = v.dropLast(1)
                                }
                            } else {
                                val session = activeSession()
                                if (session.cursor > 0) {
                                    val left = session.buffer.substring(0, session.cursor - 1)
                                    val right = session.buffer.substring(session.cursor)
                                    session.buffer = left + right
                                    session.cursor--

                                    if (session.buffer.isEmpty() && (currentCommandMode == CommandMode.APPS || currentCommandMode == CommandMode.ALIASES)) {
                                        currentCommandMode = CommandMode.NORMAL
                                    }
                                    scrollToBottom()
                                }
                            }
                            requestUpdate()
                            return true
                        }
                        KeyEvent.KEYCODE_ENTER -> {
                            if (currentOverlay == OverlayState.ADD_SSH_HOST) {
                                sshFormActiveField = (sshFormActiveField + 1) % 5
                            } else {
                                val session = activeSession()
                                if (session.buffer.isNotBlank()) {
                                    if (currentCommandMode == CommandMode.APPS) {
                                        val filterText = session.buffer.lowercase()
                                        val topApp = installedApps.find { it.name.lowercase().contains(filterText) }
                                        if (topApp != null) launchApp(topApp.name)
                                    } else if (currentCommandMode == CommandMode.ALIASES) {
                                        val filterText = session.buffer.lowercase()
                                        val topAlias = aliases.keys.find { it.lowercase().contains(filterText) }
                                        if (topAlias != null) {
                                            session.history.add(session.getPrompt() + topAlias)
                                            processCommand(aliases[topAlias]!!)
                                            currentCommandMode = CommandMode.NORMAL
                                        }
                                    } else {
                                        if (session.type == SessionType.LOCAL) {
                                            session.history.add(session.getPrompt() + session.buffer)
                                        }
                                        processCommand(session.buffer)
                                    }
                                    session.buffer = ""
                                    session.cursor = 0
                                    scrollToBottom()
                                }
                            }
                            requestUpdate()
                            return true
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }
}