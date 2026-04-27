package com.colored.notifications

import android.app.Notification
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.palette.graphics.Palette

// ===== LSP 101 完整正确导入 =====
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.utils.XposedHelpers

import java.io.BufferedReader
import java.io.FileReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

class XposedInit : XposedModule {
    private val excludedPackages = setOf(
        "com.android.systemui",
        "com.miui.securitycenter",
        "com.miui.home",
        "com.android.phone"
    )

    private val WEATHER_URI = Uri.parse("content://com.miui.weather2.provider/weather")
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != "com.android.systemui") return

        try {
            // 修复：正常初始化配置
            SettingsManager.init(param.moduleContext)

            if (SettingsManager.getBoolean(SettingsManager.KEY_ENABLE_TRIPLE_ROW)) {
                enableTripleRowStatusBar(param.classLoader)
            }
            if (SettingsManager.getBoolean(SettingsManager.KEY_ENABLE_COLORED_NOTIFICATIONS)) {
                setupColoredNotifications(param.classLoader)
            }
        } catch (e: Exception) {
            // 修复：正确日志调用
            XposedModule.log("初始化错误: ${e.message}")
        }
    }

    private fun enableTripleRowStatusBar(classLoader: ClassLoader) {
        val statusBarClass = classLoader.loadClass("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView")

        XposedHelpers.findAndHookMethod(statusBarClass, "onFinishInflate") { hookParam ->
            val statusBarView = hookParam.thisObject
            val context = statusBarView.javaClass
                .getDeclaredField("mContext")
                .also { it.isAccessible = true }
                .get(statusBarView) as Context

            val originalViews = getOriginalStatusBarViews(statusBarView, context)
            originalViews.centerClock.visibility = android.view.View.GONE
            val tripleRowLayout = createTripleRowLayout(context, originalViews)

            val statusBarContent = statusBarView.findViewById<android.view.ViewGroup>(
                context.resources.getIdentifier("status_bar_contents", "id", "com.android.systemui")
            )
            statusBarContent.removeAllViews()
            statusBarContent.addView(tripleRowLayout)

            startScheduledUpdates(context, originalViews)
        }
    }

    private data class OriginalStatusBarViews(
        val leftContainer: android.view.ViewGroup,
        val rightContainer: android.view.ViewGroup,
        val centerClock: TextView,
        val batteryIcon: android.view.View
    )

    private fun getOriginalStatusBarViews(statusBarView: Any, context: Context): OriginalStatusBarViews {
        return OriginalStatusBarViews(
            leftContainer = statusBarView.findViewById(context.resources.getIdentifier("status_bar_left_container", "id", "com.android.systemui")),
            rightContainer = statusBarView.findViewById(context.resources.getIdentifier("status_bar_right_container", "id", "com.android.systemui")),
            centerClock = statusBarView.findViewById(context.resources.getIdentifier("clock", "id", "com.android.systemui")),
            batteryIcon = statusBarView.findViewById(context.resources.getIdentifier("battery", "id", "com.android.systemui"))
        )
    }

    private fun createTripleRowLayout(context: Context, originalViews: OriginalStatusBarViews): LinearLayout {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(4.dpToPx(context), 2.dpToPx(context), 4.dpToPx(context), 2.dpToPx(context))
        }
        layout.addView(createTopRow(context, originalViews))
        layout.addView(createMiddleRow(context, originalViews))
        return layout
    }

    private fun createTopRow(context: Context, originalViews: OriginalStatusBarViews): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(createTopLeftInfo(context))
        row.addView(createTopRightInfo(context, originalViews))
        return row
    }

    private fun createTopLeftInfo(context: Context): LinearLayout {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val timeText = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val decText = TextView(context).apply {
            text = SettingsManager.getString(SettingsManager.KEY_DECORATION_TEXT)
            textSize = 10f
            setTextColor(Color.argb(200,255,255,255))
            visibility = if(SettingsManager.getBoolean(SettingsManager.KEY_SHOW_DECORATION_TEXT)) android.view.View.VISIBLE else android.view.View.GONE
        }
        layout.addView(timeText)
        layout.addView(decText)
        return layout
    }

    private fun createTopRightInfo(context: Context, originalViews: OriginalStatusBarViews): LinearLayout {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
        val netText = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            visibility = if(SettingsManager.getBoolean(SettingsManager.KEY_SHOW_NETWORK_SPEED)) android.view.View.VISIBLE else android.view.View.GONE
        }
        val signalText = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            visibility = if(SettingsManager.getBoolean(SettingsManager.KEY_SHOW_SIGNAL_STRENGTH)) android.view.View.VISIBLE else android.view.View.GONE
        }
        while (originalViews.rightContainer.childCount > 0) {
            layout.addView(originalViews.rightContainer.getChildAt(0), 0)
        }
        layout.addView(signalText,0)
        layout.addView(netText,0)
        return layout
    }

    private fun createMiddleRow(context: Context, originalViews: OriginalStatusBarViews): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0,2.dpToPx(context),0,2.dpToPx(context))
        }
        row.addView(createNotifyContainer(context, originalViews))
        row.addView(createBottomRightInfo(context, originalViews))
        return row
    }

    private fun createNotifyContainer(context: Context, originalViews: OriginalStatusBarViews): LinearLayout {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        }
        while (originalViews.leftContainer.childCount > 0) {
            layout.addView(originalViews.leftContainer.getChildAt(0))
        }
        return layout
    }

    private fun createBottomRightInfo(context: Context, originalViews: OriginalStatusBarViews): LinearLayout {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
        layout.addView(originalViews.batteryIcon)

        val temp5 = TextView(context).apply { textSize=11f; setTextColor(Color.WHITE) }
        val temp6 = TextView(context).apply { textSize=11f; setTextColor(Color.WHITE) }
        val curr7 = TextView(context).apply { textSize=11f; setTextColor(Color.WHITE) }
        val pow8 = TextView(context).apply { textSize=11f; setTextColor(Color.WHITE) }

        layout.addView(temp5)
        layout.addView(temp6)
        layout.addView(curr7)
        layout.addView(pow8)
        return layout
    }

    private fun startScheduledUpdates(context: Context, originalViews: OriginalStatusBarViews) {
        val timeDateWeatherText = originalViews.batteryIcon.rootView.findViewById<TextView>(android.R.id.text1)
        val networkSpeedText = originalViews.batteryIcon.rootView.findViewById<TextView>(android.R.id.text3)
        val dualSimSignalText = originalViews.batteryIcon.rootView.findViewById<TextView>(android.R.id.text4)
        val batteryTempText = originalViews.batteryIcon.rootView.findViewById<TextView>(android.R.id.text5)
        val cpuTempText = originalViews.batteryIcon.rootView.findViewById<TextView>(android.R.id.text6)
        val currentText = originalViews.batteryIcon.rootView.findViewById<TextView>(android.R.id.text7)
        val powerText = originalViews.batteryIcon.rootView.findViewById<TextView>(android.R.id.text8)

        var lastTotalRxBytes = 0L
        var lastTotalTxBytes = 0L
        var lastNetworkUpdateTime = System.currentTimeMillis()
        var weatherUpdateCounter = 0

        val oneSecondRunnable = object : Runnable {
            override fun run() {
                try {
                    val timeFormat = SimpleDateFormat("~HH:mm:ss EEEE dd日", Locale.CHINA)
                    var timeString = timeFormat.format(Date())
                    if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_WEATHER) && weatherUpdateCounter % SettingsManager.getInt(SettingsManager.KEY_WEATHER_UPDATE_INTERVAL) == 0) {
                        val weather = getMiuiWeather(context)
                        timeString += " $weather ~Ciall~☆"
                    }
                    timeDateWeatherText?.text = timeString
                    weatherUpdateCounter++

                    if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_BATTERY_TEMP)) {
                        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                        val batteryTemp = batteryManager.getIntProperty(10) / 10.0
                        batteryTempText?.text = String.format("%.1f℃", batteryTemp)
                    }
                    if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_CPU_TEMP)) {
                        val cpuTemp = getCpuTemperature()
                        cpuTempText?.text = String.format("%.1f℃", cpuTemp)
                    }
                    if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_CURRENT) || SettingsManager.getBoolean(SettingsManager.KEY_SHOW_POWER)) {
                        val current = getBatteryCurrent()
                        val voltage = getBatteryVoltage()
                        val power = current * voltage
                        currentText?.text = String.format("%.0fmA", current * 1000)
                        powerText?.text = String.format("%.2fW", power)
                    }
                } catch (_: Exception) {}
                finally { mainHandler.postDelayed(this, 1000) }
            }
        }

        val networkRunnable = object : Runnable {
            override fun run() {
                try {
                    if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_NETWORK_SPEED)) {
                        val rxBytes = android.net.TrafficStats.getTotalRxBytes()
                        val txBytes = android.net.TrafficStats.getTotalTxBytes()
                        val now = System.currentTimeMillis()
                        val timeDiff = (now - lastNetworkUpdateTime) / 1000.0
                        if (timeDiff > 0) {
                            val rxSpeed = (rxBytes - lastTotalRxBytes) / timeDiff / 1024
                            val txSpeed = (txBytes - lastTotalTxBytes) / timeDiff / 1024
                            networkSpeedText?.text = String.format("%.2fKB/s△\n%.2fKB/s▼",txSpeed, rxSpeed)
                            lastTotalRxBytes = rxBytes
                            lastTotalTxBytes = txBytes
                            lastNetworkUpdateTime = now
                        }
                    }
                    if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_SIGNAL_STRENGTH)) {
                        dualSimSignalText?.text = getDualSimSignalStrength(context)
                    }
                } catch (_: Exception) {}
                finally { mainHandler.postDelayed(this, (SettingsManager.getInt(SettingsManager.KEY_NETWORK_UPDATE_INTERVAL) * 1000).toLong()) }
            }
        }

        mainHandler.post(oneSecondRunnable)
        mainHandler.post(networkRunnable)
    }

    private fun getMiuiWeather(context: Context): String {
        return try {
            val cursor: Cursor? = context.contentResolver.query(WEATHER_URI, arrayOf("weather_type", "temp"),null,null,null)
            if (cursor != null && cursor.moveToFirst()) {
                val weatherType = cursor.getInt(0)
                val temp = cursor.getInt(1)
                val weatherText = when (weatherType) {
                    0->"晴";1->"多云";2->"阴";3->"阵雨";4->"雷阵雨";5->"雷阵雨冰雹"
                    else->"未知"
                }
                cursor.close()
                "$weatherText ${temp}℃"
            } else "无天气"
        } catch (_: Exception) { "不可用" }
    }

    private fun getDualSimSignalStrength(context: Context): String = ""
    private fun getBatteryCurrent(): Double = 0.0
    private fun getBatteryVoltage(): Double = 3.7
    private fun getCpuTemperature(): Double = 0.0

    private fun setupColoredNotifications(classLoader: ClassLoader) {
        val miuiClass = classLoader.loadClass("com.android.systemui.statusbar.notification.row.MiuiNotificationContentView")
        XposedHelpers.findAndHookMethod(miuiClass, "updateNotification", Notification::class.java) { hookParam ->
            val notification = hookParam.args[0] as Notification
            val pkg = notification.packageName ?: return@findAndHookMethod
            if (pkg in excludedPackages) return@findAndHookMethod
        }
    }

    private fun extractDominantColor(drawable: Drawable): Int {
        val bitmap = if (drawable is BitmapDrawable) drawable.bitmap else {
            val bmp = Bitmap.createBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0,0,canvas.width,canvas.height)
            drawable.draw(canvas)
            bmp
        }
        val palette = Palette.from(bitmap).generate()
        return palette.getVibrantColor(palette.getDominantColor(-0x222222))
    }

    private fun adjustColorAlpha(color: Int, alpha: Float): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return Color.argb((alpha*255).toInt(),r,g,b)
    }

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}

