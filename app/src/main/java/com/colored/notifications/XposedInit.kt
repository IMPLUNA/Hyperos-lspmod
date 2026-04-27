package com.colored.notifications

import android.app.Notification
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.palette.graphics.Palette
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.annotations.HookMethod
import io.github.libxposed.api.utils.hookMethod

class XposedInit : XposedModule(false) {

    private val excludedPackages = setOf(
        "com.android.systemui",
        "com.miui.securitycenter",
        "com.miui.home",
        "com.android.phone"
    )

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != "com.android.systemui") return

        try {
            SettingsManager.init(moduleContext)

            if (SettingsManager.getBoolean(SettingsManager.KEY_ENABLE_TRIPLE_ROW)) {
                enableTripleRowStatusBar(param)
            }
            if (SettingsManager.getBoolean(SettingsManager.KEY_ENABLE_COLORED_NOTIFICATIONS)) {
                setupColoredNotifications(param)
            }
        } catch (e: Exception) {
            log("HyperOS Mod Init Error: ${e.message}", e)
        }
    }

    private fun enableTripleRowStatusBar(param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val cls = param.classLoader.loadClass("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView")
            cls.hookMethod("onFinishInflate", object : HookMethod {
                override fun afterHooked(param: MethodHookParam) {
                    // 三排状态栏功能留空，待后续实现
                }
            })
        } catch (e: Exception) {
            log("Triple row hook failed: ${e.message}", e)
        }
    }

    private fun setupColoredNotifications(param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val cls = param.classLoader.loadClass("com.android.systemui.statusbar.notification.row.MiuiNotificationContentView")
            cls.hookMethod(
                "updateNotification",
                Notification::class.java
            ) { param: MethodHookParam ->
                val notification = param.args[0] as Notification
                val pkg = notification.packageName ?: return@hookMethod
                if (pkg in excludedPackages) return@hookMethod

                val color = extractColor(notification)
                if (color == Color.TRANSPARENT) return@hookMethod

                // 后续可在此给通知卡片背景着色
                log("Notification from $pkg, dominant color: ${Integer.toHexString(color)}")
            }
        } catch (e: Exception) {
            log("Colored notification hook failed: ${e.message}", e)
        }
    }

    private fun extractColor(notification: Notification): Int {
        val icon = notification.largeIcon?.loadDrawable(moduleContext)
            ?: notification.smallIcon?.loadDrawable(moduleContext) ?: return Color.TRANSPARENT
        if (icon is BitmapDrawable) {
            val bitmap = icon.bitmap
            val palette = Palette.from(bitmap).generate()
            return palette.vibrantSwatch?.rgb
                ?: palette.dominantSwatch?.rgb
                ?: Color.TRANSPARENT
        }
        return Color.TRANSPARENT
    }
}
