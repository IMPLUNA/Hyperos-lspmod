package com.colored.notifications;

// 引入传统 Xposed API 的接口
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

import androidx.palette.graphics.Palette;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

// 实现传统模块接口 IXposedHookLoadPackage
public class LegacyHook implements IXposedHookLoadPackage, IXposedHookInitPackageResources {

    private final Set<String> excludedPackages = new HashSet<>(Arrays.asList(
        "com.android.systemui",
        "com.miui.securitycenter",
        "com.miui.home",
        "com.android.phone"
    ));

    // 模块被加载时调用，作用类似新版 API 的 onPackageLoaded
    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals("com.android.systemui")) {
            return;
        }

        // 从新版 SettingsManager 中读取用户设置
        if (SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_ENABLE_TRIPLE_ROW)) {
            enableTripleRowStatusBar(lpparam);
        }
        if (SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_ENABLE_COLORED_NOTIFICATIONS)) {
            setupColoredNotifications(lpparam);
        }
    }

    private void enableTripleRowStatusBar(LoadPackageParam lpparam) {
        try {
            Class<?> cls = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView", 
                lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(cls, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View view = (View) param.thisObject;
                    if (view != null) {
                        SettingsManager.INSTANCE.init(view.getContext().getApplicationContext());
                    }
                    Log.i("LegacyHyperOS", "Triple row status bar hooked (Legacy)");
                }
            });
        } catch (Exception e) {
            Log.e("LegacyHyperOS", "Triple row hook failed", e);
        }
    }

    private void setupColoredNotifications(LoadPackageParam lpparam) {
        try {
            Class<?> cls = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.row.MiuiNotificationContentView", 
                lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(cls, "updateNotification", Notification.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Notification notification = (Notification) param.args[0];
                    View view = (View) param.thisObject;
                    if (notification == null || view == null) return;
                    Context ctx = view.getContext();

                    String pkg = getPackageName(notification);
                    if (pkg == null || excludedPackages.contains(pkg)) return;

                    int color = extractColor(notification, ctx);
                    if (color == Color.TRANSPARENT) return;

                    Log.i("LegacyHyperOS", "Notification from " + pkg + ", color: " + Integer.toHexString(color));
                }
            });
        } catch (Exception e) {
            Log.e("LegacyHyperOS", "Colored notification hook failed", e);
        }
    }

    private String getPackageName(Notification n) {
        if (n.packageName != null) return n.packageName;
        if (n.extras != null) return n.extras.getString("android.extra.PACKAGE");
        return null;
    }

    private int extractColor(Notification n, Context ctx) {
        try {
            Drawable icon = n.largeIcon != null ? n.largeIcon.loadDrawable(ctx) : null;
            if (icon == null && n.smallIcon != null) icon = n.smallIcon.loadDrawable(ctx);
            if (icon == null) return Color.TRANSPARENT;
            Bitmap bitmap = drawableToBitmap(icon);
            if (bitmap == null) return Color.TRANSPARENT;
            return Palette.from(bitmap).generate().getDominantSwatch() != null
                ? Palette.from(bitmap).generate().getDominantSwatch().getRgb()
                : Color.TRANSPARENT;
        } catch (Exception e) {
            return Color.TRANSPARENT;
        }
    }

    private Bitmap drawableToBitmap(Drawable d) {
        if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
        if (d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) return null;
        Bitmap bitmap = Bitmap.createBitmap(d.getIntrinsicWidth(), d.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        d.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        d.draw(canvas);
        return bitmap;
    }
}
