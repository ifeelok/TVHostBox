package tv.hostbox.app;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** GitHub raw 加速的纯逻辑：挑最快、注释文案、认不认自己的条目 */
public class RawTest {

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

    private static RawAccel.Pick p(String ip, int rtt) {
        return new RawAccel.Pick(ip, rtt);
    }

    private static List<RawAccel.Pick> list(RawAccel.Pick... ps) {
        List<RawAccel.Pick> l = new ArrayList<RawAccel.Pick>();
        for (RawAccel.Pick x : ps) l.add(x);
        return l;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 挑最快的 ===");
        eq("三个都能连，挑最小", "185.199.110.133",
                RawAccel.pickFastest(list(p("185.199.108.133", 40),
                        p("185.199.109.133", 31),
                        p("185.199.110.133", 22))).ip);
        eq("最快的排在最前面也一样", 22,
                RawAccel.pickFastest(list(p("185.199.110.133", 22),
                        p("185.199.109.133", 31))).rttMs);
        // -1 = 没连上，必须排除，否则「最快的」会是一个根本不通的地址
        eq("连不上的不参与比较", "185.199.108.133",
                RawAccel.pickFastest(list(p("185.199.109.133", -1),
                        p("185.199.108.133", 88))).ip);
        eq("全都不通 → 没得选", null,
                RawAccel.pickFastest(list(p("185.199.108.133", -1),
                        p("185.199.109.133", -1))));
        eq("空列表不炸", null, RawAccel.pickFastest(list()));
        eq("null 不炸", null, RawAccel.pickFastest(null));
        eq("速度一样时取先出现的（结果要稳定）", "185.199.108.133",
                RawAccel.pickFastest(list(p("185.199.108.133", 30),
                        p("185.199.109.133", 30))).ip);

        System.out.println("\n=== 写进行尾的注释 ===");
        long t = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .parse("2026-11-02 11:20").getTime();
        eq("带延迟和时间", "GitHub raw 加速 · 34ms · 11-02 11:20",
                RawAccel.note(34, t));
        eq("没测出延迟（不该发生，但不能写个 -1ms）", "GitHub raw 加速 · 11-02 11:20",
                RawAccel.note(-1, t));
        check("注释里没有 #（# 由写入方加）", RawAccel.note(34, t).indexOf('#') < 0);
        check("注释里没有换行", RawAccel.note(34, t).indexOf('\n') < 0);

        System.out.println("\n=== 认不认得出「这是加速条目」 ===");
        for (int i = 0; i < RawAccel.IPS.length; i++) {
            eq("Fastly 的地址算自己的：" + RawAccel.IPS[i], true,
                    RawAccel.ours(new HostsManager.Entry(RawAccel.IPS[i],
                            "raw.githubusercontent.com", null, 0)));
        }
        // 撤掉的实现是「删掉 IP 属于这 4 个的条目」，认错了就会把用户自己的解析删掉
        eq("用户自己钉的别的 IP 不算", false,
                RawAccel.ours(new HostsManager.Entry("1.2.3.4",
                        "raw.githubusercontent.com", null, 0)));
        eq("CF 优选出来的 IP 不算", false,
                RawAccel.ours(new HostsManager.Entry("104.16.1.1",
                        "raw.githubusercontent.com", null, 0)));
        eq("null 不算", false, RawAccel.ours(null));

        System.out.println("\n=== 候选清单本身就是约定 ===");
        eq("4 个候选地址", 4, RawAccel.IPS.length);
        eq("只钉 raw 和 gist 两个域名（objects./codeload. 不走 Fastly，钉了反而坏）",
                2, RawAccel.HOSTS.length);
        eq("第一个是 raw", "raw.githubusercontent.com", RawAccel.HOSTS[0]);
        eq("第二个是 gist", "gist.githubusercontent.com", RawAccel.HOSTS[1]);

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
