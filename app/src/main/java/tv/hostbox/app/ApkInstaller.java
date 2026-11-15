package tv.hostbox.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;

/**
 * 手机网页「下载安装」：把 APK 下载下来，再用 root 静默装到盒子上。
 *
 * 为什么要有这个：以前每次改完 App 都得在电脑上 adb push + adb install，
 * 或者把 APK 拷到 U 盘再对着电视点文件选择器（这台盒子的 ROM 连文件选择器都没有）。
 * 现在手机扫码页填个链接就能装，装自己、装 CMFA、装文件管理器都走同一条路。
 *
 * 设计要点：
 *  - HTTP 请求只负责「起一个后台线程 + 立刻回执」，下载/安装可能几十秒，
 *    让 socket 干等会超时；页面轮询 /api/install/status 看进度。
 *  - 下载复用 UpdateChecker：PK 文件头、体积、多通道降级都不用再写一遍。
 *  - URL 只进 HttpURLConnection，绝不拼进 shell 命令；root 命令里只有固定缓存路径，
 *    所以用户填什么链接都不会变成命令注入。
 *  - 「安装最新版」会先比版本号：远端比本地旧就不装，避免把刚加的功能降级掉。
 */
final class ApkInstaller {

    private static volatile String phase = "idle";      // idle/downloading/installing/done/error
    private static volatile String message = "空闲";
    private static volatile int percent = 0;            // -1 = 服务器没给总大小
    private static volatile long bytes = 0;
    private static volatile String source = "";
    private static volatile boolean busy = false;
    private static volatile long startedAt = 0;

    /** 装自己时进程会被 pm 杀掉，静态状态全丢；把结果写进 prefs，重启后页面还能看到 */
    private static final String PREF = "hostbox";
    private static final String K_PHASE = "instPhase";
    private static final String K_MSG = "instMsg";

    private ApkInstaller() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static void save(Context app, String p, String m) {
        try {
            prefs(app).edit().putString(K_PHASE, p).putString(K_MSG, m).apply();
        } catch (Exception ignored) {}
    }

    /** 一键入口：MidnightBeam 官方仓库（动态取最新版，不写死版本号/资产 id） */
    static final String MB_REPO = "MadeinTaly/midnightbeam";

    /**
     * @param mode "latest" = 从 GitHub Release 装新版自己；
     *             "midnightbeam" = 装 MidnightBeam 官方最新版；
     *             "url" = 装 url 指向的任意 APK
     * @return false 表示正在忙或参数不合法
     */
    static synchronized boolean start(final Context app, String mode, String url) {
        if (busy) return false;
        final boolean latest = "latest".equals(mode);
        final boolean mb = "midnightbeam".equals(mode);
        if (!latest && !mb) {
            if (url == null) return false;
            url = url.trim();
            String low = url.toLowerCase();
            if (!low.startsWith("http://") && !low.startsWith("https://")) return false;
            if (url.indexOf(' ') >= 0 || url.indexOf('\n') >= 0 || url.indexOf('\r') >= 0
                    || url.indexOf('\'') >= 0 || url.indexOf('"') >= 0) return false;
        }

        busy = true;
        phase = "downloading";
        percent = 0;
        bytes = 0;
        startedAt = System.currentTimeMillis();
        source = latest ? "GitHub Release" : mb ? "MidnightBeam 官方 Release" : url;
        message = latest ? "正在查询最新版本…"
                : mb ? "正在查询 MidnightBeam 最新版…" : "准备下载…";
        save(app, phase, message);

        final String finalUrl = url;
        new Thread(new Runnable() {
            @Override public void run() {
                work(app.getApplicationContext(), latest, mb, finalUrl);
            }
        }, "apk-install").start();
        return true;
    }

