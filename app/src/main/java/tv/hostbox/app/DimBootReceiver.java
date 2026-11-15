package tv.hostbox.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机恢复上次亮度。
 *
 * 用户设好的亮度不该因为盒子重启就消失。遮罩亮度下限是 30%（不会全黑），
 * 且随时能在 App、手机网页或 adb 里关掉，所以开机自动恢复是安全的。
 */
public class DimBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())
                && !"android.intent.action.QUICKBOOT_POWERON".equals(i.getAction())) return;
        DimManager.init(c);
        if (!DimManager.isOn()) return;
        // 权限是 AppOps 持久化的，正常情况不会走 root 分支；
        // 就算走了，这里在广播线程里执行，不阻塞 UI。
        DimManager.apply(c, true, DimManager.brightness(), DimManager.temp());
    }
}
