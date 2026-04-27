package com.colored.notifications

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private lateinit var sp: SharedPreferences

    const val KEY_ENABLE_TRIPLE_ROW = "enable_triple_row"
    const val KEY_ENABLE_COLORED_NOTIFICATIONS = "enable_colored_notifications"
    const val KEY_SHOW_WEATHER = "show_weather"
    const val KEY_SHOW_NETWORK_SPEED = "show_network_speed"
    const val KEY_SHOW_SIGNAL_STRENGTH = "show_signal_strength"
    const val KEY_SHOW_BATTERY_TEMP = "show_battery_temp"
    const val KEY_SHOW_CPU_TEMP = "show_cpu_temp"
    const val KEY_SHOW_CURRENT = "show_current"
    const val KEY_SHOW_POWER = "show_power"
    const val KEY_SHOW_DECORATION_TEXT = "show_decoration_text"
    const val KEY_DECORATION_TEXT = "decoration_text"
    const val KEY_NOTIFICATION_ALPHA = "notification_alpha"
    const val KEY_WEATHER_UPDATE_INTERVAL = "weather_interval"
    const val KEY_NETWORK_UPDATE_INTERVAL = "network_interval"

    fun init(context: Context) {
        sp = context.getSharedPreferences("hyperos_mod_config", Context.MODE_PRIVATE)
    }

    fun getBoolean(key: String): Boolean = sp.getBoolean(key, false)
    fun getString(key: String): String = sp.getString(key, "") ?: ""
    fun getInt(key: String): Int = sp.getInt(key, 10)
    fun getFloat(key: String): Float = sp.getFloat(key, 0.15f)
}

