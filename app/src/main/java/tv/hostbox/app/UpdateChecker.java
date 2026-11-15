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
import java.util.ArrayList;
import java.util.List;

/**
 * 升级检查：读 GitHub Releases 的最新版本，零第三方依赖。
 *
 * 为什么不用库：这个项目刻意保持零依赖（QR 码、root、配置服务都是自己写的），
 * 升级检查只需要一个 GET + 一段 JSON 解析，用系统自带的 HttpURLConnection + org.json 就够。
 *
 * ==== 国内网络（重点）====
 * api.github.com 在不少地区要么连不上、要么被限流（共享出口 IP 一小时只有 60 次）。
 * 所以这里不是"能不能访问 GitHub"的二选一，而是一条**自动降级链**，逐级尝试、谁先通用谁：
 *
 *   1. api.github.com 直连                    —— 网络正常时最快，也最省事
 *   2. gh-proxy.com   / ghfast.top / ghproxy.net  —— 反向代理，转发 api.github.com 与 release 资产
 *   3. data.jsdelivr.com                      —— 只给"有哪些 tag"，拿不到发布说明和资产列表，
 *                                                但足以判断版本号；APK 地址按仓库固定命名规则拼出来
 *
 * ==== 下载 APK（2026-11-15 补）====
 * github.com/.../releases/download/... 这条直链在国内经常直接 502（当天实测），
 * 但同一时间 api.github.com 是通的。GitHub 还有一条不经过 release 域名的资产通道：
 *
 *   https://api.github.com/repos/{owner}/{repo}/releases/assets/{asset_id}
 *   Accept: application/octet-stream     ← 不带这个头只会回一段 JSON 元数据
 *
 * 它 302 到 CDN，绕过 502 的 release 域名。所以下载顺序是：
 *   **API 资产通道** → release 直链 → 镜像+直链 → 镜像+API 资产通道
 * 有 asset id 时把 API 通道放第一位：直链 502 前要先耗掉一次超时，用户得干等半分钟；
 * API 通道失败也很快返回，不耽误后续兜底。asset_id 从 release 的 JSON 里顺手读出；
 * 手填 release 直链时按 owner/repo/tag 反查，jsDelivr 兜底时按 tag 反查。
 *
 * 每个源都校验 HTTP 状态、ZIP 文件头（PK）和体积，避免把镜像的错误页当安装包装上。
 * 重定向改成手动跟随：自动跟随会在跳到 CDN 时丢掉自定义请求头，API 资产通道就废了。
 */
final class UpdateChecker {

    private static final String REPO = "ifeelok/TVHostBox";
    private static final String API_PATH = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String ASSETS_API = "https://api.github.com/repos/" + REPO + "/releases/assets/";
    private static final String TAG_API = "https://api.github.com/repos/" + REPO + "/releases/tags/";
    private static final String OCTET = "application/octet-stream";
    private static final String JSDELIVR_PATH = "https://data.jsdelivr.com/v1/packages/gh/" + REPO + "/resolved";
    /** 反向代理前缀，按实测速度排序（都支持转发 api.github.com 与 release 下载） */
    private static final String[] MIRRORS = {
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
    };
    private static final String UA = "TVHostBox-AndroidTV";

    private UpdateChecker() {}

    /** 最新版本信息 */
    static class Info {
        String tag = "";        // v1.4.0
        String name = "";       // 发布标题
        String body = "";       // 发布说明（可能为空）
        String apkUrl = "";     // APK 直链（github.com 原始地址，下载时再套镜像）
        String htmlUrl = "";    // 发布页（浏览器兜底）
        long size = 0;          // APK 字节数
        String source = "";     // 这条信息是从哪拿到的（给用户看）
        boolean noNotes = false; // true = 走了 jsDelivr 兜底，没有发布说明
        long assetId = 0;       // GitHub 资产 id，用于 API 资产下载通道
        String assetApiUrl = "";// assets/{id} 地址；空表示还没解析到
    }

