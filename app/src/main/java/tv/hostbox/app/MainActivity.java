package tv.hostbox.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import io.nayuki.qrcodegen.QrCode;
import io.nayuki.qrcodegen.QrSegment;

public class MainActivity extends Activity {

    private static final int COLOR_BG = 0xFF0E1420;
    private static final int COLOR_PANEL = 0xFF161E2E;
    private static final int COLOR_TEXT = 0xFFE6EAF2;
    private static final int COLOR_HINT = 0xFF9AA7BD;
    private static final int COLOR_ACCENT = 0xFF4FC3F7;
    private static final int COLOR_DANGER = 0xFFEF5350;

    private LinearLayout navHostsBtn, navQrBtn, navCfBtn;
    private LinearLayout panelHosts, panelQr, panelCf;

    // Hosts 面板
    private TextView tvRootStatus;
    private ListView listHosts;
    private TextView tvHostsSummary;
    private List<HostsManager.Entry> entries = new ArrayList<HostsManager.Entry>();
    private HostsAdapter hostsAdapter;
    private String rawHosts = "";

    // 扫码面板
    private TextView tvQrUrl;
    private TextView tvServerStatus;
    private ImageView imgQr;

    // CF 面板
    private Button btnCfStart;
    private TextView tvCfProgress;
    private ListView listCf;
    private List<CfOptimizer.Candidate> cfResults = new ArrayList<CfOptimizer.Candidate>();
    private CfAdapter cfAdapter;
    private volatile boolean cfRunning = false;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppContextHolder.init(this);
        prefs = getSharedPreferences("hostbox", Context.MODE_PRIVATE);

        setContentView(R.layout.activity_main);
        navHostsBtn = findViewById(R.id.nav_hosts);
        navQrBtn = findViewById(R.id.nav_qr);
        navCfBtn = findViewById(R.id.nav_cf);

        panelHosts = buildHostsPanel();
        panelQr = buildQrPanel();
        panelCf = buildCfPanel();

        ViewGroup content = findViewById(R.id.content);
        content.addView(panelHosts);
        content.addView(panelQr);
        content.addView(panelCf);

        navHostsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(0); }
        });
        navQrBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(1); }
        });
        navCfBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(2); }
        });

        showTab(0);

        ConfigServer.setStatusListener(new ConfigServer.StatusListener() {
            @Override public void onStatus(String msg) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (tvServerStatus != null) tvServerStatus.setText(msg);
                    }
                });
            }
            @Override public void onDataChanged() {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(MainActivity.this, "手机端已更新 hosts", Toast.LENGTH_SHORT).show();
                        loadHostsAsync();
                    }
                });
            }
        });
        startServer();

        loadHostsAsync();
        checkRootAsync();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ConfigServer.stop();
    }

    // ==================== 页面切换 ====================

    private void showTab(int index) {
        panelHosts.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        panelQr.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        panelCf.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        styleNav(navHostsBtn, index == 0);
        styleNav(navQrBtn, index == 1);
        styleNav(navCfBtn, index == 2);
        if (index == 1) refreshQr();
    }

    private void styleNav(View v, boolean active) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setTextColor(active ? 0xFF0E1420 : COLOR_TEXT);
            tv.setBackgroundColor(active ? COLOR_ACCENT : 0x00000000);
        }
    }

    // ==================== Tab 0: Hosts 管理 ====================

    private LinearLayout buildHostsPanel() {
        LinearLayout root = verticalPanel();

        tvRootStatus = new TextView(this);
        styleText(tvRootStatus, COLOR_HINT, 14);
        tvRootStatus.setText("正在检测 root …");

        TextView title = new TextView(this);
        styleText(title, COLOR_TEXT, 24);
        title.setText("当前 hosts 条目（选中按 OK 删除）");

        tvHostsSummary = new TextView(this);
        styleText(tvHostsSummary, COLOR_HINT, 14);

        hostsAdapter = new HostsAdapter();
        listHosts = new ListView(this);
        listHosts.setAdapter(hostsAdapter);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(8);
        listHosts.setLayoutParams(lp);
        listHosts.setBackgroundColor(COLOR_PANEL);
        listHosts.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= entries.size()) return;
                confirmDelete(entries.get(position));
            }
        });

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(actionButton("刷新", new View.OnClickListener() {
            @Override public void onClick(View v) { loadHostsAsync(); }
        }));
        buttons.addView(actionButton("添加条目", new View.OnClickListener() {
            @Override public void onClick(View v) { showAddDialog(); }
        }));
        buttons.addView(actionButton("备份列表", new View.OnClickListener() {
            @Override public void onClick(View v) { showBackupList(); }
        }));

        root.addView(tvRootStatus);
        root.addView(title);
        root.addView(tvHostsSummary);
        root.addView(listHosts);
        root.addView(buttons);
        return root;
    }

    private void loadHostsAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String raw = HostsManager.readRaw();
                    final List<HostsManager.Entry> parsed = HostsManager.parse(raw);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            rawHosts = raw;
                            entries = parsed;
                            hostsAdapter.notifyDataSetChanged();
                            tvHostsSummary.setText("共 " + entries.size() + " 条解析记录 · 文件 " + raw.length() + " 字节");
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            tvHostsSummary.setText("读取失败：" + e.getMessage());
                        }
                    });
                }
            }
        }, "load-hosts").start();
    }

    private void checkRootAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = RootShell.isRootAvailable();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tvRootStatus.setText(ok
                                ? "✔ root 可用，可以修改 hosts"
                                : "✘ 未获取 root，修改/写入功能不可用");
                        tvRootStatus.setTextColor(ok ? 0xFF66BB6A : COLOR_DANGER);
                    }
                });
            }
        }, "check-root").start();
    }

    private void confirmDelete(final HostsManager.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle("删除该条？")
                .setMessage(e.host + " → " + e.ip)
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        doRemoveAsync(e.host);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doRemoveAsync(final String domain) {
        toast("正在删除 " + domain + " …");
        new Thread(new Runnable() {
            @Override public void run() {
                final String err = HostsManager.remove(domain);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (err == null) toast("已删除 " + domain);
                        else toast("删除失败：" + err);
                        loadHostsAsync();
                    }
                });
            }
        }, "remove-host").start();
    }

    private void showAddDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, 0);

        final EditText etIp = new EditText(this);
        etIp.setHint("IP（如 104.17.142.159）");
        final EditText etDomain = new EditText(this);
        etDomain.setHint("域名");
        etDomain.setText(prefs.getString("lastDomain", ""));
        box.addView(etIp);
        box.addView(etDomain);

        new AlertDialog.Builder(this)
                .setTitle("添加 hosts 条目")
                .setView(box)
                .setPositiveButton("写入", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String ip = etIp.getText().toString().trim();
                        String domain = etDomain.getText().toString().trim();
                        if (!HostsManager.isValidIp(ip) || domain.isEmpty()) {
                            toast("IP 或域名无效");
                            return;
                        }
                        prefs.edit().putString("lastDomain", domain).apply();
                        doUpsertAsync(domain, ip);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doUpsertAsync(final String domain, final String ip) {
        toast("正在写入 " + domain + " → " + ip);
        new Thread(new Runnable() {
            @Override public void run() {
                final String err = HostsManager.upsert(domain, ip);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (err == null) toast("写入成功（原文件已备份到 /sdcard/hostbox）");
                        else toast("写入失败：" + err);
                        loadHostsAsync();
                    }
                });
            }
        }, "upsert-host").start();
    }

    private void showBackupList() {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<String> backups = HostsManager.listBackups();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (backups.isEmpty()) {
                            toast("暂无备份（首次写入时会自动生成）");
                            return;
                        }
                        Collections.reverse(backups);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("选择要恢复的备份（近 10 个）")
                                .setItems(backups.subList(0, Math.min(10, backups.size()))
                                        .toArray(new String[0]),
                                        new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int w) {
                                        // w 是索引，取对应名字
                                        final String name = backups.get(w);
                                        new Thread(new Runnable() {
                                            @Override public void run() {
                                                final String err = HostsManager.restoreBackup(name);
                                                runOnUiThread(new Runnable() {
                                                    @Override public void run() {
                                                        toast(err == null ? "已恢复 " + name : "恢复失败：" + err);
                                                        loadHostsAsync();
                                                    }
                                                });
                                            }
                                        }, "restore").start();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        }, "list-backups").start();
    }

    // ==================== Tab 1: 扫码配置 ====================

    private LinearLayout buildQrPanel() {
        LinearLayout root = verticalPanel();

        TextView tip = new TextView(this);
        styleText(tip, COLOR_HINT, 16);
        tip.setText("用手机「相机 / 微信扫一扫」扫描二维码，在手机浏览器里编辑 hosts 并保存");

        imgQr = new ImageView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(300), dp(300));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(10);
        imgQr.setLayoutParams(lp);
        imgQr.setBackgroundColor(Color.WHITE);
        imgQr.setPadding(dp(8), dp(8), dp(8), dp(8));

        tvQrUrl = new TextView(this);
        styleText(tvQrUrl, COLOR_ACCENT, 20);
        tvQrUrl.setGravity(Gravity.CENTER_HORIZONTAL);

        tvServerStatus = new TextView(this);
        styleText(tvServerStatus, COLOR_HINT, 14);
        tvServerStatus.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_HORIZONTAL);
        buttons.addView(actionButton("刷新二维码", new View.OnClickListener() {
            @Override public void onClick(View v) { refreshQr(); }
        }));
        buttons.addView(actionButton("停止/启动服务", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (ConfigServer.isRunning()) ConfigServer.stop(); else startServer();
            }
        }));

        root.addView(tip);
        root.addView(imgQr);
        root.addView(tvQrUrl);
        root.addView(tvServerStatus);
        root.addView(buttons);
        return root;
    }

    private void startServer() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ConfigServer.start();
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (tvServerStatus != null)
                                tvServerStatus.setText("服务启动失败：" + e.getMessage());
                        }
                    });
                }
            }
        }, "start-server").start();
    }

    private void refreshQr() {
        String ip = getBoxIp();
        if (ip == null) {
            tvQrUrl.setText("未获取到盒子 IP，请确认网络已连接");
            return;
        }
        final String url = "http://" + ip + ":" + ConfigServer.PORT;
        tvQrUrl.setText(url);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    QrCode qr = QrCode.encodeText(url, QrCode.Ecc.MEDIUM);
                    final Bitmap bmp = qrToBitmap(qr, 16, 4);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            imgQr.setImageBitmap(bmp);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            tvQrUrl.setText("二维码生成失败：" + e);
                        }
                    });
                }
            }
        }, "gen-qr").start();
    }

    private Bitmap qrToBitmap(QrCode qr, int scale, int border) {
        int size = (qr.size + border * 2) * scale;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean black = qr.getModule(x / scale - border, y / scale - border);
                bmp.setPixel(x, y, black ? Color.BLACK : Color.WHITE);
            }
        }
        return bmp;
    }

    // ==================== Tab 2: CF 优选 ====================

    private LinearLayout buildCfPanel() {
        LinearLayout root = verticalPanel();

        btnCfStart = actionButton("开始优选（延迟 + 下载测速）", new View.OnClickListener() {
            @Override public void onClick(View v) { startCfOptimize(); }
        });

        tvCfProgress = new TextView(this);
        styleText(tvCfProgress, COLOR_HINT, 15);
        tvCfProgress.setText("对 Cloudflare 官方网段采样 → TCP 443 延迟测试 → 下载测速 → 取前 8 名。");

        cfAdapter = new CfAdapter();
        listCf = new ListView(this);
        listCf.setAdapter(cfAdapter);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(8);
        listCf.setLayoutParams(lp);
        listCf.setBackgroundColor(COLOR_PANEL);
        listCf.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= cfResults.size()) return;
                showApplyDialog(cfResults.get(position));
            }
        });

        root.addView(btnCfStart);
        root.addView(tvCfProgress);
        root.addView(listCf);
        return root;
    }

    private void startCfOptimize() {
        if (cfRunning) {
            toast("优选进行中，请稍候");
            return;
        }
        cfRunning = true;
        btnCfStart.setEnabled(false);
        cfResults.clear();
        cfAdapter.notifyDataSetChanged();

        new Thread(new Runnable() {
            @Override public void run() {
                CfOptimizer opt = new CfOptimizer();
                try {
                    final List<CfOptimizer.Candidate> result = opt.optimize(new CfOptimizer.Progress() {
                        @Override public void onProgress(final String phase, final int done, final int total, final String detail) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    String label = phase.equals("sample") ? "采样" :
                                            phase.equals("latency") ? "延迟" :
                                            phase.equals("speed") ? "测速" : "完成";
                                    tvCfProgress.setText("[" + label + " " + done + "/" + total + "] " + detail);
                                }
                            });
                        }
                    });
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            cfResults.clear();
                            cfResults.addAll(result);
                            cfAdapter.notifyDataSetChanged();
                            tvCfProgress.setText("优选完成，共 " + result.size() + " 个。选中某条 → 应用为指定域名的 IP");
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            tvCfProgress.setText("优选失败：" + e);
                        }
                    });
                } finally {
                    cfRunning = false;
                    runOnUiThread(new Runnable() {
                        @Override public void run() { btnCfStart.setEnabled(true); }
                    });
                }
            }
        }, "cf-optimize").start();
    }

    private void showApplyDialog(final CfOptimizer.Candidate c) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, 0);

        TextView info = new TextView(this);
        styleText(info, COLOR_TEXT, 16);
        info.setText("优选 IP：" + c.ip + "   延迟 " + c.rttMs + "ms   "
                + (c.mbps > 0 ? String.format("%.1f Mbps", c.mbps) : "未测速"));
        final EditText etDomain = new EditText(this);
        etDomain.setHint("要应用的域名");
        etDomain.setText(prefs.getString("lastDomain", ""));
        box.addView(info);
        box.addView(etDomain);

        new AlertDialog.Builder(this)
                .setTitle("将优选 IP 写入 hosts")
                .setView(box)
                .setPositiveButton("写入", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String domain = etDomain.getText().toString().trim();
                        if (domain.isEmpty()) { toast("域名为空"); return; }
                        prefs.edit().putString("lastDomain", domain).apply();
                        doUpsertAsync(domain, c.ip);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 通用 UI 辅助 ====================

    private LinearLayout verticalPanel() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(COLOR_BG);
        return root;
    }

    private Button actionButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(COLOR_TEXT);
        b.setBackgroundResource(android.R.drawable.btn_default);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(6), dp(4), dp(6));
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        return b;
    }

    private void styleText(TextView tv, int color, int sp) {
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setPadding(0, dp(4), 0, dp(4));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                v, getResources().getDisplayMetrics());
    }

    private String getBoxIp() {
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

    // ==================== Adapter ====================

    private class HostsAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView tvMain, tvSub;
            if (convertView == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(12);
                row.setPadding(pad, pad, pad, pad);
                tvMain = new TextView(MainActivity.this);
                styleText(tvMain, COLOR_TEXT, 18);
                tvSub = new TextView(MainActivity.this);
                styleText(tvSub, COLOR_HINT, 13);
                row.addView(tvMain);
                row.addView(tvSub);
                row.setTag(new TextView[]{tvMain, tvSub});
            } else {
                row = (LinearLayout) convertView;
                TextView[] tags = (TextView[]) row.getTag();
                tvMain = tags[0];
                tvSub = tags[1];
            }
            HostsManager.Entry e = entries.get(position);
            tvMain.setText(e.host + "  →  " + e.ip);
            tvSub.setText("行 " + (e.lineIndex + 1) + (e.comment != null ? " · 注释: " + e.comment : "")
                    + " · 按 OK 删除");
            return row;
        }
    }

    private class CfAdapter extends BaseAdapter {
        @Override public int getCount() { return cfResults.size(); }
        @Override public Object getItem(int position) { return cfResults.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView tvMain, tvSub;
            if (convertView == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(12);
                row.setPadding(pad, pad, pad, pad);
                tvMain = new TextView(MainActivity.this);
                styleText(tvMain, COLOR_TEXT, 18);
                tvSub = new TextView(MainActivity.this);
                styleText(tvSub, COLOR_HINT, 14);
                row.addView(tvMain);
                row.addView(tvSub);
                row.setTag(new TextView[]{tvMain, tvSub});
            } else {
                row = (LinearLayout) convertView;
                TextView[] tags = (TextView[]) row.getTag();
                tvMain = tags[0];
                tvSub = tags[1];
            }
            CfOptimizer.Candidate c = cfResults.get(position);
            tvMain.setText((position + 1) + ". " + c.ip);
            tvSub.setText("延迟 " + c.rttMs + "ms · "
                    + (c.mbps > 0 ? String.format("%.1f Mbps", c.mbps) : "未测速")
                    + " · 按 OK 应用到域名");
            return row;
        }
    }
}
