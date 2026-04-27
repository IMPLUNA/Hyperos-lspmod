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
import android.widget.TextView
import androidx.palette.graphics.Palette
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.XposedEntryPoint
import java.io.BufferedReader
import java.io.FileReader
import java.text.SimpleDateFormat
import java.util.*

@XposedEntryPoint
class XposedInit : XposedModule {
    // 排除这些系统应用的通知着色
    private val excludedPackages = setOf(
        "com.android.systemui",
        "com.miui.securitycenter",
        "com.miui.home",
        "com.android.phone"
    )

    // 小米系统天气ContentProvider（米客同款）
    private val WEATHER_URI = Uri.parse("content://com.miui.weather2.provider/weather")

    // 全局Handler用于UI更新
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // 只Hook系统界面
        if (param.packageName != "com.android.systemui") return

        try {
            // 初始化设置管理器
            SettingsManager.init(param.moduleContext)

            // 根据设置启用功能
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

    // ==================== 三排状态栏核心实现 ====================
    private fun enableTripleRowStatusBar(classLoader: ClassLoader) {
        val statusBarClass = classLoader.loadClass(
            "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView"
        )

        hookMethod(statusBarClass, "onFinishInflate") { callback ->
            val statusBarView = callback.thisObject
            val context = statusBarView.javaClass
                .getDeclaredField("mContext")
                .apply { isAccessible = true }
                .get(statusBarView) as Context

            // 获取原状态栏视图组件
            val originalViews = getOriginalStatusBarViews(statusBarView, context)
            
            // 隐藏原中心时钟
            originalViews.centerClock.visibility = android.view.View.GONE

            // 创建并设置三排布局
            val tripleRowLayout = createTripleRowLayout(context, originalViews)
            
            // 替换原状态栏内容
            val statusBarContent = statusBarView.findViewById<android.view.ViewGroup>(
                context.resources.getIdentifier("status_bar_contents", "id", "com.android.systemui")
            )
            statusBarContent.removeAllViews()
            statusBarContent.addView(tripleRowLayout)

            // 启动所有信息更新任务（不同信息不同更新频率）
            startScheduledUpdates(context, originalViews)
        }
    }

    // 数据类：存储原状态栏视图引用
    private data class OriginalStatusBarViews(
        val leftContainer: android.view.ViewGroup,
        val rightContainer: android.view.ViewGroup,
        val centerClock: TextView,
        val batteryIcon: android.view.View
    )

    // 获取原状态栏所有需要的视图
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

    // 创建三排主布局
    private fun createTripleRowLayout(context: Context, originalViews: OriginalStatusBarViews): android.view.ViewGroup {
        // 主垂直布局
        val tripleRowLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(4.dpToPx(context), 2.dpToPx(context), 4.dpToPx(context), 2.dpToPx(context))
        }

        // 创建第一排和第二排
        val topRow = createTopRow(context, originalViews)
        val middleRow = createMiddleRow(context, originalViews)

        // 添加到主布局
        tripleRowLayout.addView(topRow)
        tripleRowLayout.addView(middleRow)

        return tripleRowLayout
    }

    // 创建第一排：时间+天气+装饰文字 + 网速+系统图标+信号强度
    private fun createTopRow(context: Context, originalViews: OriginalStatusBarViews): android.view.ViewGroup {
        val topRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 左上角信息
        val topLeftInfo = createTopLeftInfo(context)
        
        // 右上角信息
        val topRightInfo = createTopRightInfo(context, originalViews)

        topRow.addView(topLeftInfo)
        topRow.addView(topRightInfo)

        return topRow
    }

    // 创建左上角信息区域
    private fun createTopLeftInfo(context: Context): android.view.ViewGroup {
        val topLeftInfo = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        // 时间日期天气文本
        val timeDateWeatherText = TextView(context).apply {
            id = android.R.id.text1
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        // 装饰文字
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

    // 创建右上角信息区域
    private fun createTopRightInfo(context: Context, originalViews: OriginalStatusBarViews): android.view.ViewGroup {
        val topRightInfo = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            spacing = 6.dpToPx(context)
        }

        // 网络速度文本
        val networkSpeedText = TextView(context).apply {
            id = android.R.id.text3
            textSize = 11f
            setTextColor(Color.WHITE)
            visibility = if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_NETWORK_SPEED)) 
                android.view.View.VISIBLE else android.view.View.GONE
        }

