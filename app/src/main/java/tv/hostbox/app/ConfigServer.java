package tv.hostbox.app;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 手机扫码配置用的极简 HTTP 服务器（零依赖）。
 *
 * 路由：
 *   GET  /            → 手机配置页（assets/mobile.html）
 *   GET  /api/state   → 条目列表 + 盒子 IP 的 JSON
 *   GET  /api/raw     → 返回 hosts 全文
 *   POST /api/raw     → body 为新的 hosts 全文，校验后写入
 *   POST /api/set     → form: ip=&domain=  新增/替换一条解析
 *   POST /api/delete  → form: domain=      删除某域名的解析
 */
public final class ConfigServer {

    public interface StatusListener {
        void onStatus(String msg);
        void onDataChanged();
    }

    public static final int PORT = 18123;

    private static volatile boolean running = false;
    private static ServerSocket serverSocket;
    private static Thread acceptThread;
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool();
    private static volatile StatusListener listener;
    private static volatile String pageCache;

    private ConfigServer() {}

    public static void setStatusListener(StatusListener l) {
        listener = l;
    }

    public static synchronized boolean isRunning() {
        return running;
    }

    public static synchronized void start() throws Exception {
        if (running) return;
        serverSocket = new ServerSocket(PORT, 8);
        serverSocket.setReuseAddress(true);
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() {
                acceptLoop();
            }
        }, "ConfigServer-accept");
        acceptThread.start();
        notifyStatus("配置服务已启动 · 端口 " + PORT);
    }

    public static synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
        notifyStatus("配置服务已停止");
    }

    private static void notifyStatus(String msg) {
        StatusListener l = listener;
        if (l != null) l.onStatus(msg);
    }

    private static void notifyDataChanged() {
        StatusListener l = listener;
        if (l != null) l.onDataChanged();
    }

    private static void acceptLoop() {
        while (running) {
            try {
                final Socket s = serverSocket.accept();
                s.setSoTimeout(10000);
                WORKERS.execute(new Runnable() {
                    @Override public void run() {
                        handle(s);
                    }
                });
            } catch (Exception e) {
                if (running) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) { return; }
                }
            }
        }
    }

    private static void handle(Socket s) {
        try {
            InputStream is = s.getInputStream();

            // 按字节读到 \r\n\r\n 为止：按字符读会把 content-length（字节数）
            // 当成字符数，含中文注释的 hosts 会读不满而卡到超时
            ByteArrayOutputStream headBuf = new ByteArrayOutputStream(1024);
            int last4 = 0, b;
            while ((b = is.read()) >= 0) {
                headBuf.write(b);
                last4 = (last4 << 8) | (b & 0xFF);
                if (last4 == 0x0D0A0D0A) break;               // \r\n\r\n
                if (headBuf.size() > 64 * 1024) return;        // 防异常长头
            }
            if (headBuf.size() == 0) return;

            String[] headLines = new String(headBuf.toByteArray(), "ISO-8859-1").split("\r\n");
            String requestLine = headLines[0];
            if (requestLine == null || requestLine.isEmpty()) return;

            int contentLength = 0;
            for (int i = 1; i < headLines.length; i++) {
                int c = headLines[i].indexOf(':');
                if (c > 0 && headLines[i].substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                    try { contentLength = Integer.parseInt(headLines[i].substring(c + 1).trim()); }
                    catch (Exception ignored) {}
                }
            }
            if (contentLength < 0 || contentLength > 4 * 1024 * 1024) contentLength = 0;

            byte[] bodyBytes = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = is.read(bodyBytes, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            String body = new String(bodyBytes, 0, read, "UTF-8");

            String[] parts = requestLine.split("\\s+");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";
            if (path.contains("?")) path = path.substring(0, path.indexOf('?'));

            if ("GET".equals(method) && "/".equals(path)) {
                respond(s, 200, "text/html; charset=utf-8", mobilePage());
            } else if ("GET".equals(method) && "/api/state".equals(path)) {
                respond(s, 200, "application/json; charset=utf-8", buildStateJson());
            } else if ("GET".equals(method) && "/api/raw".equals(path)) {
                try {
                    respond(s, 200, "text/plain; charset=utf-8", HostsManager.readRaw());
                } catch (Exception e) {
                    respond(s, 500, "text/plain; charset=utf-8", "read error: " + e);
                }
            } else if ("POST".equals(method) && "/api/raw".equals(path)) {
                String err = validateAll(body);
                if (err != null) {
                    respond(s, 400, "text/plain; charset=utf-8", err);
                    return;
                }
                err = HostsManager.writeRaw(body);
                if (err == null) {
                    respond(s, 200, "text/plain; charset=utf-8", "已写入并回读校验通过");
                    notifyDataChanged();
                } else {
                    respond(s, 500, "text/plain; charset=utf-8", err);
                }
            } else if ("POST".equals(method) && "/api/set".equals(path)) {
                Map<String, String> f = parseForm(body);
                String ip = f.get("ip");
                String domain = f.get("domain");
                if (ip == null || !HostsManager.isValidIp(ip.trim())) {
                    respond(s, 400, "text/plain; charset=utf-8", "IP 无效：" + ip);
                    return;
                }
                if (domain == null || !domain.trim().matches("(?i)[a-z0-9._\\-]+")) {
                    respond(s, 400, "text/plain; charset=utf-8", "域名无效：" + domain);
                    return;
                }
                String err = HostsManager.upsert(domain.trim(), ip.trim());
                if (err == null) {
                    respond(s, 200, "text/plain; charset=utf-8", "已保存");
                    notifyDataChanged();
                } else {
                    respond(s, 500, "text/plain; charset=utf-8", err);
                }
            } else if ("POST".equals(method) && "/api/delete".equals(path)) {
                Map<String, String> f = parseForm(body);
                String domain = f.get("domain");
                if (domain == null || domain.trim().isEmpty()) {
                    respond(s, 400, "text/plain; charset=utf-8", "缺少 domain");
                    return;
                }
                String err = HostsManager.remove(domain.trim());
                if (err == null) {
                    respond(s, 200, "text/plain; charset=utf-8", "已删除");
                    notifyDataChanged();
                } else {
                    respond(s, 500, "text/plain; charset=utf-8", err);
                }
            } else {
                respond(s, 404, "text/plain; charset=utf-8", "not found");
            }
        } catch (Exception e) {
            // 连接中断等，忽略
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<String, String>();
        if (body == null) return map;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                map.put(k, v);
            } catch (Exception ignored) {}
        }
        return map;
    }

    private static String validateAll(String content) {
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String err = HostsManager.validateLine(lines[i]);
            if (err != null) return "第 " + (i + 1) + " 行有误：" + err;
        }
        return null;
    }

    /** 手机端状态：盒子地址 + 解析条目列表 */
    private static String buildStateJson() {
        StringBuilder sb = new StringBuilder(512);
        String ip = localIp();
        sb.append("{\"ip\":\"").append(ip == null ? "" : ip).append('"')
          .append(",\"port\":").append(PORT);
        int bytes = 0;
        try {
            String raw = HostsManager.readRaw();
            bytes = raw.length();
            List<HostsManager.Entry> list = HostsManager.parse(raw);
            sb.append(",\"bytes\":").append(bytes).append(",\"entries\":[");
            for (int i = 0; i < list.size(); i++) {
                HostsManager.Entry e = list.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"ip\":\"").append(jsonEscape(e.ip)).append('"')
                  .append(",\"host\":\"").append(jsonEscape(e.host)).append('"')
                  .append(",\"comment\":").append(e.comment == null
                        ? "null" : "\"" + jsonEscape(e.comment) + "\"")
                  .append(",\"line\":").append(e.lineIndex + 1)
                  .append('}');
            }
            sb.append(']');
        } catch (Exception e) {
            sb.append(",\"bytes\":0,\"entries\":[],\"error\":\"")
              .append(jsonEscape(String.valueOf(e.getMessage()))).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String localIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void respond(Socket s, int code, String type, String content) {
        try {
            byte[] data = content.getBytes("UTF-8");
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "ISO-8859-1"));
            w.write("HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "ERR") + "\r\n");
            w.write("Content-Type: " + type + "\r\n");
            w.write("Content-Length: " + data.length + "\r\n");
            w.write("Cache-Control: no-store\r\n");
            w.write("Connection: close\r\n\r\n");
            w.flush();
            s.getOutputStream().write(data);
            s.getOutputStream().flush();
        } catch (Exception ignored) {}
    }

    // ===================== 配置页 =====================

    /** 读取 assets/mobile.html（首次读入后常驻内存） */
    private static String mobilePage() {
        String cached = pageCache;
        if (cached != null) return cached;
        try {
            InputStream is = AppContextHolder.get().getAssets().open("mobile.html");
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                cached = new String(bos.toByteArray(), "UTF-8");
            } finally {
                is.close();
            }
            pageCache = cached;
            return cached;
        } catch (Exception e) {
            return "<!DOCTYPE html><meta charset=utf-8>"
                    + "<body style='background:#0B111C;color:#E8EDF6;font-family:sans-serif;padding:24px'>"
                    + "<h2>页面加载失败</h2><p>" + e + "</p></body>";
        }
    }
}
