package tv.hostbox.app;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 通过 su 执行 root 命令的最小封装（零依赖，替代 libsu）。
 * 所有方法都会阻塞，调用方需自行放到工作线程。
 *
 * 设计：复用一个常驻 su 会话，避免每次操作都重新申请 root
 * （部分授权管理器每次都会弹窗）。会话意外退出时自动重建并重试一次。
 */
public final class RootShell {

    public static class Result {
        public final boolean ok;      // 退出码为 0
        public final int exitCode;
        public final String output;   // stdout

        Result(boolean ok, int exitCode, String output) {
            this.ok = ok;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    private static final Object LOCK = new Object();
    private static Process sProc;
    private static DataOutputStream sIn;
    private static BufferedReader sOut;
    private static final String MARKER = "__HOSTBOX_RC__";
    private static final int TIMEOUT_MS = 20000;

    private RootShell() {}

    /** 检测是否有可用 root（su 能执行 id 且 uid=0） */
    public static boolean isRootAvailable() {
        Result r = run("id");
        return r.ok && r.output.contains("uid=0");
    }

    /** 以 root 执行一段 shell 脚本（可多行）。带一次会话重建重试。 */
    public static Result run(String script) {
        synchronized (LOCK) {
            for (int attempt = 0; attempt < 2; attempt++) {
                Result r = runOnce(script);
                if (r != null) return r;
                resetSession(); // 会话已坏，重建后重试
            }
            return new Result(false, -1, "RootShell error: su 会话不可用");
        }
    }

    /** 单次尝试；返回 null 表示会话失效需要重建 */
    private static Result runOnce(String script) {
        try {
            if (!ensureSession()) return null;

            // 看门狗：命令超时则杀掉 su 会话解除阻塞
            final Process p = sProc;
            Thread watchdog = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        Thread.sleep(TIMEOUT_MS);
                        p.destroy();
                    } catch (InterruptedException ignored) {}
                }
            });
            watchdog.start();

            sIn.write(script.getBytes("UTF-8"));
            sIn.writeBytes("\necho " + MARKER + "=$?\n");
            sIn.flush();

            StringBuilder out = new StringBuilder();
            String line;
            int rc = -1;
            while ((line = sOut.readLine()) != null) {
                if (line.startsWith(MARKER)) {
                    try { rc = Integer.parseInt(line.substring(MARKER.length() + 1).trim()); } catch (Exception ignored) {}
                    break;
                }
                out.append(line).append('\n');
            }
            watchdog.interrupt();

            if (rc < 0) return null; // readLine 异常结束/流关闭 → 会话已死
            return new Result(rc == 0, rc, out.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean ensureSession() {
        if (sProc != null) {
            try { sProc.exitValue(); resetSession(); } catch (IllegalThreadStateException e) { /* 还活着 */ }
        }
        if (sProc != null) return true;
        try {
            sProc = Runtime.getRuntime().exec("su");
            sIn = new DataOutputStream(sProc.getOutputStream());
            sOut = new BufferedReader(new InputStreamReader(sProc.getInputStream(), "UTF-8"));
            // stderr 持续排空，防止阻塞
            final Process p = sProc;
            Thread errDrainer = new Thread(new Runnable() {
                @Override public void run() {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(p.getErrorStream()));
                    try { while (br.readLine() != null) {} } catch (IOException ignored) {}
                }
            });
            errDrainer.setDaemon(true);
            errDrainer.start();
            return true;
        } catch (Exception e) {
            resetSession();
            return false;
        }
    }

    private static void resetSession() {
        if (sProc != null) {
            try { sProc.destroy(); } catch (Exception ignored) {}
        }
        sProc = null;
        sIn = null;
        sOut = null;
    }
}
