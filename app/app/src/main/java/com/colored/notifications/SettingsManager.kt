import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private const val PREFS_NAME = "StatusBarSettings"
    
    // 状态栏开关
    const val KEY_ENABLE_TRIPLE_ROW = "enable_triple_row"
    const val KEY_ENABLE_COLORED_NOTIFICATIONS = "enable_colored_notifications"
    
    // 显示开关
    const val KEY_SHOW_WEATHER = "show_weather"
    const val KEY_SHOW_NETWORK_SPEED = "show_network_speed"
    const val KEY_SHOW_SIGNAL_STRENGTH = "show_signal_strength"
    const val KEY_SHOW_BATTERY_TEMP = "show_battery_temp"
    const val KEY_SHOW_CPU_TEMP = "show_cpu_temp"
    const val KEY_SHOW_CURRENT = "show_current"
    const val KEY_SHOW_POWER = "show_power"
    const val KEY_SHOW_DECORATION_TEXT = "show_decoration_text"
    
    // 可调整参数
    const val KEY_DECORATION_TEXT = "decoration_text"
    const val KEY_NOTIFICATION_ALPHA = "notification_alpha"
    const val KEY_WEATHER_UPDATE_INTERVAL = "weather_update_interval"
    const val KEY_TEMP_UPDATE_INTERVAL = "temp_update_interval"
    const val KEY_NETWORK_UPDATE_INTERVAL = "network_update_interval"
    
    // 默认值
    private val defaults = mapOf(
        KEY_ENABLE_TRIPLE_ROW to true,
        KEY_ENABLE_COLORED_NOTIFICATIONS to true,
        KEY_SHOW_WEATHER to true,
        KEY_SHOW_NETWORK_SPEED to true,
        KEY_SHOW_SIGNAL_STRENGTH to true,
        KEY_SHOW_BATTERY_TEMP to true,
        KEY_SHOW_CPU_TEMP to true,
        KEY_SHOW_CURRENT to true,
        KEY_SHOW_POWER to true,
        KEY_SHOW_DECORATION_TEXT to true,
        KEY_DECORATION_TEXT to "☆眺望朦胧黎明 遥闻槐序馨香 照明万象更迭☆",
        KEY_NOTIFICATION_ALPHA to 0.15f,
        KEY_WEATHER_UPDATE_INTERVAL to 30,
        KEY_TEMP_UPDATE_INTERVAL to 5,
        KEY_NETWORK_UPDATE_INTERVAL to 1
    )

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getBoolean(key: String): Boolean {
        return prefs.getBoolean(key, defaults[key] as Boolean)
    }

    fun getString(key: String): String {
        return prefs.getString(key, defaults[key] as String) ?: ""
    }

    fun getFloat(key: String): Float {
        return prefs.getFloat(key, defaults[key] as Float)
    }

    fun getInt(key: String): Int {
        return prefs.getInt(key, defaults[key] as Int)
    }

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }
}
