package tv.hostbox.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 写 hosts 的脚本 + 写操作的门禁，这两处以前是「看不见」的：
 *  - 写入脚本直接 cp 覆盖，掉电就留半截文件；现在改成先写 .new 再 mv 原子替换，
 *    但要保证「hosts 是软链接」的机器不被换成普通文件；
 *  - 配置服务对 POST 不设防，任何网页都能跨站改写 hosts；现在要求一个自定义头。
 *
 * 脚本是真的交给 /bin/sh 跑（临时目录里跑），不是比对字符串。
 */
public class WriteTest {

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

    private static File tmpDir() throws Exception {
        File d = File.createTempFile("wtest", ".d");
        d.delete();
        d.mkdirs();
        return d;
    }

    private static void write(File f, String content) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(content.getBytes("UTF-8"));
        o.close();
    }

    private static String read(File f) throws Exception {
        InputStream is = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static String sh(String script) throws Exception {
        Process p = new ProcessBuilder("/bin/sh").start();
        java.io.OutputStream os = p.getOutputStream();
        os.write(script.getBytes("UTF-8"));
        os.close();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        InputStream is = p.getInputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        p.waitFor();
        return new String(bo.toByteArray(), "UTF-8");
    }

    /** 把脚本里写死的两个路径换到临时目录下跑 */
    private static String localize(String script, File dir) {
        return script.replace(HostsManager.BACKUP_DIR, dir.getAbsolutePath() + "/bk");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 写入脚本：原子替换 ===");
        File d = tmpDir();
        File hosts = new File(d, "hosts");
        write(hosts, "1.1.1.1  a.com\n");
        File tmp = new File(d, "hosts_new");
        write(tmp, "2.2.2.2  b.com\n");

        String script = localize(
                HostsManager.writeScript(tmp.getAbsolutePath(), hosts.getAbsolutePath(),
                        "hosts.bak.v1.20261101_100000"), d);
        String out = sh(script);
        check("脚本报告成功：" + out.trim(), out.contains("WRITE_RC=0"));
        eq("目标文件内容已更新", "2.2.2.2  b.com\n", read(hosts));
        check("不留 hosts.new 残file", !new File(d, "hosts.new").exists());
        File bk = new File(d, "bk/hosts.bak.v1.20261101_100000");
        check("备份文件生成了", bk.exists());
        eq("备份里是旧内容", "1.1.1.1  a.com\n", read(bk));
        // 直接 cp 到目标只允许出现在「软链接」那一支；普通文件必须走 .new + mv，
        // 否则掉电还是半截文件。这里查结构而不是查行为——两种写法行为上都对，
        // 只有「是不是原子的」区分不出来
        String tl = tmp.getAbsolutePath(), hp = hosts.getAbsolutePath();
        int iLink = script.indexOf("[ -L ");
        int iDirect = script.indexOf("cp -f " + tl + " " + hp + "\n");
        int iNew = script.indexOf("cp -f " + tl + " " + hp + ".new");
        check("先判是不是软链接", iLink >= 0);
        check("直接 cp 目标只出现在软链接分支里", iDirect > iLink);
        check("普通文件走 .new + mv（原子替换）",
                iNew > iDirect && script.contains("mv -f " + hp + ".new " + hp));

        System.out.println("\n=== 写入脚本：目标本来是软链接 ===");
        File d2 = tmpDir();
        File real = new File(d2, "real_hosts");
        write(real, "1.1.1.1  a.com\n");
        File link = new File(d2, "hosts");
        // 有些机器的 /system/etc/hosts 是软链接，mv 上去会把链接换成普通文件
        java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath());
        File tmp2 = new File(d2, "hosts_new");
        write(tmp2, "3.3.3.3  c.com\n");
        String out2 = sh(localize(
                HostsManager.writeScript(tmp2.getAbsolutePath(), link.getAbsolutePath(),
                        "hosts.bak.v2.20261101_100000"), d2));
        check("脚本报告成功：" + out2.trim(), out2.contains("WRITE_RC=0"));
        check("软链接还是软链接（没被换成普通文件）",
                java.nio.file.Files.isSymbolicLink(link.toPath()));
        eq("链接指向的真文件内容已更新", "3.3.3.3  c.com\n", read(real));

        System.out.println("\n=== 恢复脚本：同样是原子替换 ===");
        File d3 = tmpDir();
        File cur = new File(d3, "hosts");
        write(cur, "9.9.9.9  now.com\n");
        new File(d3, "bk").mkdirs();
        File src = new File(d3, "bk/hosts.bak.v1.20261101_100000");
        write(src, "1.1.1.1  old.com\n");
        String out3 = sh(localize(HostsManager.restoreScript(
                src.getAbsolutePath(), cur.getAbsolutePath(),
                "hosts.bak.v2.20261101_110000"), d3));
        check("脚本报告成功：" + out3.trim(), out3.contains("RC=0"));
        eq("恢复成了备份里的内容", "1.1.1.1  old.com\n", read(cur));
        check("不留 hosts.new", !new File(d3, "hosts.new").exists());
        check("恢复前也先备份了当前文件", new File(d3, "bk/hosts.bak.v2.20261101_110000").exists());

        System.out.println("\n=== 写操作的门禁 ===");
        Map<String, String> noHead = new HashMap<String, String>();
        Map<String, String> withHead = new HashMap<String, String>();
        withHead.put("x-hostbox", "1");
        eq("POST 不带自定义头 → 拒绝（跨站表单发不出自定义头）",
                false, ConfigServer.writeAllowed("POST", noHead));
        eq("POST 带了自定义头 → 放行", true, ConfigServer.writeAllowed("POST", withHead));
        eq("GET 不需要头（只读，跨站读不到响应）",
                true, ConfigServer.writeAllowed("GET", noHead));
        eq("头名大小写不敏感", true, ConfigServer.writeAllowed("post", withHead));
        Map<String, String> emptyVal = new HashMap<String, String>();
        emptyVal.put("x-hostbox", "");
        eq("头是空值也不行", false, ConfigServer.writeAllowed("POST", emptyVal));

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
