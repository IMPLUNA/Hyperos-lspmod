package com.colored.notifications

import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        SettingsManager.init(applicationContext)

        val s1: Switch = findViewById(R.id.switch_triple_row)
        val s2: Switch = findViewById(R.id.switch_color_notify)
        val s3: Switch = findViewById(R.id.switch_weather)
        val s4: Switch = findViewById(R.id.switch_net_speed)
        val s5: Switch = findViewById(R.id.switch_signal)
        val s6: Switch = findViewById(R.id.switch_bat_temp)
        val s7: Switch = findViewById(R.id.switch_cpu_temp)
        val s8: Switch = findViewById(R.id.switch_current)
        val s9: Switch = findViewById(R.id.switch_power)
        val s10: Switch = findViewById(R.id.switch_deco_text)
        val btnRefresh: Button = findViewById(R.id.btn_refresh_log)
        val tvLog: TextView = findViewById(R.id.tv_log)

        s1.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_ENABLE_TRIPLE_ROW)
        s2.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_ENABLE_COLORED_NOTIFICATIONS)
        s3.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_SHOW_WEATHER)
        s4.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_SHOW_NETWORK_SPEED)
        s5.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_SHOW_SIGNAL_STRENGTH)
        s6.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_SHOW_BATTERY_TEMP)
        s7.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_SHOW_CPU_TEMP)
        s8.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_SHOW_CURRENT)
        s9.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_SHOW_POWER)
        s10.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_SHOW_DECORATION_TEXT)

        val sp = getSharedPreferences("hyperos_mod_config", MODE_PRIVATE).edit()
        s1.setOnCheckedChangeListener { _, b -> sp.putBoolean(SettingsManager.KEY_ENABLE_TRIPLE_ROW, b).apply() }
        s2.setOnCheckedChangeListener { _, b -> sp.putBoolean(SettingsManager.KEY_ENABLE_COLORED_NOTIFICATIONS, b).apply() }
        s3.setOnCheckedChangeListener { _, b -> sp.putBoolean(SettingsManager.KEY_SHOW_WEATHER, b).apply() }
        s4.setOnCheckedChangeListener { _, b -> sp.putBoolean(SettingsManager.KEY_SHOW_NETWORK_SPEED, b).apply() }
        s5.setOnCheckedChangeListener { _, b -> sp.putBoolean(SettingsManager.KEY_SHOW_SIGNAL_STRENGTH, b).apply() }
        s6.setOnCheckedChangeListener { _, b -> sp.putBoolean(SettingsManager.KEY_SHOW_BATTERY_TEMP, b).apply() }
        s7.setOnCheckedChangeListener { _, b -> sp.putBoolean(SettingsManager.KEY_SHOW_CPU_TEMP, b).apply() }
        s8.setOnCheckedChangeListener { _, b -> sp.putBoolean(SettingsManager.KEY_SHOW_CURRENT, b).apply() }
        s9.setOnCheckedChangeListener { _, b -> sp.putBoolean(SettingsManager.KEY_SHOW_POWER, b).apply() }
        s10.setOnCheckedChangeListener { _, b -> sp.putBoolean(SettingsManager.KEY_SHOW_DECORATION_TEXT, b).apply() }

        btnRefresh.setOnClickListener {
            loadLog(tvLog)
        }
        loadLog(tvLog)
    }

    private fun loadLog(tvLog: TextView) {
        val sp = getSharedPreferences("log_sp", MODE_PRIVATE)
        val logs = sp.getString("logs", "")
        tvLog.text = if (logs.isNullOrEmpty()) "暂无日志" else logs
    }
}
