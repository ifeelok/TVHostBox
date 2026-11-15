package tv.hostbox.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 全局调光（软件遮罩）。
 *
 * 这台 IPTV 盒子没有背光硬件（/sys/class/backlight 为空、无 lights HAL），
 * 系统设置和 Aerial 桌面都刻意不提供亮度条。唯一能生效的做法是盖一层
 * 半透明的全屏悬浮窗：桌面、其它 App、视频画面会一起变暗。
 *
 * 几个刻意的设计：
 *  - 遮罩由前台 Service 持有。挂在 Activity 上，一退出就没了，等于没有"全局"。
 *  - 亮度下限 30%：0% 会让电视全黑、遥控器也看不见界面，无法自己调回来。
 *  - 关闭时立即 removeView，不依赖 Service 什么时候被系统回收。
 *  - 悬浮窗权限用 root 的 appops 自动授予：这台 ROM 没有 DocumentsUI，
 *    用户没法在设置里手动开"显示在其他应用上层"。
 */
public final class DimManager {

    public static final int MIN_BRIGHT = 30;
    public static final int MAX_BRIGHT = 100;
    public static final int MIN_TEMP = 3300;
    public static final int MAX_TEMP = 6500;
    public static final int DEFAULT_BRIGHT = 80;
    public static final int DEFAULT_TEMP = 6500;

    private static final String PREF = "hostbox";
    private static final String K_ON = "dimOn";
    private static final String K_BRIGHT = "dimBright";
    private static final String K_TEMP = "dimTemp";

    private static volatile boolean on;
    private static volatile int bright = DEFAULT_BRIGHT;
    private static volatile int temp = DEFAULT_TEMP;
    private static volatile View overlay;
    private static volatile WindowManager wm;
    /** addView / removeView 只能在主线程；apply() 会被 HTTP 工作线程调用，统一 post 过来 */
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private DimManager() {}

    public static synchronized void init(Context c) {
        SharedPreferences p = prefs(c);
        on = p.getBoolean(K_ON, false);
        bright = clampBright(p.getInt(K_BRIGHT, DEFAULT_BRIGHT));
        temp = clampTemp(p.getInt(K_TEMP, DEFAULT_TEMP));
    }

    public static boolean isOn() { return on; }
    public static int brightness() { return bright; }
    public static int temp() { return temp; }

    public static int clampBright(int v) {
        return v < MIN_BRIGHT ? MIN_BRIGHT : (v > MAX_BRIGHT ? MAX_BRIGHT : v);
    }

    public static int clampTemp(int v) {
        return v < MIN_TEMP ? MIN_TEMP : (v > MAX_TEMP ? MAX_TEMP : v);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 遮罩颜色：亮度决定 alpha，色温决定偏暖程度（6500K = 纯黑，3300K = 橙红） */
    private static int overlayColor() {
        int alpha = Math.round((MAX_BRIGHT - bright) * 2.55f);
        float warm = (MAX_TEMP - temp) / (float) (MAX_TEMP - MIN_TEMP);
        if (warm < 0f) warm = 0f;
        if (warm > 1f) warm = 1f;
        return Color.argb(alpha,
                Math.round(255 * warm), Math.round(110 * warm), Math.round(20 * warm));
    }

    /** 手机/电视共用的状态 JSON */
    public static String stateJson(Context c) {
        return "{\"on\":" + on
                + ",\"bright\":" + bright
                + ",\"temp\":" + temp
                + ",\"min\":" + MIN_BRIGHT
                + ",\"max\":" + MAX_BRIGHT
                + ",\"permission\":" + hasPermission(c)
                + "}";
    }

    public static boolean hasPermission(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return Settings.canDrawOverlays(c.getApplicationContext());
        } catch (Exception e) {
            return false;
        }
    }

