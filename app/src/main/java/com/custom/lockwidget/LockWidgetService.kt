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
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout

class LockWidgetService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var windowManager: WindowManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost
    private lateinit var prefs: SharedPreferences

    private var overlayContainer: FrameLayout? = null
    private var isOverlayAdded = false
    private var currentRenderedWidgetId = -1

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> evaluateLockState()
                Intent.ACTION_USER_PRESENT -> hideOverlay()
                Intent.ACTION_SCREEN_OFF -> hideOverlay()
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

        createOverlayContainer()
        evaluateLockState()
    }

    private fun createOverlayContainer() {
        if (isOverlayAdded) return

        overlayContainer = FrameLayout(this).apply {
            visibility = View.GONE
        }

        val density = resources.displayMetrics.density
        val widgetHeightPx = (220 * density).toInt() // ارتفاع متناسق يناسب أغلب الويدجت الرسمية

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            widgetHeightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER // يظهر في منتصف شاشة القفل تماماً
        }

        try {
            windowManager.addView(overlayContainer, params)
            isOverlayAdded = true
            renderWidgetView()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun renderWidgetView() {
        val savedWidgetId = prefs.getInt(MainActivity.KEY_WIDGET_ID, -1)
        overlayContainer?.removeAllViews()
        currentRenderedWidgetId = savedWidgetId

        if (savedWidgetId != -1) {
            val providerInfo = appWidgetManager.getAppWidgetInfo(savedWidgetId)
            if (providerInfo != null) {
                try {
                    val hostView = appWidgetHost.createView(applicationContext, savedWidgetId, providerInfo)
                    val density = resources.displayMetrics.density
                    val padding = (16 * density).toInt()
                    hostView.setPadding(padding, padding, padding, padding)
                    overlayContainer?.addView(hostView)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun evaluateLockState() {
        val savedWidgetId = prefs.getInt(MainActivity.KEY_WIDGET_ID, -1)
        if (keyguardManager.isKeyguardLocked && savedWidgetId != -1) {
            if (currentRenderedWidgetId != savedWidgetId || overlayContainer?.childCount == 0) {
                renderWidgetView()
            }
            if (overlayContainer?.visibility != View.VISIBLE) {
                appWidgetHost.startListening()
                overlayContainer?.visibility = View.VISIBLE
            }
        } else {
            hideOverlay()
        }
    }

    private fun hideOverlay() {
        if (overlayContainer?.visibility == View.VISIBLE) {
            overlayContainer?.visibility = View.GONE
            try {
                appWidgetHost.stopListening()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == MainActivity.KEY_WIDGET_ID) {
            renderWidgetView()
            evaluateLockState()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            evaluateLockState()
        }
    }

    override fun onInterrupt() {
        hideOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        try {
            unregisterReceiver(screenReceiver)
            appWidgetHost.stopListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (isOverlayAdded && overlayContainer != null) {
            windowManager.removeView(overlayContainer)
            isOverlayAdded = false
        }
    }
}
