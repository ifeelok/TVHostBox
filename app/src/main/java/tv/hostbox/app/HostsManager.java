package tv.hostbox.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** 备份目录（包内可见：测试要把脚本里的这个路径换成临时目录才能真跑一遍） */
    static final String BACKUP_DIR = "/sdcard/hostbox";
    /** 备份保留数量的 SharedPreferences key；0 = 不限制 */
    public static final String PREF_MAX_BACKUPS = "maxBackups";
    public static final int DEFAULT_MAX_BACKUPS = 20;
    /** 备份序号计数：单调递增，删掉旧的也不回收（v3 删了，下一个还是 v8） */
    private static final String PREF_BACKUP_SEQ = "backupSeq";
    /** 用户给某份备份起的名字（key = 本前缀 + 文件名），只改显示，不动文件 */
    private static final String PREF_BACKUP_LABEL = "blabel.";
    /** 用户名字最多几个字：电视上一行要放得下 */
    private static final int LABEL_MAX = 24;
    /** 恢复前的自动备份，名字上挂的标记 */
    private static final String BEFORE_RESTORE = ".before_restore";
    /** 老版本用的是下划线：hosts.bak.20261101_175409_before_restore，盒子上现在还躺着 */
    private static final String BEFORE_RESTORE_OLD = "_before_restore";
    private static final Object SEQ_LOCK = new Object();
    /**
     * 读-改-写的全局锁。
     *
     * upsert 是「读全文 → 改一行 → 写回全文」，中间夹着两次 root 调用。
     * RootShell 自带的 LOCK 只保证单条命令不并发，管不到这整个过程：
     * 电视上点「写入」的同时手机也在保存，两边各读一份旧全文，
     * 后写的那个会把先写的整条覆盖掉——用户看到的是「我明明加了，怎么没了」。
     * 所以整段读改写必须串行；writeRaw 自己也会拿这把锁（synchronized 可重入）。
     */
    private static final Object RW_LOCK = new Object();
    /** 拿不到 prefs 时的序号兜底（保证同一秒也不会生成两个同名文件） */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ_FALLBACK =
            new java.util.concurrent.atomic.AtomicInteger();

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

    // ==================== 备份 ====================

    /**
     * 一份备份。
     *
     * 名字分两套：
     *  - 文件名 hosts.bak.v7.20261101_173221：只含 ASCII 安全字符，因为要进 root 命令；
     *  - 显示名（label）：用户随便起，中文也行，存在 prefs 里，绝不进命令。
     * 之前只有时间戳，恢复前自动备份一堆下来全是 17:49 / 17:49 / 17:54，
     * 谁是谁根本分不出，所以加了 v 序号和条数。
     */
    public static class Backup {
        public final String name;      // 文件名
        public final int entries;      // 里面有几条；-1 = 没读到
        public final int seq;          // v 序号；0 = 老备份（没有序号）
        public final long timeMs;      // 备份时间；0 = 名字里认不出来
        public final boolean beforeRestore;
        public String label;           // 用户起的名，null = 没起

        Backup(String name, int entries) {
            this.name = name;
            this.entries = entries;
            this.seq = parseSeq(name);
            this.timeMs = parseTimeMs(name);
            // 老备份用的是下划线那一版，只认 dot 会让老文件全变成普通备份
            this.beforeRestore = name.endsWith(BEFORE_RESTORE)
                    || name.endsWith(BEFORE_RESTORE_OLD);
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
        synchronized (RW_LOCK) {
            // 1. 先写入 App 私有目录
            try {
                File tmp = new File(AppContextHolder.get().getFilesDir(), TMP_NAME);
                FileOutputStream fos = new FileOutputStream(tmp);
                fos.write(newContent.getBytes("UTF-8"));
                fos.close();

                // 2. 备份 + remount + 覆盖 + 校验
                String bak = newBackupName(System.currentTimeMillis(), false);
                RootShell.Result r = RootShell.run(
                        writeScript(tmp.getAbsolutePath(), HOSTS_PATH, bak));
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
    }

    /**
     * 覆盖 hosts 的那段 shell。抽成纯字符串是为了测试能把原文交给真 shell 跑一遍。
     *
     * 不用 `cp 新文件 hosts` 直接覆盖：覆盖到一半掉电/进程被杀，得到的是半截 hosts。
     * 改成先落到同目录的 hosts.new，再 mv 过去——同一分区上的 mv 是原子的，
     * 要么还是旧文件、要么整份新文件，不存在中间态。
     *
     * 但有的机器 /system/etc/hosts 是软链接（指向 /etc/hosts 之类），
     * mv 上去会把链接换成一个普通文件，那是改硬件行为，所以先判一下：
     * 是链接就老老实实 cp 穿透过去（保持原来的语义），只在普通文件上走原子替换。
     *
     * 用 if/[ 而不是 `cp || exit`：exit 会把常驻的 su 会话一起带走。
     */
    static String writeScript(String tmpPath, String hostsPath, String backupName) {
        return "mkdir -p " + BACKUP_DIR + "\n"
             + "cp -f " + hostsPath + " \"" + BACKUP_DIR + "/" + backupName + "\"\n"
             + "mount -o rw,remount /system 2>/dev/null\n"
             + "mount -o rw,remount / 2>/dev/null\n"
             + "rc=1\n"
             + "if [ -L " + hostsPath + " ]; then\n"
             + "  cp -f " + tmpPath + " " + hostsPath + "\n"   // 软链接：穿透写，保持链接
             + "  rc=$?\n"
             + "else\n"
             + "  cp -f " + tmpPath + " " + hostsPath + ".new\n"
             + "  if [ $? -eq 0 ]; then mv -f " + hostsPath + ".new " + hostsPath + "; rc=$?; fi\n"
             + "fi\n"
             + "chmod 644 " + hostsPath + " 2>/dev/null\n"
             + "echo WRITE_RC=$rc\n";
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
        return upsert(domain, ip, null);
    }

    /**
     * 同上，但顺手把这行原来的行尾注释换成 note（null = 原来的注释不动）。
     *
     * 优选写进来的注释是要能看懂的：「这串数字是哪台机器、多少延迟」，
     * 光靠 IP 谁也记不住。手动改 IP 时不传 note，用户自己的备注就不会被抹掉。
     *
     * @return null=成功
     */
    public static String upsert(String domain, String ip, String note) {
        synchronized (RW_LOCK) {      // 读改写整个过程串行，别让手机和电视各写各的
            try {
                return writeRaw(upsertText(readRaw(), domain, ip, note));
            } catch (Exception e) {
                return e.toString();
            }
        }
    }

    /**
     * 一次把多个域名指到同一个 IP（读改写只做一遍，只产生一份备份）。
     *
     * 为什么不是循环调 upsert：那样 raw 和 gist 各写一次，就多出两份备份，
     * 备份列表里一夜之间多出几十份「v37 · 3 条」谁也看不懂。
     */
    public static String upsertMany(String[] domains, String ip, String note) {
        if (domains == null || domains.length == 0) return "没有要写入的域名";
        synchronized (RW_LOCK) {
            try {
                String raw = readRaw();
                for (int i = 0; i < domains.length; i++) {
                    raw = upsertText(raw, domains[i], ip, note);
                }
                return writeRaw(raw);
            } catch (Exception e) {
                return e.toString();
            }
        }
    }

    /** 一次删掉多个域名（同样只写一次、只备份一份） */
    public static String removeMany(String[] domains) {
        if (domains == null || domains.length == 0) return "没有要删除的域名";
        synchronized (RW_LOCK) {
            try {
                String raw = readRaw();
                boolean any = false;
                for (int i = 0; i < domains.length; i++) {
                    String next = removeText(raw, domains[i]);
                    if (next != null) { raw = next; any = true; }
                }
                if (!any) return "没有找到要删除的条目";
                return writeRaw(raw);
            } catch (Exception e) {
                return e.toString();
            }
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
        return upsertText(raw, domain, ip, null);
    }

    /**
     * 同上，多一个 note：
     *  - note == null  → 保留原注释（手动改 IP 走这条，别把用户的备注吃掉）
     *  - note != null  → 把这行的行尾注释换成 "# note"（重新优选时连延迟一起更新）
     * 位置不同、IP 不同、注释不同，三样都没变就一个字节都不动。
     */
    static String upsertText(String raw, String domain, String ip, String note) {
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
                // 独占一行：原地换 IP（和注释），位置不动
                String oldTail = comment == null ? "" : comment;
                String newTail = note == null ? oldTail : "# " + note;
                if (parts[0].equals(ip) && newTail.equals(oldTail)) {
                    sb.append(line).append('\n');        // 已经是目标值，一字不改
                } else {
                    sb.append(ip).append("  ").append(domain)
                            .append(newTail.isEmpty() ? "" : "  " + newTail).append('\n');
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
            sb.append(ip).append("  ").append(domain)
                    .append(note == null || note.isEmpty() ? "" : "  # " + note).append('\n');
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
        synchronized (RW_LOCK) {      // 同 upsert：读改写整段串行
            try {
                String out = removeText(readRaw(), domain);
                if (out == null) return "没找到该条目：" + domain;
                return writeRaw(out);
            } catch (Exception e) {
                return e.toString();
            }
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

    /**
     * 列出备份（新→旧），顺带读出每份里有几条。
     *
     * 一条命令取回全部：`ls -1t` 让 shell 那边先按时间排好，回来再用名字里的时间戳复核——
     * 文件被拷来拷去会改 mtime，光靠 ls -t 会把老备份排到前面去。
     * 条数用 grep -cvE 数「非注释非空行」。
     * 注意 toybox 的 grep 在 -v + 多个 -e 时只对第一个模式取反：`-cve '^#' -e '^$'`
     * 会把空行也算进去（实测 10 行的文件数出 10），必须把两个条件合成一个 ERE 模式。
     */
    public static List<Backup> listBackupsDetail() {
        List<Backup> out = parseBackupList(RootShell.run(backupListScript()).output);
        // 用户起的显示名（拿不到 prefs 就只是没名字，不影响列表）
        try {
            SharedPreferences sp = prefs();
            for (int i = 0; i < out.size(); i++) {
                Backup b = out.get(i);
                String l = sp.getString(PREF_BACKUP_LABEL + b.name, null);
                if (l != null && !l.isEmpty()) b.label = l;
            }
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * 列备份的那段 shell：每行一条 `BK|文件名|条数`。
     * 单独抽出来是为了能在电脑上把这段原文交给真 shell 跑一遍（测试里就是这么干的）。
     *
     * 条数那半截必须**写在外面**：
     * `echo "…|$(grep -c … \"$f\")"` 里 `\` 会原样带进内层命令，grep 收到的文件名
     * 就变成带引号的 `"hosts.bak.xxx"` → No such file → 输出为空 → 条数一路掉成 -1。
     * （bash、mksh、盒子上那个 sh 全一样，这是 POSIX 的转义规则，不是哪个 shell 的怪癖。）
     * 把 `$(...)` 挪到引号外面，里头的 `"$f"` 就是最普通的一层引号，再也咬不到人。
     */
    static String backupListScript() {
        return "cd " + BACKUP_DIR + " 2>/dev/null\n"
             + "ls -1t | grep '^hosts\\.bak\\.' | while read f; do\n"
             + "  echo \"BK|$f|\"$(grep -cvE '^(#|$)' \"$f\")\n"
             + "done\n";
    }

    /**
     * 把 shell 输出变成备份列表（含排序）。
     * 输出里可能夹着别的脏东西，所以只收 BK| 开头的行，条数读不出来就记 -1。
     */
    static List<Backup> parseBackupList(String output) {
        List<Backup> out = new ArrayList<Backup>();
        if (output == null || output.isEmpty()) return out;
        for (String line : output.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("BK|")) continue;
            String[] p = t.split("\\|");
            if (p.length < 2) continue;
            String name = p[1];
            if (!isSafeBackupName(name)) continue;
            int n = -1;
            if (p.length >= 3) {
                try { n = Integer.parseInt(p[2].trim()); } catch (Exception ignored) {}
            }
            out.add(new Backup(name, n));
        }
        sortBackups(out);
        return out;
    }

    /**
     * 排序（新→旧）：先按名字里的时间戳，认不出时间的排最后，再按 v 序号兜底。
     *
     * 不能按文件名排——v10 会排到 v9 前面（字符串比较）；
     * 也不能只按 ls -t——mtime 会被文件拷贝改写。
     */
    static void sortBackups(List<Backup> list) {
        Collections.sort(list, new Comparator<Backup>() {
            @Override public int compare(Backup a, Backup b) {
                if (a.timeMs != b.timeMs) return a.timeMs > b.timeMs ? -1 : 1;
                if (a.seq != b.seq) return b.seq - a.seq;
                return b.name.compareTo(a.name);
            }
        });
    }

    /** 从名字里取 v 序号：hosts.bak.v7.20261101_173221 → 7；老备份没有 → 0 */
    static int parseSeq(String name) {
        if (name == null) return 0;
        Matcher m = Pattern.compile("\\.v(\\d+)\\.").matcher(name);
        if (!m.find()) return 0;
        try { return Integer.parseInt(m.group(1)); } catch (Exception e) { return 0; }
    }

    /** 从名字里取时间（毫秒）：…20261101_173221… → 毫秒；认不出 → 0 */
    static long parseTimeMs(String name) {
        if (name == null) return 0;
        Matcher m = Pattern.compile("(\\d{8})_(\\d{6})").matcher(name);
        if (!m.find()) return 0;
        try {
            return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(m.group(0)).getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    /**
     * 生成一个新备份名：hosts.bak.v7.20261101_173221
     * （恢复前自动备份再挂 .before_restore）。
     *
     * 序号单调递增且不回收：删掉 v3 之后下一个仍是 v8，不会把别的备份挤成 v3——
     * 那样用户会说"我明明记的是 v3，怎么变成另一个了"。
     * 时间戳在 Java 里生成、名字直接拼进命令，shell 里从此不再有 $(date) 这类命令替换。
     */
    static String newBackupName(long nowMs, boolean beforeRestore) {
        int seq;
        synchronized (SEQ_LOCK) {
            try {
                SharedPreferences sp = prefs();
                seq = sp.getInt(PREF_BACKUP_SEQ, 0) + 1;
                sp.edit().putInt(PREF_BACKUP_SEQ, seq).apply();
            } catch (Exception e) {
                // 拿不到 prefs 时用进程内计数器兜底：宁可序号和上次对不上，
                // 也不能同秒生成两个同名文件——那会直接把上一份备份覆盖掉
                seq = SEQ_FALLBACK.incrementAndGet();
            }
        }
        return "hosts.bak." + (seq > 0 ? "v" + seq + "." : "")
                + stamp(nowMs) + (beforeRestore ? BEFORE_RESTORE : "");
    }

    private static String stamp(long nowMs) {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(nowMs));
    }

    /**
     * 列表里显示的那一整行：  v7 · 改 emby 前 · 11-01 17:32 · 3 条（恢复前自动备份）
     *
     * 序号让用户能口述"恢复到 v5 那次"；条数让一堆恢复前备份彼此分得开
     * （同一个下午连着恢复 5 次，光看时间分不出，看 11 条 / 7 条 / 3 条 一眼就知道）。
     */
    public static String backupLabelText(Backup b) {
        StringBuilder sb = new StringBuilder();
        if (b.seq > 0) sb.append("v").append(b.seq);
        if (b.label != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(b.label);
        }
        if (b.timeMs > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(b.timeMs)));
        }
        if (b.entries >= 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(b.entries).append(" 条");
        }
        if (b.beforeRestore) sb.append("   （恢复前自动备份）");
        if (sb.length() == 0) sb.append(b.name);   // 什么都不认识就直接给文件名
        return sb.toString();
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
     * 删除一份备份。
     * 名字先过白名单再进命令——它要拼进 root 命令，用户起的显示名再花哨也不参与。
     */
    public static String deleteBackup(String name) {
        if (!isSafeBackupName(name)) return "备份名不合法：" + name;
        removeLabel(name);
        RootShell.Result r = RootShell.run("rm -f \"" + BACKUP_DIR + "/" + name + "\"\n");
        return r.ok ? null : "删除失败：" + r.output;
    }

    /** 清空全部备份（连用户起的名字一起清） */
    public static String clearBackups() {
        // 用通配符而不是逐个拼名字：文件名由 shell 展开，天然不掺任何外来输入。
        // 路径写全，不靠先 cd——cd 失败时 rm 会落到 su 的当前目录里（那可能是 /）
        RootShell.Result r = RootShell.run("rm -f " + BACKUP_DIR + "/hosts.bak.*\n");
        if (!r.ok) return "清空失败：" + r.output;
        clearAllLabels();
        return null;
    }

    /**
     * 给备份起个好认的名字：只改显示，文件名一个字都不动。
     *
     * 显示名允许中文、空格、emoji，因为它只进 prefs 和界面，永远不进 root 命令；
     * 文件名则永远是 ASCII 白名单内的字符。两者分开，用户起名才不必被安全规则卡住。
     */
    public static String renameBackup(String name, String rawLabel) {
        if (!isSafeBackupName(name)) return "备份名不合法：" + name;
        String label = cleanLabel(rawLabel);
        try {
            SharedPreferences.Editor ed = prefs().edit();
            if (label.isEmpty()) ed.remove(PREF_BACKUP_LABEL + name);
            else ed.putString(PREF_BACKUP_LABEL + name, label);
            ed.apply();
        } catch (Exception e) {
            return "改名失败：" + e;
        }
        return null;
    }

    /** 用户起的显示名净化：压成一行、去控制字符、限长（电视上一行要放得下） */
    static String cleanLabel(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') c = ' ';
            if (c < 0x20 || c == 0x7f) continue;
            sb.append(c);
            if (sb.length() >= LABEL_MAX * 2) break;   // 中文二字算一字，先多收点再裁
        }
        String t = sb.toString().trim().replaceAll(" {2,}", " ");
        // 按字符数截断（emoji  surrogate pair 也不会被切坏，因为按码点递推）
        int count = 0, end = 0;
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            if (count + 1 > LABEL_MAX) break;
            count++;
            end = i + Character.charCount(cp);
            i = end;
        }
        return t.substring(0, end).trim();
    }

    /**
     * 读一份备份的全文（纯只读，一个字都不写）。
     *
     * 走 root cat 而不是直接 File 读：/sdcard 上的文件 App 未必有权限读，
     * 而且备份目录里可能有别的 App 放的怪名文件，名字必须先进白名单再 cat。
     */
    public static String readBackup(String name) {
        if (!isSafeBackupName(name)) return null;
        RootShell.Result r = RootShell.run("cat \"" + BACKUP_DIR + "/" + name + "\" 2>/dev/null\n");
        if (!r.ok) return null;
        return r.output;
    }

    /**
     * 「恢复这份备份以后会变成什么样」。
     *
     * 只比解析出来的条目，不比行号和整行文本——行号会随增删整体平移（删一行，后面全错位），
     * 拿行号当身份会得出一片假差异。注释里的时间戳同理，比的是「同一个域名指向哪」。
     *
     * 方向按「恢复后」说，这才是用户按下恢复时关心的事：
     *    -  恢复后消失（现在有、这份没有）
     *    +  恢复后新增（这份有、现在没有）
     *    ~  恢复后变了（同一个域名：IP 变了，或只有备注变了）
     */
    public static String diffText(String current, String backup) {
        Map<String, Entry> cur = byDomain(current);
        Map<String, Entry> bak = byDomain(backup);
        List<String> lines = new ArrayList<String>();
        int total = 0;

        // 按当前文件的顺序列：先看「会丢什么」，用户最怕的是这个
        for (Map.Entry<String, Entry> e : cur.entrySet()) {
            Entry now = e.getValue();
            Entry then = bak.get(e.getKey());
            if (then == null) {
                lines.add("-  " + e.getKey() + " · " + now.ip);
            } else if (!now.ip.equals(then.ip)) {
                lines.add("~  " + e.getKey() + " · " + now.ip + " → " + then.ip);
            } else if (!sameText(now.comment, then.comment)) {
                lines.add("~  " + e.getKey() + " · " + now.ip + " · 备注会变");
            } else {
                continue;
            }
            total++;
        }
        for (Map.Entry<String, Entry> e : bak.entrySet()) {
            if (cur.containsKey(e.getKey())) continue;
            lines.add("+  " + e.getKey() + " · " + e.getValue().ip);
            total++;
        }

        if (total == 0) return "和当前完全一样，恢复它不会有任何变化。";
        StringBuilder sb = new StringBuilder();
        int show = Math.min(lines.size(), DIFF_MAX);
        for (int i = 0; i < show; i++) sb.append(lines.get(i)).append('\n');
        if (lines.size() > show) {
            sb.append("…还有 ").append(lines.size() - show).append(" 处变化没列出来\n");
        }
        return sb.toString();
    }

    /** 最多列多少行差异：再多就是上下翻屏，反正恢复是一整份覆盖 */
    private static final int DIFF_MAX = 40;

    /** 域名 → 条目（同域名出现多次只留第一条：全文本来就只认第一条解析） */
    private static Map<String, Entry> byDomain(String raw) {
        Map<String, Entry> map = new java.util.LinkedHashMap<String, Entry>();
        if (raw == null) return map;
        List<Entry> list = parse(raw);
        for (int i = 0; i < list.size(); i++) {
            Entry e = list.get(i);
            if (!map.containsKey(e.host)) map.put(e.host, e);
        }
        return map;
    }

    private static boolean sameText(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 取用户给这份备份起的名字（没起过则空串） */
    public static String backupLabelOf(String name) {
        try {
            String l = prefs().getString(PREF_BACKUP_LABEL + name, null);
            return l == null ? "" : l;
        } catch (Exception e) {
            return "";
        }
    }

    private static void removeLabel(String name) {
        try { prefs().edit().remove(PREF_BACKUP_LABEL + name).apply(); }
        catch (Exception ignored) {}
    }

    /** 清掉所有显示名，免得备份删光了 prefs 还留一堆孤儿 key */
    private static void clearAllLabels() {
        try {
            SharedPreferences sp = prefs();
            SharedPreferences.Editor ed = sp.edit();
            java.util.Map<String, ?> all = sp.getAll();
            for (String k : all.keySet()) {
                if (k.startsWith(PREF_BACKUP_LABEL)) ed.remove(k);
            }
            ed.apply();
        } catch (Exception ignored) {}
    }

    /**
     * 按用户设置（PREF_MAX_BACKUPS，0=不限制）清理多余备份，保留最新的 N 份。
     * 内部方法，writeRaw / restoreBackup 成功后调用；失败静默（不影响主流程）。
     */
    private static void cleanupBackups() {
        try {
            int keep = prefs().getInt(PREF_MAX_BACKUPS, DEFAULT_MAX_BACKUPS);
            if (keep <= 0) return; // 不限制
            List<Backup> all = listBackupsDetail();
            if (all.size() <= keep) return;
            // 按名字逐个删：名字都过过白名单，比在 shell 里拼 tail -n +N 更靠得住
            StringBuilder sb = new StringBuilder();
            for (int i = keep; i < all.size(); i++) {
                // 绝对路径，不靠 cd：cd 失败时 rm 会在错误目录里删同名文件
                sb.append("rm -f \"").append(BACKUP_DIR).append("/")
                        .append(all.get(i).name).append("\"\n");
            }
            RootShell.run(sb.toString());
            for (int i = keep; i < all.size(); i++) removeLabel(all.get(i).name);
        } catch (Exception ignored) {}
    }

    /** 恢复指定备份文件（恢复前先把当前 hosts 也备份一份，防误恢复无法回退） */
    public static String restoreBackup(String name) {
        if (!isSafeBackupName(name)) return "备份名不合法：" + name;
        synchronized (RW_LOCK) {
            String bak = newBackupName(System.currentTimeMillis(), true);
            RootShell.Result r = RootShell.run(restoreScript(
                    BACKUP_DIR + "/" + name, HOSTS_PATH, bak));
            if (r.output.contains("RC=0")) {
                cleanupBackups();
                return null;
            }
            return "恢复失败：" + r.output;
        }
    }

    /**
     * 恢复用的 shell：和 writeScript 一样走「先 .new 再 mv」的原子替换，
     * 恢复同样是一次整文件覆盖，中途断掉一样会得到半截 hosts。
     * 恢复前先把当前文件备份一份（.before_restore），这是恢复唯一的后悔药。
     */
    static String restoreScript(String backupPath, String hostsPath, String beforeName) {
        return "mkdir -p " + BACKUP_DIR + "\n"
             + "cp -f " + hostsPath + " \"" + BACKUP_DIR + "/" + beforeName + "\"\n"
             + "mount -o rw,remount /system 2>/dev/null\n"
             + "mount -o rw,remount / 2>/dev/null\n"
             + "rc=1\n"
             + "if [ -L " + hostsPath + " ]; then\n"
             + "  cp -f \"" + backupPath + "\" " + hostsPath + "\n"
             + "  rc=$?\n"
             + "else\n"
             + "  cp -f \"" + backupPath + "\" " + hostsPath + ".new\n"
             + "  if [ $? -eq 0 ]; then mv -f " + hostsPath + ".new " + hostsPath + "; rc=$?; fi\n"
             + "fi\n"
             + "chmod 644 " + hostsPath + " 2>/dev/null\n"
             + "echo RC=$rc\n";
    }

    private static SharedPreferences prefs() {
        return AppContextHolder.get().getSharedPreferences("hostbox", Context.MODE_PRIVATE);
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
