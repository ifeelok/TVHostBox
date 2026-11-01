package tv.hostbox.app;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Cloudflare 优选 IP。
 * 流程：
 *  1. 从 CF 官方 IPv4 网段按大小加权随机采样 N 个 IP（顺带记下该段的归属国家 / 城市）；
 *  2. 并发 TCP connect 443 测延迟（淘汰 > 某阈值的）；
 *  3. 对延迟最好的 M 个走 HTTPS 下载测速（speed.cloudflare.com/__down），
 *     同时对这一批并发查落点机房（三字码，如 LAX）；
 *  4. 按下载速度排序取前 topN，速度为零的按延迟补齐。
 *
 * 归属有两套口径，别混用：
 *  - 段归属（Candidate.segCountry）：IP 库登记的国家，CF 是 Anycast，这只是"户籍地"；
 *  - 落点机房（Candidate.colo）：连 /cdn-cgi/trace 拿到的 CF 三字码，表示这条链路落在哪。
 */
public final class CfOptimizer {

    /**
     * Cloudflare 官方公告的 IPv4 段 + 该段在 IP 库里登记的归属（国家 / 城市）。
     *
     * 一行三段是为了保证「段—国家—城市」永远对齐，不会因改一处而错位。
     *
     * 注意事项：CF 的段几乎全是 Anycast——同一个段在全世界都被通告。
     * 所以这里的归属只是 **户籍地**（注册 / IP 库登记），不等于你连这条 IP 实际落到哪个机房。
     * 真正反映链路的是 {@link Candidate#colo}（连 /cdn-cgi/trace 拿到的 CF 三字机房码），
     * 它能告诉你这条链路的落点，界面上两者的显示优先级是 colo > 段归属。
     */
    private static final String[][] SEGMENTS = {
            {"173.245.48.0/20",  "美国",       "洛杉矶"},
            {"103.21.244.0/22",  "美国",       "纽约"},
            {"103.22.200.0/22",  "新加坡",     "新加坡"},
            {"103.31.4.0/22",    "美国",       "纽约"},
            {"141.101.64.0/18",  "荷兰",       "阿姆斯特丹"},
            {"108.162.192.0/18", "美国",       "旧金山"},
            {"190.93.240.0/20",  "哥斯达黎加", "圣何塞"},
            {"188.114.96.0/20",  "美国",       "旧金山"},
            {"197.234.240.0/22", "南非",       "约翰内斯堡"},
            {"198.41.128.0/17",  "美国",       "旧金山"},
            {"162.158.0.0/15",   "澳大利亚",   "墨尔本"},
            {"104.16.0.0/13",    "美国",       "旧金山"},
            {"104.24.0.0/14",    "美国",       "旧金山"},
            {"172.64.0.0/13",    "美国",       "旧金山"},
            {"131.0.72.0/22",    "哥斯达黎加", "圣何塞"},
    };

    /** 由 SEGMENTS 派生，供采样 / 掩码匹配使用 */
    private static final String[] CIDRS = new String[SEGMENTS.length];
    private static final int[] SEG_BASE = new int[SEGMENTS.length];
    private static final int[] SEG_MASK = new int[SEGMENTS.length];

    static {
        for (int i = 0; i < SEGMENTS.length; i++) {
            CIDRS[i] = SEGMENTS[i][0];
            int prefix = Integer.parseInt(SEGMENTS[i][0].split("/")[1]);
            SEG_MASK[i] = prefix == 0 ? 0 : (0xFFFFFFFF << (32 - prefix));
            SEG_BASE[i] = ipToInt(SEGMENTS[i][0].split("/")[0]);
        }
    }

    /** 测速用的目标域名（SNI + Host 头都必须是它） */
    private static final String SPEED_HOST = "speed.cloudflare.com";
    /** 查机房用的目标域名：/cdn-cgi/trace 是 CF 边缘节点自带的端点 */
    private static final String TRACE_HOST = "cloudflare.com";

