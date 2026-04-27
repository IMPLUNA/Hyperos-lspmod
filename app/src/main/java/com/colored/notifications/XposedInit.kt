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

// LSP API 101 官方正确依赖导入
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.bean.MethodHookParam
import io.github.libxposed.api.interfaces.MethodHook
import io.github.libxposed.api.utils.XposedHelpers

import java.io.BufferedReader
import java.io.FileReader
import java.text.SimpleDateFormat
import java.util.*

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
            SettingsManager.init(param.moduleContext)
            if (SettingsManager.getBoolean(SettingsManager.KEY_ENABLE_TRIPLE_ROW)) {
                enableTripleRowStatusBar(param.classLoader)
            }
            if (SettingsManager.getBoolean(SettingsManager.KEY_ENABLE_COLORED_NOTIFICATIONS)) {
                setupColoredNotifications(param.classLoader)
            }
        } catch (e: Exception) {
            log("模块初始化错误: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun enableTripleRowStatusBar(classLoader: ClassLoader) {
        val statusBarClass = classLoader.loadClass("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView")

        XposedHelpers.findAndHookMethod(
            statusBarClass,
            "onFinishInflate",
            object : MethodHook {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val statusBarView = param.thisObject
                    val context = statusBarView.javaClass
                        .getDeclaredField("mContext")
                        .apply { isAccessible = true }
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
        )
    }

    private data class OriginalStatusBarViews(
        val leftContainer: android.view.ViewGroup,
        val rightContainer: android.view.ViewGroup,
        val centerClock: TextView,
        val batteryIcon: android.view.View
    )

    private fun getOriginalStatusBarViews(statusBarView: Any, context: Context): OriginalStatusBarViews {
        return OriginalStatusBarViews(
            leftContainer = statusBarView.findViewById(
                context.resources.getIdentifier("status_bar_left_container", "id", "com.android.systemui")
            ),
            rightContainer = statusBarView.findViewById(
                context.resources.getIdentifier("status_bar_right_container", "id", "com.android.systemui")
            ),
            centerClock = statusBarView.findViewById(
                context.resources.getIdentifier("clock", "id", "com.android.systemui")
            ),
            batteryIcon = statusBarView.findViewById(
                context.resources.getIdentifier("battery", "id", "com.android.systemui")
            )
        )
    }

    private fun createTripleRowLayout(context: Context, originalViews: OriginalStatusBarViews): android.view.ViewGroup {
        val tripleRowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(4.dpToPx(context), 2.dpToPx(context), 4.dpToPx(context), 2.dpToPx(context))
        }
        val topRow = createTopRow(context, originalViews)
        val middleRow = createMiddleRow(context, originalViews)
        tripleRowLayout.addView(topRow)
        tripleRowLayout.addView(middleRow)
        return tripleRowLayout
    }

    private fun createTopRow(context: Context, originalViews: OriginalStatusBarViews): LinearLayout {
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val topLeftInfo = createTopLeftInfo(context)
        val topRightInfo = createTopRightInfo(context, originalViews)
        topRow.addView(topLeftInfo)
        topRow.addView(topRightInfo)
        return topRow
    }

    private fun createTopLeftInfo(context: Context): LinearLayout {
        val topLeftInfo = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val timeDateWeatherText = TextView(context).apply {
            id = android.R.id.text1
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val decorationText = TextView(context).apply {
            id = android.R.id.text2
            text = SettingsManager.getString(SettingsManager.KEY_DECORATION_TEXT)
            textSize = 10f
            setTextColor(Color.argb(200, 255, 255, 255))
            visibility = if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_DECORATION_TEXT))
                android.view.View.VISIBLE else android.view.View.GONE
        }
        topLeftInfo.addView(timeDateWeatherText)
        topLeftInfo.addView(decorationText)
        return topLeftInfo
    }

    private fun createTopRightInfo(context: Context, originalViews: OriginalStatusBarViews): LinearLayout {
        val topRightInfo = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
        val networkSpeedText = TextView(context).apply {
            id = android.R.id.text3
            textSize = 11f
            setTextColor(Color.WHITE)
            visibility = if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_NETWORK_SPEED))
                android.view.View.VISIBLE else android.view.View.GONE
        }
        val dualSimSignalText = TextView(context).apply {
            id = android.R.id.text4
            textSize = 11f
            setTextColor(Color.WHITE)
            visibility = if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_SIGNAL_STRENGTH))
                android.view.View.VISIBLE else android.view.View.GONE
        }
        while (originalViews.rightContainer.childCount > 0) {
            val child = originalViews.rightContainer.getChildAt(0)
            originalViews.rightContainer.removeViewAt(0)
            topRightInfo.addView(child, 0)
        }
        topRightInfo.addView(dualSimSignalText, 0)
        topRightInfo.addView(networkSpeedText, 0)
        return topRightInfo
    }

    private fun createMiddleRow(context: Context, originalViews: OriginalStatusBarViews): LinearLayout {
        val middleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 2.dpToPx(context), 0, 2.dpToPx(context))
        }
        val notificationIcons = createNotificationIconsContainer(context, originalViews)
        val bottomRightInfo = createBottomRightInfo(context, originalViews)
        middleRow.addView(notificationIcons)
        middleRow.addView(bottomRightInfo)
        return middleRow
    }

    private fun createNotificationIconsContainer(context: Context, originalViews: OriginalStatusBarViews): LinearLayout {
        val notificationIcons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        }
        while (originalViews.leftContainer.childCount > 0) {
            val child = originalViews.leftContainer.getChildAt(0)
            originalViews.leftContainer.removeViewAt(0)
            notificationIcons.addView(child)
        }
        return notificationIcons
    }

    private fun createBottomRightInfo(context: Context, originalViews: OriginalStatusBarViews): LinearLayout {
        val bottomRightInfo = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
        bottomRightInfo.addView(originalViews.batteryIcon)
        val batteryTempText = TextView(context).apply {
            id = android.R.id.text5
            textSize = 11f
            setTextColor(Color.WHITE)
            visibility = if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_BATTERY_TEMP))
                android.view.View.VISIBLE else android.view.View.GONE
        }
        bottomRightInfo.addView(batteryTempText)
        val cpuTempText = TextView(context).apply {
            id = android.R.id.text6
            textSize = 11f
            setTextColor(Color.WHITE)
            visibility = if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_CPU_TEMP))
                android.view.View.VISIBLE else android.view.View.GONE
        }
        bottomRightInfo.addView(cpuTempText)
        val currentText = TextView(context).apply {
            id = android.R.id.text7
            textSize = 11f
            setTextColor(Color.WHITE)
            visibility = if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_CURRENT))
                android.view.View.VISIBLE else android.view.View.GONE
        }
        bottomRightInfo.addView(currentText)
        val powerText = TextView(context).apply {
            id = android.R.id.text8
            textSize = 11f
            setTextColor(Color.WHITE)
            visibility = if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_POWER))
                android.view.View.VISIBLE else android.view.View.GONE
        }
        bottomRightInfo.addView(powerText)
        return bottomRightInfo
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
                    if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_WEATHER) &&
                        weatherUpdateCounter % SettingsManager.getInt(SettingsManager.KEY_WEATHER_UPDATE_INTERVAL) == 0
                    ) {
                        val weather = getMiuiWeather(context)
                        timeString += " $weather ~Ciall~☆"
                    }
                    timeDateWeatherText?.text = timeString
                    weatherUpdateCounter++

                    if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_BATTERY_TEMP)) {
                        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                        val batteryTemp = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_TEMPERATURE) / 10.0
                        batteryTempText?.text = String.format("%.1f℃", batteryTemp)
                    }
                    if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_CPU_TEMP)) {
                        val cpuTemp = getCpuTemperature()
                        cpuTempText?.text = String.format("%.1f℃", cpuTemp)
                    }
                    if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_CURRENT) ||
                        SettingsManager.getBoolean(SettingsManager.KEY_SHOW_POWER)
                    ) {
                        val current = getBatteryCurrent()
                        val voltage = getBatteryVoltage()
                        val power = current * voltage
                        currentText?.text = String.format("%.0fmA", current * 1000)
                        powerText?.text = String.format("%.2fW", power)
                    }
                } catch (e: Exception) {
                    log("更新错误: ${e.message}")
                } finally {
                    mainHandler.postDelayed(this, 1000)
                }
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
                            networkSpeedText?.text = String.format(
                                "%.2fKB/s△\n%.2fKB/s▼",
                                txSpeed, rxSpeed
                            )
                            lastTotalRxBytes = rxBytes
                            lastTotalTxBytes = txBytes
                            lastNetworkUpdateTime = now
                        }
                    }
                    if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_SIGNAL_STRENGTH)) {
                        dualSimSignalText?.text = getDualSimSignalStrength(context)
                    }
                } catch (e: Exception) {
                    log("网络更新错误: ${e.message}")
                } finally {
                    mainHandler.postDelayed(this, (SettingsManager.getInt(SettingsManager.KEY_NETWORK_UPDATE_INTERVAL) * 1000).toLong())
                }
            }
        }

        mainHandler.post(oneSecondRunnable)
        mainHandler.post(networkRunnable)
    }

    private fun getMiuiWeather(context: Context): String {
        return try {
            val cursor: Cursor? = context.contentResolver.query(
                WEATHER_URI,
                arrayOf("weather_type", "temp"),
                null, null, null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val weatherType = cursor.getInt(0)
                val temp = cursor.getInt(1)
                val weatherText = when (weatherType) {
                    0 -> "晴"
                    1 -> "多云"
                    2 -> "阴"
                    3 -> "阵雨"
                    4 -> "雷阵雨"
                    5 -> "雷阵雨伴有冰雹"
                    6 -> "雨夹雪"
                    7 -> "小雨"
                    8 -> "中雨"
                    9 -> "大雨"
                    10 -> "暴雨"
                    11 -> "大暴雨"
                    12 -> "特大暴雨"
                    13 -> "阵雪"
                    14 -> "小雪"
                    15 -> "中雪"
                    16 -> "大雪"
                    17 -> "暴雪"
                    18 -> "雾"
                    19 -> "冻雨"
                    20 -> "沙尘暴"
                    else -> "未知"
                }
                cursor.close()
                "$weatherText ${temp}℃"
            } else "获取天气失败"
        } catch (e: Exception) {
            "天气服务未启用"
        }
    }

    private fun getDualSimSignalStrength(context: Context): String {
        return ""
    }

    private fun getBatteryCurrent(): Double {
        return try {
            val br = BufferedReader(FileReader("/sys/class/power_supply/battery/current_now"))
            val current = br.readLine().toDouble() / 1000000.0
            br.close()
            kotlin.math.abs(current)
        } catch (e: Exception) {
            0.0
        }
    }

    private fun getBatteryVoltage(): Double {
        return try {
            val br = BufferedReader(FileReader("/sys/class/power_supply/voltage_now"))
            val voltage = br.readLine().toDouble() / 1000000.0
            br.close()
            voltage
        } catch (e: Exception) {
            3.7
        }
    }

    private fun getCpuTemperature(): Double {
        return 0.0
    }

    private fun setupColoredNotifications(classLoader: ClassLoader) {
        val miuiClass = classLoader.loadClass("com.android.systemui.statusbar.notification.row.MiuiNotificationContentView")
        XposedHelpers.findAndHookMethod(miuiClass, "updateNotification", Notification::class.java) { param ->
            val notification = param.args[0] as Notification
            val packageName = notification.packageName
            if (packageName in excludedPackages) return@findAndHookMethod
        }
    }

    private fun extractDominantColor(drawable: Drawable): Int {
        val bitmap = if (drawable is BitmapDrawable) drawable.bitmap else {
            val bmp = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        }
        val palette = Palette.from(bitmap).generate()
        return palette.getVibrantColor(palette.getDominantColor(0xFF6200EE.toInt()))
    }

    private fun adjustColorAlpha(color: Int, alpha: Float): Int {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return Color.argb((alpha * 255).toInt(), red, green, blue)
    }

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}

