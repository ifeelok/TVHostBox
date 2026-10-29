package tv.hostbox.app;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 手机扫码配置用的极简 HTTP 服务器（零依赖）。
 *
 * 路由：
 *   GET  /          → 配置页（手机浏览器打开）
 *   GET  /api/raw   → 返回 hosts 全文
 *   POST /api/raw   → body 为新的 hosts 全文，校验后写入
 *   GET  /api/exit  → 停止服务器
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
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            int contentLength = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
                }
            }

            char[] bodyChars = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(bodyChars, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            String body = new String(bodyChars, 0, read);

            String[] parts = requestLine.split("\\s+");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";
            if (path.contains("?")) path = path.substring(0, path.indexOf('?'));

            if ("GET".equals(method) && "/".equals(path)) {
                respond(s, 200, "text/html; charset=utf-8", buildPage());
            } else if ("GET".equals(method) && "/api/raw".equals(path)) {
                String raw;
                try {
                    raw = HostsManager.readRaw();
                    respond(s, 200, "text/plain; charset=utf-8", raw);
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
                    respond(s, 200, "text/plain; charset=utf-8", "OK 已写入并回读校验通过");
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

    private static String validateAll(String content) {
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String err = HostsManager.validateLine(lines[i]);
            if (err != null) return "第 " + (i + 1) + " 行有误：" + err;
        }
        return null;
    }

    private static void respond(Socket s, int code, String type, String content) {
        try {
            byte[] data = content.getBytes("UTF-8");
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "ISO-8859-1"));
            w.write("HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "ERR") + "\r\n");
            w.write("Content-Type: " + type + "\r\n");
            w.write("Content-Length: " + data.length + "\r\n");
            w.write("Connection: close\r\n\r\n");
            w.flush();
            s.getOutputStream().write(data);
            s.getOutputStream().flush();
        } catch (Exception ignored) {}
    }

    // ===================== 配置页 =====================

    private static String buildPage() {
        return "<!DOCTYPE html>\n"
            + "<html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>Host管家 · 配置</title>"
            + "<style>"
            + "body{font-family:-apple-system,sans-serif;background:#0e1420;color:#e6eaf2;margin:0;padding:16px;max-width:720px;margin:0 auto}"
            + "h1{font-size:20px;margin:8px 0}p.tip{color:#9aa7bd;font-size:13px}"
            + "textarea{width:100%;height:45vh;background:#161e2e;color:#d6e2ff;border:1px solid #2a3854;border-radius:8px;padding:10px;font-size:13px;line-height:1.5;box-sizing:border-box;font-family:monospace}"
            + "button{background:#2f7cf6;color:#fff;border:0;border-radius:8px;padding:12px 22px;font-size:16px;margin:8px 4px 0 0}"
            + "button.gray{background:#3a4a66}"
            + "#msg{margin-top:10px;font-size:14px;white-space:pre-wrap}"
            + ".row{display:flex;gap:8px;margin-top:10px}"
            + "input{flex:1;background:#161e2e;color:#d6e2ff;border:1px solid #2a3854;border-radius:8px;padding:10px;font-size:14px}"
            + "</style></head><body>"
            + "<h1>Host管家 · 手机配置</h1>"
            + "<p class=\"tip\">直接编辑下方 hosts 全文（每行一条：IP 域名），保存后写入盒子 /system/etc/hosts 并自动备份。</p>"
            + "<div class=\"row\">"
            + "<input id=\"ip\" placeholder=\"优选 IP，如 104.17.142.159\">"
            + "<input id=\"domain\" placeholder=\"域名，如 emby-proxy.example.com\">"
            + "<button class=\"gray\" onclick=\"addLine()\">追加</button>"
            + "</div>"
            + "<textarea id=\"content\" spellcheck=\"false\"></textarea>"
            + "<div>"
            + "<button onclick=\"save()\">保存写入</button>"
            + "<button class=\"gray\" onclick=\"load()\">重新加载</button>"
            + "</div>"
            + "<div id=\"msg\"></div>"
            + "<script>"
            + "function load(){fetch('/api/raw').then(r=>r.text()).then(t=>{document.getElementById('content').value=t;msg('已加载当前 hosts');})}"
            + "function save(){fetch('/api/raw',{method:'POST',body:document.getElementById('content').value})"
            + ".then(r=>r.text().then(t=>msg((r.ok?'✅ ':'❌ ')+t)))}"
            + "function addLine(){var ip=document.getElementById('ip').value.trim();var d=document.getElementById('domain').value.trim();"
            + "if(!ip||!d){msg('请先填写 IP 和域名');return}"
            + "var ta=document.getElementById('content');var v=ta.value.replace(/\\n*$/,'\\n');"
            + "var lines=v.split('\\n');var found=false;"
            + "for(var i=0;i<lines.length;i++){var parts=lines[i].trim().split(/\\s+/);"
            + "if(parts.length>=2&&parts[1].toLowerCase()===d.toLowerCase()){lines[i]=ip+'  '+d;found=true}}"
            + "ta.value=lines.join('\\n')+(found?'':'\\n'+ip+'  '+d);"
            + "msg(found?'已替换 '+d+' 的条目':'已在末尾追加');}"
            + "function msg(t){document.getElementById('msg').textContent=t}"
            + "load();"
            + "</script></body></html>";
    }
}
