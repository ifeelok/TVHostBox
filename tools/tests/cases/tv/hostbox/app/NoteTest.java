package tv.hostbox.app;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * 「把优选信息写进 hosts 行尾注释」的测试——调的都是真方法（upsertText / Candidate.hostsNote）。
 *
 * 为什么要有它：注释是要写进系统文件的，写坏了比不写更糟
 * （比如把兄弟域名的行搞丢、每次写都多一行、把用户的旧备注悄悄抹掉）。
 */
public class NoteTest {

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

    /** 固定一个时间点，避开时区差异：用同一个格式化器反解 */
    private static long ts() throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .parse("2026-11-01 18:30").getTime();
    }

    private static String note(String ip, String colo, int rtt, double mbps, long tsMs) {
        CfOptimizer.Candidate c = new CfOptimizer.Candidate(ip);
        c.colo = colo;
        c.rttMs = rtt;
        c.mbps = mbps;
        return c.hostsNote(tsMs);
    }

    public static void main(String[] args) throws Exception {
        long T = ts();

        System.out.println("=== 优选信息拼成一行注释 ===");
        eq("位置 + 延迟 + 速度 + 时间",
                "CF优选 美国·洛杉矶(LAX) · 23ms · 18.2Mbps · 11-01 18:30",
                note("104.16.1.1", "LAX", 23, 18.24, T));
        // 没查到机房码（trace 没回）就退回 CF 段归属，不能让位置空着
        eq("没查到时用段归属",
                "CF优选 美国·旧金山 · 23ms · 18.2Mbps · 11-01 18:30",
                note("104.16.1.1", null, 23, 18.24, T));
        eq("没测速就不写速度",
                "CF优选 美国·洛杉矶(LAX) · 23ms · 11-01 18:30",
                note("104.16.1.1", "LAX", 23, -1, T));
        eq("没测延迟就不写延迟",
                "CF优选 美国·洛杉矶(LAX) · 18.2Mbps · 11-01 18:30",
                note("104.16.1.1", "LAX", -1, 18.24, T));
        eq("既没延迟也没速度（刚采样完就写）",
                "CF优选 美国·洛杉矶(LAX) · 11-01 18:30",
                note("104.16.1.1", "LAX", -1, -1, T));
        eq("不在 CF 段里的 IP 也要说得清位置",
                "CF优选 位置未知 · 11-01 18:30",
                note("8.8.8.8", null, -1, -1, T));
        check("注释里没有 #（# 由写入方加，注释自身再带一个就成两层了）",
                note("104.16.1.1", "LAX", 23, 18.24, T).indexOf('#') < 0);
        check("注释里没有换行（一个换行就等于在 hosts 里插了一行新内容）",
                note("104.16.1.1", "LAX", 23, 18.24, T).indexOf('\n') < 0);
        eq("速度四舍五入到一位小数", true,
                note("104.16.1.1", "LAX", 23, 18.96, T).contains("19.0Mbps"));

        System.out.println("\n=== 写进 hosts：新域名 ===");
        String raw1 = "# 头注释\n127.0.0.1  localhost\n";
        String out1 = HostsManager.upsertText(raw1, "emby.cc", "104.16.1.1",
                "CF优选 美国·洛杉矶(LAX) · 23ms");
        eq("追加的行带注释",
                "# 头注释\n127.0.0.1  localhost\n104.16.1.1  emby.cc  # CF优选 美国·洛杉矶(LAX) · 23ms\n",
                out1);
        List<HostsManager.Entry> p1 = HostsManager.parse(out1);
        eq("写进去以后能读回来", 2, p1.size());
        eq("注释读回来是干净的（没有 #）", "CF优选 美国·洛杉矶(LAX) · 23ms", p1.get(1).comment);

        System.out.println("\n=== 写进 hosts：已有条目 ===");
        String raw2 = "104.16.1.1  emby.cc  # CF优选 美国·旧金山 · 40ms\n";
        eq("换 IP 时把旧注释换成新的",
                "104.25.51.1  emby.cc  # CF优选 美国·洛杉矶(LAX) · 23ms\n",
                HostsManager.upsertText(raw2, "emby.cc", "104.25.51.1",
                        "CF优选 美国·洛杉矶(LAX) · 23ms"));
        // 手动改 IP（不带注释）时，原来的备注不能被抹掉——用户没让删的东西不能自己消失
        eq("不带注释地改 IP，旧注释保留",
                "104.25.51.1  emby.cc  # CF优选 美国·旧金山 · 40ms\n",
                HostsManager.upsertText(raw2, "emby.cc", "104.25.51.1"));
        eq("不带注释、IP 也没变 → 一字不改", raw2,
                HostsManager.upsertText(raw2, "emby.cc", "104.16.1.1"));

        System.out.println("\n=== 重复写同一份优选结果 ===");
        String raw3 = "104.16.1.1  emby.cc  # CF优选 美国·洛杉矶(LAX) · 23ms\n";
        eq("IP 和注释都一样 → 一字不改（不会每次改都多一行）", raw3,
                HostsManager.upsertText(raw3, "emby.cc", "104.16.1.1",
                        "CF优选 美国·洛杉矶(LAX) · 23ms"));
        eq("IP 没变但重新优选过（延迟变了）→ 注释跟着更新",
                "104.16.1.1  emby.cc  # CF优选 美国·洛杉矶(LAX) · 19ms\n",
                HostsManager.upsertText(raw3, "emby.cc", "104.16.1.1",
                        "CF优选 美国·洛杉矶(LAX) · 19ms"));
        // 连着写 5 次，行数不能涨
        String again = raw3;
        for (int i = 0; i < 5; i++) {
            again = HostsManager.upsertText(again, "emby.cc", "104.16.1.1",
                    "CF优选 美国·洛杉矶(LAX) · 23ms");
        }
        eq("连写 5 次还是一行", raw3, again);

        System.out.println("\n=== 一个 IP 挂多个域名 ===");
        String raw4 = "104.16.1.1  a.com  b.com\n";
        String out4 = HostsManager.upsertText(raw4, "b.com", "104.16.1.1",
                "CF优选 美国·洛杉矶(LAX) · 23ms");
        eq("摘出来单独成行并带上注释",
                "104.16.1.1  a.com\n104.16.1.1  b.com  # CF优选 美国·洛杉矶(LAX) · 23ms\n",
                out4);
        eq("兄弟域名没丢", 2, HostsManager.parse(out4).size());

        System.out.println("\n=== 注释不影响校验 ===");
        String line = "104.16.1.1  emby.cc  # CF优选 美国·洛杉矶(LAX) · 23ms · 18.2Mbps";
        eq("带注释的行能通过校验", null, HostsManager.validateLine(line));

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