    /** root 授权悬浮窗。阻塞调用，必须放子线程。 */
    public static boolean grantByRoot() {
        // appops 直接改 AppOpsManager 的 mode，Settings.canDrawOverlays 读的就是它。
        // 这台盒子的 ROM 精简了系统设置，只能走这条路。
        RootShell.Result r = RootShell.run(
                "appops set tv.hostbox.app SYSTEM_ALERT_WINDOW allow");
        if (r.ok) return true;
        // 个别 ROM 只有 cmd appops，没有老式的 appops 命令
        RootShell.Result r2 = RootShell.run(
                "cmd appops set tv.hostbox.app SYSTEM_ALERT_WINDOW allow");
        return r2.ok;
    }

    /**
     * 应用一次设置：保存 → 起/停前台服务 → 刷新遮罩。
     * 可从任意线程调用（HTTP 工作线程 / 广播 / UI）；窗口操作统一 post 到主线程。
     * 注意：无权限时会尝试 root 授权，属于阻塞操作，UI 线程请先切到子线程再调用。
     * @return null 表示成功，否则是可直接展示给用户的错误说明
     */
    public static String apply(Context c, final boolean enabled, int b, int k) {
        final Context app = c.getApplicationContext();
        on = enabled;
        bright = clampBright(b);
        temp = clampTemp(k);
        prefs(app).edit().putBoolean(K_ON, on)
                .putInt(K_BRIGHT, bright).putInt(K_TEMP, temp).commit();

        if (!on) {
            UI.post(new Runnable() {
                @Override public void run() { hideOverlay(); }
            });
            try { app.stopService(new Intent(app, DimService.class)); } catch (Exception ignored) {}
            return null;
        }
        if (!hasPermission(app)) {
            if (!grantByRoot() || !hasPermission(app)) {
                on = false;
                prefs(app).edit().putBoolean(K_ON, false).commit();
                return "没有「显示在其他应用上层」权限，且 root 自动授权失败。"
                        + "请在盒子上执行：su -c 'appops set tv.hostbox.app SYSTEM_ALERT_WINDOW allow'";
            }
        }
        try {
            Intent i = new Intent(app, DimService.class);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Exception e) {
            return "启动调光服务失败：" + e.getMessage();
        }
        // Service 的 onStartCommand 也会刷一次；这里再 post 一次，
        // 保证「按一下遥控器立刻变暗」不等服务启动。
        UI.post(new Runnable() {
            @Override public void run() {
                if (enabled) refresh(app);
            }
        });
        return null;
    }

    /** 只改数值、不改开关（左右键连续调亮度时用），返回是否成功 */
    public static String setLevel(Context c, int b, int k) {
        return apply(c, on, b, k);
    }

    /** 由 Service 调用：把遮罩挂到系统窗口上 */
    static synchronized void showOverlay(Context c) {
        if (overlay != null) return;
        if (!on || !hasPermission(c)) return;
        try {
            WindowManager w = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            if (w == null) return;
            View v = new View(c);
            v.setBackgroundColor(overlayColor());
            WindowManager.LayoutParams lp = layoutParams(w);
            w.addView(v, lp);
            overlay = v;
            wm = w;
        } catch (Exception ignored) {
            // 权限/窗口令牌异常： App 不崩，状态接口会显示未生效
        }
    }

    private static WindowManager.LayoutParams layoutParams(WindowManager w) {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
        // 真实屏幕尺寸（含状态栏/导航栏），留 200px 余量防止旋转或 overscan 露边
        DisplayMetrics dm = new DisplayMetrics();
        try { w.getDefaultDisplay().getRealMetrics(dm); }
        catch (Exception e) { dm.widthPixels = 1920; dm.heightPixels = 1080; }
        int longSide = Math.max(dm.widthPixels, dm.heightPixels) + 200;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                longSide, longSide, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = -100;
        lp.y = -100;
        return lp;
    }

    /** 更新颜色；必须在主线程（Service 的 onStartCommand / Activity 都在主线程） */
    static synchronized void refresh() {
        View v = overlay;
        if (v != null) v.setBackgroundColor(overlayColor());
    }

    static synchronized void refresh(Context c) {
        if (on) showOverlay(c);
        refresh();
    }

    static synchronized void hideOverlay() {
        View v = overlay;
        overlay = null;
        if (v != null && wm != null) {
            try { wm.removeViewImmediate(v); } catch (Exception ignored) {}
        }
        wm = null;
    }
}