    public static class Candidate {
        public final String ip;
        /** 段归属（户籍地）：采样那一刻就确定，一定不为空（查不到就是"未知"） */
        public final String segCountry;
        public final String segCity;
        /** CF 三字机房码（如 LAX / NRT），测速阶段并发探测；null = 还没查到 */
        public volatile String colo;
        public int rttMs = -1;      // -1 = 未测/失败
        public double mbps = -1;    // -1 = 未测速

        Candidate(String ip) {
            this.ip = ip;
            int seg = segIndexOf(ip);
            this.segCountry = seg < 0 ? "未知" : SEGMENTS[seg][1];
            this.segCity = seg < 0 ? "" : SEGMENTS[seg][2];
        }

        /** 实测机房的国家优先；没查到 colo 就退回段归属 */
        public String countryName() {
            String[] g = coloGeo(colo);
            return g != null ? g[0] : segCountry;
        }

        /** 实测机房的城市优先；都没有就返回 "" */
        public String cityName() {
            String[] g = coloGeo(colo);
            if (g != null) return g[1];
            return segCity;
        }

        /** 行内短标签：「美国 LAX」/「美国」/「—」。列宽有限，只放国家 + 三字码 */
        public String regionShort() {
            String c = countryName();
            if (c == null || c.isEmpty() || "未知".equals(c)) return colo == null ? "—" : colo;
            return colo == null ? c : c + " " + colo;
        }

        /** 详情行（表尾 / 弹窗）：「美国 · 洛杉矶（LAX）」 */
        public String regionFull() {
            String c = countryName(), city = cityName();
            if (c == null || c.isEmpty() || "未知".equals(c)) {
                return colo == null ? "地区未知" : "机房 " + colo;
            }
            String s = c + (city == null || city.isEmpty() ? "" : " · " + city);
            return colo == null ? s : s + "（" + colo + "）";
        }

        @Override public String toString() { return ip; }
    }

    /** IP 落在哪个 CF 段；不在官方段里返回 -1 */
    static int segIndexOf(String ip) {
        int v = ipToInt(ip);
        for (int i = 0; i < SEG_BASE.length; i++) {
            if ((v & SEG_MASK[i]) == (SEG_BASE[i] & SEG_MASK[i])) return i;
        }
        return -1;
    }

