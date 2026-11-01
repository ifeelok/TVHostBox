package tv.hostbox.app;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * /system/etc/hosts 的读取 / 修改（需 root）。
 *
 * 设计：
 *  - 读取：hosts 权限 644，App 直接读即可，失败再用 root cat。
 *  - 写入：先在 App 私有目录生成新文件 → 备份原文件到 /sdcard/hostbox/ →
 *          remount rw → cp 覆盖 → chmod 644 → 回读校验。
 */
public final class HostsManager {

    public static final String HOSTS_PATH = "/system/etc/hosts";
    private static final String TMP_NAME = "hosts_new";
    private static final String BACKUP_DIR = "/sdcard/hostbox";
    /** 备份保留数量的 SharedPreferences key；0 = 不限制 */
    public static final String PREF_MAX_BACKUPS = "maxBackups";
    public static final int DEFAULT_MAX_BACKUPS = 20;

    public static class Entry {
        public String ip;
        public String host;
        public String comment; // 行尾注释，可为 null
        public int lineIndex;  // 在原文件中的行号（0-based）

        public Entry(String ip, String host, String comment, int lineIndex) {
            this.ip = ip;
            this.host = host;
            this.comment = comment;
            this.lineIndex = lineIndex;
        }

        @Override
        public String toString() {
            return host + " → " + ip;
        }
    }

    private HostsManager() {}

    /** 读取 hosts 原始全文 */
    public static String readRaw() throws Exception {
        try {
            FileInputStream fis = new FileInputStream(HOSTS_PATH);
            try {
                return readAll(fis);
            } finally {
                fis.close();
            }
        } catch (Exception direct) {
            RootShell.Result r = RootShell.run("cat " + HOSTS_PATH);
            if (r.ok && r.output.contains("\n")) {
                return r.output;
            }
            throw new Exception("无法读取 hosts：" + direct + " / " + r.output);
        }
    }

