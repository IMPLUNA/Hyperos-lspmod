package com.colored.notifications

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import androidx.palette.graphics.Palette
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method

class XposedInit : XposedModule() {

    private val excludedPackages = setOf(
        "com.android.systemui",
        "com.miui.securitycenter",
        "com.miui.home",
        "com.android.phone"
    )

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // 只 Hook 系统界面
        if (param.packageName != "com.android.systemui") return

        try {
            // 将SettingsManager初始化延迟到Hook中获取Context后执行
            if (SettingsManager.getBoolean(SettingsManager.KEY_ENABLE_TRIPLE_ROW)) {
                enableTripleRowStatusBar(param)
            }
            if (SettingsManager.getBoolean(SettingsManager.KEY_ENABLE_COLORED_NOTIFICATIONS)) {
                setupColoredNotifications(param)
            }
        } catch (e: Exception) {
            log("HyperOS Mod Error: ${e.message}")
        }
    }

    // ---------- 三排状态栏 ----------
    private fun enableTripleRowStatusBar(param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val cls = param.classLoader.loadClass("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView")
            val method = cls.getDeclaredMethod("onFinishInflate")
            hookMethodViaXposedBridge(method) { xparam ->
                val view = xparam.thisObject as? View
                view?.let {
                    // 用系统UI提供的Context初始化SharedPreferences
                    SettingsManager.init(it.context.applicationContext)
                }
                log("Triple row status bar: onFinishInflate hooked")
            }
        } catch (e: Exception) {
            log("Triple row hook failed: ${e.message}")
        }
    }

    // ---------- 通知自动着色 ----------
    private fun setupColoredNotifications(param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val cls = param.classLoader.loadClass("com.android.systemui.statusbar.notification.row.MiuiNotificationContentView")
            val method = cls.getDeclaredMethod("updateNotification", Notification::class.java)
            hookMethodViaXposedBridge(method) { xparam ->
                val notification = xparam.args[0] as? Notification ?: return@hookMethodViaXposedBridge
                val view = xparam.thisObject as? View ?: return@hookMethodViaXposedBridge
                val ctx = view.context

                // 获取通知对应应用包名
                val pkg = notification.extras?.getString(Notification.EXTRA_TEMPLATE_PACKAGE) ?: return@hookMethodViaXposedBridge
                if (pkg in excludedPackages) return@hookMethodViaXposedBridge

                // 提取图标主色
                val color = extractColor(notification, ctx)
                if (color == Color.TRANSPARENT) return@hookMethodViaXposedBridge

                // 这里可以给通知卡片设置背景色（此处仅记录日志）
                log("Notification from $pkg, dominant color: ${Integer.toHexString(color)}")
            }
        } catch (e: Exception) {
            log("Colored notification hook failed: ${e.message}")
        }
    }

    // ---------- 颜色提取 ----------
    private fun extractColor(notification: Notification, context: Context): Int {
        val iconDrawable: Drawable? =
            notification.largeIcon?.loadDrawable(context) ?: notification.smallIcon?.loadDrawable(context)
        if (iconDrawable is BitmapDrawable) {
            return getDominantColor(iconDrawable.bitmap)
        }
        val bmp = drawableToBitmap(iconDrawable) ?: return Color.TRANSPARENT
        return getDominantColor(bmp)
    }

    private fun getDominantColor(bitmap: Bitmap): Int {
        val palette = Palette.from(bitmap).generate()
        return palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: Color.TRANSPARENT
    }

    // ---------- 实用反射：调用运行时 XposedBridge ----------
    /**
     * 通过反射调用 de.robv.android.xposed.XposedBridge.hookMethod
     * 在 LSPosed 环境下总是可用，且无需额外依赖
     */
    private fun hookMethodViaXposedBridge(method: Method, afterHook: (XC_MethodHookParam) -> Unit) {
        try {
            val xposedBridge = Class.forName("de.robv.android.xposed.XposedBridge")
            val hookMethod = xposedBridge.getMethod(
                "hookMethod",
                java.lang.reflect.Member::class.java,
                Class.forName("de.robv.android.xposed.XC_MethodHook")
            )
            val callback = object : Any() {
                @Suppress("unused")
                override fun afterHooked(param: Any?) {
                    if (param != null) {
                        // 包装成 XC_MethodHookParam
                        val paramClass = param.javaClass
                        val args = paramClass.getDeclaredField("args").also { it.isAccessible = true }.get(param) as Array<Any?>
                        val thisObj = paramClass.getDeclaredField("thisObject").also { it.isAccessible = true }.get(param)
                        val result = paramClass.getDeclaredField("result").also { it.isAccessible = true }.get(param)
                        afterHook(XC_MethodHookParam(args, thisObj, result))
                    }
                }
            }
            hookMethod.invoke(null, method, callback)
        } catch (e: Exception) {
            log("XposedBridge reflection failed: ${e.message}")
        }
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

    /** 轻量参数容器 */
    class XC_MethodHookParam(
        val args: Array<Any?>,
        val thisObject: Any?,
        val result: Any?
    )
}