    /**
     * CF 三字机房码 → {国家, 城市}。
     * 收录常见节点；没收录的码照样能显示，只是落回段归属（regionShort 会退化成只显示国家）。
     */
    private static String[] coloGeo(String colo) {
        if (colo == null || colo.isEmpty()) return null;
        switch (colo) {
            case "LAX": return new String[]{"美国", "洛杉矶"};
            case "SJC": return new String[]{"美国", "圣何塞"};
            case "SEA": return new String[]{"美国", "西雅图"};
            case "IAD": return new String[]{"美国", "华盛顿"};
            case "ORD": return new String[]{"美国", "芝加哥"};
            case "DFW": return new String[]{"美国", "达拉斯"};
            case "EWR": return new String[]{"美国", "纽瓦克"};
            case "ATL": return new String[]{"美国", "亚特兰大"};
            case "MIA": return new String[]{"美国", "迈阿密"};
            case "DEN": return new String[]{"美国", "丹佛"};
            case "PHX": return new String[]{"美国", "菲尼克斯"};
            case "NRT": return new String[]{"日本", "东京"};
            case "KIX": return new String[]{"日本", "大阪"};
            case "ICN": return new String[]{"韩国", "首尔"};
            case "SIN": return new String[]{"新加坡", "新加坡"};
            case "HKG": return new String[]{"中国香港", "香港"};
            case "TPE": return new String[]{"中国台湾", "台北"};
            case "BKK": return new String[]{"泰国", "曼谷"};
            case "KUL": return new String[]{"马来西亚", "吉隆坡"};
            case "CGK": return new String[]{"印尼", "雅加达"};
            case "MNL": return new String[]{"菲律宾", "马尼拉"};
            case "BOM": return new String[]{"印度", "孟买"};
            case "DEL": return new String[]{"印度", "新德里"};
            case "MAA": return new String[]{"印度", "金奈"};
            case "FRA": return new String[]{"德国", "法兰克福"};
            case "AMS": return new String[]{"荷兰", "阿姆斯特丹"};
            case "LHR": return new String[]{"英国", "伦敦"};
            case "CDG": return new String[]{"法国", "巴黎"};
            case "MAD": return new String[]{"西班牙", "马德里"};
            case "MXP": return new String[]{"意大利", "米兰"};
            case "ARN": return new String[]{"瑞典", "斯德哥尔摩"};
            case "WAW": return new String[]{"波兰", "华沙"};
            case "IST": return new String[]{"土耳其", "伊斯坦布尔"};
            case "DXB": return new String[]{"阿联酋", "迪拜"};
            case "JNB": return new String[]{"南非", "约翰内斯堡"};
            case "CPT": return new String[]{"南非", "开普敦"};
            case "LOS": return new String[]{"尼日利亚", "拉各斯"};
            case "NBO": return new String[]{"肯尼亚", "内罗毕"};
            case "GRU": return new String[]{"巴西", "圣保罗"};
            case "EZE": return new String[]{"阿根廷", "布宜诺斯艾利斯"};
            case "SCL": return new String[]{"智利", "圣地亚哥"};
            case "BOG": return new String[]{"哥伦比亚", "波哥大"};
            case "LIM": return new String[]{"秘鲁", "利马"};
            case "SJO": return new String[]{"哥斯达黎加", "圣何塞"};
            case "SYD": return new String[]{"澳大利亚", "悉尼"};
            case "MEL": return new String[]{"澳大利亚", "墨尔本"};
            case "PER": return new String[]{"澳大利亚", "珀斯"};
            case "AKL": return new String[]{"新西兰", "奥克兰"};
            case "YYZ": return new String[]{"加拿大", "多伦多"};
            case "YVR": return new String[]{"加拿大", "温哥华"};
            case "YYC": return new String[]{"加拿大", "卡尔加里"};
            case "PVG": return new String[]{"中国", "上海"};
            case "PEK": return new String[]{"中国", "北京"};
            case "CAN": return new String[]{"中国", "广州"};
            case "CTU": return new String[]{"中国", "成都"};
            case "HGH": return new String[]{"中国", "杭州"};
            case "SZX": return new String[]{"中国", "深圳"};
            default: return null;
        }
    }

    public interface Progress {
        /** phase: sample / latency / speed / done */
        void onProgress(String phase, int done, int total, String detail);
    }

    /**
     * 逐 IP 的过程回调（可选）。
     *
     * 只有 {@link Progress} 的话，界面上只能看到「存活 42 / 已测 128」这种聚合数字——
     * 每个 IP 的测试结果一闪而过、跑完就没了，用户看不出「到底在测什么」。
     * 这里把单条结果抛给界面，界面才能把过程本身画成一张实时表。
     *
     * 回调发生在工作线程（测速阶段最多 8 个并发），实现方自行加锁。
     */
    public interface IpListener {
        /** 延迟测试完成：rttMs >= 0 表示通，< 0 表示不通（超时/拒绝） */
        void onLatencyTested(String ip, int rttMs);

        /** 开始对该 IP 做下载测速（此时还没有速度，界面可以先显示"测速中"） */
        void onSpeedStarted(String ip);

        /** 下载测速完成：mbps > 0 为实测速度，< 0 为失败 */
        void onSpeedMeasured(String ip, double mbps);

        /** 查到该 IP 连过去落在哪个 CF 机房（三字码，如 LAX / NRT） */
        void onColoMeasured(String ip, String colo);
    }

    private volatile IpListener ipListener;

    /** 设置逐 IP 回调（null = 不关心过程，只要最终结果） */
    public void setIpListener(IpListener l) { this.ipListener = l; }

    private volatile boolean cancelled = false;

    /**
     * 正在读的测速 socket。
     * 阻塞在 read() 上的线程是interrupt不动的，只有把 socket 关掉它才会抛异常退出——
     * 否则按了「取消优选」之后，那几条下载线程还要把剩下的 6MB 慢慢读完才肯停。
     */
    private final java.util.List<javax.net.ssl.SSLSocket> activeSockets =
            java.util.Collections.synchronizedList(new java.util.ArrayList<javax.net.ssl.SSLSocket>());

