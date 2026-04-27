package com.colored.notifications;

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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class LegacyHook implements IXposedHookLoadPackage {

    private final Set<String> excludedPackages = new HashSet<>(Arrays.asList(
            "com.android.systemui",
            "com.miui.securitycenter",
            "com.miui.home",
            "com.android.phone"
    ));

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals("com.android.systemui")) return;

        try {
            if (SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_ENABLE_TRIPLE_ROW)) {
                enableTripleRowStatusBar(lpparam);
            }
            if (SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_ENABLE_COLORED_NOTIFICATIONS)) {
                setupColoredNotifications(lpparam);
            }
        } catch (Exception e) {
            Log.e("LegacyHyperOS", "handleLoadPackage error", e);
        }
    }

    private void enableTripleRowStatusBar(XC_LoadPackage.LoadPackageParam lpparam) {
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

    private void setupColoredNotifications(XC_LoadPackage.LoadPackageParam lpparam) {
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

                    int color = extractColorReflect(notification, ctx);
                    if (color == Color.TRANSPARENT) return;

                    Log.i("LegacyHyperOS", "Notification from " + pkg + ", color: " + Integer.toHexString(color));
                }
            });
        } catch (Exception e) {
            Log.e("LegacyHyperOS", "Colored notification hook failed", e);
        }
    }

    private String getPackageName(Notification n) {
        try {
            Field field = Notification.class.getDeclaredField("packageName");
            field.setAccessible(true);
            return (String) field.get(n);
        } catch (Exception e) {
            if (n.extras != null) return n.extras.getString("android.extra.PACKAGE");
            return null;
        }
    }

    private int extractColorReflect(Notification n, Context ctx) {
        try {
            // 反射获取 largeIcon / smallIcon
            Field largeIconField = Notification.class.getDeclaredField("largeIcon");
            largeIconField.setAccessible(true);
            Object largeIcon = largeIconField.get(n);

            Field smallIconField = Notification.class.getDeclaredField("smallIcon");
            smallIconField.setAccessible(true);
            Object smallIcon = smallIconField.get(n);

            Object icon = largeIcon != null ? largeIcon : smallIcon;
            if (icon == null) return Color.TRANSPARENT;

            // 反射调用 icon.loadDrawable(Context)
            Method loadMethod = icon.getClass().getMethod("loadDrawable", Context.class);
            Drawable drawable = (Drawable) loadMethod.invoke(icon, ctx);
            if (drawable == null) return Color.TRANSPARENT;

            Bitmap bitmap = drawableToBitmap(drawable);
            if (bitmap == null) return Color.TRANSPARENT;

            Palette palette = Palette.from(bitmap).generate();
            return palette.getDominantSwatch() != null ?
                    palette.getDominantSwatch().getRgb() : Color.TRANSPARENT;
        } catch (Exception e) {
            Log.e("LegacyHyperOS", "extractColorReflect error", e);
            return Color.TRANSPARENT;
        }
    }

    private Bitmap drawableToBitmap(Drawable d) {
        if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
        if (d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) return null;
        Bitmap bitmap = Bitmap.createBitmap(
                d.getIntrinsicWidth(), d.getIntrinsicHeight(), Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(bitmap);
        d.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        d.draw(canvas);
        return bitmap;
    }
}
