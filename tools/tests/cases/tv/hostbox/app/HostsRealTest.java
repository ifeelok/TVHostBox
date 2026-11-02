package tv.hostbox.app;

import java.util.List;

/**
 * 直接测 HostsManager 真代码的纯文本部分。
 *
 * 之前那份 HostsTest 是把逻辑复制一份来测——复制品全绿，真代码却是坏的
 * （upsert 里 replaced 漏赋值，改一次 IP 就多出一行重复解析）。
 * 所以这份测试只调 HostsManager.upsertText / removeText / parse 本身，不复制任何逻辑。
 */
public class HostsRealTest {

    static int failed = 0;

    public static void main(String[] args) {
        System.out.println("== upsert：独占行改 IP ==");
        {
            String raw = "# 顶部注释\n127.0.0.1  localhost\n1.1.1.1  a.com\n2.2.2.2  b.com\n";
            String out = HostsManager.upsertText(raw, "a.com", "9.9.9.9");
            check("a.com 全文只出现 1 次", countEntry(out, "a.com"), 1);
            check("改成了新 IP", out.contains("9.9.9.9  a.com"), true);
            check("旧 IP 没了", out.contains("1.1.1.1  a.com"), false);
            check("兄弟条目还在", out.contains("2.2.2.2  b.com"), true);
            check("localhost 还在", out.contains("127.0.0.1  localhost"), true);
            check("行数没膨胀", lineCount(out), lineCount(raw));
        }

        System.out.println("\n== upsert：目标值就是当前值，不该动 ==");
        {
            String raw = "1.1.1.1  a.com\n";
            String out = HostsManager.upsertText(raw, "a.com", "1.1.1.1");
            check("a.com 只出现 1 次", countEntry(out, "a.com"), 1);
            check("原文一字不改", out, raw);
        }

        System.out.println("\n== upsert：共享行里摘出来 ==");
        {
            String raw = "1.1.1.1  a.com b.com\n";
            String out = HostsManager.upsertText(raw, "a.com", "9.9.9.9");
            check("a.com 只出现 1 次", countEntry(out, "a.com"), 1);
            check("b.com 跟着原 IP 留着", out.contains("1.1.1.1  b.com"), true);
            check("b.com 没有被误改", out.contains("9.9.9.9  b.com"), false);
            check("a.com 独占新行", out.contains("9.9.9.9  a.com"), true);
        }

        System.out.println("\n== upsert：新条目追加到末尾 ==");
        {
            String raw = "127.0.0.1  localhost\n";
            String out = HostsManager.upsertText(raw, "new.com", "5.5.5.5");
            check("新条目在末尾", out.trim().endsWith("5.5.5.5  new.com"), true);
            check("localhost 还在", out.contains("127.0.0.1  localhost"), true);
        }

        System.out.println("\n== upsert：全文已有重复行时必须收敛成一条 ==");
        {
            String raw = "1.1.1.1  x.com\n2.2.2.2  x.com\n";
            String out = HostsManager.upsertText(raw, "x.com", "9.9.9.9");
            check("x.com 只剩 1 条", countEntry(out, "x.com"), 1);
            check("指向新 IP", out.contains("9.9.9.9  x.com"), true);
        }

        System.out.println("\n== upsert：带行尾注释 ==");
        {
            String raw = "1.1.1.1  a.com  # 我的备注\n";
            String out = HostsManager.upsertText(raw, "a.com", "9.9.9.9");
            check("a.com 只出现 1 次", countEntry(out, "a.com"), 1);
            check("注释保留", out.contains("# 我的备注"), true);
        }

        System.out.println("\n== remove ==");
        {
            String raw = "1.1.1.1  a.com b.com\n127.0.0.1  localhost\n";
            String out = HostsManager.removeText(raw, "a.com");
            check("a.com 没了", out != null && !out.contains("a.com"), true);
            check("b.com 还在", out != null && out.contains("1.1.1.1  b.com"), true);
            check("localhost 还在", out != null && out.contains("127.0.0.1  localhost"), true);
        }
        {
            String raw = "127.0.0.1  localhost\n1.1.1.1  a.com\n";
            String out = HostsManager.removeText(raw, "a.com");
            check("独占行整行删掉", out != null && !out.contains("a.com"), true);
            check("localhost 不受影响", out != null && out.contains("127.0.0.1  localhost"), true);
        }
        check("删不存在的返回 null（=报错）", HostsManager.removeText("1.1.1.1  a.com\n", "nope.com") == null, true);

        System.out.println("\n== parse：一行多域名 ==");
        {
            List<HostsManager.Entry> es = HostsManager.parse("1.1.1.1  a.com b.com\n");
            check("两条都解析出来", es.size(), 2);
            check("第一条是 a.com", es.get(0).host, "a.com");
            check("第二条是 b.com", es.get(1).host, "b.com");
        }

        System.out.println("\n== 极端：一行里同一个域名写了两遍 ==");
        {
            String raw = "1.1.1.1  a.com a.com\n";
            String out = HostsManager.removeText(raw, "a.com");
            check("删完不留孤立 IP", out != null && out.trim().isEmpty(), true);
        }

        System.out.println("\n== 反复写入不该让文件越来越长 ==");
        {
            String raw = "127.0.0.1  localhost\n1.1.1.1  a.com\n";
            String out = raw;
            for (int i = 0; i < 5; i++) out = HostsManager.upsertText(out, "a.com", (i + 1) + ".0.0.1");
            check("改 5 次后行数不变", lineCount(out), lineCount(raw));
            check("改 5 次后 a.com 仍只有 1 条", countEntry(out, "a.com"), 1);
            check("末尾没有堆积空行", out.endsWith("5.0.0.1  a.com\n"), true);
        }
        {
            String raw = "1.1.1.1  a.com\n2.2.2.2  b.com\n";
            String out = HostsManager.removeText(raw, "b.com");
            check("删完正好少一行", lineCount(out), lineCount(raw) - 1);
            check("删完末尾没有空行", out, "1.1.1.1  a.com\n");
        }

        System.out.println("\n== 域名校验 ==");
        check("中文域名被拒", HostsManager.isValidDomain("中文.com"), false);
        check("带空格被拒", HostsManager.isValidDomain("a b.com"), false);
        check("正常域名放行", HostsManager.isValidDomain("a.b-c.com"), true);

        System.out.println(failed == 0 ? "\n全部通过" : "\n失败 " + failed + " 项");
        if (failed > 0) System.exit(1);
    }

    /** 数独立行上出现该域名的次数（不作子串匹配，避免 a.com 命中 a.com.cn） */
    static int countEntry(String text, String domain) {
        int n = 0;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int h = t.indexOf('#');
            if (h >= 0) t = t.substring(0, h).trim();
            for (String p : t.split("\\s+")) if (p.equals(domain)) n++;
        }
        return n;
    }

    static int lineCount(String text) {
        return text.split("\n", -1).length;
    }

    static void check(String name, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        if (!ok) failed++;
        System.out.println("  " + (ok ? "PASS" : "FAIL") + "  " + name
                + (ok ? "" : "   期望 [" + want + "] 实际 [" + got + "]"));
    }
}