    /**
     * 请求取消优选，optimize 会尽快以 InterruptedException 结束。
     *
     * 除了置标志，还要做两件事：掐掉正在读的 socket（不然取消要等几十秒），
     * 以及摘掉回调（不然旧任务的 IP 结果还会往已经收尾的成绩单里塞行，把结果搅乱）。
     */
    public void cancel() {
        cancelled = true;
        ipListener = null;
        synchronized (activeSockets) {
            for (int i = 0; i < activeSockets.size(); i++) {
                try { activeSockets.get(i).close(); } catch (Exception ignored) {}
            }
            activeSockets.clear();
        }
    }

    private void checkCancelled() throws InterruptedException {
        if (cancelled) throw new InterruptedException("已取消");
    }

    public final int sampleCount;   // 采样数
    public final int latencyTop;    // 进入测速的个数
    public final int topN;          // 最终保留个数
    public final int downloadBytes; // 测速下载字节

    public CfOptimizer() {
        this(320, 24, 8, 6 * 1024 * 1024);
    }

    public CfOptimizer(int sampleCount, int latencyTop, int topN, int downloadBytes) {
        this.sampleCount = sampleCount;
        this.latencyTop = latencyTop;
        this.topN = topN;
        this.downloadBytes = downloadBytes;
    }

