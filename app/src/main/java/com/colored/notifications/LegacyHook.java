package com.colored.notifications;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.palette.graphics.Palette;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class LegacyHook implements IXposedHookLoadPackage {

    private final String TAG = "HyperOSMod";
    private final Set<String> excludedPackages = new HashSet<>(Arrays.asList(
            "com.android.systemui",
            "com.miui.securitycenter",
            "com.miui.home",
            "com.android.phone"
    ));

    // 状态栏信息行相关视图
    private LinearLayout mInfoBar;
    private TextView mWeatherText;
    private TextView mNetSpeedText, mSignalText, mBatteryTempText, mCpuTempText, mCurrentText, mPowerText, mDecoText;
    private Handler mHandler;
    private Runnable mRefreshRunnable;
    private long mLastRxBytes = 0, mLastTxBytes = 0;
    private long mLastUpdateTime = 0;
    private boolean mIsDestroyed = false;

    // 天气相关
    private String mWeatherCity = "北京";
    private String mWeatherCityId = "101010100";
    private long mLastWeatherUpdate = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals("com.android.systemui")) return;

        boolean needTripleRow = SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_ENABLE_TRIPLE_ROW);
        boolean needInfoBar = SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_SHOW_NETWORK_SPEED)
                || SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_SHOW_SIGNAL_STRENGTH)
                || SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_SHOW_BATTERY_TEMP)
                || SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_SHOW_CPU_TEMP)
                || SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_SHOW_CURRENT)
                || SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_SHOW_POWER)
                || SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_SHOW_DECORATION_TEXT)
                || SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_SHOW_WEATHER);

        if (needTripleRow || needInfoBar) {
            enableTripleRowStatusBar(lpparam);
        }

        if (SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_ENABLE_COLORED_NOTIFICATIONS)) {
            setupColoredNotifications(lpparam);
        }
    }

    private void enableTripleRowStatusBar(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> statusBarCls = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView",
                    lpparam.classLoader
            );

            XposedHelpers.findAndHookMethod(statusBarCls, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View statusBarView = (View) param.thisObject;
                    Context ctx = statusBarView.getContext();
                    SettingsManager.INSTANCE.init(ctx.getApplicationContext());

                    mIsDestroyed = false;

                    // 状态栏高度 40dp（豆包调整后更适配 HyperOS）
                    ViewGroup.LayoutParams lp = statusBarView.getLayoutParams();
                    if (lp != null) {
                        lp.height = dip2px(ctx, 40);
                        statusBarView.setLayoutParams(lp);
                    }

                    if (mInfoBar == null) {
                        mInfoBar = new LinearLayout(ctx);
                        mInfoBar.setOrientation(LinearLayout.HORIZONTAL);
                        mInfoBar.setGravity(Gravity.CENTER_VERTICAL);
                        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, dip2px(ctx, 22));
                        mInfoBar.setLayoutParams(barLp);
                        mInfoBar.setPadding(dip2px(ctx, 6), 0, dip2px(ctx, 6), 2);

                        if (statusBarView instanceof ViewGroup) {
                            ((ViewGroup) statusBarView).addView(mInfoBar);
                        }
                    }

                    addInfoTextViews(ctx);
                    startRefreshing();
                    hookViewDestroy(statusBarView); // 生命周期管理
                }
            });
        } catch (Throwable e) {
            XposedBridge.log(TAG + " TripleRow err: " + e.getMessage());
        }
    }

    private void addInfoTextViews(Context ctx) {
        if (mInfoBar == null) return;
        mInfoBar.removeAllViews();

        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        textLp.gravity = Gravity.CENTER;

        // 天气
        if (SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_SHOW_WEATHER)) {
            mWeatherText = new TextView(ctx);
            mWeatherText.setTextSize(10);
            mWeatherText.setTextColor(Color.WHITE);
            mWeatherText.setGravity(Gravity.CENTER);
            mWeatherText.setText("天气加载中...");
            mInfoBar.addView(mWeatherText, textLp);
        }

        // 网速
        if (SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_SHOW_NETWORK_SPEED)) {
            mNetSpeedText = new TextView(ctx);
            mNetSpeedText.setTextSize(10);
            mNetSpeedText.setTextColor(Color.WHITE);
            mNetSpeedText.setGravity(Gravity.CENTER);
            mInfoBar.addView(mNetSpeedText, textLp);
        }

        // 信号强度
        if (SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_SHOW_SIGNAL_STRENGTH)) {
            mSignalText = new TextView(ctx);
            mSignalText.setTextSize(10);
            mSignalText.setTextColor(Color.WHITE);
            mSignalText.setGravity(Gravity.CENTER);
            mInfoBar.addView(mSignalText, textLp);
        }

        // 电池温度
        if (SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_SHOW_BATTERY_TEMP)) {
            mBatteryTempText = new TextView(ctx);
            mBatteryTempText.setTextSize(10);
            mBatteryTempText.setTextColor(Color.WHITE);
            mBatteryTempText.setGravity(Gravity.CENTER);
            mInfoBar.addView(mBatteryTempText, textLp);
        }

        // CPU温度
        if (SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_SHOW_CPU_TEMP)) {
            mCpuTempText = new TextView(ctx);
            mCpuTempText.setTextSize(10);
            mCpuTempText.setTextColor(Color.WHITE);
            mCpuTempText.setGravity(Gravity.CENTER);
            mInfoBar.addView(mCpuTempText, textLp);
        }

        // 充电电流
        if (SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_SHOW_CURRENT)) {
            mCurrentText = new TextView(ctx);
            mCurrentText.setTextSize(10);
            mCurrentText.setTextColor(Color.WHITE);
            mCurrentText.setGravity(Gravity.CENTER);
            mInfoBar.addView(mCurrentText, textLp);
        }

        // 实时功耗
        if (SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_SHOW_POWER)) {
            mPowerText = new TextView(ctx);
            mPowerText.setTextSize(10);
            mPowerText.setTextColor(Color.WHITE);
            mPowerText.setGravity(Gravity.CENTER);
            mInfoBar.addView(mPowerText, textLp);
        }

        // 装饰文字
        if (SettingsManager.INSTANCE.getBoolean(SettingsManager.KEY_SHOW_DECORATION_TEXT)) {
            mDecoText = new TextView(ctx);
            mDecoText.setTextSize(10);
            mDecoText.setTextColor(Color.WHITE);
            mDecoText.setGravity(Gravity.CENTER);
            String text = SettingsManager.INSTANCE.getString(SettingsManager.KEY_DECORATION_TEXT);
            if (text.isEmpty()) text = "HyperOS";
            mDecoText.setText(text);
            mInfoBar.addView(mDecoText, textLp);
        }
    }

    private void startRefreshing() {
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }
        if (mRefreshRunnable == null) {
            mRefreshRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!mIsDestroyed) {
                        refreshInfo();
                    }
                    mHandler.postDelayed(this, 1000);
                }
            };
        }
        mHandler.removeCallbacks(mRefreshRunnable);
        mHandler.post(mRefreshRunnable);
    }

    private void refreshInfo() {
        if (mInfoBar == null || mInfoBar.getContext() == null) return;
        Context ctx = mInfoBar.getContext();

        // 网速
        if (mNetSpeedText != null) {
            long[] traffic = getNetworkTraffic();
            long nowRx = traffic[0];
            long nowTx = traffic[1];
            long now = System.currentTimeMillis();
            if (mLastUpdateTime > 0 && now > mLastUpdateTime) {
                double rxSpeed = (nowRx - mLastRxBytes) * 1000.0 / (now - mLastUpdateTime);
                double txSpeed = (nowTx - mLastTxBytes) * 1000.0 / (now - mLastUpdateTime);
                mNetSpeedText.setText(String.format("↓%.1fK ↑%.1fK", rxSpeed / 1024, txSpeed / 1024));
            }
            mLastRxBytes = nowRx;
            mLastTxBytes = nowTx;
            mLastUpdateTime = now;
        }

        // 信号强度（示例占位）
        if (mSignalText != null) {
            // 需要系统权限，暂时显示 N/A
            mSignalText.setText("Sig: N/A");
        }

        // 电池温度
        if (mBatteryTempText != null) {
            try {
                BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    int temp = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_TEMPERATURE);
                    mBatteryTempText.setText(String.format("Bat: %.1f℃", temp / 10.0f));
                }
            } catch (Exception ignored) {}
        }

        // CPU温度
        if (mCpuTempText != null) {
            mCpuTempText.setText(readCpuTemp());
        }

        // 充电电流
        if (mCurrentText != null) {
            mCurrentText.setText(readChargingCurrent());
        }

        // 实时功耗
        if (mPowerText != null) {
            mPowerText.setText(calculatePower());
        }

        // 天气（每30分钟更新一次）
        if (mWeatherText != null && System.currentTimeMillis() - mLastWeatherUpdate > 1800000) {
            fetchWeather();
        }
    }

    // ==================== 生命周期：视图销毁时停止刷新 ====================
    private void hookViewDestroy(View statusBarView) {
        try {
            ViewGroup vg = (ViewGroup) statusBarView;
            vg.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    mIsDestroyed = false;
                    if (mHandler != null && mRefreshRunnable != null) {
                        mHandler.removeCallbacks(mRefreshRunnable);
                        mHandler.post(mRefreshRunnable);
                    }
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    mIsDestroyed = true;
                    if (mHandler != null && mRefreshRunnable != null) {
                        mHandler.removeCallbacks(mRefreshRunnable);
                    }
                }
            });
        } catch (Exception e) {
            XposedBridge.log(TAG + " hookViewDestroy failed: " + e.getMessage());
        }
    }

    // ==================== 通知着色 ====================
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

                    int color = extractColor(notification, ctx);
                    if (color == Color.TRANSPARENT) return;

                    view.setBackgroundColor(color);
                    XposedBridge.log(TAG + " Colored notification from " + pkg);
                }
            });
        } catch (Exception e) {
            XposedBridge.log(TAG + " Colored notification hook failed: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================
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

    private int extractColor(Notification n, Context ctx) {
        try {
            Field largeIconField = Notification.class.getDeclaredField("largeIcon");
            largeIconField.setAccessible(true);
            Object largeIcon = largeIconField.get(n);

            Field smallIconField = Notification.class.getDeclaredField("smallIcon");
            smallIconField.setAccessible(true);
            Object smallIcon = smallIconField.get(n);

            Object icon = largeIcon != null ? largeIcon : smallIcon;
            if (icon == null) return Color.TRANSPARENT;

            Method loadMethod = icon.getClass().getMethod("loadDrawable", Context.class);
            Drawable drawable = (Drawable) loadMethod.invoke(icon, ctx);
            if (drawable == null) return Color.TRANSPARENT;

            Bitmap bitmap = drawableToBitmap(drawable);
            if (bitmap == null) return Color.TRANSPARENT;

            Palette palette = Palette.from(bitmap).generate();
            return palette.getDominantSwatch() != null ?
                    palette.getDominantSwatch().getRgb() : Color.TRANSPARENT;
        } catch (Exception e) {
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

    private int dip2px(Context context, float dpValue) {
        final float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dpValue * scale + 0.5f);
    }

    // ==================== 网络流量 ====================
    private long[] getNetworkTraffic() {
        long rx = 0, tx = 0;
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/net/dev"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(":") && !line.contains("lo:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 10) {
                        rx += Long.parseLong(parts[1]);
                        tx += Long.parseLong(parts[9]);
                    }
                }
            }
            reader.close();
        } catch (Exception ignored) {}
        return new long[]{rx, tx};
    }

    // ==================== CPU温度 ====================
    private String readCpuTemp() {
        String[] paths = {
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/devices/virtual/thermal/thermal_zone0/temp",
                "/sys/class/hwmon/hwmon0/temp1_input"
        };
        for (String p : paths) {
            try {
                String content = readFile(p);
                if (content != null) {
                    long temp = Long.parseLong(content.trim());
                    return String.format("CPU: %.1f℃", temp / 1000.0);
                }
            } catch (Exception ignored) {}
        }
        return "CPU: N/A";
    }

    // ==================== 充电电流 ====================
    private String readChargingCurrent() {
        String[] paths = {
                "/sys/class/power_supply/battery/current_now",
                "/sys/class/power_supply/usb/current_max"
        };
        for (String p : paths) {
            try {
                String content = readFile(p);
                if (content != null) {
                    long current = Long.parseLong(content.trim());
                    return String.format("Cur: %.0fmA", current / 1000.0);
                }
            } catch (Exception ignored) {}
        }
        return "Cur: N/A";
    }

    // ==================== 实时功耗 ====================
    private String calculatePower() {
        try {
            String v = readFile("/sys/class/power_supply/battery/voltage_now");
            String c = readFile("/sys/class/power_supply/battery/current_now");
            if (v != null && c != null) {
                long voltage = Long.parseLong(v.trim());
                long current = Long.parseLong(c.trim());
                double power = (voltage / 1000000.0) * (current / 1000000.0);
                return String.format("Pow: %.2fW", power);
            }
        } catch (Exception ignored) {}
        return "Pow: N/A";
    }

    private String readFile(String path) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(new File(path)));
            String line = reader.readLine();
            reader.close();
            return line;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 天气（小米天气接口） ====================
    private void fetchWeather() {
        new Thread(() -> {
            try {
                String weatherUrl = "http://weatherapi.market.xiaomi.com/wtr-v2/weather?cityId=" + mWeatherCityId;
                URL url = new URL(weatherUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "MIUI/14.0");

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                conn.disconnect();

                String json = sb.toString();
                String info = parseWeatherJson(json);
                mLastWeatherUpdate = System.currentTimeMillis();

                if (mWeatherText != null && mHandler != null) {
                    mHandler.post(() -> mWeatherText.setText(info));
                }
            } catch (Exception e) {
                XposedBridge.log(TAG + " Weather fetch failed: " + e.getMessage());
            }
        }).start();
    }

    private String parseWeatherJson(String json) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            String temp = obj.optString("temp", obj.optJSONObject("weatherinfo") != null ?
                    obj.optJSONObject("weatherinfo").optString("temp", "--") : "--");
            String weather = obj.optString("weather", obj.optJSONObject("weatherinfo") != null ?
                    obj.optJSONObject("weatherinfo").optString("weather", "--") : "--");
            if ("--".equals(temp)) {
                // 备用解析
                int tIdx = json.indexOf("\"temp\"");
                int wIdx = json.indexOf("\"weather\"");
                if (tIdx > 0) {
                    int start = json.indexOf("\"", json.indexOf(":", tIdx) + 1) + 1;
                    int end = json.indexOf("\"", start);
                    temp = json.substring(start, end);
                }
                if (wIdx > 0) {
                    int start = json.indexOf("\"", json.indexOf(":", wIdx) + 1) + 1;
                    int end = json.indexOf("\"", start);
                    weather = json.substring(start, end);
                }
            }
            return mWeatherCity + " " + weather + " " + temp + "℃";
        } catch (Exception e) {
            return "天气: N/A";
        }
    }
                }
