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
 *  1. 从 CF 官方 IPv4 网段按大小加权随机采样 N 个 IP；
 *  2. 并发 TCP connect 443 测延迟（淘汰 > 某阈值的）；
 *  3. 对延迟最好的 M 个走 HTTPS 下载测速（speed.cloudflare.com/__down）；
 *  4. 按下载速度排序取前 topN，速度为零的按延迟补齐。
 */
public final class CfOptimizer {

    /** Cloudflare 官方公告的 IPv4 段 */
    private static final String[] CIDRS = {
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
            "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
            "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22"
    };

    /** 测速用的目标域名（SNI + Host 头都必须是它） */
    private static final String SPEED_HOST = "speed.cloudflare.com";

    public static class Candidate {
        public final String ip;
        public int rttMs = -1;      // -1 = 未测/失败
        public double mbps = -1;    // -1 = 未测速

        Candidate(String ip) { this.ip = ip; }

        @Override public String toString() { return ip; }
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
    }

    private volatile IpListener ipListener;

    /** 设置逐 IP 回调（null = 不关心过程，只要最终结果） */
    public void setIpListener(IpListener l) { this.ipListener = l; }

    private volatile boolean cancelled = false;

    /** 请求取消优选，optimize 会尽快以 InterruptedException 结束 */
    public void cancel() { cancelled = true; }

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

        // ---------- 3. 下载测速 ----------
        progress.onProgress("speed", 0, speedPool.size(), "下载测速中…");
        AtomicInteger done2 = new AtomicInteger();
        ExecutorService pool2 = Executors.newFixedThreadPool(8);
        for (final Candidate c : new ArrayList<Candidate>(speedPool)) {
            pool2.execute(new Runnable() {
                @Override public void run() {
                    if (cancelled) return;
                    IpListener il = ipListener;
                    if (il != null) il.onSpeedStarted(c.ip);
                    c.mbps = downloadSpeed(c.ip, downloadBytes, 8000);
                    if (il != null) il.onSpeedMeasured(c.ip, c.mbps);
                    progress.onProgress("speed", done2.incrementAndGet(), speedPool.size(),
                            c.ip + " → " + (c.mbps < 0 ? "失败" : String.format("%.1f Mbps", c.mbps)));
                }
            });
        }
        pool2.shutdown();
        while (!pool2.isTerminated()) {
            checkCancelled();
            if (cancelled) pool2.shutdownNow();
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
     */
    static double downloadSpeed(String ip, int bytes, int timeoutMs) {
        javax.net.ssl.SSLSocket sock = null;
        try {
            SSLSocketFactory f = trustAllFactory();
            sock = (javax.net.ssl.SSLSocket) f.createSocket();
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
            if (!head.startsWith("HTTP/1.")) return -1;
            int sp = head.indexOf(' ');
            if (sp < 0 || sp + 4 > head.length()) return -1;
            if (!"200".equals(head.substring(sp + 1, sp + 4))) return -1;

            // 只计时响应体
            long t0 = System.nanoTime();
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            long deadline = t0 + (long) timeoutMs * 1000000L * 3;
            while (total < bytes && (n = is.read(buf)) > 0) {
                total += n;
                if (System.nanoTime() > deadline) break;
            }
            long t1 = System.nanoTime();
            double seconds = (t1 - t0) / 1e9;
            if (total == 0 || seconds <= 0) return -1;
            return (total * 8 / seconds) / 1e6; // Mbps
        } catch (Exception e) {
            return -1;
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