    /** 一条下载路线：地址 + 展示名 + 需要带的 Accept（API 资产通道专用） */
    private static class Route {
        final String url, desc, accept;
        Route(String url, String desc, String accept) {
            this.url = url; this.desc = desc; this.accept = accept;
        }
    }

    interface DownloadProgress {
        void onProgress(long done, long total);
    }

    /** 换源通知：下载/检查切到下一个源时告诉界面一声，别让用户对着 0% 干等 */
    interface SourceListener {
        void onSource(String desc);
    }

    // ==================== 查版本 ====================

    /** 拉取最新 release；抛异常表示所有通道都失败 */
    static Info fetchLatest() throws Exception {
        List<String> routes = new ArrayList<String>();
        routes.add(API_PATH);
        for (String m : MIRRORS) routes.add(m + API_PATH);

        String firstErr = null;
        for (int i = 0; i < routes.size(); i++) {
            String url = routes.get(i);
            try {
                Info info = parseApi(url, ASSETS_API);
                info.source = i == 0 ? "GitHub 直连" : "镜像 " + hostOf(url);
                return info;
            } catch (Exception e) {
                if (firstErr == null) firstErr = shortMsg(e);
                // 403 = 通了但被 GitHub 限流（共享出口 IP 常见），换镜像也是同一批出口，
                // 继续试只是干等，直接跳到 jsDelivr 兜底更快
                String m = e.getMessage();
                if (m != null && m.contains("403")) break;
            }
        }

        // 兜底：jsDelivr 的包信息接口（国内一般可达），只能拿到版本号
        try {
            Info info = parseJsdelivr();
            info.source = "jsDelivr 兜底";
            return info;
        } catch (Exception e) {
            throw new IOException("直连和 " + MIRRORS.length + " 个镜像都不通（"
                    + (firstErr == null ? shortMsg(e) : firstErr) + "）");
        }
    }

