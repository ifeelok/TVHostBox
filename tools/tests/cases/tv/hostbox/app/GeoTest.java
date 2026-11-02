package tv.hostbox.app;

import java.util.List;

/** 验证 CfOptimizer 的段归属匹配 + 采样结果（用真代码，不是复制品） */
public class GeoTest {

    static int failed = 0;

    static void check(String name, Object actual, Object expect) {
        boolean ok = String.valueOf(actual).trim().equals(String.valueOf(expect).trim());
        if (!ok) failed++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name
                + (ok ? "   → " + actual : "   期望 [" + expect + "] 实际 [" + actual + "]"));
    }

    static long sum(int[] a) {
        long s = 0;
        for (long v : a) s += v;
        return s;
    }

    static void checkSizes(String name, int[] sizes, long[] expect) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sizes.length; i++) {
            if (i > 0) sb.append("+");
            sb.append(sizes[i] / (1024 * 1024)).append("MB");
        }
        boolean ok = sizes.length == expect.length;
        if (ok) {
            for (int i = 0; i < sizes.length; i++) if (sizes[i] != expect[i]) ok = false;
        }
        if (!ok) failed++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name + " 分片   → " + sb
                + (ok ? "" : "（期望与给定序列一致）"));
    }

    public static void main(String[] args) {
        System.out.println("== 段归属：IP → 国家 / 城市 ==");
        String[][] cases = {
                {"104.16.123.45",   "美国",       "旧金山"},
                {"104.20.1.1",       "美国",       "旧金山"},
                {"172.64.88.12",     "美国",       "旧金山"},
                {"172.71.255.254",   "美国",       "旧金山"},
                {"197.234.240.1",    "南非",       "约翰内斯堡"},
                {"131.0.72.30",      "哥斯达黎加", "圣何塞"},
                {"190.93.250.9",     "哥斯达黎加", "圣何塞"},
                {"103.22.200.7",     "新加坡",     "新加坡"},
                {"141.101.64.10",    "荷兰",       "阿姆斯特丹"},
                {"162.158.200.5",    "澳大利亚",   "墨尔本"},
                {"198.41.200.1",     "美国",       "旧金山"},
                {"173.245.55.1",     "美国",       "洛杉矶"},
        };
        for (String[] c : cases) {
            CfOptimizer.Candidate cand = new CfOptimizer.Candidate(c[0]);
            check(c[0] + " 归属", cand.segCountry + "/" + cand.segCity, c[1] + "/" + c[2]);
        }

        System.out.println("\n== 段外 IP 不谎报归属 ==");
        CfOptimizer.Candidate outside = new CfOptimizer.Candidate("8.8.8.8");
        check("8.8.8.8 段归属", outside.segCountry, "未知");
        check("8.8.8.8 行内标签", outside.regionShort(), "—");

        System.out.println("\n== 行内标签：没 colo 显示段归属，有 colo 显示机房 ==");
        CfOptimizer.Candidate c1 = new CfOptimizer.Candidate("104.16.1.1");
        check("无 colo", c1.regionShort(), "美国");
        c1.colo = "LAX";
        check("有 colo", c1.regionShort(), "美国 LAX");
        check("详情行", c1.regionFull(), "美国 · 洛杉矶（LAX）");
        c1.colo = "NRT";
        check("机房优先于段归属", c1.regionFull(), "日本 · 东京（NRT）");
        c1.colo = "XYZ";   // 没收录的机房码
        check("未收录的机房码退回段归属", c1.regionShort(), "美国 XYZ");

        System.out.println("\n== 采样：2000 个 IP 必须全部落在 CF 官方段内 ==");
        List<String> ips = CfOptimizer.sampleIps(2000);
        int bad = 0;
        for (String ip : ips) {
            if (CfOptimizer.segIndexOf(ip) < 0) bad++;
        }
        check("采样数", ips.size(), 2000);
        check("落在官方段外的个数", bad, 0);

        int withCountry = 0;
        for (String ip : ips) {
            CfOptimizer.Candidate c = new CfOptimizer.Candidate(ip);
            if (!"未知".equals(c.segCountry)) withCountry++;
        }
        check("拿到归属的个数", withCountry, ips.size());

        System.out.println("\n== 测速超时：按字节数自适应 ==");
        check("6MB", CfOptimizer.speedTimeoutMs(6 * 1024 * 1024), 17000);
        check("12MB", CfOptimizer.speedTimeoutMs(12 * 1024 * 1024), 26000);
        check("2MB", CfOptimizer.speedTimeoutMs(2 * 1024 * 1024), 11000);
        check("50MB（上限 30 秒）", CfOptimizer.speedTimeoutMs(50 * 1024 * 1024), 30000);

        // 并发不随档位变：在途字节已经由分片限死了，再降并发只是让 12MB 档白慢一倍
        System.out.println("\n== 测速并发：与档位无关 ==");
        check("2MB 并发", CfOptimizer.speedConcurrency(2 * 1024 * 1024), 8);
        check("6MB 并发", CfOptimizer.speedConcurrency(6 * 1024 * 1024), 8);
        check("10MB 并发", CfOptimizer.speedConcurrency(10 * 1024 * 1024), 8);
        check("12MB 并发", CfOptimizer.speedConcurrency(12 * 1024 * 1024), 8);

        // 12MB 全军覆没的另一半原因：整包大请求在部分线路上会被掐断，按 6MB 拆片绕开
        System.out.println("\n== 测速分片：大包必须拆成 ≤6MB 的片 ==");
        checkSizes("2MB", CfOptimizer.speedPieceSizes(2 * 1024 * 1024), new long[]{2 * 1024 * 1024});
        checkSizes("6MB", CfOptimizer.speedPieceSizes(6 * 1024 * 1024), new long[]{6 * 1024 * 1024});
        checkSizes("12MB", CfOptimizer.speedPieceSizes(12 * 1024 * 1024),
                new long[]{6 * 1024 * 1024, 6 * 1024 * 1024});
        int[] p7 = CfOptimizer.speedPieceSizes(7 * 1024 * 1024);
        check("7MB 片数", p7.length, 2);
        check("7MB 每片都不超过 6MB", (int) (Math.max(p7[0], p7[1]) <= 6 * 1024 * 1024 ? 1 : 0), 1);
        check("7MB 各片之和", (int) (sum(p7) == 7 * 1024 * 1024 ? 1 : 0), 1);
        // 任意尺寸都不能出现 0 字节的空片（会白跑一次握手）
        int zeroPieces = 0;
        for (int mb = 1; mb <= 30; mb++) {
            for (long s : CfOptimizer.speedPieceSizes(mb * 1024 * 1024)) if (s <= 0) zeroPieces++;
        }
        check("1~30MB 里的空片数", zeroPieces, 0);

        System.out.println(failed == 0 ? "\n全部通过" : "\n失败 " + failed + " 项");
        if (failed > 0) System.exit(1);
    }
}