    /** 阻塞执行完整优选流程，在工作线程调用 */
    public List<Candidate> optimize(Progress progress) throws InterruptedException {
        // ---------- 1. 采样 ----------
        progress.onProgress("sample", 0, 1, "正在采样 CF 网段…");
        List<String> ips = sampleIps(sampleCount);
        progress.onProgress("sample", 1, 1, "已采样 " + ips.size() + " 个 IP");

        // ---------- 2. TCP 延迟 ----------
        progress.onProgress("latency", 0, ips.size(), "TCP 延迟测试中…");
        final List<Candidate> latencyOk = Collections.synchronizedList(new ArrayList<Candidate>());
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(64);
        for (final String ip : ips) {
            pool.execute(new Runnable() {
                @Override public void run() {
                    int rtt = tcpPing(ip, 443, 1200);
                    if (rtt >= 0) {
                        Candidate c = new Candidate(ip);
                        c.rttMs = rtt;
                        latencyOk.add(c);
                    }
                    IpListener il = ipListener;
                    if (il != null) il.onLatencyTested(ip, rtt);
                    int d = done.incrementAndGet();
                    if (d % 32 == 0) {
                        progress.onProgress("latency", d, ips.size(), "已测 " + d + " / " + ips.size()
                                + " · 存活 " + latencyOk.size());
                    }
                }
            });
        }
        pool.shutdown();
        while (!pool.isTerminated()) {
            checkCancelled();
            if (cancelled) pool.shutdownNow();
            Thread.sleep(100);
        }
        progress.onProgress("latency", ips.size(), ips.size(), "存活 " + latencyOk.size() + " 个");

        Collections.sort(latencyOk, new Comparator<Candidate>() {
            @Override public int compare(Candidate a, Candidate b) {
                return Integer.compare(a.rttMs, b.rttMs);
            }
        });
        List<Candidate> speedPool = latencyOk.subList(0, Math.min(latencyTop, latencyOk.size()));

        final List<Candidate> speedList = new ArrayList<Candidate>(speedPool);

        // ---------- 3. 下载测速 ----------
        progress.onProgress("speed", 0, speedList.size(), "下载测速中…");
        AtomicInteger done2 = new AtomicInteger();
        ExecutorService pool2 = Executors.newFixedThreadPool(speedConcurrency(downloadBytes));
        ExecutorService pool3 = Executors.newFixedThreadPool(4);   // 归属探测：1KB 一个，别占带宽
        for (final Candidate c : speedList) {
            pool2.execute(new Runnable() {
                @Override public void run() {
                    if (cancelled) return;
                    IpListener il = ipListener;
                    if (il != null) il.onSpeedStarted(c.ip);
                    c.mbps = downloadSpeed(c.ip, downloadBytes);
                    if (il != null) il.onSpeedMeasured(c.ip, c.mbps);
                    progress.onProgress("speed", done2.incrementAndGet(), speedList.size(),
                            c.ip + " → " + (c.mbps < 0 ? "失败" : String.format("%.1f Mbps", c.mbps)));
                }
            });
            // 只对进测速的这一批查机房（十几到二十几个），320 个全查既慢又没必要：
            // 同一出口下多数 IP 的落点相同，逐个查只是重复劳动。
            pool3.execute(new Runnable() {
                @Override public void run() {
                    if (cancelled) return;
                    String colo = fetchColo(c.ip, 6000);
                    if (colo == null || cancelled) return;
                    c.colo = colo;
                    IpListener il = ipListener;
                    if (il != null) il.onColoMeasured(c.ip, colo);
                }
            });
        }
        pool2.shutdown();
        pool3.shutdown();
        while (!pool2.isTerminated() || !pool3.isTerminated()) {
            checkCancelled();
            if (cancelled) { pool2.shutdownNow(); pool3.shutdownNow(); }
            Thread.sleep(100);
        }

        // 测速结果统计：全失败时给出明确提示，便于排查网络是否屏蔽了该域名
        int speedOk = 0;
        for (Candidate c : speedPool) if (c.mbps > 0) speedOk++;
        progress.onProgress("speed", speedPool.size(), speedPool.size(),
                speedOk > 0 ? ("测速成功 " + speedOk + "/" + speedPool.size())
                        : "测速全部失败（网络可能无法访问 " + SPEED_HOST + "）");

        // ---------- 4. 排序 ----------
        // topN > 0：保留前 N 名（老行为，给调用方留余地）
        // topN <= 0：不限条数——测速成功的全部留下，界面按速度排好直接当结果用
        List<Candidate> result = new ArrayList<Candidate>();
        // 速度有效的排前面，按速度降序；速度同分按延迟升序
        List<Candidate> bySpeed = new ArrayList<Candidate>();
        List<Candidate> noSpeed = new ArrayList<Candidate>();
        for (Candidate c : latencyOk) {
            if (c.mbps > 0) bySpeed.add(c); else if (c.rttMs >= 0) noSpeed.add(c);
        }
        Collections.sort(bySpeed, new Comparator<Candidate>() {
            @Override public int compare(Candidate a, Candidate b) {
                int cmp = Double.compare(b.mbps, a.mbps);
                return cmp != 0 ? cmp : Integer.compare(a.rttMs, b.rttMs);
            }
        });
        int limit = topN > 0 ? topN : Integer.MAX_VALUE;
        result.addAll(bySpeed.subList(0, Math.min(limit, bySpeed.size())));
        if (topN > 0) {
            for (Candidate c : noSpeed) {
                if (result.size() >= limit) break;
                result.add(c);
            }
        } else if (result.isEmpty()) {
            // 一个都没测出速度：把延迟可用的全给出去，总比空手强
            result.addAll(noSpeed);
        }
        progress.onProgress("done", 1, 1, "优选完成");
        return result;
    }

    // ===================== 内部实现 =====================

    /** 按网段大小加权随机采样 n 个不重复 IP */
    static List<String> sampleIps(int n) {
        // 计算每个网段的 IP 数
        long total = 0;
        long[] sizes = new long[CIDRS.length];
        for (int i = 0; i < CIDRS.length; i++) {
            sizes[i] = cidrSize(CIDRS[i]);
            total += sizes[i];
        }
        Random rnd = new Random();
        Set<Integer> unique = new HashSet<Integer>();
        List<String> out = new ArrayList<String>();
        int guard = 0;
        while (out.size() < n && guard++ < n * 20) {
            long target = (long) (rnd.nextDouble() * total);
            int idx = 0;
            for (int i = 0; i < sizes.length; i++) {
                if (target < sizes[i]) { idx = i; break; }
                target -= sizes[i];
            }
            int pos = (int) (rnd.nextDouble() * (sizes[idx] - 2)) + 1; // 跳过网络号
            String ip = cidrIndexToIp(CIDRS[idx], pos);
            Integer key = ipToInt(ip);
            if (unique.add(key)) out.add(ip);
        }
        return out;
    }

