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

    /**
     * 安全加载类，避免 ClassNotFoundException
     * 增加类加载器多重尝试，解决不同 Android 版本下的类加载问题
     */
    private fun safeLoadClass(className: String): Class<*>? {
        // 方案1：使用系统默认 ClassLoader
        try {
            return Class.forName(className)
        } catch (e: ClassNotFoundException) {
            // 忽略异常，准备尝试下一个 ClassLoader
        }
        
        // 方案2：使用当前线程上下文 ClassLoader
        try {
            val contextClassLoader = Thread.currentThread().contextClassLoader
            return Class.forName(className, false, contextClassLoader)
        } catch (e: ClassNotFoundException) {
            // 忽略异常
        }
        
        // 方案3：使用 BootClassLoader
        try {
            val bootClassLoader = ClassLoader.getSystemClassLoader()?.parent
            return Class.forName(className, false, bootClassLoader)
        } catch (e: ClassNotFoundException) {
            // 忽略异常
        }
        
        Log.w("HyperOSMod", "Class not found: $className, skipping hook")
        return null
    }

    private fun enableTripleRowStatusBar() {
        // 使用 safeLoadClass 进行加载尝试
        val statusBarCls = safeLoadClass("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView")
        if (statusBarCls == null) {
            Log.i("HyperOSMod", "Status bar class not available, skipping triple row hook")
            return
        }
        
        try {
            val method = statusBarCls.getDeclaredMethod("onFinishInflate")
            hookMethodWithProxy(method) { param: XC_MethodHookParam ->
                val view = param.thisObject as? View
                view?.let {
                    SettingsManager.init(it.context.applicationContext)
                }
                Log.i("HyperOSMod", "Triple row status bar hooked")
            }
        } catch (e: NoSuchMethodException) {
            Log.i("HyperOSMod", "Method 'onFinishInflate' not found, skipping triple row hook")
        } catch (e: Exception) {
            Log.e("HyperOSMod", "Triple row hook failed", e)
        }
    }

    private fun setupColoredNotifications() {
        val notificationCls = safeLoadClass("com.android.systemui.statusbar.notification.row.MiuiNotificationContentView")
        if (notificationCls == null) {
            Log.i("HyperOSMod", "Notification content view class not available, skipping colored notification hook")
            return
        }
        
        try {
            val method = notificationCls.getDeclaredMethod("updateNotification", Notification::class.java)
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
        } catch (e: NoSuchMethodException) {
            Log.i("HyperOSMod", "Method 'updateNotification' not found, skipping colored notification hook")
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
            return Color.TRANSPARENT
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
