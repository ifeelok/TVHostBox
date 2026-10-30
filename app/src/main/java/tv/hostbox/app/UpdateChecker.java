package tv.hostbox.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 升级检查：直接读 GitHub Releases 的最新版本，零第三方依赖。
 *
 * 为什么不用库：这个项目刻意保持零依赖（QR 码、root、配置服务都是自己写的），
 * 升级检查只需要一个 GET + 一段 JSON 解析，用系统自带的 HttpURLConnection + org.json 就够。
 *
 * 注意：GitHub 的 release 资产下载会 302 到 objects.githubusercontent.com，
 * HttpURLConnection 默认会跟随同协议重定向（https→https），这里再显式打开一次更稳。
 */
final class UpdateChecker {

    private static final String API =
            "https://api.github.com/repos/ifeelok/TVHostBox/releases/latest";
    private static final String UA = "TVHostBox-AndroidTV";

    private UpdateChecker() {}

    /** 最新版本信息 */
    static class Info {
        String tag = "";        // v1.4.0
        String name = "";       // 发布标题
        String body = "";       // 发布说明（可能为空）
        String apkUrl = "";     // APK 直链
        String htmlUrl = "";    // 发布页（浏览器兜底）
        long size = 0;          // APK 字节数
    }

    interface DownloadProgress {
        void onProgress(long done, long total);
    }

    /** 拉取最新 release；抛异常表示网络/解析失败 */
    static Info fetchLatest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("GitHub API 返回 HTTP " + code);

            String text = readAll(c.getInputStream());
            JSONObject json = new JSONObject(text);

            Info info = new Info();
            info.tag = json.optString("tag_name", "").trim();
            info.name = json.optString("name", "");
            info.body = json.optString("body", "");
            info.htmlUrl = json.optString("html_url", "");
            if (info.tag.isEmpty()) throw new IOException("没有读到版本号");

            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String n = a.optString("name", "").toLowerCase();
                    if (!n.endsWith(".apk")) continue;
                    info.apkUrl = a.optString("browser_download_url", "");
                    info.size = a.optLong("size", 0);
                    break;
                }
            }
            return info;
        } finally {
            c.disconnect();
        }
    }

    /** 下载 APK 到指定文件（覆盖），带进度回调 */
    static void download(String url, File dest, DownloadProgress cb) throws Exception {
        if (url == null || url.isEmpty()) throw new IOException("没有 APK 下载地址");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("下载失败 HTTP " + code);

            long total = c.getContentLength();
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[16384];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (cb != null) cb.onProgress(done, total);
                }
                out.flush();
                if (done <= 0) throw new IOException("下载内容为空");
            } finally {
                try { out.close(); } catch (Exception ignored) {}
                try { in.close(); } catch (Exception ignored) {}
            }
        } finally {
            c.disconnect();
        }
    }

    /** remote 是否比 current 新（按数字段比较，"1.10.0" > "1.9.9"） */
    static boolean isNewer(String remote, String current) {
        int[] r = parse(remote), c = parse(current);
        for (int i = 0; i < 3; i++) {
            if (r[i] != c[i]) return r[i] > c[i];
        }
        return false;
    }

    /** "v1.4.0" / "1.4.0-dev.abc" → [1,4,0]；解析不出的段按 0 */
    private static int[] parse(String v) {
        int[] out = new int[3];
        if (v == null) return out;
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        String[] seg = s.split("\\.");
        for (int i = 0; i < 3 && i < seg.length; i++) {
            StringBuilder digits = new StringBuilder();
            for (int j = 0; j < seg[i].length(); j++) {
                char ch = seg[i].charAt(j);
                if (ch >= '0' && ch <= '9') digits.append(ch);
                else break;
            }
            try { out[i] = digits.length() == 0 ? 0 : Integer.parseInt(digits.toString()); }
            catch (Exception ignored) { out[i] = 0; }
        }
        return out;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
        return new String(bos.toByteArray(), "UTF-8");
    }
}
