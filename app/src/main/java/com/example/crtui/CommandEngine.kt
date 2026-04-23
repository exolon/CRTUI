package com.example.crtui

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CommandEngine {

    fun process(
        context: Context,
        session: TerminalSession,
        input: String,
        installedApps: List<TerminalView.AppInfo>,
        aliases: MutableMap<String, String>,
        favoriteApps: MutableList<String>,
        allowedNotifApps: MutableSet<String>,
        dockApps: MutableList<String>,
        cachedContacts: List<TerminalView.ContactInfo>,
        launchApp: (String) -> Unit,
        openSettings: () -> Unit,
        scrollToBottom: () -> Unit,
        requestUpdate: () -> Unit,
        saveNotifs: () -> Unit,
        saveDock: () -> Unit,
        onMainThread: (() -> Unit) -> Unit
    ): Boolean {
        val args = input.trim().split("\\s+".toRegex())
        val cmd = args[0].lowercase(Locale.US)

        if (cmd != "alias" && aliases.containsKey(cmd)) {
            val mappedCmd = aliases[cmd]!!
            if (session.type == SessionType.LOCAL) {
                session.history.add("> executing alias: $cmd -> $mappedCmd")
            }
            return process(context, session, mappedCmd, installedApps, aliases, favoriteApps, allowedNotifApps, dockApps, cachedContacts, launchApp, openSettings, scrollToBottom, requestUpdate, saveNotifs, saveDock, onMainThread)
        }

        if (session.type == SessionType.SSH) {
            if (cmd == "clear" || cmd == "settings" || cmd == "alias" || cmd == "exit" || cmd == "changelog" || cmd == "notif" || cmd == "dock") {
                if (cmd == "exit") {
                    session.disconnectSsh()
                    session.history.add("> Terminated remote connection.")
                }
            } else {
                session.sendCommand(input) {
                    onMainThread {
                        scrollToBottom()
                        requestUpdate()
                    }
                }
            }
            return true
        }

        when (cmd) {
            "clear" -> session.clearHistory()

            "changelog" -> {
                session.history.add("> CHANGELOG v0.9.1")
                session.history.add("• Telemetry: Real-time predictive autocomplete on Command Bar.")
                session.history.add("• Telemetry: 'call' command integrated with native OS Contact routing.")
                session.history.add("• UI: Dynamic CP437 block-character boot sequence integrated.")
                session.history.add("• UI: Native quick-launch icon dock added (manage via 'dock' or settings).")
                session.history.add("• Engine: Raw-pixel hardware scroll logic deployed to fix touch leakage.")
                session.history.add("• Security: Configurable Notification Firewall with visual TUI toggles.")
                session.history.add("• Stability: Keep-Alive service deployed to stop background OS memory wipes.")
                session.history.add("• Settings: Theme and visual prefs permanently locked via SharedPreferences.")
            }

            "settings" -> {
                openSettings()
            }

            "s", "g" -> {
                val prefix = if (cmd == "s") "s " else "g "
                val query = input.substringAfter(prefix).trim()
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
                    session.history.add("Syntax error. Use: $cmd [query]")
                }
            }

            "call" -> {
                if (args.size > 1) {
                    val target = input.substringAfter("call ").trim()
                    val matchingContacts = cachedContacts.filter { it.name.equals(target, ignoreCase = true) }

                    if (matchingContacts.isNotEmpty()) {
                        if (matchingContacts.size == 1) {
                            session.history.add("> Dialing contact: ${matchingContacts[0].name}...")
                            try {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${matchingContacts[0].number}"))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                session.history.add("> Error launching dialer.")
                            }
                        } else {
                            onMainThread {
                                val numbers = matchingContacts.map { it.number }.toTypedArray()
                                android.app.AlertDialog.Builder(context)
                                    .setTitle("Select number for ${matchingContacts[0].name}")
                                    .setItems(numbers) { _, which ->
                                        try {
                                            session.history.add("> Dialing contact: ${matchingContacts[0].name}...")
                                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${numbers[which]}"))
                                            context.startActivity(intent)
                                            scrollToBottom()
                                            requestUpdate()
                                        } catch (e: Exception) {}
                                    }
                                    .show()
                            }
                        }
                    } else {
                        session.history.add("> Dialing raw number: $target...")
                        try {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$target"))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            session.history.add("> Error launching dialer.")
                        }
                    }
                } else {
                    session.history.add("Syntax error. Use: call [name/number]")
                }
            }

            "alias" -> {
                if (args.size > 1 && input.contains("=")) {
                    val mapping = input.substringAfter("alias ").trim()
                    val key = mapping.substringBefore("=").trim()
                    val value = mapping.substringAfter("=").trim()

                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        aliases[key] = value
                        try {
                            val file = File(context.filesDir, "aliases_cache.txt")
                            file.writeText(aliases.entries.joinToString("\n") { "${it.key}=${it.value}" })
                        } catch (e: Exception) {}
                        session.history.add("Alias saved: $key -> $value")
                    } else {
                        session.history.add("Syntax error. Use: alias name=command")
                    }
                } else {
                    session.history.add("Syntax error. Use: alias name=command")
                }
            }

            "dock" -> {
                if (args.size > 2) {
                    val action = args[1].lowercase(Locale.US)
                    val appNameQuery = input.substringAfter(args[1]).trim()

                    val match = installedApps.find { it.name.equals(appNameQuery, ignoreCase = true) }
                        ?: installedApps.find { it.name.startsWith(appNameQuery, ignoreCase = true) }

                    val finalName = match?.name ?: appNameQuery

                    if (action == "-add") {
                        if (dockApps.size >= 8 && !dockApps.contains(finalName)) {
                            session.history.add("> Dock full. Remove an app first (max 8).")
                        } else {
                            if (!dockApps.contains(finalName)) dockApps.add(finalName)
                            session.history.add("> Pinned to Dock: $finalName")
                            saveDock()
                        }
                    } else if (action == "-rem" || action == "-rm") {
                        val removed = dockApps.remove(finalName)
                        if (!removed) {
                            val toRemove = dockApps.find { it.equals(finalName, ignoreCase = true) }
                            if (toRemove != null) dockApps.remove(toRemove)
                        }
                        session.history.add("> Removed from Dock: $finalName")
                        saveDock()
                    } else {
                        session.history.add("Syntax error. Use: dock -add/-rem <app>")
                    }
                } else {
                    session.history.add("Syntax error. Use: dock -add/-rem <app>")
                }
            }

            "notif" -> {
                if (args.size > 2) {
                    val action = args[1].lowercase(Locale.US)
                    val appNameQuery = input.substringAfter(args[1]).trim()

                    val match = installedApps.find { it.name.equals(appNameQuery, ignoreCase = true) }
                        ?: installedApps.find { it.name.startsWith(appNameQuery, ignoreCase = true) }

                    val finalName = match?.name ?: appNameQuery

                    if (action == "-add") {
                        allowedNotifApps.add(finalName)
                        session.history.add("> Notifications ALLOWED for: $finalName")
                        saveNotifs()
                    } else if (action == "-rem" || action == "-rm") {
                        val removed = allowedNotifApps.remove(finalName)
                        if (!removed) {
                            val toRemove = allowedNotifApps.find { it.equals(finalName, ignoreCase = true) }
                            if (toRemove != null) allowedNotifApps.remove(toRemove)
                        }
                        session.history.add("> Notifications MUTED for: $finalName")
                        saveNotifs()
                    } else {
                        session.history.add("Syntax error. Use: notif -add/-rem <app>")
                    }
                } else {
                    session.history.add("Syntax error. Use: notif -add/-rem <app>")
                }
            }

            "mem" -> {
                try {
                    val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val memInfo = ActivityManager.MemoryInfo()
                    actManager.getMemoryInfo(memInfo)
                    val availMegs = memInfo.availMem / 1048576L
                    val totalMegs = memInfo.totalMem / 1048576L
                    session.history.add("RAM: $availMegs MB / $totalMegs MB free")

                    val stat = StatFs(Environment.getExternalStorageDirectory().path)
                    val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
                    val totalBytes = stat.blockSizeLong * stat.blockCountLong
                    session.history.add("STO: ${bytesAvailable / 1048576L} MB / ${totalBytes / 1048576L} MB free")
                } catch (e: Exception) {
                    session.history.add("> Error reading system memory")
                }
            }

            "bat" -> {
                val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = context.registerReceiver(null, intentFilter)
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPct = level * 100 / scale.toFloat()
                val temp = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
                val volt = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
                session.history.add("BATTERY: ${batteryPct.toInt()}% | ${temp}°C | ${volt}mV")
            }

            "vol" -> context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
            "brt" -> context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            "bt" -> context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

            "ip" -> {
                Thread {
                    try {
                        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                        var localIp = "Unknown"
                        for (intf in interfaces) {
                            for (addr in intf.inetAddresses) {
                                if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                                    localIp = addr.hostAddress ?: "Unknown"
                                }
                            }
                        }
                        onMainThread {
                            session.history.add("Local IP: $localIp")
                            scrollToBottom()
                            requestUpdate()
                        }

                        val pubIpUrl = java.net.URL("https://api.ipify.org")
                        val pubIp = pubIpUrl.readText()
                        onMainThread {
                            session.history.add("Public IP: $pubIp")
                            scrollToBottom()
                            requestUpdate()
                        }
                    } catch (e: Exception) {
                        onMainThread {
                            session.history.add("> IP fetch failed.")
                            requestUpdate()
                        }
                    }
                }.start()
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
                val ip = if (args.size > 1) args[1] else "8.8.8.8"
                session.history.add("Pinging $ip...")
                Thread {
                    try {
                        val process = Runtime.getRuntime().exec("ping -c 4 $ip")
                        val reader = BufferedReader(InputStreamReader(process.inputStream))
                        reader.forEachLine { line ->
                            onMainThread {
                                session.history.add(line)
                                scrollToBottom()
                                requestUpdate()
                            }
                        }
                    } catch (e: Exception) {
                        onMainThread {
                            session.history.add("Ping failed: ${e.message}")
                            requestUpdate()
                        }
                    }
                }.start()
            }

            else -> {
                val targetApp = input.trim().lowercase(Locale.US)
                var matches = installedApps.filter { it.name.lowercase(Locale.US) == targetApp }

                if (matches.isEmpty()) {
                    matches = installedApps.filter { it.name.lowercase(Locale.US).startsWith(targetApp) }
                }
                if (matches.isEmpty()) {
                    matches = installedApps.filter { it.name.lowercase(Locale.US).contains(targetApp) }
                }

                if (matches.size == 1) {
                    launchApp(matches[0].name)
                    return true
                } else if (matches.size > 1) {
                    session.history.add(matches.joinToString("  ") { "<bold>${it.name}</bold>" })
                    return false
                } else {
                    session.history.add("Command not found: $cmd")
                    return true
                }
            }
        }
        return true
    }
}