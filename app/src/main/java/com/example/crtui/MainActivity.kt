package com.example.crtui

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var crtSurfaceView: GLSurfaceView
    private lateinit var terminalView: TerminalView
    private lateinit var shaderRenderer: CrtShaderRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_main)

        crtSurfaceView = findViewById(R.id.crtSurface)
        terminalView = findViewById(R.id.terminalView)

        crtSurfaceView.setEGLContextClientVersion(2)
        shaderRenderer = CrtShaderRenderer(terminalView)
        crtSurfaceView.setRenderer(shaderRenderer)
        crtSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        terminalView.setCrtSurface(crtSurfaceView, shaderRenderer)

        ViewCompat.setOnApplyWindowInsetsListener(crtSurfaceView) { _, insets ->
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            val isKeyboardOpen = ime.bottom > 0
            val bottomPadding = max(navigationBars.bottom, ime.bottom)

            terminalView.setPadding(displayCutout.left, displayCutout.top, displayCutout.right, bottomPadding)

            // Tell the terminal to snap to the new boundary
            terminalView.onKeyboardToggled(isKeyboardOpen)

            shaderRenderer.isDirty = true
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        crtSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        crtSurfaceView.onPause()
    }

    private fun checkPermissions() {
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        var hasListener = false
        if (!TextUtils.isEmpty(listeners)) {
            hasListener = listeners.split(":").any {
                ComponentName.unflattenFromString(it)?.packageName == packageName
            }
        }
        if (!hasListener) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }
}