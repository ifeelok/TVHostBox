package tv.hostbox.app;

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

    /** 解析出有效条目（IP 域名），跳过注释与空行 */
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
                list.add(new Entry(parts[0], parts[1], comment, i));
            }
        }
        return list;
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
            if (!parts[i].matches("(?i)[a-z0-9._\\-]+")) {
                return "域名无效：" + parts[i];
            }
        }
        return null;
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
     * 新增 / 替换某域名的解析条目（同一域名只保留一条）。
     * @return null=成功
     */
    public static String upsert(String domain, String ip) {
        try {
            String raw = readRaw();
            StringBuilder sb = new StringBuilder();
            boolean replaced = false;
            String[] lines = raw.split("\n", -1);
            for (String line : lines) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    String body = t;
                    int hash = body.indexOf('#');
                    if (hash >= 0) body = body.substring(0, hash).trim();
                    String[] parts = body.split("\\s+");
                    if (parts.length >= 2 && parts[1].equalsIgnoreCase(domain)) {
                        if (!replaced) {
                            sb.append(ip).append("  ").append(domain).append('\n');
                            replaced = true;
                        }
                        continue; // 丢弃其余同域名的行
                    }
                }
                sb.append(line).append('\n');
            }
            if (!replaced) {
                sb.append(ip).append("  ").append(domain).append('\n');
            }
            return writeRaw(sb.toString());
        } catch (Exception e) {
            return e.toString();
        }
    }

    /** 删除指定域名条目，返回错误信息或 null */
    public static String remove(String domain) {
        try {
            String raw = readRaw();
            StringBuilder sb = new StringBuilder();
            String[] lines = raw.split("\n", -1);
            for (String line : lines) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    String body = t;
                    int hash = body.indexOf('#');
                    if (hash >= 0) body = body.substring(0, hash).trim();
                    String[] parts = body.split("\\s+");
                    if (parts.length >= 2 && parts[1].equalsIgnoreCase(domain)) {
                        continue;
                    }
                }
                sb.append(line).append('\n');
            }
            return writeRaw(sb.toString());
        } catch (Exception e) {
            return e.toString();
        }
    }

    /** 列出 /sdcard/hostbox 下的备份文件名 */
    public static List<String> listBackups() {
        RootShell.Result r = RootShell.run("ls -1 " + BACKUP_DIR + " 2>/dev/null");
        List<String> out = new ArrayList<String>();
        for (String line : r.output.split("\n")) {
            if (line.trim().startsWith("hosts.bak.")) out.add(line.trim());
        }
        return out;
    }

    /** 恢复指定备份文件 */
    public static String restoreBackup(String name) {
        String script =
            "mount -o rw,remount /system 2>/dev/null\n" +
            "mount -o rw,remount / 2>/dev/null\n" +
            "cp -f " + BACKUP_DIR + "/" + name + " " + HOSTS_PATH + "\n" +
            "chmod 644 " + HOSTS_PATH + "\n" +
            "echo RC=$?\n";
        RootShell.Result r = RootShell.run(script);
        return r.output.contains("RC=0") ? null : "恢复失败：" + r.output;
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
