package com.example.crtui

import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class TerminalNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            val packageName = it.packageName
            if (packageName == applicationContext.packageName) return

            // Fetch the human-readable app name
            val pm = applicationContext.packageManager
            val appName = try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                packageName
            }

            val extras = it.notification.extras
            val title = extras.getString("android.title") ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""

            val content = if (title.isNotEmpty()) "$title: $text" else text

            // Format perfectly for the inline parser
            val formattedLine = "<bold>![$appName]</bold> $content"

            val intent = Intent("com.example.crtui.NOTIFICATION_EVENT")
            intent.setPackage(applicationContext.packageName)
            intent.putExtra("formattedLine", formattedLine)
            sendBroadcast(intent)
        }
    }
}