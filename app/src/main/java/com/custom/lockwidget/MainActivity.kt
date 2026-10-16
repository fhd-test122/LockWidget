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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

data class WidgetItem(
    val id: Int,
    var x: Int,
    var y: Int,
    var width: Int,
    var height: Int
)

class MainActivity : Activity() {

    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost
    private lateinit var canvasLayout: FrameLayout
    private val widgetList = mutableListOf<WidgetItem>()

    companion object {
        const val HOST_ID = 2026
        const val PREFS_NAME = "LockWidgetPrefs"
        const val KEY_WIDGETS_JSON = "saved_widgets_json"
        private const val REQUEST_PICK_WIDGET = 101
        private const val REQUEST_CREATE_WIDGET = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // إظهار خلفية الشاشة الحقيقية خلف التطبيق لمحاكاة شاشة القفل
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        appWidgetManager = AppWidgetManager.getInstance(applicationContext)
        appWidgetHost = AppWidgetHost(applicationContext, HOST_ID)

        val rootLayout = FrameLayout(this)

        // 1. طبقة إرشادية شفافة تحاكي ساعة شاشة القفل لتعرف أين تضع الويدجت
        val lockScreenGuide = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dpToPx(70), 0, 0)
        }
        val mockDate = TextView(this).apply {
            text = "منطقة ساعة شاشة القفل (إرشادية)"
            textSize = 14f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            gravity = Gravity.CENTER
        }
        val mockClock = TextView(this).apply {
            text = "7:36"
            textSize = 72f
            setTextColor(Color.parseColor("#66FFFFFF"))
            gravity = Gravity.CENTER
        }
        lockScreenGuide.addView(mockDate)
        lockScreenGuide.addView(mockClock)
        rootLayout.addView(lockScreenGuide)

        // 2. مساحة العمل الحرة التي تتحرك فيها الويدجت
        canvasLayout = FrameLayout(this)
        rootLayout.addView(canvasLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 3. شريط التحكم السفلي العائم
        val controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24).toFloat()
                setColor(Color.parseColor("#CC121212"))
            }
        }

        val btnAdd = createSmallButton("+ إضافة ويدجت", "#1E88E5") { selectNewWidget() }
        val btnService = createSmallButton("تفعيل الخدمة", "#43A047") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val btnClear = createSmallButton("مسح الكل", "#E53935") { clearAllWidgets() }

        controlBar.addView(btnAdd)
        controlBar.addView(btnService)
        controlBar.addView(btnClear)

        val barParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dpToPx(32)
        }
        rootLayout.addView(controlBar, barParams)

        setContentView(rootLayout)
        loadWidgetsFromPrefs()
    }

    override fun onStart() {
        super.onStart()
        appWidgetHost.startListening()
    }

    override fun onStop() {
        super.onStop()
        try {
            appWidgetHost.stopListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createSmallButton(title: String, colorHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor(colorHex))
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dpToPx(6), 0, dpToPx(6), 0)
            }
            layoutParams = params
            setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
            setOnClickListener { onClick() }
        }
    }

    private fun selectNewWidget() {
        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        val pickIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        startActivityForResult(pickIntent, REQUEST_PICK_WIDGET)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (data == null) return
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
                        addNewWidgetToCanvas(widgetId)
                    }
                }
                REQUEST_CREATE_WIDGET -> addNewWidgetToCanvas(widgetId)
            }
        } else if (resultCode == RESULT_CANCELED) {
            appWidgetHost.deleteAppWidgetId(widgetId)
        }
    }

    private fun addNewWidgetToCanvas(widgetId: Int) {
        val displayMetrics = resources.displayMetrics
        val defaultW = displayMetrics.widthPixels - dpToPx(40)
        val defaultH = dpToPx(190)
        val defaultX = dpToPx(20)
        val defaultY = (displayMetrics.heightPixels / 2) - (defaultH / 2) + (widgetList.size * dpToPx(30))

        val newItem = WidgetItem(widgetId, defaultX, defaultY, defaultW, defaultH)
        widgetList.add(newItem)
        saveWidgetsToPrefs()
        renderEditableWidget(newItem)
        Toast.makeText(this, "اسحب الويدجت لتحريكه، أو اسحب الزاوية الزرقاء لتغيير حجمه", Toast.LENGTH_LONG).show()
    }

    private fun renderEditableWidget(item: WidgetItem) {
        val providerInfo = appWidgetManager.getAppWidgetInfo(item.id) ?: return
        val hostView = appWidgetHost.createView(applicationContext, item.id, providerInfo)
        updateWidgetSizeOptions(item)

        // الحاوية القابلة للتحريك وتغيير الحجم
        val wrapper = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor("#22FFFFFF"))
                setStroke(dpToPx(2), Color.parseColor("#801E88E5"))
            }
        }

        val wrapperParams = FrameLayout.LayoutParams(item.width, item.height).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            leftMargin = item.x
            topMargin = item.y
        }

        // إضافة الويدجت داخل الحاوية
        wrapper.addView(hostView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            val pad = dpToPx(6)
            setMargins(pad, pad, pad, pad)
        })

        // طبقة شفافة تلتقط سحب الإصبع لتحريك الويدجت بسهولة دون فتح التطبيق الأصلي بالخطأ أثناء التعديل
        val dragInterceptor = View(this)
        var startX = 0f
        var startY = 0f
        var initialLeft = 0
        var initialTop = 0

        dragInterceptor.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wrapper.bringToFront()
                    startX = event.rawX
                    startY = event.rawY
                    initialLeft = wrapperParams.leftMargin
                    initialTop = wrapperParams.topMargin
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startX).toInt()
                    val dy = (event.rawY - startY).toInt()
                    wrapperParams.leftMargin = max(0, initialLeft + dx)
                    wrapperParams.topMargin = max(0, initialTop + dy)
                    wrapper.layoutParams = wrapperParams
                    item.x = wrapperParams.leftMargin
                    item.y = wrapperParams.topMargin
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    saveWidgetsToPrefs()
                    true
                }
                else -> false
            }
        }
        wrapper.addView(dragInterceptor, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // زر الحذف الأحمر في الزاوية العلوية اليسرى
        val btnDelete = TextView(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E53935"))
            }
            setOnClickListener {
                appWidgetHost.deleteAppWidgetId(item.id)
                widgetList.remove(item)
                canvasLayout.removeView(wrapper)
                saveWidgetsToPrefs()
            }
        }
        wrapper.addView(btnDelete, FrameLayout.LayoutParams(dpToPx(32), dpToPx(32)).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            setMargins(dpToPx(4), dpToPx(4), 0, 0)
        })

        // مقبض تغيير الحجم الأزرق في الزاوية السفلية اليمنى
        val resizeHandle = TextView(this).apply {
            text = "⤡"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E88E5"))
            }
        }

        var rStartX = 0f
        var rStartY = 0f
        var initialW = 0
        var initialH = 0

        resizeHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wrapper.bringToFront()
                    rStartX = event.rawX
                    rStartY = event.rawY
                    initialW = wrapperParams.width
                    initialH = wrapperParams.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - rStartX).toInt()
                    val dy = (event.rawY - rStartY).toInt()
                    wrapperParams.width = max(dpToPx(100), initialW + dx)
                    wrapperParams.height = max(dpToPx(80), initialH + dy)
                    wrapper.layoutParams = wrapperParams
                    item.width = wrapperParams.width
                    item.height = wrapperParams.height
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    updateWidgetSizeOptions(item)
                    saveWidgetsToPrefs()
                    true
                }
                else -> false
            }
        }

        wrapper.addView(resizeHandle, FrameLayout.LayoutParams(dpToPx(36), dpToPx(36)).apply {
            gravity = Gravity.BOTTOM or Gravity.RIGHT
            setMargins(0, 0, dpToPx(4), dpToPx(4))
        })

        canvasLayout.addView(wrapper, wrapperParams)
    }

    private fun updateWidgetSizeOptions(item: WidgetItem) {
        val density = resources.displayMetrics.density
        val wDp = (item.width / density).toInt()
        val hDp = (item.height / density).toInt()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
        }
        appWidgetManager.updateAppWidgetOptions(item.id, options)
    }

    private fun loadWidgetsFromPrefs() {
        widgetList.clear()
        canvasLayout.removeAllViews()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_WIDGETS_JSON, "[]") ?: "[]"

        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val item = WidgetItem(
                    id = obj.getInt("id"),
                    x = obj.getInt("x"),
                    y = obj.getInt("y"),
                    width = obj.getInt("width"),
                    height = obj.getInt("height")
                )
                widgetList.add(item)
                renderEditableWidget(item)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveWidgetsToPrefs() {
        val arr = JSONArray()
        for (item in widgetList) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("x", item.x)
                put("y", item.y)
                put("width", item.width)
                put("height", item.height)
            }
            arr.put(obj)
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WIDGETS_JSON, arr.toString())
            .apply()
    }

    private fun clearAllWidgets() {
        for (item in widgetList) {
            appWidgetHost.deleteAppWidgetId(item.id)
        }
        widgetList.clear()
        canvasLayout.removeAllViews()
        saveWidgetsToPrefs()
        Toast.makeText(this, "تم مسح جميع الويدجت", Toast.LENGTH_SHORT).show()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
