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
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

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
                enableTripleRowStatusBar()
            }
            if (SettingsManager.getBoolean(SettingsManager.KEY_ENABLE_COLORED_NOTIFICATIONS)) {
                setupColoredNotifications()
            }
        } catch (e: Exception) {
            Log.e("HyperOSMod", "Mod Error", e)
        }
    }

    // ---------- 三排状态栏 ----------
    private fun enableTripleRowStatusBar() {
        try {
            val cls = Class.forName("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView")
            val method = cls.getDeclaredMethod("onFinishInflate")
            hookViaProxy(method) { param ->
                val view = param.thisObject as? View
                view?.let {
                    SettingsManager.init(it.context.applicationContext)
                }
                Log.i("HyperOSMod", "Triple row status bar hooked")
            }
        } catch (e: Exception) {
            Log.e("HyperOSMod", "Triple row hook failed", e)
        }
    }

    // ---------- 通知自动着色 ----------
    private fun setupColoredNotifications() {
        try {
            val cls = Class.forName("com.android.systemui.statusbar.notification.row.MiuiNotificationContentView")
            val method = cls.getDeclaredMethod("updateNotification", Notification::class.java)
            hookViaProxy(method) { param ->
                val notification = param.args[0] as? Notification ?: return@hookViaProxy
                val view = param.thisObject as? View ?: return@hookViaProxy
                val ctx = view.context

                // 反射获取包名，避免直接访问 notification.packageName
                val pkg = getNotificationPackage(notification) ?: return@hookViaProxy
                if (pkg in excludedPackages) return@hookViaProxy

                // 尝试提取颜色
                val color = extractColorSafe(notification, ctx)
                if (color == Color.TRANSPARENT) return@hookViaProxy

                // 后续可在此给卡片设置背景色
                Log.i("HyperOSMod", "Notification from $pkg, color: ${Integer.toHexString(color)}")
            }
        } catch (e: Exception) {
            Log.e("HyperOSMod", "Colored notification hook failed", e)
        }
    }

    // ---------- 安全提取包名 ----------
    private fun getNotificationPackage(notification: Notification): String? {
        return try {
            // 尝试直接访问字段（低 API 可用）
            val field = Notification::class.java.getDeclaredField("packageName")
            field.isAccessible = true
            field.get(notification) as? String
        } catch (e: Exception) {
            null
        } ?: try {
            // 备用：从 extras 中获取
            notification.extras?.getString("android.extra.PACKAGE")
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 安全提取颜色 ----------
    private fun extractColorSafe(notification: Notification, context: Context): Int {
        try {
            // 优先尝试 largeIcon (Icon 类型)
            val icon = notification.largeIcon ?: notification.smallIcon
            val drawable: Drawable? = if (icon != null) {
                // 反射调用 icon.loadDrawable(context) 避免直接调用可能因SDK差异报错
                val loadMethod = icon.javaClass.getMethod("loadDrawable", Context::class.java)
                loadMethod.invoke(icon, context) as? Drawable
            } else null

            if (drawable is BitmapDrawable) {
                return getDominantColor(drawable.bitmap)
            }
            val bmp = drawableToBitmap(drawable)
            if (bmp != null) return getDominantColor(bmp)
        } catch (_: Exception) {}
        return Color.TRANSPARENT
    }

    private fun getDominantColor(bitmap: Bitmap): Int {
        val palette = Palette.from(bitmap).generate()
        return palette.vibrantSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
            ?: Color.TRANSPARENT
    }

    // ---------- Hook 动态代理 ----------
    private fun hookViaProxy(targetMethod: Method, afterHook: (XC_MethodHookParam) -> Unit) {
        try {
            val xposedBridge = Class.forName("de.robv.android.xposed.XposedBridge")
            val xcHookClass = Class.forName("de.robv.android.xposed.XC_MethodHook")
            val hookMethod = xposedBridge.getMethod("hookMethod",
                java.lang.reflect.Member::class.java, xcHookClass)

            val proxy = Proxy.newProxyInstance(xcHookClass.classLoader,
                arrayOf(xcHookClass),
                InvocationHandler { _, method, args ->
                    if (method.name == "afterHooked") {
                        val param = args?.getOrNull(0) ?: return@InvocationHandler null
                        val pClass = param.javaClass
                        val arr = pClass.getDeclaredField("args").apply { isAccessible = true }.get(param) as Array<Any?>
                        val thisObj = pClass.getDeclaredField("thisObject").apply { isAccessible = true }.get(param)
                        val res = pClass.getDeclaredField("result").apply { isAccessible = true }.get(param)
                        afterHook(XC_MethodHookParam(arr, thisObj, res))
                    }
                    null
                })
            hookMethod.invoke(null, targetMethod, proxy)
        } catch (e: Exception) {
            Log.e("HyperOSMod", "Hook proxy failed", e)
        }
    }

    // ---------- 工具方法 ----------
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

    class XC_MethodHookParam(
        val args: Array<Any?>,
        val thisObject: Any?,
        val result: Any?
    )
}
