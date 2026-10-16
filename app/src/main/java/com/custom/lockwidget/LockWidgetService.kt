package com.custom.lockwidget

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import org.json.JSONArray

class LockWidgetService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var windowManager: WindowManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost
    private lateinit var prefs: SharedPreferences

    private val activeOverlays = mutableListOf<View>()
    private var lastRenderedJson = ""
    private var isListening = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> evaluateLockState()
                Intent.ACTION_USER_PRESENT -> hideAllOverlays()
                Intent.ACTION_SCREEN_OFF -> hideAllOverlays()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        appWidgetManager = AppWidgetManager.getInstance(applicationContext)
        appWidgetHost = AppWidgetHost(applicationContext, MainActivity.HOST_ID)

        prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }

        rebuildOverlays()
        evaluateLockState()
    }

    private fun rebuildOverlays() {
        removeAllOverlaysFromWindow()

        val jsonStr = prefs.getString(MainActivity.KEY_WIDGETS_JSON, "[]") ?: "[]"
        lastRenderedJson = jsonStr

        try {
            val arr = JSONArray(jsonStr)
            val density = resources.displayMetrics.density

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val widgetId = obj.getInt("id")
                val x = obj.getInt("x")
                val y = obj.getInt("y")
                val width = obj.getInt("width")
                val height = obj.getInt("height")
                val alpha = obj.optDouble("alpha", 1.0).toFloat()

                val providerInfo = appWidgetManager.getAppWidgetInfo(widgetId) ?: continue

                val wDp = (width / density).toInt()
                val hDp = (height / density).toInt()
                val options = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
                }
                appWidgetManager.updateAppWidgetOptions(widgetId, options)

                val container = FrameLayout(this).apply {
                    visibility = View.GONE
                    this.alpha = alpha // تطبيق درجة الشفافية المختارة
                }

                val hostView = appWidgetHost.createView(applicationContext, widgetId, providerInfo).apply {
                    this.alpha = alpha
                }
                val pad = (6 * density).toInt()
                hostView.setPadding(pad, pad, pad, pad)
                container.addView(hostView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))

                val params = WindowManager.LayoutParams(
                    width,
                    height,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.LEFT
                    this.x = x
                    this.y = y
                }

                windowManager.addView(container, params)
                activeOverlays.add(container)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun evaluateLockState() {
        val currentJson = prefs.getString(MainActivity.KEY_WIDGETS_JSON, "[]") ?: "[]"
        if (currentJson != lastRenderedJson) {
            rebuildOverlays()
        }

        if (keyguardManager.isKeyguardLocked && activeOverlays.isNotEmpty()) {
            if (!isListening) {
                try {
                    appWidgetHost.startListening()
                    isListening = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            for (view in activeOverlays) {
                if (view.visibility != View.VISIBLE) {
                    view.visibility = View.VISIBLE
                }
            }
        } else {
            hideAllOverlays()
        }
    }

    private fun hideAllOverlays() {
        for (view in activeOverlays) {
            if (view.visibility == View.VISIBLE) {
                view.visibility = View.GONE
            }
        }
        if (isListening) {
            try {
                appWidgetHost.stopListening()
                isListening = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun removeAllOverlaysFromWindow() {
        for (view in activeOverlays) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        activeOverlays.clear()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == MainActivity.KEY_WIDGETS_JSON) {
            rebuildOverlays()
            evaluateLockState()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            evaluateLockState()
        }
    }

    override fun onInterrupt() {
        hideAllOverlays()
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        try {
            unregisterReceiver(screenReceiver)
            if (isListening) appWidgetHost.stopListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        removeAllOverlaysFromWindow()
    }
}