        // 双卡信号强度文本
        val dualSimSignalText = TextView(context).apply {
            id = android.R.id.text4
            textSize = 11f
            setTextColor(Color.WHITE)
            visibility = if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_SIGNAL_STRENGTH)) 
                android.view.View.VISIBLE else android.view.View.GONE
        }

        // 将原系统图标移到这里
        while (originalViews.rightContainer.childCount > 0) {
            val child = originalViews.rightContainer.getChildAt(0)
            originalViews.rightContainer.removeViewAt(0)
            topRightInfo.addView(child, 0)
        }

        // 添加信号强度和网速
        topRightInfo.addView(dualSimSignalText, 0)
        topRightInfo.addView(networkSpeedText, 0)

        return topRightInfo
    }

    // 创建第二排：通知图标 + 电池+温度+电流+功耗
    private fun createMiddleRow(context: Context, originalViews: OriginalStatusBarViews): android.view.ViewGroup {
        val middleRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 2.dpToPx(context), 0, 2.dpToPx(context))
        }

        // 左下角通知图标
        val notificationIcons = createNotificationIconsContainer(context, originalViews)
        
        // 右下角状态信息
        val bottomRightInfo = createBottomRightInfo(context, originalViews)

        middleRow.addView(notificationIcons)
        middleRow.addView(bottomRightInfo)

        return middleRow
    }

    // 创建通知图标容器
    private fun createNotificationIconsContainer(context: Context, originalViews: OriginalStatusBarViews): android.view.ViewGroup {
        val notificationIcons = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                0,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            spacing = 8.dpToPx(context)
        }

        // 将原通知图标移到这里
        while (originalViews.leftContainer.childCount > 0) {
            val child = originalViews.leftContainer.getChildAt(0)
            originalViews.leftContainer.removeViewAt(0)
            notificationIcons.addView(child)
        }

        return notificationIcons
    }

    // 创建右下角状态信息区域
    private fun createBottomRightInfo(context: Context, originalViews: OriginalStatusBarViews): android.view.ViewGroup {
        val bottomRightInfo = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            spacing = 8.dpToPx(context)
        }

        // 电池图标
        bottomRightInfo.addView(originalViews.batteryIcon)

        // 电池温度
        val batteryTempText = TextView(context).apply {
            id = android.R.id.text5
            textSize = 11f
            setTextColor(Color.WHITE)
            visibility = if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_BATTERY_TEMP)) 
                android.view.View.VISIBLE else android.view.View.GONE
        }
        bottomRightInfo.addView(batteryTempText)

        // CPU温度
        val cpuTempText = TextView(context).apply {
            id = android.R.id.text6
            textSize = 11f
            setTextColor(Color.WHITE)
            visibility = if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_CPU_TEMP)) 
                android.view.View.VISIBLE else android.view.View.GONE
        }
        bottomRightInfo.addView(cpuTempText)

        // 电流
        val currentText = TextView(context).apply {
            id = android.R.id.text7
            textSize = 11f
            setTextColor(Color.WHITE)
            visibility = if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_CURRENT)) 
                android.view.View.VISIBLE else android.view.View.GONE
        }
        bottomRightInfo.addView(currentText)

        // 功耗
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

    // 启动所有定时更新任务（不同信息不同频率，优化性能）
    private fun startScheduledUpdates(context: Context, originalViews: OriginalStatusBarViews) {
        // 查找所有文本视图
        val timeDateWeatherText = originalViews.batteryIcon.rootView.findViewById<TextView>(android.R.id.text1)
        val networkSpeedText = originalViews.batteryIcon.rootView.findViewById<TextView>(android.R.id.text3)
        val dualSimSignalText = originalViews.batteryIcon.rootView.findViewById<TextView>(android.R.id.text4)
        val batteryTempText = originalViews.batteryIcon.rootView.findViewById<TextView>(android.R.id.text5)
        val cpuTempText = originalViews.batteryIcon.rootView.findViewById<TextView>(android.R.id.text6)
        val currentText = originalViews.batteryIcon.rootView.findViewById<TextView>(android.R.id.text7)
        val powerText = originalViews.batteryIcon.rootView.findViewById<TextView>(android.R.id.text8)

        // 网络速度统计变量
        var lastTotalRxBytes = 0L
        var lastTotalTxBytes = 0L
        var lastNetworkUpdateTime = System.currentTimeMillis()

        // 天气更新计数器
        var weatherUpdateCounter = 0

        // 1秒更新一次：时间、温度、电流、功耗
        val oneSecondRunnable = object : Runnable {
            override fun run() {
                try {
                    // 更新时间
                    val timeFormat = SimpleDateFormat("~HH:mm:ss EEEE dd日", Locale.CHINA)
                    var timeString = timeFormat.format(Date())

                    // 每30秒更新一次天气
                    if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_WEATHER) && 
                        weatherUpdateCounter % SettingsManager.getInt(SettingsManager.KEY_WEATHER_UPDATE_INTERVAL) == 0) {
                        val weather = getMiuiWeather(context)
                        timeString += " $weather ~Ciall~☆"
                    }
                    timeDateWeatherText?.text = timeString
                    weatherUpdateCounter++

                    // 更新电池温度
                    if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_BATTERY_TEMP)) {
                        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                        val batteryTemp = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_TEMPERATURE) / 10.0
                        batteryTempText?.text = String.format("%.1f℃", batteryTemp)
                    }

                    // 更新CPU温度
                    if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_CPU_TEMP)) {
                        val cpuTemp = getCpuTemperature()
                        cpuTempText?.text = String.format("%.1f℃", cpuTemp)
                    }

                    // 更新电流和功耗
                    if (SettingsManager.getBoolean(SettingsManager.KEY_SHOW_CURRENT) || 
                        SettingsManager.getBoolean(SettingsManager.KEY_SHOW_POWER)) {
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

        // 1秒更新一次：网络速度和信号强度
        val networkRunnable = object : Runnable {
            override fun run() {
                try {
                    // 更新网络速度
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

                    // 更新信号强度
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

        // 启动更新任务
        mainHandler.post(oneSecondRunnable)
        mainHandler.post(networkRunnable)
    }

    // ==================== 系统信息获取方法 ====================
    // 获取小米系统天气（米客同款实现）
    private fun getMiuiWeather(context: Context): String {
        return try {
            val cursor: Cursor? = context.contentResolver.query(
                WEATHER_URI,
                arrayOf("weather_type", "temp"),
                null,
                null,
                null
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
                    21 -> "小雨转中雨"
                    22 -> "中雨转大雨"
                    23 -> "大雨转暴雨"
                    24 -> "暴雨转大暴雨"
                    25 -> "大暴雨转特大暴雨"
                    26 -> "小雪转中雪"
                    27 -> "中雪转大雪"
                    28 -> "大雪转暴雪"
                    29 -> "浮尘"
                    30 -> "扬沙"
                    31 -> "强沙尘暴"
                    else -> "未知"
                }
                
                cursor.close()
                "$weatherText ${temp}℃"
            } else {
                "获取天气失败"
            }
        } catch (e: Exception) {
            "天气服务未启用"
        }
    }

    // 获取双卡信号强度
    private fun getDualSimSignalStrength(context: Context): String {
        return try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
            
            if (activeSubscriptions == null || activeSubscriptions.isEmpty()) {
                return ""
            }

            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val signalStrings = mutableListOf<String>()

            for (subscription in activeSubscriptions) {
                val subId = subscription.subscriptionId
                val subTelephonyManager = telephonyManager.createForSubscriptionId(subId)
                val signalStrength = subTelephonyManager.signalStrength ?: continue

                val dbm = when {
                    signalStrength.is5gAvailable -> signalStrength.nrDbm
                    signalStrength.isLteAvailable -> signalStrength.lteDbm
                    else -> -113 + 2 * signalStrength.gsmSignalStrength
                }

                if (dbm > -140 && dbm < 0) {
                    signalStrings.add("${dbm}dBm")
                }
            }

            signalStrings.joinToString("\n")
        } catch (e: Exception) {
            ""
        }
    }

    // 读取电池电流（单位：安培）
    private fun getBatteryCurrent(): Double {
        return try {
            val br = BufferedReader(FileReader("/sys/class/power_supply/battery/current_now"))
            val current = br.readLine().toDouble() / 1000000.0
            br.close()
            Math.abs(current)
        } catch (e: Exception) {
            0.0
        }
    }

    // 读取电池电压（单位：伏特）
    private fun getBatteryVoltage(): Double {
        return try {
            val br = BufferedReader(FileReader("/sys/class/power_supply/battery/voltage_now"))
            val voltage = br.readLine().toDouble() / 1000000.0
            br.close()
            voltage
        } catch (e: Exception) {
            3.7
        }
    }

    // 读取CPU温度（单位：摄氏度）
    private fun getCpuTemperature(): Double {
        return try {
            // 小米/红米通用CPU温度路径
            val paths = arrayOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp",
                "/sys/class/thermal/thermal_zone2/temp",
                "/sys/class/thermal/thermal_zone3/temp"
            )
            
            for (path in paths) {
                try {
                    val br = BufferedReader(FileReader(path))
                    val temp = br.readLine().toDouble() / 1000.0
                    br.close()
                    if (temp > 20 && temp < 100) {
                        return temp
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            0.0
        } catch (e: Exception) {
            0.0
        }
    }

    // ==================== 通知着色功能 ====================
    private fun setupColoredNotifications(classLoader: ClassLoader) {
        // HyperOS 2.0 专属hook点
        val miuiClass = classLoader.loadClass(
            "com.android.systemui.statusbar.notification.row.MiuiNotificationContentView"
        )
        
        hookMethod(miuiClass, "updateNotification", Notification::class.java) { callback ->
            val notification = callback.args[0] as Notification
            val packageName = notification.packageName
            
            if (packageName in excludedPackages) return@hookMethod
            
            val context = callback.thisObject.javaClass
                .getDeclaredField("mContext")
                .apply { isAccessible = true }
                .get(callback.thisObject) as Context

            // 从设置中获取透明度
            val alpha = SettingsManager.getFloat(SettingsManager.KEY_NOTIFICATION_ALPHA)
            
            val appIcon = context.packageManager.getApplicationIcon(packageName)
            val dominantColor = extractDominantColor(appIcon)
            val backgroundColor = adjustColorAlpha(dominantColor, alpha)
            
            val backgroundId = context.resources.getIdentifier(
                "notification_background",
                "id",
                "com.android.systemui"
            )

            notification.contentView?.setInt(backgroundId, "setBackgroundColor", backgroundColor)
            notification.bigContentView?.setInt(backgroundId, "setBackgroundColor", backgroundColor)
            notification.mediaContentView?.setInt(backgroundId, "setBackgroundColor", backgroundColor)
        }

        // 原生Android兼容
        val nativeClass = classLoader.loadClass(
            "com.android.systemui.statusbar.notification.row.NotificationContentView"
        )
        
        hookMethod(nativeClass, "updateNotification", Notification::class.java) { callback ->
            val notification = callback.args[0] as Notification
            val packageName = notification.packageName
            
            if (packageName in excludedPackages) return@hookMethod
            
            val context = callback.thisObject.javaClass
                .getDeclaredField("mContext")
                .apply { isAccessible = true }
                .get(callback.thisObject) as Context

            // 从设置中获取透明度
            val alpha = SettingsManager.getFloat(SettingsManager.KEY_NOTIFICATION_ALPHA)
            
            val appIcon = context.packageManager.getApplicationIcon(packageName)
            val dominantColor = extractDominantColor(appIcon)
            val backgroundColor = adjustColorAlpha(dominantColor, alpha)

            notification.contentView?.setInt(android.R.id.notification_background, "setBackgroundColor", backgroundColor)
            notification.bigContentView?.setInt(android.R.id.notification_background, "setBackgroundColor", backgroundColor)
        }
    }

    /**
     * 从Drawable中提取主色调
     * 优先使用鲜艳色，如果没有则使用主色调
     */
    private fun extractDominantColor(drawable: Drawable): Int {
        val bitmap = if (drawable is BitmapDrawable) {
            drawable.bitmap
        } else {
            // 将Drawable转换为Bitmap
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        }

        // 使用Palette库提取颜色
        val palette = Palette.from(bitmap).generate()
        return palette.getVibrantColor(palette.getDominantColor(0xFF6200EE.toInt()))
    }

    /**
     * 调整颜色的透明度
     * @param color 原始颜色
     * @param alpha 透明度(0-1)
     * @return 调整后的颜色
     */
    private fun adjustColorAlpha(color: Int, alpha: Float): Int {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return Color.argb((alpha * 255).toInt(), red, green, blue)
    }

    // ==================== 工具方法 ====================
    /**
     * dp转px
     */
    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }

    /**
     * Hook方法的工具函数
     */
    private fun hookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>,
        callback: (XposedInterface.AfterHookCallback) -> Unit
    ) {
        XposedInterface.hookMethod(
            clazz.getDeclaredMethod(methodName, *parameterTypes),
            object : XposedInterface.MethodHook {
                @AfterInvocation
                override fun after(param: XposedInterface.AfterHookCallback) {
                    try {
                        callback(param)
                    } catch (e: Exception) {
                        log("Hook错误: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        )
    }
}
