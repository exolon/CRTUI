package com.example.crtui

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- STATIC STATE PRESERVATION ---
    companion object {
        val sessions = CopyOnWriteArrayList<TerminalSession>()
        var activeTabIndex = 0
        var hasBooted = false
    }

    private fun activeSession(): TerminalSession = sessions[activeTabIndex]

    // --- CUSTOM KEYBOARD ENGINE ---
    private var useCustomKeyboard = true
    private val keyboardEngine = KeyboardEngine { keyId -> handleCustomKey(keyId) }

    // --- THEME & FONT ENGINE ---
    enum class TerminalTheme { GREEN, LUMON, AMBER, CPC464 }
    enum class TerminalFont { GLASS_TTY, PIXELIFY }

    private var currentTheme = TerminalTheme.LUMON
    private var currentFont = TerminalFont.GLASS_TTY
    private var currentTextColor = Color.parseColor("#00E5FF")
    private var currentTextSize = 37f
    private var currentGlowIntensity = 0.6f
    private var currentBgOpacity = 0.2f
    private var isScanlinesEnabled = true
    private var showIconDock = true

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

    private enum class CommandMode { NORMAL, APPS, ALIASES, SYS, NET }
    private var currentCommandMode = CommandMode.NORMAL

    private enum class OverlayState { NONE, FAVORITES, NEW_TAB_PROMPT, SSH_HOSTS, MANUAL, ADD_SSH_HOST, NOTIFICATIONS, DOCK_CONFIG }
    @Volatile private var currentOverlay = OverlayState.NONE

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
    @Volatile private var dockHitboxes: Map<String, RectF> = emptyMap()

    data class AppInfo(val name: String, val packageName: String)
    data class ContactInfo(val name: String, val number: String)

    private val installedApps = mutableListOf<AppInfo>()
    private val cachedContacts = mutableListOf<ContactInfo>()

    private val favoriteApps = CopyOnWriteArrayList<String>()
    private val aliases = ConcurrentHashMap<String, String>()

    // --- FIREWALL & DOCK ENGINES ---
    private val allowedNotifApps = ConcurrentHashMap.newKeySet<String>()
    private val dockApps = CopyOnWriteArrayList<String>()

    private val savedSshHosts = CopyOnWriteArrayList<SshConfig>()
    private val builtinCmds = listOf("clear", "settings", "changelog", "s", "g", "alias", "dock", "notif", "mem", "bat", "vol", "brt", "bt", "ip", "pwd", "ls", "cd", "mkdir", "rm", "note", "read", "dnd", "wifi", "ping", "call", "fav")

    private val manualLines = listOf(
        "<bold>INTERFACE & NAVIGATION</bold>",
        "• Tabs: Double-tap to close. Tap [+] for new session.",
        "• Overlays: Tap [Favs] for Favorites, [*] for Settings.",
        "• Suggest: Start typing to auto-filter the command bar.",
        "• Scroll: Drag terminal to view history.",
        " ",
        "<bold>CORE COMMANDS</bold>",
        "• s/g [query]: System-wide web search.",
        "• call [name]: Instantly filter and dial local contacts.",
        "• dock -add/rm: Add app shortcuts to main icon dock.",
        "• notif -add/rm: Add app to Notification Firewall whitelist.",
        "• alias x=y: Map custom execution macros.",
        "• dnd: Toggle Android Do-Not-Disturb mode.",
        "• wifi: Open native Network panel.",
        "• changelog: View system update history.",
        " ",
        "<bold>FILE SYSTEM & UTILS</bold>",
        "• ls, cd, pwd, mkdir, rm: Local file navigation.",
        "• note [text]: Append strings to local note buffer.",
        "• read: Display local note buffer.",
        "• ping [ip]: Test ICMP packet latency."
    )

    private var scrollOffset = 0f
    private var forceScrollToBottom = true
    private var weatherString = "WTHR: SYNC..."

    // --- CACHING ENGINE ---
    private var lastHistorySize = -1
    private var cachedTotalHeight = 0f
    private val MAX_HISTORY_RENDER_LINES = 150

    // --- RAW HARDWARE SCROLL ENGINE ---
    private var commandScrollOffset = 0f
    private var maxCommandScroll = 0f
    private var lastCommandBarY = 0f
    private var commandBarStartX = 0f
    private var overlayScrollOffset = 0f
    private var settingsScrollOffset = 0f

    // --- BOOT & TOUCH ENGINES ---
    @Volatile private var isBooting = true
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    @Volatile private var isFingerDown = false

    private var crtSurfaceView: GLSurfaceView? = null
    private var shaderRenderer: CrtShaderRenderer? = null

    private val prefs by lazy { context.getSharedPreferences("CRTUI_PREFS", Context.MODE_PRIVATE) }

    private val cursorPulseRunnable = object : Runnable {
        override fun run() {
            requestUpdate()
            postDelayed(this, 32)
        }
    }

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val formattedLine = intent?.getStringExtra("formattedLine") ?: return
            val notifId = intent?.getIntExtra("notificationId", -1) ?: -1

            val isAllowed = allowedNotifApps.any { formattedLine.contains(it, ignoreCase = true) }
            if (isAllowed) {
                activeSession().handleNotification(notifId, formattedLine)
                scrollToBottom()
                requestUpdate()
            }
        }
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            return false // Handled manually to prevent leakages
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentState == AppState.TERMINAL && currentOverlay == OverlayState.NONE && !isBooting) {
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
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val file = File(context.filesDir, "lumon_notes.txt")
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                file.appendText("[$timestamp] CRASH DUMP: ${throwable.message}\n")
            } catch (e: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }

        if (sessions.isEmpty()) {
            sessions.add(TerminalSession("Local", SessionType.LOCAL))
        }

        loadInstalledApps()
        fetchWeather()
        loadPreferences()

        try {
            val aliasFile = File(context.filesDir, "aliases_cache.txt")
            if (aliasFile.exists()) {
                aliasFile.readLines().forEach { line ->
                    if (line.contains("=")) {
                        val key = line.substringBefore("=").trim()
                        val value = line.substringAfter("=").trim()
                        if (key.isNotEmpty()) aliases[key] = value
                    }
                }
            }

            val notifFile = File(context.filesDir, "notifs_cache.txt")
            if (notifFile.exists()) {
                notifFile.readLines().forEach {
                    if (it.isNotBlank()) allowedNotifApps.add(it.trim())
                }
            } else {
                allowedNotifApps.addAll(installedApps.map { it.name })
                saveNotifSettings()
            }

            val dockFile = File(context.filesDir, "dock_cache.txt")
            if (dockFile.exists()) {
                dockFile.readLines().forEach {
                    if (it.isNotBlank() && dockApps.size < 8) dockApps.add(it.trim())
                }
            }
        } catch (e: Exception) {}

        startForegroundDaemon()

        if (!hasBooted) {
            runBootSequence()
        } else {
            isBooting = false
            forceScrollToBottom = true
        }

        post(cursorPulseRunnable)
    }

    private fun loadPreferences() {
        val savedTheme = prefs.getInt("theme", TerminalTheme.LUMON.ordinal)
        currentTheme = TerminalTheme.values()[savedTheme]

        val savedFont = prefs.getInt("font", TerminalFont.GLASS_TTY.ordinal)
        currentFont = TerminalFont.values()[savedFont]

        currentTextSize = prefs.getFloat("textSize", 37f)
        currentGlowIntensity = prefs.getFloat("glowIntensity", 0.6f)
        currentBgOpacity = prefs.getFloat("bgOpacity", 0.2f)
        isScanlinesEnabled = prefs.getBoolean("scanlines", true)
        useCustomKeyboard = prefs.getBoolean("customKeyboard", true)
        showIconDock = prefs.getBoolean("showDock", true)

        applyFont()
        applyTheme(currentTheme)
    }

    private fun requestContactsSync() {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Thread {
                try {
                    val cursor = context.contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
                    cursor?.use {
                        val nameIdx = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numIdx = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val tempContacts = mutableListOf<ContactInfo>()
                        while (it.moveToNext()) {
                            tempContacts.add(ContactInfo(it.getString(nameIdx), it.getString(numIdx)))
                        }
                        cachedContacts.clear()
                        cachedContacts.addAll(tempContacts)
                    }
                } catch(e: Exception){}
            }.start()
        } else {
            (context as? android.app.Activity)?.requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 113)
        }
    }

    private fun saveNotifSettings() {
        Thread {
            try {
                val file = File(context.filesDir, "notifs_cache.txt")
                file.writeText(allowedNotifApps.joinToString("\n"))
            } catch (e: Exception) {}
        }.start()
    }

    private fun saveDockSettings() {
        Thread {
            try {
                val file = File(context.filesDir, "dock_cache.txt")
                file.writeText(dockApps.joinToString("\n"))
            } catch (e: Exception) {}
        }.start()
    }

    private fun startForegroundDaemon() {
        try {
            val serviceIntent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {}
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            Thread {
                try {
                    val file = File(context.filesDir, "session_cache.txt")
                    val activeHist = activeSession().history.takeLast(MAX_HISTORY_RENDER_LINES).joinToString("\n")
                    file.writeText(activeHist)
                } catch(e: Exception){}
            }.start()
        }
    }

    private fun applyFont() {
        val fontRes = if (currentFont == TerminalFont.GLASS_TTY) {
            R.font.glass_tty
        } else {
            R.font.pixelifysans
        }

        try {
            val tf = ResourcesCompat.getFont(context, fontRes)
            textPaint.typeface = tf
            boldTextPaint.typeface = tf
            promptTextPaint.typeface = tf
            invertedTextPaint.typeface = tf
        } catch (e: Exception) {}
        requestUpdate()
    }

    private fun runBootSequence() {
        Thread {
            val session = activeSession()
            if (session.history.isEmpty()) {
                session.clearHistory()
            }

            val logo = listOf(
                "                                    ",
                "                                    ",
                "                                    ",
                "      ..--------------------..      ",
                "    .##########################.    ",
                "   .############################.   ",
                "   .############+..+############.   ",
                "   .###########+.  .-###########.   ",
                "   .##########-.    .-##########.   ",
                "   .#########-.      .-#########.   ",
                "   .#########.        .#########.   ",
                "   .#########-        .#########.   ",
                "   .##########-.    .-##########.   ",
                "   .############################.   ",
                "    .##########################.    ",
                "      ..--------------------..      ",
                "                                    ",
                "                                    ",
                "                                    "
            )

            var appVersion = "0.9.1"
            try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                appVersion = pInfo.versionName ?: "0.9.1"
            } catch(e: Exception){}

            val steps = listOf(
                "EAGAN_OS v$appVersion ... INITIALIZING",
                "MEMORY CHECK ........................ OK",
                "LOADING MACRODATA MODULE ............ OK",
                "MOUNTING /dev/local ................. OK",
                "DECRYPTING SEVERANCE PROTOCOL ....... OK",
                "PRAISE KIER.",
                ""
            )

            fun bootSleep(ms: Long) {
                val end = System.currentTimeMillis() + ms
                while (System.currentTimeMillis() < end && isBooting) {
                    Thread.sleep(10)
                }
            }

            for (line in logo) {
                session.history.add("<bold>$line</bold>")
                post { scrollToBottom(); requestUpdate() }
                bootSleep(20)
            }

            bootSleep(150)

            for (line in steps) {
                session.history.add(line)
                post { scrollToBottom(); requestUpdate() }
                bootSleep((100..300).random().toLong())
            }

            bootSleep(500)
            isBooting = false
            hasBooted = true

            session.history.add("> Session established: ${session.name}")
            post {
                scrollToBottom()
                requestUpdate()
                if (!useCustomKeyboard) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(this, 0)
                }
            }
        }.start()
    }

    private fun fetchWeather() {
        Thread {
            try {
                val geoUrl = java.net.URL("https://ipapi.co/json/")
                val geoConn = geoUrl.openConnection() as java.net.HttpURLConnection
                geoConn.setRequestProperty("User-Agent", "CRTUI-Terminal/0.9")
                geoConn.connectTimeout = 5000
                geoConn.readTimeout = 5000

                val geoReader = BufferedReader(InputStreamReader(geoConn.inputStream))
                val geoResponse = geoReader.readText()
                geoReader.close()

                val latMatch = Regex("\"latitude\":\\s*([\\d.-]+)").find(geoResponse)
                val lonMatch = Regex("\"longitude\":\\s*([\\d.-]+)").find(geoResponse)
                val cityMatch = Regex("\"city\":\\s*\"([^\"]+)\"").find(geoResponse)

                val lat = latMatch?.groupValues?.get(1) ?: "37.98"
                val lon = lonMatch?.groupValues?.get(1) ?: "23.72"
                val city = cityMatch?.groupValues?.get(1)?.take(3)?.uppercase(Locale.US) ?: "LOC"

                val url = java.net.URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()

                val tempMatch = Regex("\"temperature\":\\s*([\\d.-]+)").find(response)
                val codeMatch = Regex("\"weathercode\":\\s*(\\d+)").find(response)

                val temp = tempMatch?.groupValues?.get(1) ?: "--"
                val code = codeMatch?.groupValues?.get(1)?.toIntOrNull() ?: -1

                val condition = when(code) {
                    0 -> "CLR"
                    1, 2, 3 -> "CLD"
                    45, 48 -> "FOG"
                    51, 53, 55, 56, 57 -> "DRIZ"
                    61, 63, 65, 66, 67 -> "RAIN"
                    71, 73, 75, 77 -> "SNOW"
                    80, 81, 82 -> "SHWR"
                    95, 96, 99 -> "STRM"
                    else -> "WTHR"
                }

                weatherString = "$city: $condition $temp°C"
                post { requestUpdate() }
            } catch (e: Exception) {
                weatherString = "WTHR: ERR"
                post { requestUpdate() }
            }
        }.start()
    }

    override fun onCheckIsTextEditor(): Boolean = true
    override fun performClick(): Boolean = super.performClick()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            touchDownX = event.x
            touchDownY = event.y
            lastTouchX = event.x
            lastTouchY = event.y
            isFingerDown = true

            if (isBooting) {
                isBooting = false
                hasBooted = true
                return true
            }
            if (currentState == AppState.TERMINAL) {
                requestFocus()
            }

            if (useCustomKeyboard && currentState == AppState.TERMINAL && currentOverlay == OverlayState.NONE) {
                if (keyboardEngine.handleTouch(event.x, event.y)) {
                    requestUpdate()
                    return true
                }
            }
        } else if (event.action == MotionEvent.ACTION_MOVE) {
            val dx = lastTouchX - event.x
            val dy = lastTouchY - event.y
            lastTouchX = event.x
            lastTouchY = event.y

            if (abs(dx) > 2f || abs(dy) > 2f) {
                if (!isBooting) {
                    // Highest priority: Overlay layer routing
                    if (currentOverlay != OverlayState.NONE) {
                        overlayScrollOffset += dy
                        requestUpdate()
                    }
                    // Secondary priority: Settings menu layer routing
                    else if (currentState == AppState.SETTINGS) {
                        settingsScrollOffset += dy
                        requestUpdate()
                    }
                    // Base priority: Terminal layer routing
                    else if (currentState == AppState.TERMINAL) {
                        val fontMetrics = textPaint.fontMetrics
                        val textHeight = fontMetrics.descent - fontMetrics.ascent

                        if (event.y > lastCommandBarY - textHeight * 1.5f && event.y < lastCommandBarY + textHeight * 1.5f && abs(dx) > abs(dy)) {
                            commandScrollOffset = (commandScrollOffset + dx).coerceIn(0f, maxCommandScroll)
                            requestUpdate()
                        } else {
                            if (abs(dy) > 3f) {
                                scrollOffset += dy
                                forceScrollToBottom = false
                                requestUpdate()
                            }
                        }
                    }
                }
            }

        } else if (event.action == MotionEvent.ACTION_UP) {
            isFingerDown = false
            val totalDx = abs(event.x - touchDownX)
            val totalDy = abs(event.y - touchDownY)

            if (totalDx < 30f && totalDy < 30f) {
                performClick()
                if (!isBooting) {
                    var tapped = false
                    if (currentOverlay != OverlayState.NONE) {
                        tapped = handleOverlayTap(event.x, event.y)
                    } else if (currentState == AppState.SETTINGS) {
                        tapped = handleSettingsTap(event.x, event.y)
                    } else if (currentState == AppState.TERMINAL) {
                        tapped = handleTap(event.x, event.y)
                    }

                    if (tapped) {
                        requestUpdate()
                        return true
                    }
                }
            }
        } else if (event.action == MotionEvent.ACTION_CANCEL) {
            isFingerDown = false
        }

        val handled = gestureDetector.onTouchEvent(event)
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
            TerminalTheme.CPC464 -> {
                currentTextColor = Color.parseColor("#FFFF00")
                promptTextPaint.color = Color.parseColor("#FFFF00")
                bgColorArray = floatArrayOf(0.0f, 0.0f, 0.6f)
                textColorArray = floatArrayOf(1.0f, 1.0f, 0.0f)
                invertedTextPaint.color = Color.parseColor("#0000AA")
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
        if (isOpen && !useCustomKeyboard) scrollToBottom()
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

    private fun getTotalTextHeight(maxWidth: Float, historySnapshot: List<String>): Float {
        var h = 0f
        for (line in historySnapshot) {
            val maxL = if (line.startsWith("<bold>![")) 2 else Int.MAX_VALUE
            h = drawAndMeasureWrappedText(null, line, 0f, h, maxWidth, maxLines = maxL).nextY
        }
        val session = activeSession()
        return drawAndMeasureWrappedText(null, "<prompt>${session.getPrompt()}</prompt>${session.buffer}", 0f, h, maxWidth).nextY
    }

    override fun onDraw(canvas: Canvas) {}

    fun renderContentForTexture(canvas: Canvas) {
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent

        // Ensure space is explicitly reserved if the dock is toggled on, even if empty, to prevent overlap
        var bottomLimit = height - paddingBottom - 20f
        if (showIconDock && !isBooting) {
            bottomLimit -= 100f
        }

        val tabsY = bottomLimit - 20f
        var commandBarY = tabsY - textHeight - 25f

        lastCommandBarY = commandBarY

        if (!isBooting) {
            if (!useCustomKeyboard) {
                val extraKeysY = commandBarY
                commandBarY = extraKeysY - textHeight - 25f
                lastCommandBarY = commandBarY
                if (currentState != AppState.SETTINGS) {
                    drawExtraKeysBar(canvas, extraKeysY)
                }
            } else {
                if (currentState == AppState.TERMINAL) {
                    val kbdBottom = tabsY - 40f
                    commandBarY = keyboardEngine.draw(canvas, paddingLeft + 10f, width - paddingRight - 10f, kbdBottom, textPaint, boldTextPaint, tabFramePaint)
                    lastCommandBarY = commandBarY
                }
            }
        }

        val headerY = paddingTop + max(40f, textHeight)
        val textTopLimit = headerY + 20f
        val textBottomLimit = if (isBooting) height - paddingBottom - 20f else commandBarY - textHeight - 20f

        if (currentBgOpacity > 0.0f) {
            try {
                val bgId = resources.getIdentifier("lumon_globe_logo", "drawable", context.packageName)
                if (bgId != 0) {
                    val bgDrawable = context.getDrawable(bgId)
                    if (bgDrawable != null) {
                        val size = min(width, height) * 0.7f
                        val left = (width - size) / 2f
                        val top = textTopLimit + (textBottomLimit - textTopLimit - size) / 2f
                        bgDrawable.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
                        bgDrawable.alpha = (currentBgOpacity * 255).toInt()
                        bgDrawable.colorFilter = PorterDuffColorFilter(currentTextColor, PorterDuff.Mode.SRC_IN)
                        bgDrawable.draw(canvas)
                    }
                }
            } catch(e: Exception) {}
        }

        if (currentState == AppState.SETTINGS && !isBooting) {
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

        val historySnapshot = session.history.takeLast(MAX_HISTORY_RENDER_LINES).toList()

        val totalHeight = getTotalTextHeight(maxWidth, historySnapshot)
        val maxScroll = max(0f, totalHeight - visibleHeight)

        if (forceScrollToBottom) {
            scrollOffset = maxScroll
        } else {
            scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
        }

        var currentY = textTopLimit - scrollOffset

        val timeStr = SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(Date())
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batStr = "PWR: $batteryPct%"

        val origSize = promptTextPaint.textSize
        var hSize = origSize

        var timeW = promptTextPaint.measureText(timeStr)
        var weatherW = promptTextPaint.measureText(weatherString)
        var batW = promptTextPaint.measureText(batStr)
        val maxHeaderW = width - paddingLeft - paddingRight - 40f

        while ((timeW + weatherW + batW + 40f) > maxHeaderW && hSize > 16f) {
            hSize -= 1f
            promptTextPaint.textSize = hSize
            timeW = promptTextPaint.measureText(timeStr)
            weatherW = promptTextPaint.measureText(weatherString)
            batW = promptTextPaint.measureText(batStr)
        }

        canvas.drawText(timeStr, startX, headerY, promptTextPaint)
        val centerWeatherX = (width / 2f) - (weatherW / 2f)
        canvas.drawText(weatherString, centerWeatherX, headerY, promptTextPaint)
        canvas.drawText(batStr, width - paddingRight - 40f - batW, headerY, promptTextPaint)

        promptTextPaint.textSize = origSize

        canvas.save()
        canvas.clipRect(0f, textTopLimit, width.toFloat(), textBottomLimit + fontMetrics.descent)

        for (line in historySnapshot) {
            val maxL = if (line.startsWith("<bold>![")) 2 else Int.MAX_VALUE
            if (currentY > textBottomLimit + textHeight) break

            val lineMetrics = drawAndMeasureWrappedText(null, line, startX, currentY, maxWidth, Float.MAX_VALUE, maxL)
            if (lineMetrics.nextY >= textTopLimit) {
                drawAndMeasureWrappedText(canvas, line, startX, currentY, maxWidth, textBottomLimit, maxL)
            }
            currentY = lineMetrics.nextY
        }

        if (!isBooting) {
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
        }
        canvas.restore()

        if (!isBooting) {
            if (currentOverlay != OverlayState.NONE) {
                drawOverlayWindow(canvas, textBottomLimit)
            }
            drawTabs(canvas, tabsY)
            drawCommandBar(canvas, commandBarY)
            if (showIconDock) {
                drawIconDock(canvas, height - paddingBottom - 30f)
            }
        }
    }

    private fun drawIconDock(canvas: Canvas, yPos: Float) {
        val newDockHitboxes = mutableMapOf<String, RectF>()

        canvas.drawLine(paddingLeft + 10f, yPos - 60f, width - paddingRight - 10f, yPos - 60f, tabFramePaint)

        if (dockApps.isEmpty()) {
            val emptyText = "[ DOCK EMPTY - USE 'dock -add' OR SETTINGS ]"
            val eX = (width / 2f) - (promptTextPaint.measureText(emptyText) / 2f)
            canvas.drawText(emptyText, eX, yPos - 15f, promptTextPaint)
            dockHitboxes = newDockHitboxes
            return
        }

        val iconSize = 60f
        val spacing = (width - paddingLeft - paddingRight) / max(dockApps.size, 1).toFloat()
        var currentX = paddingLeft + (spacing / 2f)

        for (appName in dockApps) {
            val lowerName = appName.lowercase(Locale.US)
            val drawableId = when {
                lowerName.contains("phone") || lowerName.contains("dial") || lowerName.contains("call") -> android.R.drawable.ic_menu_call
                lowerName.contains("message") || lowerName.contains("sms") || lowerName.contains("text") || lowerName.contains("whatsapp") || lowerName.contains("chat") -> android.R.drawable.sym_action_email
                lowerName.contains("chrome") || lowerName.contains("browser") || lowerName.contains("web") || lowerName.contains("internet") || lowerName.contains("brave") || lowerName.contains("firefox") || lowerName.contains("duck") -> android.R.drawable.ic_menu_mapmode
                lowerName.contains("camera") || lowerName.contains("photo") || lowerName.contains("gallery") -> android.R.drawable.ic_menu_camera
                lowerName.contains("setting") || lowerName.contains("config") -> android.R.drawable.ic_menu_manage
                lowerName.contains("mail") || lowerName.contains("gmail") -> android.R.drawable.sym_action_email
                lowerName.contains("map") || lowerName.contains("nav") || lowerName.contains("maps") -> android.R.drawable.ic_dialog_map
                lowerName.contains("music") || lowerName.contains("spotify") || lowerName.contains("audio") || lowerName.contains("podcast") -> android.R.drawable.ic_media_play
                lowerName.contains("calc") -> android.R.drawable.ic_menu_agenda
                lowerName.contains("clock") || lowerName.contains("alarm") || lowerName.contains("time") -> android.R.drawable.ic_menu_recent_history
                lowerName.contains("calendar") -> android.R.drawable.ic_menu_month
                lowerName.contains("contact") -> android.R.drawable.ic_menu_myplaces
                else -> android.R.drawable.ic_menu_sort_by_size
            }

            try {
                val d = context.getDrawable(drawableId)
                if (d != null) {
                    d.setBounds((currentX - (iconSize/2f)).toInt(), (yPos - iconSize).toInt(), (currentX + (iconSize/2f)).toInt(), yPos.toInt())
                    d.colorFilter = PorterDuffColorFilter(currentTextColor, PorterDuff.Mode.SRC_IN)
                    d.draw(canvas)
                }
            } catch(e: Exception){}

            newDockHitboxes["dock_launch_$appName"] = RectF(currentX - (spacing/2f), yPos - 80f, currentX + (spacing/2f), yPos)
            currentX += spacing
        }
        dockHitboxes = newDockHitboxes
    }

    private fun drawOverlayWindow(canvas: Canvas, maxBottomY: Float) {
        val newHitboxes = mutableMapOf<String, RectF>()
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val touchPadX = 40f
        val touchPadY = 15f

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
            OverlayState.MANUAL, OverlayState.NOTIFICATIONS, OverlayState.DOCK_CONFIG -> (maxBottomY - 20f) - topY
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
            OverlayState.MANUAL -> " USER MANUAL v0.9.1 "
            OverlayState.NOTIFICATIONS -> " FIREWALL: NOTIFICATIONS "
            OverlayState.DOCK_CONFIG -> " DOCK: APPLICATION SELECT "
            else -> " OVERLAY "
        }
        canvas.drawText(title, leftX + 20f, currentY, boldTextPaint)

        val closeText = "[X]"
        val closeWidth = textPaint.measureText(closeText)
        val closeX = rightX - closeWidth - 20f
        canvas.drawText(closeText, closeX, currentY, boldTextPaint)

        newHitboxes["close"] = RectF(closeX - touchPadX, currentY - textHeight - touchPadY, closeX + closeWidth + touchPadX, currentY + fontMetrics.descent + touchPadY)

        canvas.drawLine(leftX, currentY + fontMetrics.descent, rightX, currentY + fontMetrics.descent, textPaint)
        currentY += textHeight * 1.5f

        when (currentOverlay) {
            OverlayState.NOTIFICATIONS -> {
                val allowAll = "[ ALLOW ALL ]"
                val allowNone = "[ ALLOW NONE ]"
                canvas.drawText(allowAll, leftX + 20f, currentY, boldTextPaint)
                newHitboxes["allow_all_notifs"] = RectF(leftX + 20f - touchPadX, currentY - textHeight - touchPadY, leftX + 20f + textPaint.measureText(allowAll) + touchPadX, currentY + fontMetrics.descent + touchPadY)

                val noneX = leftX + 60f + textPaint.measureText(allowAll)
                canvas.drawText(allowNone, noneX, currentY, boldTextPaint)
                newHitboxes["allow_none_notifs"] = RectF(noneX - touchPadX, currentY - textHeight - touchPadY, noneX + textPaint.measureText(allowNone) + touchPadX, currentY + fontMetrics.descent + touchPadY)

                currentY += textHeight * 1.5f
                canvas.drawLine(leftX, currentY - textHeight*0.5f, rightX, currentY - textHeight*0.5f, textPaint)

                val itemSpacing = textHeight * 2.0f
                val maxScroll = max(0f, (installedApps.size * itemSpacing) - (bottomY - currentY))
                overlayScrollOffset = overlayScrollOffset.coerceIn(0f, maxScroll)

                canvas.save()
                canvas.clipRect(leftX + 5f, currentY - textHeight, rightX - 5f, bottomY - 5f)
                var listY = currentY - overlayScrollOffset

                for (app in installedApps) {
                    if (listY > bottomY + itemSpacing) {
                        listY += itemSpacing
                        continue
                    }
                    if (listY > currentY - textHeight) {
                        val appNameStr = "  ${app.name}"
                        val isAllowed = allowedNotifApps.contains(app.name)
                        val boxText = if (isAllowed) "[X]" else "[ ]"

                        val paintToUse = if (isAllowed) boldTextPaint else textPaint
                        canvas.drawText(appNameStr, leftX + 20f, listY, paintToUse)

                        val boxX = rightX - 40f - textPaint.measureText(boxText)
                        canvas.drawText(boxText, boxX, listY, paintToUse)

                        newHitboxes["toggle_notif_${app.name}"] = RectF(leftX, listY - textHeight - 5f, rightX, listY + fontMetrics.descent + 5f)
                    }
                    listY += itemSpacing
                }
                canvas.restore()
            }
            OverlayState.DOCK_CONFIG -> {
                val clearDock = "[ CLEAR DOCK ]"
                canvas.drawText(clearDock, leftX + 20f, currentY, boldTextPaint)
                newHitboxes["clear_dock"] = RectF(leftX + 20f - touchPadX, currentY - textHeight - touchPadY, leftX + 20f + textPaint.measureText(clearDock) + touchPadX, currentY + fontMetrics.descent + touchPadY)

                val countText = "(${dockApps.size}/8 PINNED)"
                canvas.drawText(countText, rightX - textPaint.measureText(countText) - 20f, currentY, textPaint)

                currentY += textHeight * 1.5f
                canvas.drawLine(leftX, currentY - textHeight*0.5f, rightX, currentY - textHeight*0.5f, textPaint)

                val itemSpacing = textHeight * 2.0f
                val maxScroll = max(0f, (installedApps.size * itemSpacing) - (bottomY - currentY))
                overlayScrollOffset = overlayScrollOffset.coerceIn(0f, maxScroll)

                canvas.save()
                canvas.clipRect(leftX + 5f, currentY - textHeight, rightX - 5f, bottomY - 5f)
                var listY = currentY - overlayScrollOffset

                for (app in installedApps) {
                    if (listY > bottomY + itemSpacing) {
                        listY += itemSpacing
                        continue
                    }
                    if (listY > currentY - textHeight) {
                        val appNameStr = "  ${app.name}"
                        val isPinned = dockApps.contains(app.name)
                        val boxText = if (isPinned) "[X]" else "[ ]"

                        val paintToUse = if (isPinned) boldTextPaint else textPaint
                        canvas.drawText(appNameStr, leftX + 20f, listY, paintToUse)

                        val boxX = rightX - 40f - textPaint.measureText(boxText)
                        canvas.drawText(boxText, boxX, listY, paintToUse)

                        newHitboxes["toggle_dockapp_${app.name}"] = RectF(leftX, listY - textHeight - 5f, rightX, listY + fontMetrics.descent + 5f)
                    }
                    listY += itemSpacing
                }
                canvas.restore()
            }
            OverlayState.FAVORITES -> {
                if (favoriteApps.isEmpty()) {
                    canvas.drawText(" (No favorites. Use 'fav -add <app>')", leftX + 20f, currentY, textPaint)
                } else {
                    for (app in favoriteApps) {
                        if (currentY > bottomY - textHeight) break
                        canvas.drawText("  $app", leftX + 20f, currentY, textPaint)
                        newHitboxes["launch_$app"] = RectF(leftX, currentY - textHeight - 10f, rightX, currentY + fontMetrics.descent + 10f)
                        currentY += textHeight * 1.5f
                    }
                }
            }
            OverlayState.NEW_TAB_PROMPT -> {
                val optLocal = "  [ LOCAL TERMINAL ]"
                canvas.drawText(optLocal, leftX + 20f, currentY, textPaint)
                newHitboxes["new_local"] = RectF(leftX, currentY - textHeight - 15f, rightX, currentY + fontMetrics.descent + 15f)
                currentY += textHeight * 1.5f

                val optRemote = "  [ REMOTE (SSH) TERMINAL ]"
                canvas.drawText(optRemote, leftX + 20f, currentY, textPaint)
                newHitboxes["new_remote"] = RectF(leftX, currentY - textHeight - 15f, rightX, currentY + fontMetrics.descent + 15f)
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

                        newHitboxes["del_ssh_$index"] = RectF(delX - 20f, currentY - textHeight - 15f, delX + textPaint.measureText(delBtn) + 20f, currentY + fontMetrics.descent + 15f)
                        newHitboxes["edit_ssh_$index"] = RectF(editX - 20f, currentY - textHeight - 15f, editX + textPaint.measureText(editBtn) + 20f, currentY + fontMetrics.descent + 15f)
                        newHitboxes["connect_ssh_$index"] = RectF(leftX, currentY - textHeight - 15f, editX - 10f, currentY + fontMetrics.descent + 15f)

                        currentY += textHeight * 1.5f
                    }
                }

                currentY += textHeight * 0.5f
                val addText = "  [+ ADD NEW HOST]"
                canvas.drawText(addText, leftX + 20f, currentY, boldTextPaint)
                newHitboxes["add_ssh"] = RectF(leftX, currentY - textHeight - 15f, rightX, currentY + fontMetrics.descent + 15f)
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
                    newHitboxes["ssh_field_$i"] = RectF(leftX, currentY - textHeight - 15f, rightX, currentY + fontMetrics.descent + 15f)
                    currentY += textHeight * 1.5f
                }

                currentY += textHeight * 0.5f
                val saveBtn = "  [ SAVE ]"
                canvas.drawText(saveBtn, leftX + 20f, currentY, boldTextPaint)
                newHitboxes["ssh_save"] = RectF(leftX, currentY - textHeight - 15f, leftX + 40f + textPaint.measureText(saveBtn), currentY + fontMetrics.descent + 15f)

                val cancelBtn = "  [ CANCEL ]"
                val cancelX = leftX + 60f + textPaint.measureText(saveBtn)
                canvas.drawText(cancelBtn, cancelX, currentY, boldTextPaint)
                newHitboxes["ssh_cancel"] = RectF(cancelX - 20f, currentY - textHeight - 15f, rightX, currentY + fontMetrics.descent + 15f)
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

        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val touchPadX = 50f
        val touchPadY = 20f
        val lineSpacing = textHeight * 2.5f

        val totalSettingsHeight = (18 + aliases.size) * lineSpacing + 200f
        val visibleArea = height - paddingTop - paddingBottom
        val maxScroll = max(0f, totalSettingsHeight - visibleArea)
        settingsScrollOffset = settingsScrollOffset.coerceIn(0f, maxScroll)

        val startX = paddingLeft + 40f
        var currentY = paddingTop + 100f - settingsScrollOffset

        canvas.drawText("MACRODATA REFINEMENT :: PREFERENCES", startX, currentY, boldTextPaint)
        currentY += lineSpacing

        val themeLabel = "Color Theme: [${currentTheme.name}]  "
        canvas.drawText(themeLabel, startX, currentY, textPaint)
        val cycleBtn = "[ CYCLE ]"
        val cycleX = startX + textPaint.measureText(themeLabel)
        canvas.drawText(cycleBtn, cycleX, currentY, boldTextPaint)
        newSettings["theme_cycle"] = RectF(startX, currentY - textHeight - 15f, width.toFloat(), currentY + fontMetrics.descent + 15f)
        currentY += lineSpacing

        val fontTypeLabel = "Typeface: [${currentFont.name}]  "
        canvas.drawText(fontTypeLabel, startX, currentY, textPaint)
        val cycleFontBtn = "[ CYCLE ]"
        val cycleFontX = startX + textPaint.measureText(fontTypeLabel)
        canvas.drawText(cycleFontBtn, cycleFontX, currentY, boldTextPaint)
        newSettings["font_cycle"] = RectF(startX, currentY - textHeight - 15f, width.toFloat(), currentY + fontMetrics.descent + 15f)
        currentY += lineSpacing

        val kbdLabel = "Keyboard: "
        canvas.drawText(kbdLabel, startX, currentY, textPaint)
        val kbdBtn = if (useCustomKeyboard) "[ NATIVE ]" else "[ SYSTEM ]"
        val kbdX = startX + textPaint.measureText(kbdLabel)
        canvas.drawText(kbdBtn, kbdX, currentY, boldTextPaint)
        newSettings["toggle_keyboard"] = RectF(startX, currentY - textHeight - 15f, width.toFloat(), currentY + fontMetrics.descent + 15f)
        currentY += lineSpacing

        val notifLabel = "Notifications: "
        canvas.drawText(notifLabel, startX, currentY, textPaint)
        val notifBtn = "[ CONFIGURE ]"
        val notifX = startX + textPaint.measureText(notifLabel)
        canvas.drawText(notifBtn, notifX, currentY, boldTextPaint)
        newSettings["configure_notifs"] = RectF(startX, currentY - textHeight - 15f, width.toFloat(), currentY + fontMetrics.descent + 15f)
        currentY += lineSpacing

        val dockLabel = "Icon Dock: "
        canvas.drawText(dockLabel, startX, currentY, textPaint)
        val dockConfigBtn = "[ CONFIGURE ]"
        val dockConfigX = startX + textPaint.measureText(dockLabel)
        canvas.drawText(dockConfigBtn, dockConfigX, currentY, boldTextPaint)
        newSettings["configure_dock"] = RectF(startX, currentY - textHeight - 15f, dockConfigX + textPaint.measureText(dockConfigBtn) + 15f, currentY + fontMetrics.descent + 15f)

        val dockShowBtn = if (showIconDock) "[ HIDE ]" else "[ SHOW ]"
        val dockShowX = dockConfigX + textPaint.measureText(dockConfigBtn) + 30f
        canvas.drawText(dockShowBtn, dockShowX, currentY, boldTextPaint)
        newSettings["toggle_dock"] = RectF(dockShowX - 15f, currentY - textHeight - 15f, width.toFloat(), currentY + fontMetrics.descent + 15f)
        currentY += lineSpacing

        val bgLabel = String.format(Locale.US, "Background Opacity: %.1f  ", currentBgOpacity)
        canvas.drawText(bgLabel, startX, currentY, textPaint)
        var btempX = startX + textPaint.measureText(bgLabel)

        val bMinusBtn = "[-] "
        canvas.drawText(bMinusBtn, btempX, currentY, boldTextPaint)
        newSettings["bg_minus"] = RectF(btempX - 20f, currentY - textHeight - 15f, btempX + textPaint.measureText(bMinusBtn) + 20f, currentY + fontMetrics.descent + 15f)
        btempX += textPaint.measureText(bMinusBtn)

        val bPlusBtn = "[+]"
        canvas.drawText(bPlusBtn, btempX, currentY, boldTextPaint)
        newSettings["bg_plus"] = RectF(btempX - 20f, currentY - textHeight - 15f, btempX + textPaint.measureText(bPlusBtn) + 20f, currentY + fontMetrics.descent + 15f)
        currentY += lineSpacing

        val glowLabel = String.format(Locale.US, "Glow Intensity: %.1f  ", currentGlowIntensity)
        canvas.drawText(glowLabel, startX, currentY, textPaint)
        var tempX = startX + textPaint.measureText(glowLabel)

        val gMinusBtn = "[-] "
        canvas.drawText(gMinusBtn, tempX, currentY, boldTextPaint)
        newSettings["glow_minus"] = RectF(tempX - 20f, currentY - textHeight - 15f, tempX + textPaint.measureText(gMinusBtn) + 20f, currentY + fontMetrics.descent + 15f)
        tempX += textPaint.measureText(gMinusBtn)

        val gPlusBtn = "[+]"
        canvas.drawText(gPlusBtn, tempX, currentY, boldTextPaint)
        newSettings["glow_plus"] = RectF(tempX - 20f, currentY - textHeight - 15f, tempX + textPaint.measureText(gPlusBtn) + 20f, currentY + fontMetrics.descent + 15f)
        currentY += lineSpacing

        val scanlineLabel = "Scanlines: "
        canvas.drawText(scanlineLabel, startX, currentY, textPaint)
        val scanBtn = if (isScanlinesEnabled) "[ ENABLED ]" else "[ DISABLED ]"
        val scanX = startX + textPaint.measureText(scanlineLabel)
        canvas.drawText(scanBtn, scanX, currentY, boldTextPaint)
        newSettings["toggle_scanlines"] = RectF(startX, currentY - textHeight - 15f, width.toFloat(), currentY + fontMetrics.descent + 15f)
        currentY += lineSpacing

        val fontLabel = "Font Size: ${currentTextSize.toInt()}  "
        canvas.drawText(fontLabel, startX, currentY, textPaint)
        tempX = startX + textPaint.measureText(fontLabel)

        val minusBtn = "[-] "
        canvas.drawText(minusBtn, tempX, currentY, boldTextPaint)
        newSettings["font_minus"] = RectF(tempX - 20f, currentY - textHeight - 15f, tempX + textPaint.measureText(minusBtn) + 20f, currentY + fontMetrics.descent + 15f)
        tempX += textPaint.measureText(minusBtn)

        val plusBtn = "[+]"
        canvas.drawText(plusBtn, tempX, currentY, boldTextPaint)
        newSettings["font_plus"] = RectF(tempX - 20f, currentY - textHeight - 15f, tempX + textPaint.measureText(plusBtn) + 20f, currentY + fontMetrics.descent + 15f)
        currentY += lineSpacing

        val manualBtn = "[ VIEW SYSTEM MANUAL ]"
        canvas.drawText(manualBtn, startX, currentY, boldTextPaint)
        newSettings["open_manual"] = RectF(startX, currentY - textHeight - 15f, width.toFloat(), currentY + fontMetrics.descent + 15f)
        currentY += lineSpacing

        val exportBtn = "[ EXPORT SETTINGS ]"
        canvas.drawText(exportBtn, startX, currentY, boldTextPaint)
        newSettings["export"] = RectF(startX, currentY - textHeight - 15f, width.toFloat(), currentY + fontMetrics.descent + 15f)
        currentY += lineSpacing

        canvas.drawText("--- MACRO ALIASES ---", startX, currentY, boldTextPaint)
        currentY += lineSpacing

        if (aliases.isEmpty()) {
            canvas.drawText("(No aliases defined)", startX, currentY, textPaint)
            currentY += lineSpacing
        } else {
            for ((key, cmd) in aliases) {
                val aliasText = "$key -> $cmd  "
                canvas.drawText(aliasText, startX, currentY, textPaint)

                val delX = startX + textPaint.measureText(aliasText)
                val delBtn = "[X]"
                canvas.drawText(delBtn, delX, currentY, boldTextPaint)
                newSettings["delete_alias_$key"] = RectF(delX - 20f, currentY - textHeight - 15f, delX + textPaint.measureText(delBtn) + 20f, currentY + fontMetrics.descent + 15f)

                currentY += lineSpacing
            }
        }

        val addBtn = "[+ ADD ALIAS]"
        canvas.drawText(addBtn, startX, currentY, boldTextPaint)
        newSettings["add_alias"] = RectF(startX, currentY - textHeight - 15f, width.toFloat(), currentY + fontMetrics.descent + 15f)
        currentY += lineSpacing

        val repoText = "VERSION v0.9.1 :: [ GitHub: exolon/CRTUI ]"
        canvas.drawText(repoText, startX, currentY, promptTextPaint)
        newSettings["github_link"] = RectF(startX, currentY - textHeight - 15f, width.toFloat(), currentY + fontMetrics.descent + 15f)
        currentY += lineSpacing

        val exitBtn = "[ EXIT SETTINGS ]"
        canvas.drawText(exitBtn, startX, currentY, boldTextPaint)
        newSettings["exit"] = RectF(startX, currentY - textHeight - 15f, width.toFloat(), currentY + fontMetrics.descent + 15f)

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

        val q = activeSession().buffer.lowercase(Locale.US)

        val itemsToDraw = if (q.isNotEmpty()) {
            if (q.startsWith("call ")) {
                val cq = q.substringAfter("call ").trim()
                if (cq.isNotEmpty()) {
                    cachedContacts.filter { it.name.lowercase(Locale.US).contains(cq) }.map { it.name }.distinct().take(20)
                } else {
                    cachedContacts.map { it.name }.distinct().take(20)
                }
            } else {
                val appMatches = installedApps.filter { it.name.lowercase(Locale.US).contains(q) }.map { it.name }
                val aliasMatches = aliases.keys.filter { it.lowercase(Locale.US).contains(q) }
                val cmdMatches = builtinCmds.filter { it.contains(q) }
                (appMatches + aliasMatches + cmdMatches).take(20)
            }
        } else {
            when (currentCommandMode) {
                CommandMode.NORMAL -> listOf("Apps", "Favs", "Alias", "Sys", "Net")
                CommandMode.APPS -> installedApps.take(50).map { it.name }
                CommandMode.ALIASES -> aliases.keys.take(50).toList()
                CommandMode.SYS -> listOf("MEM", "BAT", "DND", "VOL", "BRT")
                CommandMode.NET -> listOf("IP", "WIFI", "BT", "PING")
            }
        }

        val rowPrefix = if (q.isNotEmpty()) "SUGGEST: " else {
            when (currentCommandMode) {
                CommandMode.NORMAL -> "CMD: "
                CommandMode.APPS -> "APP: "
                CommandMode.ALIASES -> "ALS: "
                CommandMode.SYS -> "SYS: "
                CommandMode.NET -> "NET: "
            }
        }

        canvas.drawText(rowPrefix, currentX, commandBarY, promptTextPaint)
        currentX += textPaint.measureText(rowPrefix)

        commandBarStartX = currentX

        var totalItemsWidth = 0f
        for (item in itemsToDraw) {
            totalItemsWidth += textPaint.measureText("[$item]") + 25f
        }
        val visibleWidth = width - paddingRight - commandBarStartX
        maxCommandScroll = max(0f, totalItemsWidth - visibleWidth)
        commandScrollOffset = commandScrollOffset.coerceIn(0f, maxCommandScroll)

        val visibleRect = RectF(commandBarStartX, commandBarY - textHeight - touchPad, width - paddingRight.toFloat(), commandBarY + fontMetrics.descent + touchPad)

        canvas.save()
        canvas.clipRect(visibleRect)

        currentX -= commandScrollOffset

        for (item in itemsToDraw) {
            val displayText = "[$item]"
            val textWidth = textPaint.measureText(displayText)
            val rect = RectF(currentX - touchPad, commandBarY - textHeight - touchPad, currentX + textWidth + touchPad, commandBarY + fontMetrics.descent + touchPad)

            if (RectF.intersects(visibleRect, rect)) {
                val clampedRect = RectF(
                    max(visibleRect.left, rect.left),
                    max(visibleRect.top, rect.top),
                    min(visibleRect.right, rect.right),
                    min(visibleRect.bottom, rect.bottom)
                )
                newCmds[item] = clampedRect
                canvas.drawText(displayText, currentX, commandBarY, textPaint)
            }
            currentX += textWidth + 25f
        }
        canvas.restore()

        commandHitboxes = newCmds
    }

    private fun injectChar(text: String) {
        if (currentOverlay == OverlayState.ADD_SSH_HOST) {
            sshFormValues[sshFormActiveField] += text
        } else {
            val session = activeSession()
            val left = session.buffer.substring(0, session.cursor)
            val right = session.buffer.substring(session.cursor)
            session.buffer = left + text + right
            session.cursor += text.length

            commandScrollOffset = 0f

            if (session.buffer.lowercase(Locale.US).startsWith("call ")) {
                requestContactsSync()
            }

            scrollToBottom()
        }
    }

    private fun deleteLastChar() {
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
                if (session.buffer.isEmpty() && currentCommandMode != CommandMode.NORMAL) {
                    currentCommandMode = CommandMode.NORMAL
                }
                commandScrollOffset = 0f
                scrollToBottom()
            }
        }
    }

    private fun submitBuffer() {
        if (currentOverlay == OverlayState.ADD_SSH_HOST) {
            sshFormActiveField = (sshFormActiveField + 1) % 5
        } else {
            val session = activeSession()
            if (session.buffer.isNotBlank()) {
                if (currentCommandMode == CommandMode.APPS) {
                    val filterText = session.buffer.lowercase().trim()
                    var matches = installedApps.filter { it.name.lowercase() == filterText }
                    if (matches.isEmpty()) {
                        matches = installedApps.filter { it.name.lowercase().startsWith(filterText) }
                    }
                    if (matches.isEmpty()) {
                        matches = installedApps.filter { it.name.lowercase().contains(filterText) }
                    }

                    session.history.add(session.getPrompt() + session.buffer)
                    if (matches.size == 1) {
                        launchApp(matches[0].name)
                        session.buffer = ""
                        session.cursor = 0
                        currentCommandMode = CommandMode.NORMAL
                    } else if (matches.isEmpty()) {
                        session.history.add("> App not found: $filterText")
                        session.buffer = ""
                        session.cursor = 0
                        currentCommandMode = CommandMode.NORMAL
                    } else {
                        session.history.add(matches.joinToString("  ") { "<bold>${it.name}</bold>" })
                    }
                } else if (currentCommandMode == CommandMode.ALIASES) {
                    val filterText = session.buffer.lowercase()
                    val topAlias = aliases.keys.find { it.lowercase().contains(filterText) }
                    if (topAlias != null) {
                        session.history.add(session.getPrompt() + topAlias)
                        CommandEngine.process(context, session, aliases[topAlias]!!, installedApps, aliases, favoriteApps, allowedNotifApps, dockApps, cachedContacts, ::launchApp, { currentState = AppState.SETTINGS; val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager; imm.hideSoftInputFromWindow(windowToken, 0) }, ::scrollToBottom, ::requestUpdate, ::saveNotifSettings, ::saveDockSettings) { post(it) }
                        currentCommandMode = CommandMode.NORMAL
                    }
                    session.buffer = ""
                    session.cursor = 0
                } else {
                    if (session.type == SessionType.LOCAL) {
                        session.history.add(session.getPrompt() + session.buffer)
                    }
                    val shouldClear = CommandEngine.process(context, session, session.buffer, installedApps, aliases, favoriteApps, allowedNotifApps, dockApps, cachedContacts, ::launchApp, { currentState = AppState.SETTINGS; val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager; imm.hideSoftInputFromWindow(windowToken, 0) }, ::scrollToBottom, ::requestUpdate, ::saveNotifSettings, ::saveDockSettings) { post(it) }
                    if (shouldClear) {
                        session.buffer = ""
                        session.cursor = 0
                    }
                }
                scrollToBottom()
            }
        }
    }

    private fun handleCustomKey(keyId: String) {
        val session = activeSession()
        when (keyId) {
            "ENTR" -> submitBuffer()
            "DEL" -> deleteLastChar()
            "SPACE" -> injectChar(" ")
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
                scrollOffset += 200f
                forceScrollToBottom = false
            }
            "v" -> {
                scrollOffset = (scrollOffset - 200f).coerceAtLeast(0f)
                forceScrollToBottom = false
            }
            else -> injectChar(keyId)
        }
        requestUpdate()
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
                injectChar("    ")
            }
        } else {
            injectChar("    ")
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
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
            data.appendLine("Font: ${currentFont.name}")
            data.appendLine("FontSize: $currentTextSize")
            data.appendLine("GlowIntensity: $currentGlowIntensity")
            data.appendLine("Scanlines: $isScanlinesEnabled")
            data.appendLine("Keyboard: ${if (useCustomKeyboard) "NATIVE" else "SYSTEM"}")
            data.appendLine("--- ALIASES ---")
            for ((key, value) in aliases) {
                data.appendLine("$key=$value")
            }
            data.appendLine("--- FAVORITES ---")
            for (app in favoriteApps) {
                data.appendLine(app)
            }
            data.appendLine("--- SSH HOSTS ---")
            for (host in savedSshHosts) {
                data.appendLine("${host.name},${host.user},${host.host},${host.port}")
            }

            exportFile.writeText(data.toString())
            activeSession().history.add("> Exported to Downloads/lumon_settings_export.txt")
            scrollToBottom()
        } catch (e: Exception) {
            activeSession().history.add("> Export Failed. Details: ${e.message}")
            scrollToBottom()
        }
    }

    private fun handleOverlayTap(touchX: Float, touchY: Float): Boolean {
        var actionTaken = false
        for ((action, rect) in overlayHitboxes) {
            if (rect.contains(touchX, touchY)) {
                when (currentOverlay) {
                    OverlayState.DOCK_CONFIG -> {
                        if (action == "close") {
                            currentOverlay = OverlayState.NONE
                        } else if (action == "clear_dock") {
                            dockApps.clear()
                            saveDockSettings()
                        } else if (action.startsWith("toggle_dockapp_")) {
                            val appName = action.removePrefix("toggle_dockapp_")
                            if (dockApps.contains(appName)) {
                                dockApps.remove(appName)
                            } else {
                                if (dockApps.size < 8) {
                                    dockApps.add(appName)
                                }
                            }
                            saveDockSettings()
                        }
                    }
                    OverlayState.NOTIFICATIONS -> {
                        if (action == "close") {
                            currentOverlay = OverlayState.NONE
                        } else if (action == "allow_all_notifs") {
                            allowedNotifApps.addAll(installedApps.map { it.name })
                            saveNotifSettings()
                        } else if (action == "allow_none_notifs") {
                            allowedNotifApps.clear()
                            saveNotifSettings()
                        } else if (action.startsWith("toggle_notif_")) {
                            val appName = action.removePrefix("toggle_notif_")
                            if (allowedNotifApps.contains(appName)) {
                                allowedNotifApps.remove(appName)
                            } else {
                                allowedNotifApps.add(appName)
                            }
                            saveNotifSettings()
                        }
                    }
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
                            if (!useCustomKeyboard) {
                                post {
                                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    imm.showSoftInput(this, 0)
                                }
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
                                if (!useCustomKeyboard) {
                                    post {
                                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                        imm.showSoftInput(this, 0)
                                    }
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
                            if (!useCustomKeyboard) {
                                post {
                                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    imm.showSoftInput(this, 0)
                                }
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
                actionTaken = true
                break
            }
        }
        if (actionTaken) {
            scrollToBottom()
            requestUpdate()
        }
        return actionTaken
    }

    private fun handleSettingsTap(touchX: Float, touchY: Float): Boolean {
        for ((action, rect) in settingsHitboxes) {
            if (rect.contains(touchX, touchY)) {
                when {
                    action == "theme_cycle" -> {
                        val nextTheme = TerminalTheme.values()[(currentTheme.ordinal + 1) % TerminalTheme.values().size]
                        applyTheme(nextTheme)
                        prefs.edit().putInt("theme", nextTheme.ordinal).apply()
                    }
                    action == "font_cycle" -> {
                        currentFont = if (currentFont == TerminalFont.GLASS_TTY) TerminalFont.PIXELIFY else TerminalFont.GLASS_TTY
                        applyFont()
                        prefs.edit().putInt("font", currentFont.ordinal).apply()
                    }
                    action == "toggle_keyboard" -> {
                        useCustomKeyboard = !useCustomKeyboard
                        prefs.edit().putBoolean("customKeyboard", useCustomKeyboard).apply()
                    }
                    action == "configure_notifs" -> {
                        overlayScrollOffset = 0f
                        currentOverlay = OverlayState.NOTIFICATIONS
                    }
                    action == "configure_dock" -> {
                        overlayScrollOffset = 0f
                        currentOverlay = OverlayState.DOCK_CONFIG
                    }
                    action == "toggle_dock" -> {
                        showIconDock = !showIconDock
                        prefs.edit().putBoolean("showDock", showIconDock).apply()
                    }
                    action == "bg_minus" -> {
                        if (currentBgOpacity > 0.0f) {
                            currentBgOpacity = max(0f, currentBgOpacity - 0.1f)
                            prefs.edit().putFloat("bgOpacity", currentBgOpacity).apply()
                        }
                    }
                    action == "bg_plus" -> {
                        if (currentBgOpacity < 1.0f) {
                            currentBgOpacity = min(1.0f, currentBgOpacity + 0.1f)
                            prefs.edit().putFloat("bgOpacity", currentBgOpacity).apply()
                        }
                    }
                    action == "glow_minus" -> {
                        if (currentGlowIntensity > 0.0f) {
                            currentGlowIntensity = max(0f, currentGlowIntensity - 0.1f)
                            shaderRenderer?.glowIntensity = currentGlowIntensity
                            prefs.edit().putFloat("glowIntensity", currentGlowIntensity).apply()
                        }
                    }
                    action == "glow_plus" -> {
                        if (currentGlowIntensity < 2.0f) {
                            currentGlowIntensity = min(2.0f, currentGlowIntensity + 0.1f)
                            shaderRenderer?.glowIntensity = currentGlowIntensity
                            prefs.edit().putFloat("glowIntensity", currentGlowIntensity).apply()
                        }
                    }
                    action == "toggle_scanlines" -> {
                        isScanlinesEnabled = !isScanlinesEnabled
                        shaderRenderer?.isScanlinesEnabled = isScanlinesEnabled
                        prefs.edit().putBoolean("scanlines", isScanlinesEnabled).apply()
                    }
                    action == "font_minus" -> {
                        if (currentTextSize > 20f) {
                            currentTextSize -= 2f
                            updatePaintSizes()
                            prefs.edit().putFloat("textSize", currentTextSize).apply()
                        }
                    }
                    action == "font_plus" -> {
                        if (currentTextSize < 80f) {
                            currentTextSize += 2f
                            updatePaintSizes()
                            prefs.edit().putFloat("textSize", currentTextSize).apply()
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
                        if (!useCustomKeyboard) {
                            post {
                                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                imm.showSoftInput(this, 0)
                            }
                        }
                    }
                    action == "exit" -> {
                        currentState = AppState.TERMINAL
                        activeSession().history.add("> Settings saved.")
                        scrollToBottom()
                    }
                }
                requestUpdate()
                return true
            }
        }
        return false
    }

    private fun handleTap(touchX: Float, touchY: Float): Boolean {
        if (settingsTabHitbox?.contains(touchX, touchY) == true) {
            currentState = AppState.SETTINGS
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(windowToken, 0)
            requestUpdate()
            return true
        }

        if (newTabHitbox?.contains(touchX, touchY) == true) {
            currentOverlay = OverlayState.NEW_TAB_PROMPT
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(windowToken, 0)
            requestUpdate()
            return true
        }

        if (showIconDock && dockApps.isNotEmpty()) {
            for ((action, rect) in dockHitboxes) {
                if (rect.contains(touchX, touchY)) {
                    if (action.startsWith("dock_launch_")) {
                        launchApp(action.removePrefix("dock_launch_"))
                        return true
                    }
                }
            }
        }

        val session = activeSession()

        if (!useCustomKeyboard) {
            for ((key, rect) in extraKeyHitboxes) {
                if (rect.contains(touchX, touchY)) {
                    handleCustomKey(key)
                    return true
                }
            }
        }

        for ((index, rect) in tabHitboxes) {
            if (rect.contains(touchX, touchY)) {
                activeTabIndex = index
                scrollToBottom()
                requestUpdate()
                return true
            }
        }

        for ((commandString, rect) in commandHitboxes) {
            if (rect.contains(touchX, touchY)) {
                if (session.buffer.lowercase(Locale.US).startsWith("call ")) {
                    val matchingContacts = cachedContacts.filter { it.name == commandString }
                    if (matchingContacts.isNotEmpty()) {
                        if (matchingContacts.size == 1) {
                            try {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${matchingContacts[0].number}"))
                                context.startActivity(intent)
                                session.history.add("> Calling ${matchingContacts[0].name}...")
                            } catch(e: Exception){}
                        } else {
                            val numbers = matchingContacts.map { it.number }.toTypedArray()
                            android.app.AlertDialog.Builder(context)
                                .setTitle("Select number for $commandString")
                                .setItems(numbers) { _, which ->
                                    try {
                                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${numbers[which]}"))
                                        context.startActivity(intent)
                                        session.history.add("> Calling $commandString...")
                                        requestUpdate()
                                    } catch(e: Exception){}
                                }
                                .show()
                        }
                        session.buffer = ""
                        session.cursor = 0
                    }
                } else if (session.buffer.isNotEmpty()) {
                    if (installedApps.any { it.name == commandString }) {
                        launchApp(commandString)
                        session.buffer = ""
                        session.cursor = 0
                    } else {
                        session.buffer = commandString + " "
                        session.cursor = session.buffer.length
                    }
                } else {
                    when {
                        currentCommandMode == CommandMode.NORMAL && commandString == "Apps" -> {
                            currentCommandMode = CommandMode.APPS
                            commandScrollOffset = 0f
                        }
                        currentCommandMode == CommandMode.NORMAL && commandString == "Alias" -> {
                            currentCommandMode = CommandMode.ALIASES
                            commandScrollOffset = 0f
                        }
                        currentCommandMode == CommandMode.NORMAL && commandString == "Sys" -> {
                            currentCommandMode = CommandMode.SYS
                        }
                        currentCommandMode == CommandMode.NORMAL && commandString == "Net" -> {
                            currentCommandMode = CommandMode.NET
                        }
                        currentCommandMode == CommandMode.NORMAL && commandString == "Favs" -> {
                            currentOverlay = OverlayState.FAVORITES
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.hideSoftInputFromWindow(windowToken, 0)
                        }
                        currentCommandMode == CommandMode.NORMAL -> {
                            launchApp(commandString)
                            session.buffer = ""
                            session.cursor = 0
                        }
                        currentCommandMode == CommandMode.APPS -> {
                            launchApp(commandString)
                            session.buffer = ""
                            session.cursor = 0
                            currentCommandMode = CommandMode.NORMAL
                        }
                        currentCommandMode == CommandMode.ALIASES -> {
                            val mappedCmd = aliases[commandString]
                            if (mappedCmd != null) {
                                session.history.add(session.getPrompt() + commandString)
                                CommandEngine.process(context, session, mappedCmd, installedApps, aliases, favoriteApps, allowedNotifApps, dockApps, cachedContacts, ::launchApp, { currentState = AppState.SETTINGS; val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager; imm.hideSoftInputFromWindow(windowToken, 0) }, ::scrollToBottom, ::requestUpdate, ::saveNotifSettings, ::saveDockSettings) { post(it) }
                            }
                            session.buffer = ""
                            session.cursor = 0
                            currentCommandMode = CommandMode.NORMAL
                        }
                        currentCommandMode == CommandMode.SYS -> {
                            val cmd = commandString.lowercase()
                            session.history.add(session.getPrompt() + cmd)
                            CommandEngine.process(context, session, cmd, installedApps, aliases, favoriteApps, allowedNotifApps, dockApps, cachedContacts, ::launchApp, { currentState = AppState.SETTINGS; val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager; imm.hideSoftInputFromWindow(windowToken, 0) }, ::scrollToBottom, ::requestUpdate, ::saveNotifSettings, ::saveDockSettings) { post(it) }
                            session.buffer = ""
                            session.cursor = 0
                            currentCommandMode = CommandMode.NORMAL
                        }
                        currentCommandMode == CommandMode.NET -> {
                            val cmd = if (commandString == "PING") "ping 8.8.8.8" else commandString.lowercase()
                            session.history.add(session.getPrompt() + cmd)
                            CommandEngine.process(context, session, cmd, installedApps, aliases, favoriteApps, allowedNotifApps, dockApps, cachedContacts, ::launchApp, { currentState = AppState.SETTINGS; val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager; imm.hideSoftInputFromWindow(windowToken, 0) }, ::scrollToBottom, ::requestUpdate, ::saveNotifSettings, ::saveDockSettings) { post(it) }
                            session.buffer = ""
                            session.cursor = 0
                            currentCommandMode = CommandMode.NORMAL
                        }
                    }
                }
                requestUpdate()
                return true
            }
        }

        currentCommandMode = CommandMode.NORMAL
        requestFocus()
        if (!useCustomKeyboard) {
            post {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(this, 0)
            }
        }
        requestUpdate()
        return false
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE

        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text != null) injectChar(text.toString())
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
                            deleteLastChar()
                            return true
                        }
                        KeyEvent.KEYCODE_ENTER -> {
                            submitBuffer()
                            return true
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }
}