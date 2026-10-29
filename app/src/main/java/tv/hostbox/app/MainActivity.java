package tv.hostbox.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
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

public class MainActivity extends Activity {

    private static final int COLOR_BG = 0xFF0E1420;
    private static final int COLOR_PANEL = 0xFF161E2E;
    private static final int COLOR_TEXT = 0xFFE6EAF2;
    private static final int COLOR_HINT = 0xFF9AA7BD;
    private static final int COLOR_ACCENT = 0xFF4FC3F7;
    private static final int COLOR_DANGER = 0xFFEF5350;

    private Button navHostsBtn, navQrBtn, navCfBtn;
    private LinearLayout panelHosts, panelQr, panelCf;
    private Button btnQrRefresh;

    // Hosts 面板
    private TextView tvRootStatus;
    private ListView listHosts;
    private TextView tvHostsSummary;
    private TextView tvActionResult; // 持久显示写入/删除/恢复结果（Toast 太快会错过）
    private List<HostsManager.Entry> entries = new ArrayList<HostsManager.Entry>();
    private HostsAdapter hostsAdapter;

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
    private volatile CfOptimizer cfOpt = null; // 当前优选任务（用于取消）

    // 设置项 key（CF 参数）
    private static final String PREF_CF_TOPN = "cfTopN";        // 保留前几名，默认 8
    private static final String PREF_CF_SAMPLE = "cfSample";    // 采样数量，默认 320
    private static final String PREF_CF_DOWNLOAD_MB = "cfDownloadMB"; // 测速大小 MB，默认 6

    private SharedPreferences prefs;
    private int currentTab = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppContextHolder.init(this);
        prefs = getSharedPreferences("hostbox", Context.MODE_PRIVATE);

        setContentView(R.layout.activity_main);
        navHostsBtn = findViewById(R.id.nav_hosts);
        navQrBtn = findViewById(R.id.nav_qr);
        navCfBtn = findViewById(R.id.nav_cf);
        styleNav(navHostsBtn, false);
        styleNav(navQrBtn, false);
        styleNav(navCfBtn, false);

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

    @Override
    protected void onResume() {
        super.onResume();
        // 盒子 IP 可能在后台被 DHCP 改变，回到前台/回到本页时刷新二维码
        if (currentTab == 1) refreshQr();
    }

    // ==================== 页面切换 ====================

    private void showTab(int index) {
        currentTab = index;
        panelHosts.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        panelQr.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        panelCf.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        styleNav(navHostsBtn, index == 0);
        styleNav(navQrBtn, index == 1);
        styleNav(navCfBtn, index == 2);
        if (index == 1) refreshQr();
        // 焦点自动落位，避免遥控器"盲按"
        if (index == 0) {
            listHosts.requestFocus();
        } else if (index == 1) {
            btnQrRefresh.requestFocus();
        } else if (index == 2) {
            listCf.requestFocus();
        }
    }