    private static void work(Context app, boolean latest, boolean mb, String url) {
        File dest = new File(app.getCacheDir(), "install.apk");
        try {
            if (!RootShell.isRootAvailable()) {
                throw new Exception("这台盒子没有 root，无法静默安装（请先在盒子上 adb 安装）");
            }
            try { if (dest.exists()) dest.delete(); } catch (Exception ignored) {}

            UpdateChecker.DownloadProgress prog = new UpdateChecker.DownloadProgress() {
                @Override public void onProgress(long done, long total) {
                    bytes = done;
                    percent = total > 0 ? (int) (done * 100 / total) : -1;
                    message = total > 0
                            ? "正在下载 " + percent + "%（" + done / 1024 + " KB）"
                            : "正在下载 " + done / 1024 + " KB";
                }
            };
            UpdateChecker.SourceListener src = new UpdateChecker.SourceListener() {
                @Override public void onSource(String desc) {
                    source = desc;
                    message = "正在通过 " + desc + " 下载…";
                }
            };

            if (latest) {
                UpdateChecker.Info info = UpdateChecker.fetchLatest();
                String local = "";
                try {
                    local = app.getPackageManager()
                            .getPackageInfo(app.getPackageName(), 0).versionName;
                } catch (Exception ignored) {}
                if (!UpdateChecker.isNewer(info.tag, local)) {
                    throw new Exception("远端 " + info.tag + " 不比当前 v" + local
                            + " 新，已取消（避免把新版降级）");
                }
                message = "正在下载 " + info.tag + "…";
                UpdateChecker.download(info, dest, prog, src);
            } else if (mb) {
                UpdateChecker.Info info = UpdateChecker.releaseOf(MB_REPO);
                message = "正在下载 MidnightBeam " + info.tag + "…";
                UpdateChecker.download(info, dest, prog, src);
            } else {
                UpdateChecker.download(url, dest, 0, prog, src);
            }

            phase = "installing";
            percent = 100;
            message = "下载完成，正在 root 静默安装…";

            // 装的是 Host管家自己时，pm install 会把当前进程杀掉，下面的代码可能根本跑不完；
            // 页面会把「连接突然断开」显示成「正在重启」，不误报失败。
            // pm 的 Success / Failure 在 stderr，必须 2>&1 合并，否则失败原因一片空白。
            // 只有装自己才需要重新拉起界面；装 MidnightBeam 这类外部包时别把
            // Host管家 一起重启，否则手机网页会白白断一次连接。
            boolean self = latest || (url != null && url.toLowerCase().contains("hostbox"));
            String restart = self
                    ? "if [ $RC -eq 0 ]; then sleep 1; "
                      + "am start -n tv.hostbox.app/.MainActivity >/dev/null 2>&1; fi\n"
                    : "";
            String cmd = "cp '" + dest.getAbsolutePath() + "' /data/local/tmp/hostbox_install.apk\n"
                    + "chmod 644 /data/local/tmp/hostbox_install.apk\n"
                    + "OUT=$(pm install -r /data/local/tmp/hostbox_install.apk 2>&1)\n"
                    + "RC=$?\n"
                    + "echo \"$OUT\"\n"
                    + "rm -f /data/local/tmp/hostbox_install.apk\n"
                    + restart
                    + "echo hostbox_rc=$RC";
            RootShell.Result r = RootShell.run(cmd, 180000);
            if (r.ok && r.output.contains("Success")) {
                phase = "done";
                message = self ? "安装成功，应用正在重启…"
                        : "安装成功，可以回桌面打开它了";
            } else {
                phase = "error";
                message = "安装失败：" + brief(r.output);
            }
            save(app, phase, message);
            try { dest.delete(); } catch (Exception ignored) {}
        } catch (Exception e) {
            phase = "error";
            String m = e.getMessage();
            message = "失败：" + brief(m == null ? e.toString() : m);
            save(app, phase, message);
            try { dest.delete(); } catch (Exception ignored) {}
        } finally {
            busy = false;
        }
    }

    static String stateJson() {
        String p = phase, m = message;
        // 装自己时 pm 把进程杀掉又拉起，静态状态全回初值；读回重启前写下的结果。
        // 若最后停在「下载中 / 安装中」，说明是安装自己导致的中断 —— 能重启就是装上了，
        // 别让页面一直显示「下载中」并无限轮询。
        if ("idle".equals(p) && startedAt == 0) {
            Context c = AppContextHolder.get();
            if (c != null) {
                SharedPreferences sp = prefs(c);
                String saved = sp.getString(K_PHASE, "");
                if (saved != null && !saved.isEmpty()) {
                    m = sp.getString(K_MSG, "空闲");
                    if ("downloading".equals(saved) || "installing".equals(saved)) {
                        p = "done";
                        m = "应用已重启，安装流程结束（请在盒子上核对版本号）";
                    } else {
                        p = saved;
                    }
                }
            }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("phase", p);
            o.put("message", m);
            o.put("percent", percent);
            o.put("bytes", bytes);
            o.put("source", source);
            o.put("busy", busy);
            o.put("startedAt", startedAt);
        } catch (Exception ignored) {}
        return o.toString();
    }

    private static String brief(String s) {
        if (s == null) return "未知原因";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 220 ? t.substring(0, 220) + "…" : t;
    }
}
