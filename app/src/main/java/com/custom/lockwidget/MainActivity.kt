package com.custom.lockwidget

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost

    companion object {
        const val HOST_ID = 2026
        const val PREFS_NAME = "LockWidgetPrefs"
        const val KEY_WIDGET_ID = "saved_widget_id"
        private const val REQUEST_PICK_WIDGET = 101
        private const val REQUEST_CREATE_WIDGET = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetManager = AppWidgetManager.getInstance(applicationContext)
        appWidgetHost = AppWidgetHost(applicationContext, HOST_ID)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(64, 64, 64, 64)
        }

        val titleText = TextView(this).apply {
            text = "إعداد ويدجت شاشة القفل"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 80)
        }

        val btnSelectWidget = createStyledButton("1. اختيار أو تغيير الويدجت", "#1E88E5").apply {
            setOnClickListener { selectWidget() }
        }

        val btnEnableService = createStyledButton("2. تفعيل خدمة شاشة القفل", "#43A047").apply {
            setOnClickListener {
                Toast.makeText(this@MainActivity, "ابحث عن LockWidget وقم بتفعيله", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnRemoveWidget = createStyledButton("إزالة الويدجت الحالي", "#E53935").apply {
            setOnClickListener { removeCurrentWidget() }
        }

        rootLayout.addView(titleText)
        rootLayout.addView(btnSelectWidget)
        rootLayout.addView(btnEnableService)
        rootLayout.addView(btnRemoveWidget)

        setContentView(rootLayout)
    }

    private fun createStyledButton(title: String, colorHex: String): Button {
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 32f
            setColor(Color.parseColor(colorHex))
        }
        return Button(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            background = drawable
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 24, 0, 24)
            }
            layoutParams = params
            setPadding(32, 40, 32, 40)
        }
    }

    private fun selectWidget() {
        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        val pickIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        startActivityForResult(pickIntent, REQUEST_PICK_WIDGET)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ( data == null) return

        val widgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (widgetId == -1) return

        if (resultCode == RESULT_OK) {
            when (requestCode) {
                REQUEST_PICK_WIDGET -> {
                    val info = appWidgetManager.getAppWidgetInfo(widgetId)
                    if (info?.configure != null) {
                        val configIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                            component = info.configure
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        }
                        startActivityForResult(configIntent, REQUEST_CREATE_WIDGET)
                    } else {
                        saveWidget(widgetId)
                    }
                }
                REQUEST_CREATE_WIDGET -> saveWidget(widgetId)
            }
        } else if (resultCode == RESULT_CANCELED) {
            appWidgetHost.deleteAppWidgetId(widgetId)
        }
    }

    private fun saveWidget(widgetId: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldId = prefs.getInt(KEY_WIDGET_ID, -1)
        if (oldId != -1 && oldId != widgetId) {
            appWidgetHost.deleteAppWidgetId(oldId)
        }
        prefs.edit().putInt(KEY_WIDGET_ID, widgetId).apply()
        Toast.makeText(this, "تم حفظ الويدجت! أقفل الشاشة لتجربته.", Toast.LENGTH_SHORT).show()
    }

    private fun removeCurrentWidget() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldId = prefs.getInt(KEY_WIDGET_ID, -1)
        if (oldId != -1) {
            appWidgetHost.deleteAppWidgetId(oldId)
            prefs.edit().remove(KEY_WIDGET_ID).apply()
            Toast.makeText(this, "تمت إزالة الويدجت من شاشة القفل", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "لا يوجد ويدجت مضاف حالياً", Toast.LENGTH_SHORT).show()
        }
    }
}