    /**
     * 查任意 GitHub 仓库的最新 release，给「安装 MidnightBeam」这类一键入口用。
     * 返回的 Info 带 assetApiUrl，下载时直接走 API 资产通道，绕开 502 的 release 直链。
     */
    static Info releaseOf(String repo) throws Exception {
        String api = "https://api.github.com/repos/" + repo + "/releases/latest";
        String assets = "https://api.github.com/repos/" + repo + "/releases/assets/";
        List<String> routes = new ArrayList<String>();
        routes.add(api);
        for (String m : MIRRORS) routes.add(m + api);
        Exception last = null;
        for (String u : routes) {
            try {
                Info info = parseApi(u, assets);
                if (info.apkUrl.isEmpty()) continue;
                info.source = repo + " 官方 Release";
                return info;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IOException("查询 " + repo + " 最新版本失败"
                + (last == null ? "" : "（" + shortMsg(last) + "）"));
    }

    private static Info parseApi(String url, String assetsBase) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(7000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code == 403) throw new IOException("HTTP 403（可能被限流）");
            if (code != 200) throw new IOException("HTTP " + code);

            JSONObject json = new JSONObject(readAll(c.getInputStream()));
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
                    info.assetId = a.optLong("id", 0);
                    if (info.assetId > 0) info.assetApiUrl = assetsBase + info.assetId;
                    break;
                }
            }
            return info;
        } finally {
            c.disconnect();
        }
    }

    /**
     * jsDelivr 兜底：只解析到最新的 tag（"1.4.0"），
     * APK 地址按 CI 的固定命名规则拼：releases/download/v{ver}/TVHostBox-v{ver}.apk
     */
    private static Info parseJsdelivr() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(JSDELIVR_PATH).openConnection();
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(7000);
            c.setRequestProperty("User-Agent", UA);
            if (c.getResponseCode() != 200) throw new IOException("jsDelivr HTTP " + c.getResponseCode());
            JSONObject json = new JSONObject(readAll(c.getInputStream()));
            String ver = json.optString("version", "").trim();
            if (ver.isEmpty()) throw new IOException("jsDelivr 没有返回版本");
            Info info = new Info();
            info.tag = "v" + ver;
            info.name = info.tag;
            info.noNotes = true;
            info.apkUrl = "https://github.com/" + REPO + "/releases/download/" + info.tag
                    + "/TVHostBox-" + info.tag + ".apk";
            info.htmlUrl = "https://github.com/" + REPO + "/releases/tag/" + info.tag;
            return info;
        } finally {
            c.disconnect();
        }
    }

    // ==================== 下载安装包 ====================

    /** 只给一个地址时用（手机网页「下载安装」）：非 release 地址只走直连，不套镜像 */
    static void download(String url, File dest, long expectedSize,
                         DownloadProgress cb, SourceListener src) throws Exception {
        Info info = new Info();
        info.apkUrl = url;
        info.size = expectedSize;
        // 手填的 GitHub release 直链同样可能 502：按 owner/repo/tag 反查 asset id，
        // 让「任意 APK 直链」也能享受 API 资产通道（MidnightBeam 就是这么装上的）。
        if (url != null && url.contains("/releases/download/")) {
            info.assetApiUrl = assetApiFromReleaseUrl(url);
        }
        download(info, dest, cb, src);
    }

    /** 从 github.com/{owner}/{repo}/releases/download/{tag}/{file}.apk 反查 asset id */
    private static String assetApiFromReleaseUrl(String apkUrl) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("github\\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/")
                    .matcher(apkUrl);
            if (!m.find()) return "";
            String base = "https://api.github.com/repos/" + m.group(1) + "/"
                    + m.group(2) + "/releases/";
            String tagsApi = base + "tags/" + m.group(3);
            List<String> routes = new ArrayList<String>();
            routes.add(tagsApi);
            for (String mir : MIRRORS) routes.add(mir + tagsApi);
            for (String u : routes) {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(3000);
                    c.setReadTimeout(5000);
                    c.setRequestProperty("User-Agent", UA);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    if (c.getResponseCode() != 200) continue;
                    JSONObject json = new JSONObject(readAll(c.getInputStream()));
                    JSONArray assets = json.optJSONArray("assets");
                    if (assets == null) continue;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a == null) continue;
                        if (!a.optString("name", "").toLowerCase().endsWith(".apk")) continue;
                        long id = a.optLong("id", 0);
                        if (id > 0) return base + "assets/" + id;
                    }
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * 下载 APK：直链 → API 资产通道 → 镜像 → 镜像 API，谁先成功用谁。
     * 每换一条通道都会回调 src，界面据此显示「正在通过 xx 下载」。
     */
    static void download(Info info, File dest, DownloadProgress cb,
                         SourceListener src) throws Exception {
        if (info == null || info.apkUrl == null || info.apkUrl.isEmpty())
            throw new IOException("没有 APK 下载地址");

        boolean releaseStyle = info.apkUrl.contains("/releases/download/");
        String api = info.assetApiUrl;
        // jsDelivr 兜底时没拿到资产列表，按 tag 反查一次 id，让 API 通道仍有机会救场
        if ((api == null || api.isEmpty()) && releaseStyle
                && info.tag != null && !info.tag.isEmpty()) {
            api = resolveAssetApiByTag(info.tag);
        }

        List<Route> routes = new ArrayList<Route>();
        // 实测（2026-11-15）：release 直链在国内基本 502，而且要先耗掉一次连接+读超时
        // 才轮到下一条通道，用户得对着「正在下载」干等半分钟。既然能拿到 asset id，
        // 就把 API 资产通道放第一位；它失败时很快返回，再按直连/镜像依次兜底。
        if (api != null && !api.isEmpty()) {
            routes.add(new Route(api, "GitHub API 资产通道", OCTET));
        }
        routes.add(new Route(info.apkUrl,
                releaseStyle ? "GitHub 直连" : "直连 " + hostOf(info.apkUrl), null));
        if (releaseStyle) {
            for (String m : MIRRORS) {
                routes.add(new Route(m + info.apkUrl, "镜像 " + hostOf(m + info.apkUrl), null));
            }
            if (api != null && !api.isEmpty()) {
                for (String m : MIRRORS) {
                    routes.add(new Route(m + api,
                            "镜像 " + hostOf(m + api) + "（API 资产）", OCTET));
                }
            }
        }

        Exception last = null;
        for (int i = 0; i < routes.size(); i++) {
            Route r = routes.get(i);
            if (src != null) src.onSource(r.desc);
            try {
                downloadOnce(r.url, r.accept, dest, info.size, cb);
                return;
            } catch (Exception e) {
                last = e;
                // 删不掉也无所谓，下一次下载会用 FileOutputStream 覆盖
                if (dest.exists() && !dest.delete()) { /* ignore */ }
            }
        }
        throw new IOException("安装包下载失败（已试过 " + routes.size() + " 条通道）"
                + (last == null ? "" : "：" + shortMsg(last)));
    }

    /** jsDelivr 只给了 tag，去 release/tags/{tag} 把 asset id 补回来（短超时，失败就放弃） */
    private static String resolveAssetApiByTag(String tag) {
        List<String> routes = new ArrayList<String>();
        routes.add(TAG_API + tag);
        for (String m : MIRRORS) routes.add(m + TAG_API + tag);
        for (String url : routes) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(5000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) continue;
                JSONObject json = new JSONObject(readAll(c.getInputStream()));
                JSONArray assets = json.optJSONArray("assets");
                if (assets == null) continue;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    if (!a.optString("name", "").toLowerCase().endsWith(".apk")) continue;
                    long id = a.optLong("id", 0);
                    if (id > 0) return ASSETS_API + id;
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return "";
    }

    /**
     * 单次下载。重定向手动跟随：HttpURLConnection 自动跟随时，跳到另一个 CDN 主机
     * 会丢掉自定义请求头，API 资产通道少了 Accept 就只会回一段 JSON，等于白试。
     */
    private static void downloadOnce(String url, String accept, File dest,
                                     long expectedSize, DownloadProgress cb) throws Exception {
        String cur = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(5000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", UA);
                if (accept != null) c.setRequestProperty("Accept", accept);
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) throw new IOException("重定向没有 Location");
                    cur = new URL(new URL(cur), loc).toString();
                    continue;                       // finally 里断开，下一跳重开连接
                }
                if (code != 200) throw new IOException("HTTP " + code);

                long total = c.getContentLength();
                InputStream in = c.getInputStream();
                FileOutputStream out = new FileOutputStream(dest);
                try {
                    byte[] buf = new byte[16384];
                    long done = 0;
                    int n;
                    boolean headChecked = false;
                    while ((n = in.read(buf)) > 0) {
                        if (!headChecked) {
                            // 镜像出错时经常返回 200 + 一张 HTML 错误页，这里挡一道
                            if (n < 4 || buf[0] != 'P' || buf[1] != 'K') {
                                throw new IOException("返回的不是安装包");
                            }
                            headChecked = true;
                        }
                        out.write(buf, 0, n);
                        done += n;
                        if (cb != null) cb.onProgress(done, total);
                    }
                    out.flush();
                    // 111KB 的包至少要过 50KB 才像话，太小基本是错误页或被截断
                    if (done <= 50 * 1024) throw new IOException("安装包不完整（" + done + " 字节）");
                    // 已知体积时对一遍：镜像中断/限速截断都会被这里拦住，换下一个源重下
                    if (expectedSize > 0 && done != expectedSize) {
                        throw new IOException("体积不符（" + done + " / " + expectedSize + " 字节）");
                    }
                } finally {
                    try { out.close(); } catch (Exception ignored) {}
                    try { in.close(); } catch (Exception ignored) {}
                }
                return;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("重定向次数过多");
    }

    // ==================== 版本比较 ====================

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

    // ==================== 小工具 ====================

    /** "https://gh-proxy.com/https://api.github.com/..." → "gh-proxy.com" */
    private static String hostOf(String url) {
        try {
            String s = url.replaceFirst("^https?://", "");
            int slash = s.indexOf('/');
            return slash > 0 ? s.substring(0, slash) : s;
        } catch (Exception e) {
            return url;
        }
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) : m;
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
