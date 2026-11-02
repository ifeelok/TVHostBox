package tv.hostbox.app;

/**
 * 备份对比：比的是「恢复这份以后会变成什么样」。
 *
 * 这里最容易错的是方向（谁减谁加）和「行号算不算变化」——行号会随增删整体平移，
 * 拿它当身份会得出一片假差异。所以这组用例专门盯这两件事。
 */
public class DiffTest {

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

    public static void main(String[] args) {
        System.out.println("=== 一模一样 ===");
        eq("完全相同 → 说清楚，不装神秘",
                "和当前完全一样，恢复它不会有任何变化。",
                HostsManager.diffText("1.1.1.1  a.com\n", "1.1.1.1  a.com\n"));
        // 行号平移不算变化：备份里多一行注释，条目的位置就变了，但内容没变
        eq("行号变了但内容没变 → 一样",
                "和当前完全一样，恢复它不会有任何变化。",
                HostsManager.diffText("# 现在的注释\n1.1.1.1  a.com\n",
                        "# 以前的注释\n\n\n1.1.1.1  a.com\n"));

        System.out.println("\n=== 方向：恢复后 ===");
        // current 有 a、bak 没有 → 恢复后 a 会消失
        eq("恢复后会消失的用 -",
                "-  a.com · 1.1.1.1\n",
                HostsManager.diffText("1.1.1.1  a.com\n", ""));
        // bak 有 c、current 没有 → 恢复后 c 会出现
        eq("恢复后会新增的用 +",
                "+  c.com · 3.3.3.3\n",
                HostsManager.diffText("", "3.3.3.3  c.com\n"));
        eq("IP 变了：当前 → 恢复后",
                "~  a.com · 1.1.1.1 → 2.2.2.2\n",
                HostsManager.diffText("1.1.1.1  a.com\n", "2.2.2.2  a.com\n"));

        System.out.println("\n=== 排序：先看会丢什么 ===");
        eq("按当前文件顺序列变化，最后列新增",
                "-  a.com · 1.1.1.1\n~  b.com · 2.2.2.2 → 9.9.9.9\n+  c.com · 3.3.3.3\n",
                HostsManager.diffText("1.1.1.1  a.com\n2.2.2.2  b.com\n",
                        "9.9.9.9  b.com\n3.3.3.3  c.com\n"));
        check("没变化的条目不出现在 diff 里",
                HostsManager.diffText("1.1.1.1  a.com\n2.2.2.2  b.com\n",
                        "1.1.1.1  a.com\n3.3.3.3  b.com\n").indexOf("a.com") < 0);

        System.out.println("\n=== 只有备注变了 ===");
        // 优选信息写在行尾注释里，IP 没变但延迟刷新了——这也是一次改动，不能装看不见
        eq("IP 相同、备注不同 → 说备注会变",
                "~  a.com · 1.1.1.1 · 备注会变\n",
                HostsManager.diffText("1.1.1.1  a.com  # 23ms\n",
                        "1.1.1.1  a.com  # 19ms\n"));
        eq("一边有备注一边没有也算变了",
                "~  a.com · 1.1.1.1 · 备注会变\n",
                HostsManager.diffText("1.1.1.1  a.com  # CF优选\n", "1.1.1.1  a.com\n"));

        System.out.println("\n=== 边界 ===");
        eq("两份都是空 → 一样",
                "和当前完全一样，恢复它不会有任何变化。",
                HostsManager.diffText("", ""));
        check("null 不炸",
                HostsManager.diffText(null, null).startsWith("和当前完全一样"));
        check("一边 null 也不炸",
                HostsManager.diffText(null, "1.1.1.1  a.com\n").startsWith("+"));
        // 同域名出现两次时只认第一条（全文本来就只认第一条解析）
        eq("重复域名不产生两条差异",
                "和当前完全一样，恢复它不会有任何变化。",
                HostsManager.diffText("1.1.1.1  a.com\n1.1.1.1  a.com\n",
                        "1.1.1.1  a.com\n"));
        eq("只有注释行不同 → 一样（注释不解析成条目）",
                "和当前完全一样，恢复它不会有任何变化。",
                HostsManager.diffText("# 现在\n1.1.1.1  a.com\n",
                        "# 以前\n1.1.1.1  a.com\n"));

        System.out.println("\n=== 太多变化要截断 ===");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 50; i++) many.append("1.1.1.").append(i).append("  h").append(i).append(".com\n");
        String d = HostsManager.diffText(many.toString(), "");
        eq("最多列 40 行", 40, d.split("\n").length - 1);
        check("并说清还有多少没列：" + d.substring(d.length() - 30),
                d.contains("…还有 10 处变化没列出来"));

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
