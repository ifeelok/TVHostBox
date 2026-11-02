package tv.hostbox.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 备份命名 / 排序 / 显示 / 改名净化的测试——直接调 HostsManager 里的真方法。
 *
 * 这些全是纯函数（不碰 root、不碰文件），所以能在电脑上直接跑；
 * 真正进 root 命令的部分（删除/清空/恢复）名字都先过 isSafeBackupName，
 * 这条防线由上一轮 HostsRealTest 的注入用例守着。
 */
public class BackupTest {

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

    private static List<HostsManager.Backup> of(String... names) {
        List<HostsManager.Backup> l = new ArrayList<HostsManager.Backup>();
        for (String n : names) l.add(new HostsManager.Backup(n, -1));
        return l;
    }

    /** 排完序后的名字序列，用逗号连起来好比对 */
    private static String sorted(String... names) {
        List<HostsManager.Backup> l = of(names);
        HostsManager.sortBackups(l);
        StringBuilder sb = new StringBuilder();
        for (HostsManager.Backup b : l) {
            if (sb.length() > 0) sb.append(',');
            sb.append(b.name);
        }
        return sb.toString();
    }

    /** 造一个备份文件 */
    private static void write(java.io.File f, String content) throws Exception {
        java.io.FileOutputStream o = new java.io.FileOutputStream(f);
        o.write(content.getBytes("UTF-8"));
        o.close();
    }

