package tv.hostbox.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * hosts 体检：把当前条目里的 IP 挨个连一下，看还活着没。
 *
 * 为什么需要：优选写进去的 IP 过一阵可能就不可达了（出口变了、机房调整、线路被掐），
 * 但界面上那一行永远长一个样——只有等某个 App 打不开才发现。这里给一次明确的体检。
 *
 * 只做一件事：TCP 连 443，连不上再试 80。不 ping（ICMP 在盒子上未必通，而且
 * 端口通不通才是 App 真正关心的）。纯只读，一个字都不写。
 */
public final class HostCheck {

    /** 先试 443（HTTPS 服务），再试 80（有些自建服务只开明文） */
    private static final int[] PORTS = {443, 80};
    private static final int TIMEOUT_MS = 1500;

    private HostCheck() {}

    public static class Result {
        public final String ip;
        /** 这个 IP 上挂着哪些域名（同一行多个域名、或多行同 IP，都会合到一条） */
        public final List<String> hosts = new ArrayList<String>();
        public int rttMs = -1;    // -1 = 不通
        public int port;          // 通的是哪个端口；0 = 没通

        Result(String ip) {
            this.ip = ip;
        }

        public boolean ok() {
            return rttMs >= 0;
        }
    }

    /**
     * 按 IP 合并条目：体检是对「地址」做的，不是对「域名」做的。
     * 一个 IP 挂三个域名只连一次，省时间也更接近实际（它们本来就是同一条链路）。
     * 用 LinkedHashMap 保序，界面上的顺序跟 hosts 列表一致，认得出是哪一条。
     */
    static Map<String, Result> group(List<HostsManager.Entry> entries) {
        Map<String, Result> map = new LinkedHashMap<String, Result>();
        if (entries == null) return map;
        for (int i = 0; i < entries.size(); i++) {
            HostsManager.Entry e = entries.get(i);
            if (e == null || e.ip == null) continue;
            Result r = map.get(e.ip);
            if (r == null) {
                r = new Result(e.ip);
                map.put(e.ip, r);
            }
            if (e.host != null && !r.hosts.contains(e.host)) r.hosts.add(e.host);
        }
        return map;
    }

    /** 通了几个 */
    static int okCount(List<Result> list) {
        int n = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).ok()) n++;
        return n;
    }

    /** 一句话总结： 「5 个地址，3 个可达」/「5 个地址全部可达」 */
    static String summary(List<Result> list) {
        if (list.isEmpty()) return "还没有可体检的条目";
        int ok = okCount(list);
        if (ok == list.size()) return list.size() + " 个地址全部可达";
        return list.size() + " 个地址，" + ok + " 个可达";
    }

    /**
     * 排成体检报告的顺序：不通的排最前面（那是你要找的），
     * 其余按延迟从低到高——一眼看出哪条最快。
     */
    static List<Result> sortForReport(List<Result> list) {
        List<Result> out = new ArrayList<Result>(list);
        java.util.Collections.sort(out, new java.util.Comparator<Result>() {
            @Override public int compare(Result a, Result b) {
                if (a.ok() != b.ok()) return a.ok() ? 1 : -1;   // 不通的在前
                if (a.rttMs != b.rttMs) return a.rttMs < b.rttMs ? -1 : 1;
                return a.ip.compareTo(b.ip);
            }
        });
        return out;
    }

    /** 弹窗里的一行： emby.cc · 104.16.1.1 · 23ms */
    static String line(Result r) {
        if (r == null) return "";
        StringBuilder sb = new StringBuilder();
        if (!r.hosts.isEmpty()) {
            sb.append(r.hosts.get(0));
            if (r.hosts.size() > 1) sb.append(" 等 ").append(r.hosts.size()).append(" 个");
            sb.append(" · ");
        }
        sb.append(r.ip).append(" · ");
        sb.append(r.ok() ? (r.rttMs + "ms") : "不通");
        return sb.toString();
    }

    /** 阻塞体检（在工作线程调用） */
    static List<Result> run(List<HostsManager.Entry> entries) {
        List<Result> list = new ArrayList<Result>(group(entries).values());
        if (list.isEmpty()) return list;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, list.size()));
        for (int i = 0; i < list.size(); i++) {
            final Result r = list.get(i);
            pool.execute(new Runnable() {
                @Override public void run() {
                    for (int p = 0; p < PORTS.length; p++) {
                        int rtt = CfOptimizer.tcpPing(r.ip, PORTS[p], TIMEOUT_MS);
                        if (rtt >= 0) {
                            r.rttMs = rtt;
                            r.port = PORTS[p];
                            return;
                        }
                    }
                }
            });
        }
        pool.shutdown();
        try {
            // 每个地址最多两轮超时，串起来的上限留够；正常情况几秒就完了
            pool.awaitTermination(PORTS.length * TIMEOUT_MS + 3000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
        return list;
    }
}
