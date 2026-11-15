package tv.hostbox.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 持有全局调光遮罩的前台服务。
 *
 * 为什么不把遮罩挂在 Activity 上：用户调完亮度一定会切去看直播/桌面，
 * Activity 一退到后台，进程随时可能被回收，遮罩跟着消失。前台服务 + 通知
 * 是 Android 8+ 上唯一稳定的保活方式；START_STICKY 让服务被杀后自动回来，
 * 回来时按 prefs 恢复上次的亮度。
 */
public class DimService extends Service {

    private static final String CHANNEL = "hostbox_dim";
    private static final int NOTIFY_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        DimManager.init(this);
        startForeground(NOTIFY_ID, buildNotification());
        if (DimManager.isOn()) DimManager.showOverlay(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        DimManager.init(this);
        if (DimManager.isOn()) {
            DimManager.showOverlay(this);
            DimManager.refresh();
        } else {
            DimManager.hideOverlay();
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        DimManager.hideOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "亮度管理",
                        NotificationManager.IMPORTANCE_MIN);
                ch.setDescription("全局调光遮罩正在运行");
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(R.drawable.app_icon)
                .setContentTitle("电视Host管家 · 亮度管理")
                .setContentText("亮度 " + DimManager.brightness() + "% · 在 App 或手机网页里调整")
                .setOngoing(true)
                .build();
    }
}