    /**
     * 解析出有效条目（IP 域名），跳过注释与空行。
     *
     * 一行可能挂着多个域名（"1.2.3.4  a.com b.com" 在盒子的默认 hosts 里很常见），
     * 这里必须逐个域名各产出一条——以前只取 parts[1]，第二个域名在界面上等于不存在，
     * 删它删不掉、改它改不到，连"它还在不在"都看不出来。
     */
    public static List<Entry> parse(String raw) {
        List<Entry> list = new ArrayList<Entry>();
        String[] lines = raw.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            String comment = null;
            int hash = trimmed.indexOf('#');
            if (hash >= 0) {
                comment = trimmed.substring(hash + 1).trim();
                trimmed = trimmed.substring(0, hash).trim();
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2 && isValidIp(parts[0])) {
                for (int k = 1; k < parts.length; k++) {
                    list.add(new Entry(parts[0], parts[k], comment, i));
                }
            }
        }
        return list;
    }

    /**
     * 在这一行的字段里找目标域名（从下标 1 起，下标 0 是 IP）。
     * 找到返回下标，没找到返回 -1。域名不分大小写。
     */
    private static int indexOfDomain(String[] parts, String domain) {
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].equalsIgnoreCase(domain)) return i;
        }
        return -1;
    }

    /**
     * 把一条有效行按「IP 域名…（+ 行尾注释）」重新拼出来，跳过要摘掉的域名。
     * 摘完一个域名都不剩时返回 null，让调用方把整行删掉（别留下孤零零一个 IP）。
     */
    private static String rebuildLine(String[] parts, String comment, String skip) {
        StringBuilder b = new StringBuilder(parts[0]);
        int remain = 0;
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].equalsIgnoreCase(skip)) continue;
            b.append(remain == 0 ? "  " : " ");
            b.append(parts[i]);
            remain++;
        }
        if (remain == 0) return null;
        if (comment != null) b.append("  ").append(comment);
        return b.toString();
    }


    /** 校验单条记录文本行：ip + 至少一个域名 */
    public static String validateLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null; // 注释/空行放行
        String body = trimmed;
        int hash = body.indexOf('#');
        if (hash >= 0) body = body.substring(0, hash).trim();
        String[] parts = body.split("\\s+");
        if (parts.length < 2) return "行格式应为「IP 域名」：" + line;
        if (!isValidIp(parts[0])) return "IP 无效：" + parts[0];
        for (int i = 1; i < parts.length; i++) {
            if (!isValidDomain(parts[i])) return "域名无效：" + parts[i];
        }
        return null;
    }

    /**
     * 域名格式校验（宽松）：只放行字母、数字和 . _ -。
     * 电视端添加条目、CF 写入、手机端 /api/set 共用这一个口径，别两边宽严不一——
     * 以前电视端根本不校验，中文、空格、"a..b" 都能写进 hosts。
     */
    public static boolean isValidDomain(String s) {
        return s != null && s.matches("(?i)[a-z0-9._\\-]+");
    }

    public static boolean isValidIp(String s) {
        // IPv4
        if (s.matches("(\\d{1,3}\\.){3}\\d{1,3}")) {
            String[] p = s.split("\\.");
            for (String q : p) {
                int v = Integer.parseInt(q);
                if (v > 255) return false;
            }
            return true;
        }
        // IPv6（宽松校验）
        return s.matches("(?i)[0-9a-f:]{2,45}") && s.contains(":");
    }

    /**
     * 用全文覆盖写入 hosts（需 root）。会先自动备份。
     * @return 成功返回 null，失败返回错误信息
     */
    public static String writeRaw(String newContent) {
        // 1. 先写入 App 私有目录
        try {
            File tmp = new File(AppContextHolder.get().getFilesDir(), TMP_NAME);
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(newContent.getBytes("UTF-8"));
            fos.close();

            // 2. 备份 + remount + 覆盖 + 校验
            String script =
                "mkdir -p " + BACKUP_DIR + "\n" +
                "cp -f " + HOSTS_PATH + " " + BACKUP_DIR + "/hosts.bak.$(date +%Y%m%d_%H%M%S)\n" +
                "mount -o rw,remount /system 2>/dev/null\n" +
                "mount -o rw,remount / 2>/dev/null\n" +
                "cp -f " + tmp.getAbsolutePath() + " " + HOSTS_PATH + "\n" +
                "rc=$?\n" +
                "chmod 644 " + HOSTS_PATH + " 2>/dev/null\n" +
                "echo WRITE_RC=$rc\n";
            RootShell.Result r = RootShell.run(script);
            if (r.output.contains("WRITE_RC=0")) {
                // 按用户设置的保留数量清理旧备份（不阻塞主流程）
                cleanupBackups();
                // 回读校验
                try {
                    String back = readRaw();
                    if (back.replaceAll("\\s+", "").equals(newContent.replaceAll("\\s+", ""))) {
                        return null;
                    }
                    return "写入后回读内容不一致";
                } catch (Exception e) {
                    return null; // 回读失败但 cp 成功，视为成功
                }
            }
            return "root 写入失败：" + r.output;
        } catch (Exception e) {
            return "写入异常：" + e;
        }
    }

    /**
     * 新增 / 替换某域名的解析条目（同一域名全文只保留一条）。
     *
     * 关键：hosts 一行可以挂多个域名。目标域名如果和别的域名共用一行，
     * 只把它摘出来单独成行——以前是整行覆盖成「IP 域名」，
     * 于是同行的兄弟域名被静默删掉，或者反过来产生两条重复解析。
     *
     * @return null=成功
     */
    public static String upsert(String domain, String ip) {
        try {
            return writeRaw(upsertText(readRaw(), domain, ip));
        } catch (Exception e) {
            return e.toString();
        }
    }

    /**
     * 纯文本变换：把 raw 里 domain 的解析改成 ip（原来没有就追加到末尾）。
     *
     * 从 IO 里抽出来是为了能单测——以前测试是把这段逻辑复制一份去测，
     * 结果"复制品通过、真代码却是坏的"（replaced 漏赋值那次就是这样蒙混过关的）。
     * 这里不碰任何文件/IO，输入输出都是字符串。
     */
    static String upsertText(String raw, String domain, String ip) {
        StringBuilder sb = new StringBuilder();
        boolean replaced = false;    // 已在独占行上改好，末尾不用再补
        boolean movedOut = false;    // 目标域名是从共享行摘出来的，末尾必须补一条
        String[] segs = raw.split("\n", -1);
        for (int i = 0; i < segs.length; i++) {
            String line = segs[i];
            boolean last = i == segs.length - 1;
            String t = line.trim();
            // 原文以 \n 结尾时 split 会多出一个空串，别再给它补一个 \n——
            // 否则每写一次文件末尾就多一个空行，改十次就多出十行
            if (t.isEmpty() || t.startsWith("#")) {
                sb.append(line);
                if (!last) sb.append('\n');
                continue;
            }

            String body = t, comment = null;
            int hash = body.indexOf('#');
            if (hash >= 0) {
                comment = body.substring(hash);
                body = body.substring(0, hash).trim();
            }
            String[] parts = body.split("\\s+");
            if (parts.length < 2 || !isValidIp(parts[0])
                    || indexOfDomain(parts, domain) < 0) {
                sb.append(line).append('\n');
                continue;
            }
            if (replaced) continue;   // 同域名的重复行整行丢弃，全文只留一条
            if (parts.length == 2) {
                // 独占一行：原地换 IP，位置和注释都不动（已经是目标值就一字不改）
                if (parts[0].equals(ip)) {
                    sb.append(line).append('\n');
                } else {
                    sb.append(ip).append("  ").append(domain)
                            .append(comment == null ? "" : "  " + comment).append('\n');
                }
                // 这一行已经处理掉了，末尾绝不能再补一条——漏了这句就会每次改 IP 都多一行
                replaced = true;
            } else {
                // 还挂着别的域名：只摘掉这一个，兄弟域名原样留下
                String rebuilt = rebuildLine(parts, comment, domain);
                if (rebuilt != null) sb.append(rebuilt).append('\n');
                movedOut = true;
            }
        }
        if (!replaced || movedOut) {
            sb.append(ip).append("  ").append(domain).append('\n');
        }
        return sb.toString();
    }

    /**
     * 删除指定域名条目。
     *
     * 与 upsert 同理：目标域名和别的域名共用一行时，只摘它一个，同行兄弟保留。
     * 没找到该域名时返回错误——以前无条件写回原文再报"已删除"，
     * 删了个不存在的东西也弹成功，用户以为删掉了。
     *
     * @return 错误信息或 null（成功）
     */
    public static String remove(String domain) {
        try {
            String out = removeText(readRaw(), domain);
            if (out == null) return "没找到该条目：" + domain;
            return writeRaw(out);
        } catch (Exception e) {
            return e.toString();
        }
    }

    /**
     * 纯文本变换：删掉 domain（同一行上的兄弟域名保留，独占行则整行删掉）。
     * 同样不碰 IO，可单测。
     *
     * @return 新文本；没找到该域名返回 null
     */
    static String removeText(String raw, String domain) {
        StringBuilder sb = new StringBuilder();
        int removed = 0;
        String[] segs = raw.split("\n", -1);
        for (int i = 0; i < segs.length; i++) {
            String line = segs[i];
            boolean last = i == segs.length - 1;
            String t = line.trim();
            // 同上：末尾那个 split 出来的空串不补 \n，删一次别多一个空行
            if (t.isEmpty() || t.startsWith("#")) {
                sb.append(line);
                if (!last) sb.append('\n');
                continue;
            }

            String body = t, comment = null;
            int hash = body.indexOf('#');
            if (hash >= 0) {
                comment = body.substring(hash);
                body = body.substring(0, hash).trim();
            }
            String[] parts = body.split("\\s+");
            if (parts.length < 2 || !isValidIp(parts[0])
                    || indexOfDomain(parts, domain) < 0) {
                sb.append(line).append('\n');
                continue;
            }
            removed++;
            // 同行还有别的域名就拼回去；只剩它一个（或摘完空了）就整行删掉
            String rebuilt = rebuildLine(parts, comment, domain);
            if (rebuilt != null) sb.append(rebuilt).append('\n');
        }
        if (removed == 0) return null;
        return sb.toString();
    }

    /** 列出 /sdcard/hostbox 下的备份文件名 */
    public static List<String> listBackups() {
        RootShell.Result r = RootShell.run("ls -1 " + BACKUP_DIR + " 2>/dev/null");
        List<String> out = new ArrayList<String>();
        for (String line : r.output.split("\n")) {
            String n = line.trim();
            if (isSafeBackupName(n)) out.add(n);
        }
        return out;
    }

    /**
     * 备份文件名必须是「hosts.bak.」+ 纯字母数字下划线点连字符。
     *
     * /sdcard 是全局可写的，别的 App 放一个叫 `hosts.bak.$(reboot)` 的文件，
     * 用户一点「恢复」，整条 root 命令就替它执行了——只判前缀是不够的。
     */
    private static boolean isSafeBackupName(String name) {
        if (name == null || name.isEmpty()) return false;
        if (!name.startsWith("hosts.bak.")) return false;
        if (name.contains("..")) return false;   // 顺手挡掉路径穿越
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * 按用户设置（PREF_MAX_BACKUPS，0=不限制）清理多余备份，保留最新的 N 份。
     * 内部方法，writeRaw 成功后调用；失败静默（不影响写入结果）。
     */
    private static void cleanupBackups() {
        try {
            int keep = AppContextHolder.get()
                    .getSharedPreferences("hostbox", Context.MODE_PRIVATE)
                    .getInt(PREF_MAX_BACKUPS, DEFAULT_MAX_BACKUPS);
            if (keep <= 0) return; // 不限制
            // ls -t 按修改时间新→旧排列，跳过前 keep 个，其余删除
            String script =
                "cd " + BACKUP_DIR + " 2>/dev/null\n" +
                "ls -t | grep '^hosts\\.bak\\.' | tail -n +" + (keep + 1) +
                " | while read f; do rm -f \"$f\"; done\n" +
                "echo CLEAN_RC=$?\n";
            RootShell.run(script);
        } catch (Exception ignored) {}
    }

    /** 恢复指定备份文件（恢复前先把当前 hosts 也备份一份，防误恢复无法回退） */
    public static String restoreBackup(String name) {
        if (!isSafeBackupName(name)) return "备份名不合法：" + name;
        String script =
            "mkdir -p " + BACKUP_DIR + "\n" +
            "cp -f " + HOSTS_PATH + " " + BACKUP_DIR + "/hosts.bak.$(date +%Y%m%d_%H%M%S)_before_restore\n" +
            "mount -o rw,remount /system 2>/dev/null\n" +
            "mount -o rw,remount / 2>/dev/null\n" +
            "cp -f \"" + BACKUP_DIR + "/" + name + "\" " + HOSTS_PATH + "\n" +
            "chmod 644 " + HOSTS_PATH + "\n" +
            "echo RC=$?\n";
        RootShell.Result r = RootShell.run(script);
        if (r.output.contains("RC=0")) {
            cleanupBackups();
            return null;
        }
        return "恢复失败：" + r.output;
    }

    public static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }
}
