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
import android.widget.SeekBar
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
    var height: Int,
    var alpha: Float = 1.0f
)

class MainActivity : Activity() {

    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost
    private lateinit var canvasLayout: FrameLayout
    private lateinit var alphaPanel: LinearLayout
    private lateinit var alphaSeekBar: SeekBar
    private lateinit var alphaLabel: TextView

    private val widgetList = mutableListOf<WidgetItem>()
    private val widgetViewsMap = mutableMapOf<Int, View>()
    private val wrapperViewsMap = mutableMapOf<Int, FrameLayout>()
    private var selectedWidget: WidgetItem? = null

    companion object {
        const val HOST_ID = 2026
        const val PREFS_NAME = "LockWidgetPrefs"
        const val KEY_WIDGETS_JSON = "saved_widgets_json"
        private const val REQUEST_PICK_WIDGET = 101
        private const val REQUEST_CREATE_WIDGET = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // إظهار خلفية شاشة القفل الحقيقية
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        appWidgetManager = AppWidgetManager.getInstance(applicationContext)
        appWidgetHost = AppWidgetHost(applicationContext, HOST_ID)

        val rootLayout = FrameLayout(this)

        // 1. طبقة إرشادية لمكان ساعة شاشة القفل
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

        // 2. مساحة العمل الحرة لتحريك الويدجت
        canvasLayout = FrameLayout(this)
        rootLayout.addView(canvasLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 3. الحاوية السفلية (تجمع شريط الشفافية + أزرار التحكم)
        val bottomContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // شريط التحكم بالشفافية
        alphaPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(20).toFloat()
                setColor(Color.parseColor("#D9121212"))
            }
        }

        alphaLabel = TextView(this).apply {
            text = "الشفافية: 100%"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 0, dpToPx(12), 0)
        }

        alphaSeekBar = SeekBar(this).apply {
            max = 90 // من 10% إلى 100%
            progress = 90
            layoutParams = LinearLayout.LayoutParams(dpToPx(180), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val actualPercent = progress + 10
                    val alphaValue = actualPercent / 100f
                    alphaLabel.text = "الشفافية: $actualPercent%"
                    selectedWidget?.let { item ->
                        item.alpha = alphaValue
                        widgetViewsMap[item.id]?.alpha = alphaValue
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    saveWidgetsToPrefs()
                }
            })
        }

        alphaPanel.addView(alphaLabel)
        alphaPanel.addView(alphaSeekBar)

        val alphaParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dpToPx(12)
        }
        bottomContainer.addView(alphaPanel, alphaParams)

        // شريط الأزرار السفلي
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
        bottomContainer.addView(controlBar)

        val bottomParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dpToPx(28)
        }
        rootLayout.addView(bottomContainer, bottomParams)

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

    private fun setSelectedWidget(item: WidgetItem?) {
        selectedWidget = item
        for ((id, wrapper) in wrapperViewsMap) {
            val isSelected = (item?.id == id)
            wrapper.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor(if (isSelected) "#331E88E5" else "#1AFFFFFF"))
                setStroke(dpToPx(2), Color.parseColor(if (isSelected) "#FF1E88E5" else "#66FFFFFF"))
            }
        }

        if (item != null) {
            alphaPanel.visibility = View.VISIBLE
            val percent = (item.alpha * 100).toInt().coerceIn(10, 100)
            alphaSeekBar.progress = percent - 10
            alphaLabel.text = "الشفافية: $percent%"
        } else {
            alphaPanel.visibility = View.GONE
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

        val newItem = WidgetItem(widgetId, defaultX, defaultY, defaultW, defaultH, 1.0f)
        widgetList.add(newItem)
        saveWidgetsToPrefs()
        renderEditableWidget(newItem)
        setSelectedWidget(newItem)
        Toast.makeText(this, "اسحب الويدجت لتحريكه، واستخدم الشريط بالأسفل لتعديل شفافيته", Toast.LENGTH_LONG).show()
    }

    private fun renderEditableWidget(item: WidgetItem) {
        val providerInfo = appWidgetManager.getAppWidgetInfo(item.id) ?: return
        val hostView = appWidgetHost.createView(applicationContext, item.id, providerInfo).apply {
            alpha = item.alpha
        }
        widgetViewsMap[item.id] = hostView
        updateWidgetSizeOptions(item)

        val wrapper = FrameLayout(this)
        wrapperViewsMap[item.id] = wrapper

        val wrapperParams = FrameLayout.LayoutParams(item.width, item.height).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            leftMargin = item.x
            topMargin = item.y
        }

        wrapper.addView(hostView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            val pad = dpToPx(6)
            setMargins(pad, pad, pad, pad)
        })

        val dragInterceptor = View(this)
        var startX = 0f
        var startY = 0f
        var initialLeft = 0
        var initialTop = 0

        dragInterceptor.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wrapper.bringToFront()
                    setSelectedWidget(item)
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

        // زر الحذف الأحمر
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
                widgetViewsMap.remove(item.id)
                wrapperViewsMap.remove(item.id)
                canvasLayout.removeView(wrapper)
                setSelectedWidget(widgetList.lastOrNull())
                saveWidgetsToPrefs()
            }
        }
        wrapper.addView(btnDelete, FrameLayout.LayoutParams(dpToPx(32), dpToPx(32)).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            setMargins(dpToPx(4), dpToPx(4), 0, 0)
        })

        // مقبض تغيير الحجم الأزرق
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
                    setSelectedWidget(item)
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
        setSelectedWidget(item)
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
        widgetViewsMap.clear()
        wrapperViewsMap.clear()
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
                    height = obj.getInt("height"),
                    alpha = obj.optDouble("alpha", 1.0).toFloat()
                )
                widgetList.add(item)
                renderEditableWidget(item)
            }
            setSelectedWidget(widgetList.lastOrNull())
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
                put("alpha", item.alpha.toDouble())
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
        widgetViewsMap.clear()
        wrapperViewsMap.clear()
        canvasLayout.removeAllViews()
        setSelectedWidget(null)
        saveWidgetsToPrefs()
        Toast.makeText(this, "تم مسح جميع الويدجت", Toast.LENGTH_SHORT).show()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
