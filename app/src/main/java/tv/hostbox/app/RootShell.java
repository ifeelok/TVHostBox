package tv.hostbox.app;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 通过 su 执行 root 命令的最小封装（零依赖，替代 libsu）。
 * 所有方法都会阻塞，调用方需自行放到工作线程。
 */
public final class RootShell {

    public static class Result {
        public final boolean ok;      // 退出码为 0
        public final int exitCode;
        public final String output;   // stdout + stderr

        Result(boolean ok, int exitCode, String output) {
            this.ok = ok;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    private RootShell() {}

    /** 检测是否有可用 root（su 能执行 id 且 uid=0） */
    public static boolean isRootAvailable() {
        Result r = run("id");
        return r.ok && r.output.contains("uid=0");
    }

    /**
     * 以 root 执行一段 shell 脚本（可多行）。
     */
    public static Result run(String script) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            final Process proc = p;

            DataOutputStream stdin = new DataOutputStream(p.getOutputStream());
            stdin.write(script.getBytes("UTF-8"));
            stdin.writeBytes("\necho __EXIT_CODE__=$?\nexit\n");
            stdin.flush();

            final StringBuilder out = new StringBuilder();
            final StringBuilder err = new StringBuilder();

            Thread tOut = new Thread() {
                @Override public void run() { drain(proc.getInputStream(), out); }
            };
            Thread tErr = new Thread() {
                @Override public void run() { drain(proc.getErrorStream(), err); }
            };
            tOut.start();
            tErr.start();

            int code = waitFor(p, 20000);
            tOut.join(3000);
            tErr.join(3000);

            String all = out.toString() + err.toString();
            boolean ok = code == 0 || all.contains("__EXIT_CODE__=0");
            return new Result(ok, code, all);
        } catch (Exception e) {
            return new Result(false, -1, "RootShell error: " + e);
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static void drain(java.io.InputStream is, StringBuilder sb) {
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException ignored) {
        }
    }

    private static int waitFor(Process p, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                return p.exitValue();
            } catch (IllegalThreadStateException e) {
                try { Thread.sleep(50); } catch (InterruptedException ie) { return -1; }
            }
        }
        p.destroy();
        return -1;
    }
}