    static long cidrSize(String cidr) {
        int prefix = Integer.parseInt(cidr.split("/")[1]);
        return 1L << (32 - prefix);
    }

    static int ipToInt(String ip) {
        String[] p = ip.split("\\.");
        return (Integer.parseInt(p[0]) << 24) | (Integer.parseInt(p[1]) << 16)
                | (Integer.parseInt(p[2]) << 8) | Integer.parseInt(p[3]);
    }

    static String cidrIndexToIp(String cidr, long index) {
        String base = cidr.split("/")[0];
        int baseInt = ipToInt(base);
        int v = baseInt + (int) index;
        return ((v >>> 24) & 0xFF) + "." + ((v >>> 16) & 0xFF) + "."
                + ((v >>> 8) & 0xFF) + "." + (v & 0xFF);
    }

    /** TCP connect 测延迟，失败返回 -1 */
    static int tcpPing(String ip, int port, int timeoutMs) {
        Socket socket = new Socket();
        try {
            long t0 = System.nanoTime();
            socket.connect(new InetSocketAddress(InetAddress.getByName(ip), port), timeoutMs);
            long t1 = System.nanoTime();
            return (int) ((t1 - t0) / 1000000L);
        } catch (Exception e) {
            return -1;
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 通过 IP 直连 HTTPS 下载测速。
     *
     * 注意：这里**不能**用 HttpURLConnection —— Android 的实现会忽略手动设置的
     * Host 请求头（属于受限头），请求会落到 https://&lt;IP&gt;/，Cloudflare 无法按域名路由，
     * 结果必然失败（表现为全部"未测速"）。因此改用裸 SSLSocket：
     *   - SNI 设为 speed.cloudflare.com（CF 靠 SNI 决定路由）
     *   - 手工拼 HTTP 请求，Host 头由我们自己写
     *   - 只统计响应体读取阶段的时间，排除 TLS 握手开销
     *   - 大包按 6MB 拆片（见 speedPieceSizes），字节数与耗时逐片累加
     */
    double downloadSpeed(String ip, int bytes) {
        // 见 speedPieceSizes：大包拆片，字节数和耗时逐片累加，
        // 只有一片都没读回来才判失败；掉了一片也不连累其余片的读数。
        int[] sizes = speedPieceSizes(bytes);
        long total = 0;
        long nanos = 0;
        for (int i = 0; i < sizes.length; i++) {
            // 已经取消就别再往下开连接了（否则取消要多等几十秒才生效）
            if (cancelled) break;
            long[] r = downloadOnce(ip, sizes[i], speedTimeoutMs(sizes[i]));
            if (r == null) continue;
            total += r[0];
            nanos += r[1];
        }
        if (total == 0 || nanos <= 0) return -1;
        double seconds = nanos / 1e9;
        return (total * 8 / seconds) / 1e6; // Mbps
    }

    /**
     * 把一次下载拆成几片。
     *
     * 原因不是"盒子吃不消"，而是 <b>speed.cloudflare.com 对单次请求有大小上限</b>：
     * 实测 6MB / 10MB 都返回 200 并给满字节，11MB、12MB 直接 403（响应体只有 1 字节），
     * 靠近上限时还会出现只给一半就断流的抖动。
     * 于是「12 MB（精确）」档的每一个 IP 都拿到非 200 → 全部判失败，
     * 而 6MB 档一切正常——就是这个 bug 的全部来龙去脉。
     *
     * 拆片后每片都落在确定能跑通的区间里（≤ 6MB），代价只是多一两次握手；
     * 字节数与耗时逐片累加，总下载量不受影响。
     *
     * @return 每片的字节数，累加正好等于 bytes
     */
    static int[] speedPieceSizes(int bytes) {
        final int MAX_PIECE = 6 * 1024 * 1024;
        int pieces = (bytes + MAX_PIECE - 1) / MAX_PIECE;
        if (pieces < 1) pieces = 1;
        int per = (bytes + pieces - 1) / pieces;
        int[] out = new int[pieces];
        int left = bytes;
        for (int i = 0; i < pieces; i++) {
            out[i] = Math.min(per, left);
            left -= out[i];
        }
        return out;
    }

    /**
     * 一条连接上的一次 GET 下载。
     *
     * @return {读到的字节数, 耗时纳秒}；一个字节都没读到返回 null
     */
    private long[] downloadOnce(String ip, int bytes, int timeoutMs) {
        javax.net.ssl.SSLSocket sock = null;
        long total = 0, t0 = 0;   // 提到 try 外：读超时时要用它们算"半程速度"
        try {
            if (cancelled) return null;
            SSLSocketFactory f = trustAllFactory();
            sock = (javax.net.ssl.SSLSocket) f.createSocket();
            activeSockets.add(sock);   // 登记：取消时好把这条连接掐断
            sock.connect(new InetSocketAddress(InetAddress.getByName(ip), 443), timeoutMs);
            sock.setSoTimeout(timeoutMs);

            // SNI：Cloudflare 依据 SNI 选择站点，必须是目标域名而不是 IP
            try {
                javax.net.ssl.SSLParameters params = sock.getSSLParameters();
                java.util.List<javax.net.ssl.SNIServerName> names = new ArrayList<javax.net.ssl.SNIServerName>();
                names.add(new javax.net.ssl.SNIHostName(SPEED_HOST));
                params.setServerNames(names);
                sock.setSSLParameters(params);
            } catch (Throwable ignored) { }

            sock.startHandshake();

            java.io.OutputStream os = sock.getOutputStream();
            String req = "GET /__down?bytes=" + bytes + " HTTP/1.1\r\n"
                    + "Host: " + SPEED_HOST + "\r\n"
                    + "User-Agent: TVHostBox/1.0\r\n"
                    + "Accept: */*\r\n"
                    + "Connection: close\r\n\r\n";
            os.write(req.getBytes("UTF-8"));
            os.flush();

            InputStream is = sock.getInputStream();

            // 读取响应头，直到 \r\n\r\n（上限 8KB）
            byte[] hdr = new byte[8192];
            int hn = 0;
            while (hn < hdr.length) {
                int b = is.read();
                if (b < 0) break;
                hdr[hn++] = (byte) b;
                if (hn >= 4 && hdr[hn - 4] == '\r' && hdr[hn - 3] == '\n'
                        && hdr[hn - 2] == '\r' && hdr[hn - 1] == '\n') break;
            }
            String head = new String(hdr, 0, hn, "ISO-8859-1");
            if (!head.startsWith("HTTP/1.")) return null;
            int sp = head.indexOf(' ');
            if (sp < 0 || sp + 4 > head.length()) return null;
            if (!"200".equals(head.substring(sp + 1, sp + 4))) return null;

            // 只计时响应体
            t0 = System.nanoTime();
            byte[] buf = new byte[16384];
            int n;
            long deadline = t0 + (long) timeoutMs * 1000000L * 3;
            while (total < bytes && (n = is.read(buf)) > 0) {
                total += n;
                if (System.nanoTime() > deadline) break;
            }
            long t1 = System.nanoTime();
            if (total == 0) return null;
            return new long[]{total, t1 - t0};
        } catch (java.net.SocketTimeoutException e) {
            // 大包在慢网或盒子 CPU 忙时，一次 read 可能卡到超时。
            // 只要已经读到过数据，就用读到的那部分算速度——半程的平均速度同样是有效测量，
            // 直接判"失败"会让人以为这个 IP 不能用，其实是没测完而已。
            double seconds = t0 > 0 ? (System.nanoTime() - t0) / 1e9 : 0;
            if (total > 0 && seconds > 0) return new long[]{total, (long) (seconds * 1e9)};
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (sock != null) {
                activeSockets.remove(sock);
                try { sock.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 下载测速的并发数。
     *
     * 不随下载量变化：每条连接上的在途字节已经被 speedPieceSizes 限在 6MB，
     * 12MB 档的在途量和 6MB 档一样，再降并发只会让整轮白白慢一倍。
     */
    static int speedConcurrency(int bytes) {
        return 8;
    }

    /**
     * 下载测速的单次读超时（按**单片**大小推导，不是整档大小）。
     *
     * 以前写死 8 秒：链路稍慢或抖一下，一次 read 就超过 8 秒 → 整条判失败，
     * 成绩单上一片红，其实那个 IP 完全能用。
     * 这里给足「1 MB 用 1.5 秒」的余量（约 5.5 Mbps 的下限），再夹在 8~30 秒之间：
     * 小包不拖沓，大包不误杀。
     */
    static int speedTimeoutMs(int bytes) {
        int t = 8000 + (bytes / (1024 * 1024)) * 1500;
        return Math.max(8000, Math.min(30000, t));
    }

    /**
     * 查一个 IP 连过去落在哪个 CF 机房。
     *
     * 走的是 CF 边缘自带的 /cdn-cgi/trace，响应里有一行 `colo=LAX`（三字机房码）。
     * 这里必须和 downloadSpeed 一样用裸 SSLSocket：Android 的 HttpURLConnection
     * 会忽略手工设置的 Host 头，请求会落到 https://&lt;IP&gt;/，CF 无法按域名路由。
     *
     * 注意：拿到的是 **这条链路的落点**，不是 IP 的"国籍"。
     * 同一出口下不同 IP 常常落在同一个机房，这是 Anycast 的正常表现，不是查错了。
     *
     * @return 三字机房码（大写），查不到返回 null
     */
    static String fetchColo(String ip, int timeoutMs) {
        SSLSocket sock = null;
        try {
            SSLSocketFactory f = trustAllFactory();
            sock = (SSLSocket) f.createSocket();
            sock.connect(new InetSocketAddress(InetAddress.getByName(ip), 443), timeoutMs);
            sock.setSoTimeout(timeoutMs);
            try {
                SSLParameters params = sock.getSSLParameters();
                List<SNIServerName> names = new ArrayList<SNIServerName>();
                names.add(new SNIHostName(TRACE_HOST));
                params.setServerNames(names);
                sock.setSSLParameters(params);
            } catch (Throwable ignored) { }

            sock.startHandshake();
            OutputStream os = sock.getOutputStream();
            os.write(("GET /cdn-cgi/trace HTTP/1.1\r\n"
                    + "Host: " + TRACE_HOST + "\r\n"
                    + "User-Agent: TVHostBox/1.0\r\n"
                    + "Accept: text/plain\r\n"
                    + "Connection: close\r\n\r\n").getBytes("UTF-8"));
            os.flush();

            // 响应约 600 字节；一路读到对方关连接即可，不用去抠 \r\n\r\n
            // （万一 CF 用 chunked 编码，按头截断反而会把 colo 那行切掉一半）
            InputStream is = sock.getInputStream();
            byte[] buf = new byte[2048];
            int n, total = 0;
            while (total < buf.length && (n = is.read(buf, total, buf.length - total)) > 0) {
                total += n;
            }
            String body = new String(buf, 0, total, "ISO-8859-1");

            int i = body.indexOf("colo=");
            if (i < 0) return null;
            int end = body.indexOf('\n', i);
            String v = body.substring(i + 5, end < 0 ? body.length() : end).trim();
            if (v.isEmpty() || v.length() > 3) return null;
            for (int k = 0; k < v.length(); k++) {
                char ch = v.charAt(k);
                if (ch < 'A' || ch > 'Z') return null;
            }
            return v;
        } catch (Exception e) {
            return null;
        } finally {
            if (sock != null) try { sock.close(); } catch (Exception ignored) {}
        }
    }

    private static SSLSocketFactory sFactory;

    static synchronized SSLSocketFactory trustAllFactory() {
        if (sFactory == null) {
            try {
                TrustManager[] tms = new TrustManager[]{ new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                }};
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, tms, new SecureRandom());
                sFactory = ctx.getSocketFactory();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return sFactory;
    }

    static HostnameVerifier trustAllVerifier() {
        return new HostnameVerifier() {
            @Override public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                return true;
            }
        };
    }
}
