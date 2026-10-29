package tv.hostbox.app;

import android.content.Context;

/** 在非 Activity 类里拿 ApplicationContext 的小工具 */
public final class AppContextHolder {
    private static Context appContext;

    public static void init(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    public static Context get() {
        if (appContext == null) throw new IllegalStateException("AppContextHolder not initialized");
        return appContext;
    }
}
