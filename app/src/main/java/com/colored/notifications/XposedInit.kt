package com.colored.notifications

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import androidx.palette.graphics.Palette
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class XposedInit : XposedModule() {

    private val excludedPackages = setOf(
        "com.android.systemui",
        "com.miui.securitycenter",
        "com.miui.home",
        "com.android.phone"
    )

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // 确保Hook逻辑只在每个包第一次加载时注册，避免重复
        if (!param.isFirstPackage) return
        
        if (param.packageName != "com.android.systemui") return

        try {
            if (SettingsManager.getBoolean(SettingsManager.KEY_ENABLE_TRIPLE_ROW)) {
                enableTripleRowStatusBar(param)
            }
            if (SettingsManager.getBoolean(SettingsManager.KEY_ENABLE_COLORED_NOTIFICATIONS)) {
                setupColoredNotifications(param)
            }
        } catch (e: Exception) {
            Log.e("HyperOSMod", "Mod Error", e)
        }
    }

    private fun enableTripleRowStatusBar(param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val cls = param.classLoader.loadClass("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView")
            cls.getDeclaredMethod("onFinishInflate").hook {
                after {
                    val view = it.thisObject as? View
                    view?.let { v ->
                        SettingsManager.init(v.context.applicationContext)
                    }
                    Log.i("HyperOSMod", "Triple row status bar hooked")
                }
            }
        } catch (e: Exception) {
            Log.e("HyperOSMod", "Triple row hook failed", e)
        }
    }

    private fun setupColoredNotifications(param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val cls = param.classLoader.loadClass("com.android.systemui.statusbar.notification.row.MiuiNotificationContentView")
            cls.getDeclaredMethod("updateNotification", Notification::class.java).hook {
                after {
                    val notification = it.args[0] as? Notification ?: return@after
                    val view = it.thisObject as? View ?: return@after
                    val ctx = view.context

                    val pkg = getPackageNameReflect(notification) ?: return@after
                    if (pkg in excludedPackages) return@after

                    val color = extractColorReflect(notification, ctx)
                    if (color == Color.TRANSPARENT) return@after

                    Log.i("HyperOSMod", "Notification from $pkg, dominant color: ${Integer.toHexString(color)}")
                }
            }
        } catch (e: Exception) {
            Log.e("HyperOSMod", "Colored notification hook failed", e)
        }
    }

    // ---------- 你的反射工具方法保持不变 ----------
    private fun getPackageNameReflect(notification: Notification): String? {
        // ... 你的反射逻辑 ...
    }

    private fun extractColorReflect(notification: Notification, context: Context): Int {
        // ... 你的取色逻辑 ...
    }

    private fun getDominantColor(bitmap: Bitmap): Int {
        // ... 你的取色逻辑 ...
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        // ... 你的转Bitmap逻辑 ...
    }
}

// ---------- 官方Hook扩展函数 (必须保留) ----------
import io.github.libxposed.api.interfaces.MethodHook
import io.github.libxposed.api.utils.hook

fun java.lang.reflect.Method.hook(callback: MethodHook.() -> Unit) {
    io.github.libxposed.api.utils.hook(this, object : MethodHook {
        override fun beforeHooked(param: MethodHookParam) {
            callback.beforeHooked(param)
        }
        override fun afterHooked(param: MethodHookParam) {
            callback.afterHooked(param)
        }
    })
}