    /** 把脚本交给真的 shell 跑一遍，拿回 stdout */
    private static String sh(String script) throws Exception {
        Process p = new ProcessBuilder("/bin/sh").redirectErrorStream(false).start();
        java.io.OutputStream os = p.getOutputStream();
        os.write(script.getBytes("UTF-8"));
        os.close();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        java.io.InputStream is = p.getInputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        p.waitFor();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static void deleteDir(java.io.File dir) {
        java.io.File[] fs = dir.listFiles();
        if (fs != null) for (java.io.File f : fs) f.delete();
        dir.delete();
    }

    private static String names(List<HostsManager.Backup> list) {
        StringBuilder sb = new StringBuilder();
        for (HostsManager.Backup b : list) {
            if (sb.length() > 0) sb.append(',');
            sb.append(b.name);
        }
        return sb.toString();
    }

    private static HostsManager.Backup find(List<HostsManager.Backup> list, String name) {
        for (HostsManager.Backup b : list) if (b.name.equals(name)) return b;
        return new HostsManager.Backup("(没找到)", -1);
    }

    private static boolean noMinusOne(List<HostsManager.Backup> list) {
        for (HostsManager.Backup b : list) if (b.entries < 0) return false;
        return true;
    }

    public static void main(String[] args) {
        System.out.println("=== 解析名字 ===");
        eq("v 序号 v7", 7, HostsManager.parseSeq("hosts.bak.v7.20261101_173221"));
        eq("v 序号 v10（两位数）", 10, HostsManager.parseSeq("hosts.bak.v10.20261101_173221"));
        eq("恢复前备份也能取出序号", 8,
                HostsManager.parseSeq("hosts.bak.v8.20261101_180102.before_restore"));
        eq("老备份没有序号 → 0", 0, HostsManager.parseSeq("hosts.bak.20261101_173221"));
        eq("null 不炸", 0, HostsManager.parseSeq(null));

        long t = HostsManager.parseTimeMs("hosts.bak.v7.20261101_173221");
        check("时间戳能取出来", t > 0);
        eq("同一时间串解析一致", t, HostsManager.parseTimeMs("hosts.bak.20261101_173221"));
        eq("认不出时间 → 0", 0L, HostsManager.parseTimeMs("hosts.bak.v9.no_stamp"));

        System.out.println("\n=== 排序（新→旧）===");
        // 字符串排序会把 v10 排到 v9 前面，按时间戳才对
        eq("v10 要排在 v9 前面",
                "hosts.bak.v10.20261101_180000,hosts.bak.v9.20261101_173221",
                sorted("hosts.bak.v9.20261101_173221", "hosts.bak.v10.20261101_180000"));
        eq("同一秒里的两份，序号大的在前",
                "hosts.bak.v8.20261101_173221,hosts.bak.v7.20261101_173221",
                sorted("hosts.bak.v7.20261101_173221", "hosts.bak.v8.20261101_173221"));
        eq("老备份（无序号）也能排进去",
                "hosts.bak.v2.20261101_180000,hosts.bak.20261101_173221,hosts.bak.20261029_101010",
                sorted("hosts.bak.20261101_173221", "hosts.bak.v2.20261101_180000",
                        "hosts.bak.20261029_101010"));
        eq("认不出时间的排最后",
                "hosts.bak.v3.20261101_180000,hosts.bak.v4.bad",
                sorted("hosts.bak.v4.bad", "hosts.bak.v3.20261101_180000"));

        System.out.println("\n=== 显示文案 ===");
        HostsManager.Backup b = new HostsManager.Backup("hosts.bak.v7.20261101_173221", 3);
        eq("序号 + 时间 + 条数", "v7 · 11-01 17:32 · 3 条", HostsManager.backupLabelText(b));
        b.label = "改 emby 前";
        eq("带上用户起的名字", "v7 · 改 emby 前 · 11-01 17:32 · 3 条",
                HostsManager.backupLabelText(b));
        HostsManager.Backup br =
                new HostsManager.Backup("hosts.bak.v8.20261101_180102.before_restore", 3);
        eq("恢复前自动备份有标记", "v8 · 11-01 18:01 · 3 条   （恢复前自动备份）",
                HostsManager.backupLabelText(br));
        HostsManager.Backup none = new HostsManager.Backup("hosts.bak.v9.20261101_190000", -1);
        eq("没读到条数就不显示条数", "v9 · 11-01 19:00", HostsManager.backupLabelText(none));
        HostsManager.Backup old = new HostsManager.Backup("hosts.bak.20261101_161556", 10);
        eq("老备份（无序号）照旧能显示", "11-01 16:15 · 10 条", HostsManager.backupLabelText(old));
        // 时间认不出、条数也没读到时，还剩序号就只显示序号（不再把一长串文件名堆上去）
        HostsManager.Backup weird = new HostsManager.Backup("hosts.bak.v5.weird", -1);
        eq("认不出时间但有序号 → 只显示序号", "v5", HostsManager.backupLabelText(weird));
        // 名字里一点信息都没有了，才直接给文件名
        HostsManager.Backup mystery = new HostsManager.Backup("hosts.bak.mystery", -1);
        eq("什么都认不出 → 显示文件名", "hosts.bak.mystery",
                HostsManager.backupLabelText(mystery));

        System.out.println("\n=== 改名净化 ===");
        eq("中文原样保留", "改 emby 前", HostsManager.cleanLabel("改 emby 前"));
        eq("换行压成空格", "A B", HostsManager.cleanLabel("A\nB"));
        eq("多余空格压成一个", "A B", HostsManager.cleanLabel("A    B"));
        eq("首尾空格去掉", "A", HostsManager.cleanLabel("  A  "));
        eq("控制字符丢掉", "AB", HostsManager.cleanLabel("A" + (char) 7 + "B"));
        eq("空 → 空", "", HostsManager.cleanLabel(""));
        eq("null → 空", "", HostsManager.cleanLabel(null));
        eq("24 字以内不截断", "123456789012345678901234",
                HostsManager.cleanLabel("123456789012345678901234"));
        eq("超长按字数截到 24", 24, HostsManager.cleanLabel("1234567890123456789012345678").length());
        eq("中文按字算不按字节", 24,
                HostsManager.cleanLabel("一一一一一一一一一一一一一一一一一一一一一一一一一一一一").length());
        // 这些要是进了 root 命令就出事了，所以显示名绝不能出现在文件名里
        eq("$(reboot) 只是普通文字", "$(reboot)", HostsManager.cleanLabel("$(reboot)"));
        eq("反引号只是普通文字", "a`b", HostsManager.cleanLabel("a`b"));

        System.out.println("\n=== 查看内容（备份正文排版）===");
        // 盒子上真实存在的那份：localhost 两行 + 一条 emby
        String real = "127.0.0.1\tlocalhost\n::1\tip6-localhost\n"
                + "104.25.51.1  emby-proxy.kingston.ccwu.cc\n";
        eq("三行内容排出三行",
                "  1   127.0.0.1    localhost\n"
                        + "  2   ::1    ip6-localhost\n"
                        + "  3   104.25.51.1    emby-proxy.kingston.ccwu.cc",
                MainActivity.backupContentText(real));
        eq("带行尾注释的要带出来",
                "  1   1.2.3.4    a.com    # 备注",
                MainActivity.backupContentText("1.2.3.4  a.com  # 备注\n"));
        eq("空文件要说清楚", "（这份备份是空的，一个字都没有）",
                MainActivity.backupContentText(""));
        eq("只有空格也当空", "（这份备份是空的，一个字都没有）",
                MainActivity.backupContentText("   \n \n"));
        eq("只有注释：说明没有条目", "（这份备份里没有解析条目，只有注释或空行）",
                MainActivity.backupContentText("# 全是注释\n\n# 再来一行\n"));
        eq("null 不炸", "", MainActivity.backupContentText(null));
        // 老版本留下来的备份末尾有一堆空行，不能因为它们就把条目挤没了
        String withBlanks = "1.1.1.1  a.com\n\n\n\n2.2.2.2  b.com\n\n\n";
        eq("空行不变成条目",
                "  1   1.1.1.1    a.com\n  5   2.2.2.2    b.com",
                MainActivity.backupContentText(withBlanks));
        // 行号是文件里的真实行号，不是排完序的序号
        eq("行号按文件里的真实位置",
                "  3   9.9.9.9    c.com",
                MainActivity.backupContentText("# 开头注释\n\n9.9.9.9  c.com\n"));

        System.out.println("\n=== 新名字必须是能进 root 命令的安全名 ===");
        // 序号拿不到（没 Context）时不带 v 前缀，但时间戳格式必须还在
        String n = HostsManager.newBackupName(0L, true);
        check("新名字以 hosts.bak. 开头：" + n, n.startsWith("hosts.bak."));
        check("恢复前标记在后缀：" + n, n.endsWith(".before_restore"));
        check("新名字里没有 $ ` ( ) 等危险字符：" + n,
                n.indexOf('$') < 0 && n.indexOf('`') < 0
                        && n.indexOf('(') < 0 && n.indexOf(')') < 0
                        && n.indexOf(';') < 0 && n.indexOf(' ') < 0);
        String n2 = HostsManager.newBackupName(0L, false);
        check("普通备份不带恢复前标记：" + n2, !n2.endsWith(".before_restore"));
        check("两次生成的名字不同（同秒也不会撞名）：" + n + " / " + n2, !n.equals(n2));

        System.out.println("\n=== 恢复前标记：新老两种写法都得认 ===");
        eq("新式 .before_restore 认得出来", true,
                new HostsManager.Backup("hosts.bak.v8.20261101_180102.before_restore", 3)
                        .beforeRestore);
        // 老版本写成 hosts.bak.20261101_175409_before_restore（下划线），
        // 用户盒子上现在还躺着这种文件，认不出来它们就跟普通备份混在一起了
        eq("老式 _before_restore 也要认得出来", true,
                new HostsManager.Backup("hosts.bak.20261101_175409_before_restore", 3)
                        .beforeRestore);
        eq("普通备份不挂恢复前标记", false,
                new HostsManager.Backup("hosts.bak.v7.20261101_173221", 3).beforeRestore);
        eq("名字里带 before_restore 但位置不对的不算", false,
                new HostsManager.Backup("hosts.bak.before_restore.20261101_173221", 3)
                        .beforeRestore);

        System.out.println("\n=== 数条数的脚本：反斜杠不能溜进 $( ) 里 ===");
        String sc = HostsManager.backupListScript();
        check("脚本里有 cd 到备份目录：" + sc, sc.contains("cd /sdcard/hostbox"));
        check("脚本里用单条 ERE 数非注释非空行：" + sc, sc.contains("grep -cvE '^(#|$)'"));
        // 关键：$(...) 里的文件名不能再转义一次。
        // 反斜杠会原样带进内层命令，grep 拿到的文件名就成了 "xxx"（带引号），
        // 于是 No such file、输出为空，条数一路掉成 -1（本地 bash 与盒子上的 sh 都一样）
        check("$( ) 里不能再有 \\\" 转义：" + sc, sc.indexOf("\\\"$f\\\"") < 0);
        check("文件名在 shell 里是裸着被引号包好的：" + sc, sc.contains("\"$f\")"));

        System.out.println("\n=== 真跑一遍脚本：/bin/sh + 临时目录 ===");
        try {
            java.io.File dir = java.io.File.createTempFile("bkprobe", ".d");
            dir.delete();
            dir.mkdirs();
            write(new java.io.File(dir, "hosts.bak.v2.20261101_100000"),
                    "1.1.1.1 a.com\n2.2.2.2 b.com\n# 注释\n\n3.3.3.3 c.com\n");
            write(new java.io.File(dir, "hosts.bak.v1.20261101_090000"),
                    "# 只有注释\n\n\n");
            write(new java.io.File(dir, "hosts.bak.v0.20261101_080000"), "");
            write(new java.io.File(dir, "hosts.bak.v9.20261101_070000.before_restore"),
                    "9.9.9.9 z.com\n");
            // 目录里放个别的文件，脚本不能把它当备份
            write(new java.io.File(dir, "not_a_backup.txt"), "1.1.1.1 x.com\n");
            String live = sc.replace("/sdcard/hostbox", dir.getAbsolutePath());
            String shellOut = sh(live);
            List<HostsManager.Backup> got = HostsManager.parseBackupList(shellOut);
            eq("认出 4 份备份（别的文件不算）", 4, got.size());
            // 排序：新的在前
            eq("排完序的顺序",
                    "hosts.bak.v2.20261101_100000,hosts.bak.v1.20261101_090000,"
                            + "hosts.bak.v0.20261101_080000,"
                            + "hosts.bak.v9.20261101_070000.before_restore",
                    names(got));
            for (HostsManager.Backup one : got) {
                if (one.name.equals("hosts.bak.v2.20261101_100000")) {
                    eq("3 条（注释和空行不算）", 3, one.entries);
                }
            }
            eq("只有注释的那份是 0 条", 0, find(got, "hosts.bak.v1.20261101_090000").entries);
            eq("空文件也是 0 条", 0, find(got, "hosts.bak.v0.20261101_080000").entries);
            eq("恢复前标记读出来了", true,
                    find(got, "hosts.bak.v9.20261101_070000.before_restore").beforeRestore);
            check("条数一个都不是 -1：" + names(got), noMinusOne(got));
            deleteDir(dir);
        } catch (Exception e) {
            check("跑脚本不抛异常：" + e, false);
        }

        System.out.println("\n=== 解析输出 ===");
        List<HostsManager.Backup> p =
                HostsManager.parseBackupList("BK|hosts.bak.v3.20261101_100000|5\n"
                        + "junk line\n"
                        + "BK|hosts.bak.v2.20261101_090000|\n");
        eq("只收 BK| 开头的行", 2, p.size());
        eq("条数读出来了", 5, p.get(0).entries);
        eq("条数读不出来是 -1（不是 0），界面上就不显示", -1, p.get(1).entries);
        eq("老式名字也能进列表", 1,
                HostsManager.parseBackupList(
                        "BK|hosts.bak.20261101_175409_before_restore|3\n").size());
        check("乱七八糟的输出不炸", HostsManager.parseBackupList("").isEmpty());
        check("null 不炸", HostsManager.parseBackupList(null).isEmpty());

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
