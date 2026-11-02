package tv.hostbox.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** hosts 体检的纯逻辑：按 IP 合并、排序、文案。真去连端口的部分不在这里测。 */
public class HostCheckTest {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object expect, Object actual) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        check(name + "（期望 [" + expect + "] 实际 [" + actual + "]", ok);
    }

    private static HostsManager.Entry e(String ip, String host) {
        return new HostsManager.Entry(ip, host, null, 0);
    }

    private static List<HostsManager.Entry> list(HostsManager.Entry... es) {
        List<HostsManager.Entry> l = new ArrayList<HostsManager.Entry>();
        for (HostsManager.Entry x : es) l.add(x);
        return l;
    }

    private static HostCheck.Result r(String ip, int rtt) {
        HostCheck.Result x = new HostCheck.Result(ip);
        x.rttMs = rtt;
        x.port = rtt >= 0 ? 443 : 0;
        return x;
    }

    private static List<HostCheck.Result> results(HostCheck.Result... rs) {
        List<HostCheck.Result> l = new ArrayList<HostCheck.Result>();
        for (HostCheck.Result x : rs) l.add(x);
        return l;
    }

    private static String order(List<HostCheck.Result> l) {
        StringBuilder sb = new StringBuilder();
        for (HostCheck.Result x : l) {
            if (sb.length() > 0) sb.append(',');
            sb.append(x.ip).append(':').append(x.rttMs);
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println("=== 按 IP 合并（体检对地址做，不对域名做）===");
        Map<String, HostCheck.Result> g = HostCheck.group(list(
                e("1.1.1.1", "a.com"),
                e("2.2.2.2", "b.com"),
                e("1.1.1.1", "c.com")));
        eq("两个 IP → 两条", 2, g.size());
        eq("同 IP 的两个域名合到一条", 2, g.get("1.1.1.1").hosts.size());
        eq("第一条域名在前", "a.com", g.get("1.1.1.1").hosts.get(0));
        eq("顺序跟列表一致（1.1.1.1 在前）", "1.1.1.1", new ArrayList<String>(g.keySet()).get(0));
        // 同一行挂着同一个域名两次（hosts 里手抖写重了）也不该变成两条
        eq("重复域名不会重复记", 1,
                HostCheck.group(list(e("1.1.1.1", "a.com"), e("1.1.1.1", "a.com")))
                        .get("1.1.1.1").hosts.size());
        check("空列表不炸", HostCheck.group(list()).isEmpty());
        check("null 不炸", HostCheck.group(null).isEmpty());

        System.out.println("\n=== 统计 ===");
        eq("通了 2 个", 2, HostCheck.okCount(results(r("1.1.1.1", 20), r("2.2.2.2", -1), r("3.3.3.3", 9))));
        eq("全通", "3 个地址全部可达", HostCheck.summary(results(r("1.1.1.1", 20), r("2.2.2.2", 30), r("3.3.3.3", 40))));
        eq("部分不通", "3 个地址，2 个可达", HostCheck.summary(results(r("1.1.1.1", 20), r("2.2.2.2", -1), r("3.3.3.3", 40))));
        eq("全不通", "2 个地址，0 个可达", HostCheck.summary(results(r("1.1.1.1", -1), r("2.2.2.2", -1))));
        eq("空列表也有话说", "还没有可体检的条目", HostCheck.summary(results()));

        System.out.println("\n=== 报告顺序：不通的排最前，其余按延迟 ===");
        eq("不通 → 20ms → 40ms",
                "2.2.2.2:-1,1.1.1.1:20,3.3.3.3:40",
                order(HostCheck.sortForReport(results(
                        r("1.1.1.1", 20), r("3.3.3.3", 40), r("2.2.2.2", -1)))));
        eq("延迟一样时按 IP 排（结果稳定，不会每次顺序都变）",
                "1.1.1.1:20,2.2.2.2:20",
                order(HostCheck.sortForReport(results(r("2.2.2.2", 20), r("1.1.1.1", 20)))));
        eq("原来的列表不能被改坏（界面还要用）", "1.1.1.1:40,2.2.2.2:20", resultsOrigin());

        System.out.println("\n=== 一行文案 ===");
        HostCheck.Result one = r("104.16.1.1", 23);
        one.hosts.add("emby.cc");
        eq("通了：域名 · IP · 延迟", "emby.cc · 104.16.1.1 · 23ms", HostCheck.line(one));
        HostCheck.Result many = r("1.1.1.1", 30);
        many.hosts.add("a.com");
        many.hosts.add("b.com");
        many.hosts.add("c.com");
        eq("一个 IP 挂多个域名要说出来", "a.com 等 3 个 · 1.1.1.1 · 30ms", HostCheck.line(many));
        HostCheck.Result dead = r("9.9.9.9", -1);
        dead.hosts.add("x.com");
        eq("不通就说「不通」，不写「-1ms」", "x.com · 9.9.9.9 · 不通", HostCheck.line(dead));
        eq("null 不炸", "", HostCheck.line(null));

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    /** 验证 sortForReport 不是原地排序 */
    private static String resultsOrigin() {
        List<HostCheck.Result> src = results(r("1.1.1.1", 40), r("2.2.2.2", 20));
        HostCheck.sortForReport(src);
        return order(src);
    }
}
