package tv.hostbox.app;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * GitHub raw 加速：给 raw / gist 这两个域名钉一个「实测能连上」的 IP。
 *
 * 解决的是「别的 App 拉订阅、插件配置时转圈」：这两个域名只有 4 个地址，
 * 全是 Fastly 的 anycast 入口，经常出现「解析得到、但连不上」——DNS 给的地址不通，
 * App 就卡在超时上。钉一个自己测过能连的，把解析这一环的不确定性去掉。
 *
 * 别指望它提速：那 4 个地址是同一张 anycast 网络的四个门牌号，不是四个不同机房，
 * 谁快谁慢基本在噪声里（实测四个的 TLS 握手都在同一量级）。这功能治的是
 * 「连不上 / 连错」，不是「不够快」。真要快得换链路（镜像前缀或代理）。
 */
public final class RawAccel {

    /** Fastly 给 raw / gist 用的 4 个 anycast 地址 */
    static final String[] IPS = {
            "185.199.108.133",
            "185.199.109.133",
            "185.199.110.133",
            "185.199.111.133",
    };

    /** 挂在这 4 个地址上的域名。只钉这两个：别的（objects./codeload.）不走 Fastly，钉了反而坏 */
    static final String[] HOSTS = {
            "raw.githubusercontent.com",
            "gist.githubusercontent.com",
    };

    private RawAccel() {}

    /** 一次测速的结果 */
    public static class Pick {
        public final String ip;
        public final int rttMs;

        Pick(String ip, int rttMs) {
            this.ip = ip;
            this.rttMs = rttMs;
        }
    }

    /**
     * 从一组「IP + 延迟」里挑最快的。
     * rtt < 0 表示没测通，直接排除；全都没通则返回 null。
     */
    static Pick pickFastest(List<Pick> list) {
        Pick best = null;
        if (list == null) return null;
        for (int i = 0; i < list.size(); i++) {
            Pick p = list.get(i);
            if (p == null || p.rttMs < 0) continue;
            if (best == null || p.rttMs < best.rttMs) best = p;
        }
        return best;
    }

    /**
     * 写进 hosts 行尾的注释：GitHub raw 加速 · 34ms · 11-02 11:05
     *
     * 不重复写 IP——它就在这行开头。时间戳是给「这条是哪天钉的」用的：
     * 过两个月发现又慢了，看一眼就知道该重新测了。
     */
    static String note(int rttMs, long nowMs) {
        StringBuilder sb = new StringBuilder("GitHub raw 加速");
        if (rttMs > 0) sb.append(" · ").append(rttMs).append("ms");
        sb.append(" · ").append(new SimpleDateFormat("MM-dd HH:mm", Locale.US)
                .format(new Date(nowMs)));
        return sb.toString();
    }

    /** 这条解析是不是「钉在 Fastly 那 4 个地址上」——撤掉时只认这个，别误删用户的其它条目 */
    static boolean ours(HostsManager.Entry e) {
        if (e == null || e.ip == null) return false;
        for (int i = 0; i < IPS.length; i++) {
            if (IPS[i].equals(e.ip)) return true;
        }
        return false;
    }

    /** 阻塞测速：4 个地址并发连 443，取最快的。全部不通返回 null */
    static Pick measure() {
        final List<Pick> got = new ArrayList<Pick>();
        final Object lock = new Object();
        List<Thread> threads = new ArrayList<Thread>();
        for (int i = 0; i < IPS.length; i++) {
            final String ip = IPS[i];
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    int rtt = CfOptimizer.tcpPing(ip, 443, 1500);
                    synchronized (lock) { got.add(new Pick(ip, rtt)); }
                }
            }, "raw-accel-" + ip);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        for (int i = 0; i < threads.size(); i++) {
            try { threads.get(i).join(3000); } catch (InterruptedException ignored) {}
        }
        return pickFastest(got);
    }

    /** 把两个域名一起钉到 ip 上（一次读改写，只产生一份备份） */
    static String apply(Pick p) {
        if (p == null) return "四个候选地址都连不上";
        return HostsManager.upsertMany(HOSTS, p.ip, note(p.rttMs, System.currentTimeMillis()));
    }

    /**
     * 撤掉加速：只删「IP 是那 4 个之一」的条目。
     * 用户手动把 raw 指到别处（比如公司内网）时，撤掉不能把人家那条一起删了。
     */
    static String clear() {
        List<String> victims = new ArrayList<String>();
        try {
            List<HostsManager.Entry> list = HostsManager.parse(HostsManager.readRaw());
            for (int i = 0; i < HOSTS.length; i++) {
                for (int k = 0; k < list.size(); k++) {
                    HostsManager.Entry e = list.get(k);
                    if (HOSTS[i].equals(e.host) && ours(e)) { victims.add(HOSTS[i]); break; }
                }
            }
        } catch (Exception e) {
            return "读取 hosts 失败：" + e;
        }
        if (victims.isEmpty()) return "没有需要撤掉的加速条目";
        String[] arr = victims.toArray(new String[0]);
        return HostsManager.removeMany(arr);
    }

    /** 设置行右边显示的状态：已钉就显示 IP，没钉就「未加速」 */
    static String stateText() {
        try {
            List<HostsManager.Entry> list = HostsManager.parse(HostsManager.readRaw());
            for (int i = 0; i < list.size(); i++) {
                HostsManager.Entry e = list.get(i);
                if (("raw.githubusercontent.com").equals(e.host) && ours(e)) {
                    return "已钉 " + e.ip;
                }
            }
        } catch (Exception ignored) {}
        return "未加速";
    }
}