    private void styleNav(View v, boolean active) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setTextColor(active ? 0xFF0E1420 : COLOR_TEXT);
            tv.setBackgroundResource(active ? R.drawable.btn_tab_active : R.drawable.btn_tv);
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
        listHosts.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        listHosts.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= entries.size()) return;
                confirmDelete(entries.get(position));
            }
        });

        // 空状态提示（ListView 自动切换可见性）
        TextView tvEmptyHosts = new TextView(this);
        styleText(tvEmptyHosts, COLOR_HINT, 16);
        tvEmptyHosts.setGravity(Gravity.CENTER);
        tvEmptyHosts.setText("暂无 hosts 条目\n\n按下方「添加条目」新增，\n或到「扫码配置」用手机添加");
        LinearLayout.LayoutParams lpEmpty = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        tvEmptyHosts.setLayoutParams(lpEmpty);
        listHosts.setEmptyView(tvEmptyHosts);

        // 操作结果持久显示（Toast 在电视上太短暂）
        tvActionResult = new TextView(this);
        styleText(tvActionResult, COLOR_HINT, 14);

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
        buttons.addView(actionButton("设置", new View.OnClickListener() {
            @Override public void onClick(View v) { showSettingsDialog(); }
        }));

        root.addView(tvRootStatus);
        root.addView(title);
        root.addView(tvHostsSummary);
        root.addView(listHosts);
        root.addView(tvEmptyHosts);
        root.addView(tvActionResult);
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
                        showActionResult(err == null,
                                err == null ? "✔ 已删除 " + domain : "✘ 删除失败：" + err);
                        loadHostsAsync();
                    }
                });
            }
        }, "remove-host").start();
    }

    /** 操作结果持久显示在列表下方（成功绿/失败红），同时配一个 Toast */
    private void showActionResult(boolean ok, String msg) {
        if (tvActionResult != null) {
            tvActionResult.setText(msg);
            tvActionResult.setTextColor(ok ? 0xFF66BB6A : COLOR_DANGER);
        }
        toast(msg);
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
        box.addView(buildDomainPicker(etDomain));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("添加 hosts 条目")
                .setView(box)
                .setPositiveButton("写入", null) // 点击行为在 show() 后拦截，校验失败不关闭
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String ip = etIp.getText().toString().trim();
                String domain = etDomain.getText().toString().trim();
                if (!HostsManager.isValidIp(ip)) { toast("IP 无效，请检查"); return; }
                if (domain.isEmpty()) { toast("域名为空"); return; }
                prefs.edit().putString("lastDomain", domain).apply();
                dlg.dismiss();
                doUpsertAsync(domain, ip);
            }
        });
    }

    private void doUpsertAsync(final String domain, final String ip) {
        toast("正在写入 " + domain + " → " + ip);
        new Thread(new Runnable() {
            @Override public void run() {
                final String err = HostsManager.upsert(domain, ip);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        showActionResult(err == null,
                                err == null ? "✔ 写入成功 " + domain + " → " + ip + "（原文件已备份）"
                                        : "✘ 写入失败：" + err);
                        loadHostsAsync();
                    }
                });
            }
        }, "upsert-host").start();
    }

    // ==================== 设置 ====================

    /** 统一设置入口：备份上限 + CF 优选参数 */
    private void showSettingsDialog() {
        int keep = prefs.getInt(HostsManager.PREF_MAX_BACKUPS, HostsManager.DEFAULT_MAX_BACKUPS);
        int topN = prefs.getInt(PREF_CF_TOPN, 8);
        int sample = prefs.getInt(PREF_CF_SAMPLE, 320);
        int mb = prefs.getInt(PREF_CF_DOWNLOAD_MB, 6);
        final String[] items = {
                "备份保留数量：" + (keep <= 0 ? "不限制" : keep + " 份"),
                "CF 优选保留条数：" + topN,
                "CF 采样数量：" + sample + (sample == 160 ? "（快）" : sample == 320 ? "（标准）" : "（充分）"),
                "CF 单 IP 测速流量：" + mb + " MB",
        };
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (w == 0) pickFromOptions("备份保留数量（写入时自动清理）",
                                new String[]{"保留最近 10 份", "保留最近 20 份", "保留最近 50 份", "不限制"},
                                new int[]{10, 20, 50, 0},
                                HostsManager.PREF_MAX_BACKUPS, HostsManager.DEFAULT_MAX_BACKUPS);
                        else if (w == 1) pickFromOptions("CF 优选结果保留前几名",
                                new String[]{"前 5 名", "前 8 名", "前 10 名", "前 15 名"},
                                new int[]{5, 8, 10, 15},
                                PREF_CF_TOPN, 8);
                        else if (w == 2) pickFromOptions("CF 网段采样数量（越多越准越慢）",
                                new String[]{"160 个（快）", "320 个（标准）", "640 个（充分）"},
                                new int[]{160, 320, 640},
                                PREF_CF_SAMPLE, 320);
                        else if (w == 3) pickFromOptions("每个候选 IP 的测速下载量",
                                new String[]{"2 MB（快）", "6 MB（标准）", "12 MB（精确）"},
                                new int[]{2, 6, 12},
                                PREF_CF_DOWNLOAD_MB, 6);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void pickFromOptions(String title, final String[] labels, final int[] values,
                                 final String key, int defValue) {
        int current = prefs.getInt(key, defValue);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) { checked = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        prefs.edit().putInt(key, values[w]).apply();
                        d.dismiss();
                        toast("已设置：" + labels[w]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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
                        Collections.reverse(backups); // 新→旧
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("选择要恢复的备份（共 " + backups.size() + " 个，新→旧）")
                                .setItems(backups.toArray(new String[0]),
                                        new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int w) {
                                        // w 是索引，取对应名字
                                        final String name = backups.get(w);
                                        new Thread(new Runnable() {
                                            @Override public void run() {
                                                final String err = HostsManager.restoreBackup(name);
                                                runOnUiThread(new Runnable() {
                                                    @Override public void run() {
                                                        showActionResult(err == null,
                                                                err == null ? "✔ 已恢复 " + name
                                                                        : "✘ 恢复失败：" + err);
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
        // 按屏幕宽度自适应，避免低分辨率/小屏盒子溢出（不超过屏宽 45%）
        int qrSide = (int) Math.min(dp(300),
                getResources().getDisplayMetrics().widthPixels * 0.45f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(qrSide, qrSide);
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
        btnQrRefresh = actionButton("刷新二维码", new View.OnClickListener() {
            @Override public void onClick(View v) { refreshQr(); }
        });
        buttons.addView(btnQrRefresh);
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
        tvCfProgress.setText("对 Cloudflare 网段采样 → TCP 443 延迟测试 → 下载测速。\n完成后选中某条按 OK 应用到域名（参数在 hosts 页「设置」里调）。");

        cfAdapter = new CfAdapter();
        listCf = new ListView(this);
        listCf.setAdapter(cfAdapter);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(8);
        listCf.setLayoutParams(lp);
        listCf.setBackgroundColor(COLOR_PANEL);
        listCf.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        listCf.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= cfResults.size()) return;
                showApplyDialog(cfResults.get(position));
            }
        });

        // 空状态提示
        TextView tvEmptyCf = new TextView(this);
        styleText(tvEmptyCf, COLOR_HINT, 16);
        tvEmptyCf.setGravity(Gravity.CENTER);
        tvEmptyCf.setText("尚未测速\n\n按上方「开始优选」获取 Cloudflare 优选 IP\n（可在 hosts 页「设置」里调参数）");
        LinearLayout.LayoutParams lpEmpty = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        tvEmptyCf.setLayoutParams(lpEmpty);
        listCf.setEmptyView(tvEmptyCf);

        root.addView(btnCfStart);
        root.addView(tvCfProgress);
        root.addView(listCf);
        root.addView(tvEmptyCf);
        return root;
    }

    private void startCfOptimize() {
        if (cfRunning) {
            // 进行中再按 = 取消
            if (cfOpt != null) cfOpt.cancel();
            btnCfStart.setText("正在取消…");
            return;
        }
        cfRunning = true;
        btnCfStart.setText("取消优选");
        cfResults.clear();
        cfAdapter.notifyDataSetChanged();

        // 设置里的 CF 参数
        int topN = prefs.getInt(PREF_CF_TOPN, 8);
        int sample = prefs.getInt(PREF_CF_SAMPLE, 320);
        int mb = prefs.getInt(PREF_CF_DOWNLOAD_MB, 6);
        final CfOptimizer opt = new CfOptimizer(sample, Math.max(16, sample / 13), topN, mb * 1024 * 1024);
        cfOpt = opt;

        new Thread(new Runnable() {
            @Override public void run() {
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
                } catch (final InterruptedException e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            tvCfProgress.setText("已取消优选。");
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
                    cfOpt = null;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            btnCfStart.setEnabled(true);
                            btnCfStart.setText("开始优选（延迟 + 下载测速）");
                        }
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
        box.addView(buildDomainPicker(etDomain));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("将优选 IP 写入 hosts")
                .setView(box)
                .setPositiveButton("写入", null) // show() 后拦截，校验失败不关闭
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String domain = etDomain.getText().toString().trim();
                if (domain.isEmpty()) { toast("域名为空"); return; }
                prefs.edit().putString("lastDomain", domain).apply();
                dlg.dismiss();
                doUpsertAsync(domain, c.ip);
            }
        });
    }

    // ==================== 通用 UI 辅助 ====================

    /**
     * 域名快捷选择：把当前 hosts 里的域名做成可点按钮列表，
     * 避免在电视上用遥控器逐字母敲域名。
     */
    private View buildDomainPicker(final EditText etDomain) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        java.util.LinkedHashSet<String> domains = new java.util.LinkedHashSet<String>();
        for (HostsManager.Entry e : entries) domains.add(e.host);
        if (domains.isEmpty()) return wrap;

        TextView lbl = new TextView(this);
        styleText(lbl, COLOR_HINT, 13);
        lbl.setText("从现有条目选择域名：");
        wrap.addView(lbl);

        for (final String d : domains) {
            Button chip = new Button(this);
            chip.setText(d);
            chip.setAllCaps(false);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setTextColor(COLOR_ACCENT);
            chip.setBackgroundResource(R.drawable.btn_tv);
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(2), 0, dp(2));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { etDomain.setText(d); }
            });
            wrap.addView(chip);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(wrap);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(130));
        lp.topMargin = dp(6);
        sv.setLayoutParams(lp);
        return sv;
    }

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
        b.setBackgroundResource(R.drawable.btn_tv);
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
                row.setBackgroundResource(R.drawable.row_tv);
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
            tvSub.setText("行 " + (e.lineIndex + 1) + (e.comment != null ? " · 注释: " + e.comment : ""));
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
                row.setBackgroundResource(R.drawable.row_tv);
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
                    + (c.mbps > 0 ? String.format("%.1f Mbps", c.mbps) : "未测速"));
            return row;
        }
    }
}
