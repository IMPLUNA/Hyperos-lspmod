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

    private fun enableTripleRowStatusBar() {
        try {
            val cls = Class.forName("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView")
            val method = cls.getDeclaredMethod("onFinishInflate")
            hookMethodWithProxy(method) { param: XC_MethodHookParam ->
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

    private fun setupColoredNotifications() {
        try {
            val cls = Class.forName("com.android.systemui.statusbar.notification.row.MiuiNotificationContentView")
            val method = cls.getDeclaredMethod("updateNotification", Notification::class.java)
            hookMethodWithProxy(method) { param: XC_MethodHookParam ->
                val notification = param.args[0] as? Notification ?: return@hookMethodWithProxy
                val view = param.thisObject as? View ?: return@hookMethodWithProxy
                val ctx = view.context

                val pkg = getPackageNameReflect(notification) ?: return@hookMethodWithProxy
                if (pkg in excludedPackages) return@hookMethodWithProxy

                val color = extractColorReflect(notification, ctx)
                if (color == Color.TRANSPARENT) return@hookMethodWithProxy

                Log.i("HyperOSMod", "Notification from $pkg, dominant color: ${Integer.toHexString(color)}")
            }
        } catch (e: Exception) {
            Log.e("HyperOSMod", "Colored notification hook failed", e)
        }
    }

    private fun getPackageNameReflect(notification: Notification): String? {
        return try {
            val field = Notification::class.java.getDeclaredField("packageName")
            field.isAccessible = true
            field.get(notification) as? String
        } catch (e: Exception) {
            null
        } ?: notification.extras?.getString("android.extra.PACKAGE")
    }

    private fun extractColorReflect(notification: Notification, context: Context): Int {
        try {
            val icon = notification.largeIcon ?: notification.smallIcon ?: return Color.TRANSPARENT
            val loadMethod = icon.javaClass.getMethod("loadDrawable", Context::class.java)
            val drawable = loadMethod.invoke(icon, context) as? Drawable ?: return Color.TRANSPARENT
            val bitmap = if (drawable is BitmapDrawable) drawable.bitmap else drawableToBitmap(drawable)
            return bitmap?.let { getDominantColor(it) } ?: Color.TRANSPARENT
        } catch (e: Exception) {
            return Color.TRANSPARENT  // 补齐 return
        }
    }

    private fun getDominantColor(bitmap: Bitmap): Int {
        val palette = Palette.from(bitmap).generate()
        return palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: Color.TRANSPARENT
    }

    private fun hookMethodWithProxy(targetMethod: Method, afterHook: (XC_MethodHookParam) -> Unit) {
        try {
            val xposedBridge = Class.forName("de.robv.android.xposed.XposedBridge")
            val xcMethodHookClass = Class.forName("de.robv.android.xposed.XC_MethodHook")
            val hookMethod = xposedBridge.getMethod(
                "hookMethod",
                java.lang.reflect.Member::class.java,
                xcMethodHookClass
            )

            val proxy = Proxy.newProxyInstance(
                xcMethodHookClass.classLoader,
                arrayOf(xcMethodHookClass),
                InvocationHandler { _, method, args ->
                    if (method.name == "afterHooked") {
                        val param = args?.getOrNull(0) ?: return@InvocationHandler null
                        val paramClass = param.javaClass
                        val arr = paramClass.getDeclaredField("args").also { it.isAccessible = true }.get(param) as Array<Any?>
                        val thisObj = paramClass.getDeclaredField("thisObject").also { it.isAccessible = true }.get(param)
                        val res = paramClass.getDeclaredField("result").also { it.isAccessible = true }.get(param)
                        afterHook(XC_MethodHookParam(arr, thisObj, res))
                    }
                    null
                }
            )

            hookMethod.invoke(null, targetMethod, proxy)
        } catch (e: Exception) {
            Log.e("HyperOSMod", "Hook proxy failed", e)
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

    class XC_MethodHookParam(
        val args: Array<Any?>,
        val thisObject: Any?,
        val result: Any?
    )
}
