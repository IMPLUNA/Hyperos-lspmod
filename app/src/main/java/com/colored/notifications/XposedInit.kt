package com.colored.notifications

import android.app.Notification
import android.content.Context
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
import io.github.libxposed.api.utils.XposedHelpers

class XposedInit : XposedModule() {

    private val excludedPackages = setOf(
        "com.android.systemui",
        "com.miui.securitycenter",
        "com.miui.home",
        "com.android.phone"
    )

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
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
            XposedHelpers.findAndHookMethod(cls, "onFinishInflate", object : io.github.libxposed.api.interfaces.MethodHook {
                override fun afterHooked(param: MethodHookParam) {
                    val view = param.thisObject as? View
                    view?.let {
                        SettingsManager.init(it.context.applicationContext)
                    }
                    Log.i("HyperOSMod", "Triple row status bar hooked")
                }
            })
        } catch (e: Exception) {
            Log.e("HyperOSMod", "Triple row hook failed", e)
        }
    }

    private fun setupColoredNotifications(param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val cls = param.classLoader.loadClass("com.android.systemui.statusbar.notification.row.MiuiNotificationContentView")
            XposedHelpers.findAndHookMethod(cls, "updateNotification", Notification::class.java, object : io.github.libxposed.api.interfaces.MethodHook {
                override fun afterHooked(param: MethodHookParam) {
                    val notification = param.args[0] as? Notification ?: return
                    val view = param.thisObject as? View ?: return
                    val ctx = view.context

                    val pkg = getPackageNameReflect(notification) ?: return
                    if (pkg in excludedPackages) return

                    val color = extractColorReflect(notification, ctx)
                    if (color == Color.TRANSPARENT) return

                    Log.i("HyperOSMod", "Notification from $pkg, dominant color: ${Integer.toHexString(color)}")
                }
            })
        } catch (e: Exception) {
            Log.e("HyperOSMod", "Colored notification hook failed", e)
        }
    }

    // ---------- 你的反射工具方法保持不变 ----------
    private fun getPackageNameReflect(notification: Notification): String? {
        return try {
            val field = Notification::class.java.getDeclaredField("packageName")
            field.isAccessible = true
            field.get(notification) as? String
        } catch (e: Exception) {
            Log.e("HyperOSMod", "Failed to get packageName via reflection", e)
            null
        } ?: notification.extras?.getString("android.extra.PACKAGE")
    }

    private fun extractColorReflect(notification: Notification, context: Context): Int {
        try {
            val icon = try {
                notification.largeIcon
            } catch (_: Exception) {
                null
            } ?: try {
                notification.smallIcon
            } catch (_: Exception) {
                null
            } ?: return Color.TRANSPARENT

            val loadMethod = icon.javaClass.getMethod("loadDrawable", Context::class.java)
            val drawable = loadMethod.invoke(icon, context) as? Drawable ?: return Color.TRANSPARENT

            val bitmap = if (drawable is BitmapDrawable) drawable.bitmap else drawableToBitmap(drawable)
            return bitmap?.let { getDominantColor(it) } ?: Color.TRANSPARENT
        } catch (e: Exception) {
            Log.e("HyperOSMod", "Failed to extract color", e)
            return Color.TRANSPARENT
        }
    }

    private fun getDominantColor(bitmap: Bitmap): Int {
        val palette = Palette.from(bitmap).generate()
        return palette.vibrantSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
            ?: Color.TRANSPARENT
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable) return drawable.bitmap
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bitmap
    }
}
