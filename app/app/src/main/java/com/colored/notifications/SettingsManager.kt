import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.colored.notifications.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        SettingsManager.init(this)
        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        binding.switchTripleRow.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_ENABLE_TRIPLE_ROW)
        binding.switchColoredNotifications.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_ENABLE_COLORED_NOTIFICATIONS)
        
        binding.switchWeather.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_SHOW_WEATHER)
        binding.switchNetworkSpeed.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_SHOW_NETWORK_SPEED)
        binding.switchSignalStrength.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_SHOW_SIGNAL_STRENGTH)
        binding.switchBatteryTemp.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_SHOW_BATTERY_TEMP)
        binding.switchCpuTemp.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_SHOW_CPU_TEMP)
        binding.switchCurrent.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_SHOW_CURRENT)
        binding.switchPower.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_SHOW_POWER)
        binding.switchDecorationText.isChecked = SettingsManager.getBoolean(SettingsManager.KEY_SHOW_DECORATION_TEXT)
        
        binding.etDecorationText.setText(SettingsManager.getString(SettingsManager.KEY_DECORATION_TEXT))
        binding.sliderNotificationAlpha.value = SettingsManager.getFloat(SettingsManager.KEY_NOTIFICATION_ALPHA)
        binding.tvNotificationAlpha.text = String.format("%.2f", SettingsManager.getFloat(SettingsManager.KEY_NOTIFICATION_ALPHA))
    }

    private fun setupListeners() {
        binding.switchTripleRow.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.putBoolean(SettingsManager.KEY_ENABLE_TRIPLE_ROW, isChecked)
            showRestartToast()
        }

        binding.switchColoredNotifications.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.putBoolean(SettingsManager.KEY_ENABLE_COLORED_NOTIFICATIONS, isChecked)
            showRestartToast()
        }

        binding.switchWeather.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.putBoolean(SettingsManager.KEY_SHOW_WEATHER, isChecked)
        }

        binding.switchNetworkSpeed.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.putBoolean(SettingsManager.KEY_SHOW_NETWORK_SPEED, isChecked)
        }

        binding.switchSignalStrength.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.putBoolean(SettingsManager.KEY_SHOW_SIGNAL_STRENGTH, isChecked)
        }

        binding.switchBatteryTemp.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.putBoolean(SettingsManager.KEY_SHOW_BATTERY_TEMP, isChecked)
        }

        binding.switchCpuTemp.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.putBoolean(SettingsManager.KEY_SHOW_CPU_TEMP, isChecked)
        }

        binding.switchCurrent.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.putBoolean(SettingsManager.KEY_SHOW_CURRENT, isChecked)
        }

        binding.switchPower.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.putBoolean(SettingsManager.KEY_SHOW_POWER, isChecked)
        }

        binding.switchDecorationText.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.putBoolean(SettingsManager.KEY_SHOW_DECORATION_TEXT, isChecked)
        }

        binding.btnSaveDecoration.setOnClickListener {
            SettingsManager.putString(SettingsManager.KEY_DECORATION_TEXT, binding.etDecorationText.text.toString())
            Toast.makeText(this, "装饰文字已保存", Toast.LENGTH_SHORT).show()
        }

        binding.sliderNotificationAlpha.addOnChangeListener { _, value, _ ->
            binding.tvNotificationAlpha.text = String.format("%.2f", value)
            SettingsManager.putFloat(SettingsManager.KEY_NOTIFICATION_ALPHA, value)
            showRestartToast()
        }

        binding.btnRestartSystemUI.setOnClickListener {
            try {
                Runtime.getRuntime().exec("su -c killall com.android.systemui")
                Toast.makeText(this, "系统界面正在重启", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "需要Root权限才能一键重启", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showRestartToast() {
        Toast.makeText(this, "更改将在重启系统界面后生效", Toast.LENGTH_SHORT).show()
    }
}

