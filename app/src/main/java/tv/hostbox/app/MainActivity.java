package tv.hostbox.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableString;
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

    // 主题调色板（onCreate 时按设置初始化，界面里只引用这些字段）
    private Theme th;
    private int COLOR_BG, COLOR_TEXT, COLOR_HINT, COLOR_ACCENT, COLOR_ON_ACCENT,
            COLOR_FOCUS_BG, COLOR_ON_FOCUS, COLOR_DANGER, COLOR_OK;

    private static final String PREF_THEME = "themeId";
    private static final String PREF_LAST_TAB = "lastTab";

    private Button navHostsBtn, navQrBtn, navCfBtn;
    private LinearLayout panelHosts, panelQr, panelCf;
    private Button btnQrRefresh;
    private Button btnQrService;
    private Button btnHostsRefresh;
    private Button btnHostsAdd;
    private Button[] hostsButtonRow;
    /** 记录光标最后停在按钮行的哪一枚：从列表按「下」回到它，而不是每次都从第一个开始 */
    private int lastHostsBtnIdx = 0;

    /** 底部按键提示：键帽 + 说明，内容随焦点位置变化，解决「遥控器不知道现在能按什么」 */
    private LinearLayout hintRow;
    /** 上一次的提示文案：相同就不重建视图，避免每次焦点变化都重新 inflate 一堆 TextView */
    private String hintRaw = "";

    /** 行尾箭头（只在光标行出现）：亮块上要用浅色，平时那枚留作占位 */
    private Drawable chevOn, chevOff;

    /** 首次拿到窗口焦点时把焦点放到页签上，只做一次 */
    private boolean initialFocusDone = false;

    // Hosts 面板
    private TextView tvRootStatus;
    private ImageView imgRootIcon;
    private SideList hostsList;
    private TextView tvHostsSummary;
    private TextView tvActionResult; // 持久显示写入/删除/恢复结果（Toast 太快会错过）
    private List<HostsManager.Entry> entries = new ArrayList<HostsManager.Entry>();

    // 扫码面板
    private TextView tvQrUrl;
    private TextView tvServerStatus;
    private ImageView imgQr;

    // CF 面板
    private Button btnCfStart;
    private TextView tvCfProgress;
    private TextView tvCfPhase;
    private TextView tvCfPct;
    private Bar barCf;
    private SideList cfList;
    private List<CfOptimizer.Candidate> cfResults = new ArrayList<CfOptimizer.Candidate>();
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

        th = Theme.get(prefs.getInt(PREF_THEME, 0));
        // 调试/截图用：am start ... --ei previewTheme N 可临时预览某套主题（不写入设置）
        int previewTheme = getIntent().getIntExtra("previewTheme", -1);
        if (previewTheme >= 0 && previewTheme < Theme.ALL.length) th = Theme.get(previewTheme);
        COLOR_BG = th.bg;
        COLOR_TEXT = th.text;
        COLOR_HINT = th.hint;
        COLOR_ACCENT = th.accent;
        COLOR_ON_ACCENT = th.onAccent;
        COLOR_FOCUS_BG = th.focusBg;
        COLOR_ON_FOCUS = th.onFocus;
        COLOR_DANGER = th.danger;
        COLOR_OK = th.ok;
        // 窗口底色也跟着主题走，避免启动瞬间闪一下深色
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(th.bg));

        setContentView(R.layout.activity_main);
        navHostsBtn = findViewById(R.id.nav_hosts);
        navQrBtn = findViewById(R.id.nav_qr);
        navCfBtn = findViewById(R.id.nav_cf);
        tvRootStatus = findViewById(R.id.root_text);
        imgRootIcon = findViewById(R.id.root_icon);

        // 顶栏品牌区跟随主题
        findViewById(R.id.root).setBackgroundColor(th.bg);
        TextView brandLogo = findViewById(R.id.brand_logo);
        brandLogo.setBackground(Skin.logo(th));
        brandLogo.setTextColor(th.onAccent);
        ((TextView) findViewById(R.id.brand_title)).setTextColor(th.text);
        ((TextView) findViewById(R.id.brand_sub)).setTextColor(th.hint);
        tvRootStatus.setTextColor(th.hint);
        findViewById(R.id.root_pill).setBackground(Skin.pill(th, th.hint));
        imgRootIcon.setImageDrawable(Icons.drawable(this, Icons.DOT, 14, th.hint));

        findViewById(R.id.hair_line).setBackground(Skin.hairline(th));

        // 底部按键提示：键帽 + 说明，遥控器不用猜现在能按什么
        hintRow = findViewById(R.id.hint_row);
        setHint("←→ 切换页签 · ↓ 进入内容 · OK 选中");
        chevOn = Icons.drawable(this, Icons.CHEVRON, 18, Theme.alpha(th.onFocus, 0.9f));
        chevOff = Icons.drawable(this, Icons.CHEVRON, 18, th.hint);

        // 页签文字色要随「是否有光标」翻转，统一在这里挂钩
        View.OnFocusChangeListener navFocus = new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                applyNavStyle();
                if (hasFocus) setHint("←→ 切换页签 · ↓ 进入内容 · OK 选中");
            }
        };
        navHostsBtn.setOnFocusChangeListener(navFocus);
        navQrBtn.setOnFocusChangeListener(navFocus);
        navCfBtn.setOnFocusChangeListener(navFocus);
        applyNavStyle();

        panelHosts = buildHostsPanel();
        panelQr = buildQrPanel();
        panelCf = buildCfPanel();

        ViewGroup content = findViewById(R.id.content);
        content.addView(panelHosts);
        content.addView(panelQr);
        content.addView(panelCf);

        // 全局记一下光标停在按钮行的哪一枚，从列表往下走时回到同一枚
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(
                new android.view.ViewTreeObserver.OnGlobalFocusChangeListener() {
                    @Override public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                        if (newFocus == null || hostsButtonRow == null) return;
                        for (int i = 0; i < hostsButtonRow.length; i++) {
                            if (hostsButtonRow[i] == newFocus) { lastHostsBtnIdx = i; return; }
                        }
                    }
                });

        navHostsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(0); }
        });
        navQrBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(1); }
        });
        navCfBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(2); }
        });

        // 方向键显式绑定，不依赖 FocusFinder 猜测（否则在页签上按左右可能没反应）
        // 左右移动焦点时直接切到该页签内容，无需再按 OK
        wireNavKeys(navHostsBtn, 0, R.id.nav_qr, -1, 1);
        wireNavKeys(navQrBtn, R.id.nav_hosts, R.id.nav_cf, 0, 2);
        wireNavKeys(navCfBtn, R.id.nav_qr, 0, 1, -1);

        // 首次进入不抢内容区焦点：焦点默认停在「Hosts 管理」页签上
        // 上次停在哪个页签就回到哪个（换主题会重建界面，这样不会跳回第一页）
        int savedTab = prefs.getInt(PREF_LAST_TAB, 0);
        showTab(savedTab >= 0 && savedTab < 3 ? savedTab : 0, false);

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

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // onCreate 里视图还没完成布局，requestFocus 不一定生效；
        // 等第一次真正拿到窗口焦点时再把光标放到页签，避免启动后落到底部「刷新」按钮。
        if (hasFocus && !initialFocusDone) {
            initialFocusDone = true;
            final View tab = currentTab == 1 ? navQrBtn : currentTab == 2 ? navCfBtn : navHostsBtn;
            if (tab != null) {
                // 再 post 一次，确保布局完成后再抢焦点（低端盒子上 onWindowFocusChanged 可能早于首帧）
                tab.post(new Runnable() {
                    @Override public void run() { tab.requestFocus(); }
                });
            }
        }
    }

    // ==================== 页面切换 ====================

    /** 返回键：电视上没有"任务栏"，直接退出容易误关，这里加一次确认 */
    @Override
    public void onBackPressed() {
        newDialog()
                .setTitle("退出电视Host管家？")
                .setMessage("hosts 修改是即时写入的，退出不影响已经生效的内容。")
                .setPositiveButton("退出", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { finish(); }
                })
                .setNegativeButton("再看看", null)
                .show();
    }

    private void showTab(int index) {
        showTab(index, true);
    }

    /**
     * 切换页签。
     * @param moveFocusIntoContent true = 焦点自动落到该页内容区（点 OK 切页签时）；
     *                             false = 保持焦点不动（左右方向键扫过页签时，焦点留在页签上）
     */
    private void showTab(int index, boolean moveFocusIntoContent) {
        currentTab = index;
        panelHosts.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        panelQr.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        panelCf.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        applyNavStyle();
        if (index == 1) refreshQr();
        // 焦点自动落位，避免遥控器"盲按"
        if (moveFocusIntoContent) focusContent(index);
        else setHint("←→ 切换页签 · ↓ 进入内容 · OK 选中");
    }

    /** 把焦点落到当前页签的内容区：列表只进到「框态」，按 OK 才选条目 */
    private boolean focusContent(int index) {
        if (index == 0) {
            if (hostsList == null) return false;
            hostsList.focusBox();
            return true;
        } else if (index == 1) {
            return btnQrRefresh != null && btnQrRefresh.requestFocus();
        } else {
            // CF 页有结果时进列表，没结果时焦点该在「开始优选」上
            if (cfList != null && !cfResults.isEmpty()) {
                cfList.focusBox();
                return true;
            }
            return btnCfStart != null && btnCfStart.requestFocus();
        }
    }

    /**
     * 底部提示条文案。
     *
     * 输入是一句用「·」分段的话，例如 "OK 进入列表 · ↑ 回页签"：
     * 每段开头的按键名（OK / ↑↓ / 返回 …）会被画成一个**键帽**，后面才是说明文字。
     * 这样一屏里"能按什么键"和"按了干什么"是两种视觉元素，扫一眼就能分清；
     * 以前一整行灰字，3 米外基本等于没写。
     */
    private void setHint(String text) {
        if (hintRow == null || text == null) return;
        if (text.equals(hintRaw)) return;
        hintRaw = text;
        hintRow.removeAllViews();

        String[] segs = text.split("\\s*·\\s*");
        int group = 0;
        for (String raw : segs) {
            String seg = raw.trim();
            if (seg.isEmpty()) continue;
            String key = null;
            String label = seg;
            int sp = seg.indexOf(' ');
            if (sp > 0) {
                String head = seg.substring(0, sp);
                if (KEY_TOKENS.contains(head)) {
                    key = head;
                    label = seg.substring(sp + 1).trim();
                }
            } else if (KEY_TOKENS.contains(seg)) {
                key = seg;
                label = "";
            }
            if (key != null) hintRow.addView(newKeyCap(key, group == 0));
            if (!label.isEmpty()) hintRow.addView(newHintLabel(label, group == segs.length - 1));
            group++;
        }
    }

    /** 提示条里认识的按键名；只有它们会被画成键帽，其余整段当普通说明 */
    private static final java.util.HashSet<String> KEY_TOKENS =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "OK", "BACK", "返回", "菜单", "↑", "↓", "←", "→", "←→", "↑↓"));

    private View newKeyCap(String key, boolean first) {
        TextView tv = new TextView(this);
        tv.setText(key);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextColor(th.text);
        tv.setLetterSpacing(0.04f);
        tv.setBackground(Skin.keycap(th));
        tv.setPadding(dp(8), dp(2), dp(8), dp(2));
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(first ? 0 : dp(18), 0, dp(7), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View newHintLabel(String label, boolean last) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(th.hint);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, last ? 0 : dp(12), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 内容区按左右键也能切页签（和页签栏行为一致），焦点跟着回到页签上 */
    private void switchTabByKey(int delta) {
        int next = currentTab + delta;
        if (next < 0 || next > 2) return;
        showTab(next, false);
        View tab = next == 0 ? navHostsBtn : next == 1 ? navQrBtn : navCfBtn;
        if (tab != null) tab.requestFocus();
    }

    /** 从列表往下走出时，落到按钮行的第 idx 个 */
    private void focusButtonRow(Button[] row, int idx) {
        if (row == null || row.length == 0) return;
        int i = Math.max(0, Math.min(idx, row.length - 1));
        if (row[i] != null && row[i].getVisibility() == View.VISIBLE) row[i].requestFocus();
    }

    /**
     * 页签按钮的方向键行为：
     *  - 左右：焦点移到相邻页签，并**立即切换到该页签的内容**（不用再按 OK）；
     *  - 向下：焦点进入当前页签的内容区。
     * 显式处理可避免某些盒子上 FocusFinder 找不到邻居、按键"没反应"的问题。
     */
    private void wireNavKeys(Button b, final int leftId, final int rightId,
                             final int leftTab, final int rightTab) {
        b.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    return focusContent(currentTab);
                }
                if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return false;
                }
                boolean left = keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
                int targetId = left ? leftId : rightId;
                int targetTab = left ? leftTab : rightTab;
                if (targetId == 0) return false;
                View t = findViewById(targetId);
                if (t == null || !t.isFocusable()) return false;
                // 先切内容（不移动焦点），再让焦点留在目标页签上
                if (targetTab >= 0) showTab(targetTab, false);
                t.requestFocus();
                return true;
            }
        });
    }

    /**
     * 页签样式：当前页签 = 强调色实心；光标所在 = 亮块（不管是否当前页）。
     * 文字色必须跟着翻转——亮块上还用浅色字就等于没做焦点提示。
     */
    private void applyNavStyle() {
        styleNav(navHostsBtn, currentTab == 0);
        styleNav(navQrBtn, currentTab == 1);
        styleNav(navCfBtn, currentTab == 2);
    }

    private void styleNav(View v, boolean active) {
        if (!(v instanceof TextView)) return;
        TextView tv = (TextView) v;
        tv.setAllCaps(false);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(null, active ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
        tv.setBackground(Skin.tab(th, active));
        if (tv.isFocused()) {
            tv.setTextColor(COLOR_ON_FOCUS);        // 光标在这儿：亮块 + 深色字
        } else if (active) {
            tv.setTextColor(COLOR_ACCENT);          // 当前页：淡强调底 + 强调色字
        } else {
            tv.setTextColor(COLOR_HINT);
        }
    }

    /**
     * 底部按钮行：左右键显式在按钮间移动（不依赖 FocusFinder），
     * 按「上」回到内容区——如果内容区是列表，直接回到刚才浏览的条目上。
     */
    private void wireButtonRow(final Button[] row, final View upTarget) {
        for (int i = 0; i < row.length; i++) {
            final int idx = i;
            row[i].setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP && upTarget != null) {
                        if (upTarget instanceof SideList) {
                            ((SideList) upTarget).focusEntered();
                            return true;
                        }
                        return upTarget.requestFocus();
                    }
                    int next = -1;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) next = idx - 1;
                    else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) next = idx + 1;
                    if (next < 0 || next >= row.length) return false;
                    return row[next].requestFocus();
                }
            });
        }
    }

    // ==================== Tab 0: Hosts 管理 ====================

    private LinearLayout buildHostsPanel() {
        LinearLayout root = verticalPanel();

        // 标题 + 统计胶囊同行：540dp 高的盒子上，省下这一行就能多显示一条 hosts
        TextView title = new TextView(this);
        styleTitle(title, "Hosts 条目", "OK 进入列表逐条操作，每次改动前自动备份");
        title.setPadding(0, 0, 0, dp(2));

        tvHostsSummary = new TextView(this);
        styleText(tvHostsSummary, COLOR_HINT, 12);
        tvHostsSummary.setGravity(Gravity.CENTER);
        tvHostsSummary.setBackground(Skin.pill(th, th.hint));
        tvHostsSummary.setPadding(dp(12), dp(5), dp(12), dp(5));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lpSum = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpSum.setMargins(dp(12), 0, 0, dp(8));
        head.addView(tvHostsSummary, lpSum);

        hostsList = new HostsSideList();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(12);
        hostsList.setLayoutParams(lp);

        // 操作结果持久显示（Toast 在电视上太短暂），样式上给一块底，和正文区分开
        tvActionResult = new TextView(this);
        styleText(tvActionResult, COLOR_HINT, 13);
        tvActionResult.setVisibility(View.GONE);
        LinearLayout.LayoutParams lpRes = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpRes.topMargin = dp(10);
        tvActionResult.setLayoutParams(lpRes);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button btnAdd = actionButton("添加条目", Icons.PLUS, new View.OnClickListener() {
            @Override public void onClick(View v) { showAddDialog(); }
        });
        // 一行里只留一个"主按钮"：加条目是这一页最高频的操作，用强调色把它抬出来
        stylePrimary(btnAdd);
        btnHostsAdd = btnAdd;
        btnHostsRefresh = actionButton("刷新", Icons.REFRESH, new View.OnClickListener() {
            @Override public void onClick(View v) { loadHostsAsync(); }
        });
        Button btnBackup = actionButton("备份", Icons.CLOCK, new View.OnClickListener() {
            @Override public void onClick(View v) { showBackupList(); }
        });
        Button btnSettings = actionButton("设置", Icons.SLIDERS, new View.OnClickListener() {
            @Override public void onClick(View v) { showSettingsDialog(); }
        });
        hostsButtonRow = new Button[]{btnAdd, btnHostsRefresh, btnBackup, btnSettings};
        addButtons(buttons, hostsButtonRow);
        wireButtonRow(hostsButtonRow, hostsList);

        root.addView(head);
        root.addView(hostsList);
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
                            if (hostsList != null) hostsList.reload();
                            tvHostsSummary.setText("共 " + entries.size() + " 条 · "
                                    + raw.length() + " 字节");
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
                        if (tvRootStatus == null) return;
                        setRootState(ok);
                        if (!ok) toast("未获取 root，写入/删除不可用");
                    }
                });
            }
        }, "check-root").start();
    }

    /**
     * 顶栏 root 状态：图标（对勾/警告）+ 文字 + 颜色，三重编码。
     * 只靠颜色的话，红绿色弱用户、或者电视色彩设置偏了的时候就看不出区别。
     */
    private void setRootState(boolean ok) {
        int c = ok ? COLOR_OK : COLOR_DANGER;
        tvRootStatus.setText(ok ? "root 可用" : "未获取 root");
        tvRootStatus.setTextColor(c);
        findViewById(R.id.root_pill).setBackground(Skin.pill(th, c));
        imgRootIcon.setImageDrawable(Icons.drawable(this, ok ? Icons.CHECK : Icons.ALERT, 14, c));
    }

    /**
     * 条目操作菜单：按下 OK 不再直接判「删除」，先问一句要做什么。
     * 电视上误触 OK 很常见，多一层菜单比多一次「误删 → 恢复备份」便宜得多。
     */
    private void showEntryActions(final HostsManager.Entry e) {
        newDialog()
                .setTitle(e.host + "   →   " + e.ip)
                .setItems(new String[]{"删除这一条", "把这个 IP 用到其他域名"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                if (w == 0) confirmDelete(e);
                                else if (w == 1) showAddDialog(e.ip);
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final HostsManager.Entry e) {        final AlertDialog dlg = newDialog()
                .setTitle("删除该条？")
                .setMessage(e.host + " → " + e.ip)
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        doRemoveAsync(e.host);
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        // 默认焦点放在「取消」上：遥控器误按 OK 不会直接删掉条目
        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override public void onShow(android.content.DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
            }
        });
        styleDialog(dlg);
        dlg.show();
    }

    private void doRemoveAsync(final String domain) {
        toast("正在删除 " + domain + " …");
        new Thread(new Runnable() {
            @Override public void run() {
                final String err = HostsManager.remove(domain);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        showActionResult(err == null,
                                err == null ? "已删除 " + domain : "删除失败：" + err);
                        loadHostsAsync();
                        if (hostsList != null) hostsList.focusEntered();
                    }
                });
            }
        }, "remove-host").start();
    }

    /** 操作结果持久显示在列表下方（成功绿/失败红 + 明确措辞，不只靠颜色） */
    private void showActionResult(boolean ok, String msg) {
        if (tvActionResult != null) {
            tvActionResult.setText(msg);
            tvActionResult.setTextColor(ok ? COLOR_OK : COLOR_DANGER);
            tvActionResult.setBackground(Skin.strip(th));
            tvActionResult.setPadding(dp(12), dp(8), dp(12), dp(8));
            tvActionResult.setVisibility(View.VISIBLE);
        }
        toast(msg);
    }

    private void showAddDialog() {
        showAddDialog(null);
    }

    /** @param prefillIp 非空时预填 IP（"把这个 IP 用到其他域名"入口用） */
    private void showAddDialog(String prefillIp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, 0);

        final EditText etIp = new EditText(this);
        etIp.setHint("IP（如 104.17.142.159）");
        if (prefillIp != null && !prefillIp.isEmpty()) etIp.setText(prefillIp);
        final EditText etDomain = new EditText(this);
        etDomain.setHint("域名");
        etDomain.setText(prefs.getString("lastDomain", ""));
        box.addView(etIp);
        box.addView(etDomain);
        box.addView(buildDomainPicker(etDomain));

        AlertDialog dlg = newDialog()
                .setTitle("添加 hosts 条目")
                .setView(box)
                .setPositiveButton("写入", null) // 点击行为在 show() 后拦截，校验失败不关闭
                .setNegativeButton("取消", null)
                .create();
        styleDialog(dlg);
        dlg.show();
        // IP 已预填时焦点直接落在域名上，省一次按键；否则光标停在 IP 上从头填
        if (prefillIp != null && !prefillIp.isEmpty()) etDomain.requestFocus();
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
                                err == null ? "写入成功：" + domain + " → " + ip + "（原文件已备份）"
                                        : "写入失败：" + err);
                        loadHostsAsync();
                        if (hostsList != null) hostsList.focusEntered();
                    }
                });
            }
        }, "upsert-host").start();
    }

    // ==================== 设置 ====================

    /** 统一设置入口：主题 + 备份上限 + CF 优选参数 */
    private void showSettingsDialog() {
        int keep = prefs.getInt(HostsManager.PREF_MAX_BACKUPS, HostsManager.DEFAULT_MAX_BACKUPS);
        int topN = prefs.getInt(PREF_CF_TOPN, 8);
        int sample = prefs.getInt(PREF_CF_SAMPLE, 320);
        int mb = prefs.getInt(PREF_CF_DOWNLOAD_MB, 6);
        final String[] items = {
                "主题：" + th.name,
                "备份保留数量：" + (keep <= 0 ? "不限制" : keep + " 份"),
                "CF 优选保留条数：" + topN,
                "CF 采样数量：" + sample + (sample == 160 ? "（快）" : sample == 320 ? "（标准）" : "（充分）"),
                "CF 单 IP 测速流量：" + mb + " MB",
        };
        newDialog()
                .setTitle("设置    v" + appVersion())
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (w == 0) pickTheme();
                        else if (w == 1) pickFromOptions("备份保留数量（写入时自动清理）",
                                new String[]{"保留最近 10 份", "保留最近 20 份", "保留最近 50 份", "不限制"},
                                new int[]{10, 20, 50, 0},
                                HostsManager.PREF_MAX_BACKUPS, HostsManager.DEFAULT_MAX_BACKUPS);
                        else if (w == 2) pickFromOptions("CF 优选结果保留前几名",
                                new String[]{"前 5 名", "前 8 名", "前 10 名", "前 15 名"},
                                new int[]{5, 8, 10, 15},
                                PREF_CF_TOPN, 8);
                        else if (w == 3) pickFromOptions("CF 网段采样数量（越多越准越慢）",
                                new String[]{"160 个（快）", "320 个（标准）", "640 个（充分）"},
                                new int[]{160, 320, 640},
                                PREF_CF_SAMPLE, 320);
                        else if (w == 4) pickFromOptions("每个候选 IP 的测速下载量",
                                new String[]{"2 MB（快）", "6 MB（标准）", "12 MB（精确）"},
                                new int[]{2, 6, 12},
                                PREF_CF_DOWNLOAD_MB, 6);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 对话框也跟主题走：浅色主题用浅色对话框，深色主题用深色；
     * 并在 show 之后把窗口底和文字色都换成当前调色板——否则会突然弹出一块 Material 灰，
     * 在一整套自绘界面里像是别的 App 弹出来的。
     */
    private AlertDialog.Builder newDialog() {
        // Builder 没有 setOnShowListener，这里覆写 show() 统一接管样式，
        // 这样所有 newDialog().setTitle(...).show() 的老写法都不用改
        return new AlertDialog.Builder(this, th.light
                ? android.R.style.Theme_Material_Light_Dialog_Alert
                : android.R.style.Theme_Material_Dialog_Alert) {
            @Override public AlertDialog show() {
                AlertDialog d = super.show();
                styleDialog(d);
                return d;
            }
        };
    }

    private void styleDialog(AlertDialog dlg) {
        android.view.Window w = dlg.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(Skin.dialog(th));
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.dimAmount = 0.62f;
        w.setAttributes(lp);
        tintDialogText(w.getDecorView());
    }

    /** 统一弹窗里的文字：正文用主文字色，底部按钮用强调色（和页内主按钮同一套视觉语言） */
    private void tintDialogText(View v) {
        if (v instanceof EditText) {
            EditText et = (EditText) v;
            et.setTextColor(COLOR_TEXT);
            et.setHintTextColor(COLOR_HINT);
            return;
        }
        if (v instanceof Button) {
            ((Button) v).setTextColor(COLOR_ACCENT);
            ((Button) v).setAllCaps(false);
            return;
        }
        if (v instanceof TextView) {
            ((TextView) v).setTextColor(COLOR_TEXT);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) tintDialogText(g.getChildAt(i));
        }
    }

    private String appVersion() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "?" : v;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 换主题：存下选择后重建界面（所有背景都是按调色板现画的，重建即生效） */
    private void pickTheme() {
        newDialog()
                .setTitle("选择主题")
                .setSingleChoiceItems(Theme.names(), th.id,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                d.dismiss();
                                if (w == th.id) return;
                                prefs.edit().putInt(PREF_THEME, w)
                                        .putInt(PREF_LAST_TAB, currentTab).apply();
                                toast("已切换到「" + Theme.get(w).name + "」");
                                new android.os.Handler(getMainLooper()).postDelayed(
                                        new Runnable() {
                                            @Override public void run() { recreate(); }
                                        }, 220);
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickFromOptions(String title, final String[] labels, final int[] values,
                                 final String key, int defValue) {
        int current = prefs.getInt(key, defValue);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) { checked = i; break; }
        }
        final AlertDialog dlg = newDialog()
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        prefs.edit().putInt(key, values[w]).apply();
                        d.dismiss();
                        toast("已设置：" + labels[w]);
                        // 改完回到设置总览，便于连续调多项，不用一层层重新进
                        new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                            @Override public void run() { showSettingsDialog(); }
                        }, 240);
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        // 选项多时滚到当前生效值，遥控器不用自己找
        final int checkedIdx = checked;
        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override public void onShow(android.content.DialogInterface d) {
                if (dlg.getListView() != null && checkedIdx >= 0) {
                    dlg.getListView().setSelection(checkedIdx);
                }
            }
        });
        styleDialog(dlg);
        dlg.show();
    }

    /** hosts.bak.20261029_101010 → 2026-10-29 10:10:10（电视上比原始文件名好认） */
    private String prettyBackupName(String name) {
        String s = name.startsWith("hosts.bak.") ? name.substring("hosts.bak.".length()) : name;
        boolean beforeRestore = s.contains("_before_restore");
        s = s.replace("_before_restore", "");
        if (s.matches("\\d{8}_\\d{6}")) {
            s = s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8)
                    + "   " + s.substring(9, 11) + ":" + s.substring(11, 13)
                    + ":" + s.substring(13, 15);
        }
        return s + (beforeRestore ? "   （恢复前自动备份）" : "");
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
                        final String[] labels = new String[backups.size()];
                        for (int i = 0; i < backups.size(); i++) {
                            labels[i] = prettyBackupName(backups.get(i));
                        }
                        newDialog()
                                .setTitle("选择要恢复的备份（共 " + backups.size() + " 个，新→旧）")
                                .setItems(labels,
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
                                                                err == null ? "已恢复 " + name
                                                                        : "恢复失败：" + err);
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

        TextView title = new TextView(this);
        styleTitle(title, "扫码配置", "手机与盒子连同一个 Wi-Fi，扫码后在网页里增删条目");
        title.setPadding(0, 0, 0, dp(2));

        // 服务状态挪到标题右侧：既省一行高度，又和 hosts 页的"统计胶囊"位置统一
        tvServerStatus = new TextView(this);
        styleText(tvServerStatus, COLOR_HINT, 12);
        tvServerStatus.setGravity(Gravity.CENTER);
        tvServerStatus.setBackground(Skin.pill(th, th.hint));
        tvServerStatus.setPadding(dp(12), dp(6), dp(12), dp(6));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lpStatus = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpStatus.setMargins(dp(12), 0, 0, dp(8));
        head.addView(tvServerStatus, lpStatus);

        imgQr = new ImageView(this);
        // 网址已挪出卡片（做成整行地址栏），卡片里只有二维码，可以放大一档
        int qrSide = (int) Math.min(dp(210),
                getResources().getDisplayMetrics().widthPixels * 0.26f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(qrSide, qrSide);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        imgQr.setLayoutParams(lp);
        imgQr.setBackground(Skin.qrFrame(th));
        imgQr.setPadding(dp(9), dp(9), dp(9), dp(9));

        // 地址栏：整行等宽字展示，绝不随卡片宽度裁字
        tvQrUrl = new TextView(this);
        styleText(tvQrUrl, COLOR_ACCENT, 16);
        tvQrUrl.setGravity(Gravity.CENTER);
        tvQrUrl.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvQrUrl.setMaxLines(1);
        tvQrUrl.setBackground(Skin.card(th));
        tvQrUrl.setPadding(dp(14), dp(10), dp(14), dp(10));

        // 左：二维码卡（竖排居中）；右：三步说明 —— 16:9 上左右分栏比一根竖条更平衡
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(Skin.card(th));
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        card.addView(imgQr);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lpBody = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lpBody.topMargin = dp(12);
        body.setLayoutParams(lpBody);
        LinearLayout.LayoutParams lpCard = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        body.addView(card, lpCard);

        LinearLayout tips = new LinearLayout(this);
        tips.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lpT = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lpT.setMargins(dp(20), 0, 0, 0);
        tips.setLayoutParams(lpT);

        tips.addView(stepRow("1", "手机连上同一个 Wi-Fi", "和盒子在同一个路由器下即可，不用装任何 App"));
        tips.addView(stepRow("2", "扫码打开配置页", "相机扫左侧二维码，或在浏览器里输入上面的网址"));
        tips.addView(stepRow("3", "增删条目，即时生效", "保存后电视这边会提示「手机端已更新 hosts」"));

        View spacer = new View(this);
        tips.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView note = new TextView(this);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setTextColor(COLOR_HINT);
        note.setLineSpacing(dp(3), 1f);
        note.setText("每次写入都会先备份原 hosts，误删可在「备份」里一键恢复。");
        note.setBackground(Skin.strip(th));
        note.setPadding(dp(14), dp(9), dp(14), dp(9));
        tips.addView(note, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        body.addView(tips);

        LinearLayout.LayoutParams lpUrl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpUrl.topMargin = dp(12);
        tvQrUrl.setLayoutParams(lpUrl);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lpBtnRow = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpBtnRow.topMargin = dp(12);
        buttons.setLayoutParams(lpBtnRow);
        btnQrRefresh = actionButton("刷新二维码", Icons.REFRESH, new View.OnClickListener() {
            @Override public void onClick(View v) { refreshQr(); }
        });
        btnQrService = actionButton("启动/停止服务", Icons.POWER, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (ConfigServer.isRunning()) {
                    ConfigServer.stop();
                    refreshQr();
                } else {
                    startServer();
                }
            }
        });
        Button[] row = {btnQrRefresh, btnQrService};
        addButtons(buttons, row);
        // 这一页没有列表，按「上」直接回页签
        wireButtonRow(row, navQrBtn);

        root.addView(head);
        root.addView(body);
        root.addView(tvQrUrl);
        root.addView(buttons);
        return root;
    }

    /** 扫码页的一步：圆形序号 + 主文案 + 补充说明 */
    private View stepRow(String no, String main, String sub) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams lpRow = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpRow.bottomMargin = dp(12);
        row.setLayoutParams(lpRow);

        TextView num = new TextView(this);
        num.setText(no);
        num.setGravity(Gravity.CENTER);
        num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        num.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        num.setTextColor(COLOR_ACCENT);
        num.setBackground(Skin.stepDot(th));
        row.addView(num, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lpCol = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpCol.setMargins(dp(11), 0, 0, 0);
        col.setLayoutParams(lpCol);

        TextView tvMain = new TextView(this);
        tvMain.setText(main);
        tvMain.setTextColor(COLOR_TEXT);
        tvMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvMain.setPadding(0, dp(3), 0, 0);

        TextView tvSub = new TextView(this);
        tvSub.setText(sub);
        tvSub.setTextColor(COLOR_HINT);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvSub.setLineSpacing(dp(2), 1f);

        col.addView(tvMain);
        col.addView(tvSub);
        row.addView(col);
        return row;
    }

    private void startServer() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ConfigServer.start();
                    runOnUiThread(new Runnable() {
                        @Override public void run() { refreshQr(); }
                    });
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
        boolean running = ConfigServer.isRunning();
        if (tvServerStatus != null) {
            tvServerStatus.setText(running
                    ? "配置服务运行中 · 手机需与盒子连同一个 Wi-Fi"
                    : "配置服务已停止 · 先启动服务再扫码");
            tvServerStatus.setTextColor(running ? COLOR_OK : COLOR_DANGER);
            tvServerStatus.setBackground(Skin.pill(th, running ? COLOR_OK : COLOR_DANGER));
        }
        if (btnQrService != null) setBtnText(btnQrService, running ? "停止服务" : "启动服务");

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

        TextView title = new TextView(this);
        styleTitle(title, "Cloudflare 优选", "从官方网段采样 → 延迟测试 → 下载测速，挑出最快的几个 IP");
        title.setPadding(0, 0, 0, dp(2));

        // 进度卡：阶段名 + 百分比 + 进度条 + 细节。原来只有一行 "延迟 12/320"，
        // 3 米外根本判断不出"还要等多久"，进度条是电视上最省认知的一眼信息。
        LinearLayout progCard = new LinearLayout(this);
        progCard.setOrientation(LinearLayout.VERTICAL);
        progCard.setBackground(Skin.card(th));
        progCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams lpProg = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpProg.topMargin = dp(12);
        progCard.setLayoutParams(lpProg);

        LinearLayout progHead = new LinearLayout(this);
        progHead.setOrientation(LinearLayout.HORIZONTAL);
        progHead.setGravity(Gravity.CENTER_VERTICAL);

        tvCfPhase = new TextView(this);
        tvCfPhase.setText("尚未开始");
        tvCfPhase.setTextColor(COLOR_TEXT);
        tvCfPhase.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvCfPhase.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        tvCfPct = new TextView(this);
        tvCfPct.setText("—");
        tvCfPct.setTextColor(COLOR_HINT);
        tvCfPct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvCfPct.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvCfPct.setGravity(Gravity.END);

        progHead.addView(tvCfPhase, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        progHead.addView(tvCfPct, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        progCard.addView(progHead);

        barCf = new Bar(this, Theme.alpha(th.border, 0.9f), COLOR_ACCENT);
        LinearLayout.LayoutParams lpBar = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        lpBar.topMargin = dp(10);
        progCard.addView(barCf, lpBar);

        tvCfProgress = new TextView(this);
        styleText(tvCfProgress, COLOR_HINT, 12);
        tvCfProgress.setLineSpacing(dp(3), 1f);
        tvCfProgress.setText("点下方「开始优选」开始。完成后在结果里选一条按 OK，即可写进指定域名的 hosts。");
        LinearLayout.LayoutParams lpDet = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpDet.topMargin = dp(8);
        progCard.addView(tvCfProgress, lpDet);

        cfList = new CfSideList();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(12);
        cfList.setLayoutParams(lp);

        // 主 CTA 放底部按钮行（与 Hosts 页「添加条目」同构）：
        // 之前挤在标题行右侧，标题比按钮矮，按钮上沿被父容器裁掉——层级和高度都不可控
        btnCfStart = actionButton("开始优选", Icons.PLAY, new View.OnClickListener() {
            @Override public void onClick(View v) { startCfOptimize(); }
        });
        stylePrimary(btnCfStart);
        LinearLayout buttons = new LinearLayout(this);
        addButtons(buttons, new Button[]{btnCfStart});
        wireButtonRow(new Button[]{btnCfStart}, navCfBtn);

        root.addView(title);
        root.addView(progCard);
        root.addView(cfList);
        root.addView(buttons);
        return root;
    }

    /** 极简进度条：6dp 圆角轨道 + 强调色填充，比一行数字更直观 */
    private static class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int trackColor;
        private final int fillColor;
        private float progress = 0f;

        Bar(Context c, int trackColor, int fillColor) {
            super(c);
            this.trackColor = trackColor;
            this.fillColor = fillColor;
        }

        void setProgress(float p) {
            this.progress = Math.max(0f, Math.min(1f, p));
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = h / 2f;
            paint.setColor(trackColor);
            canvas.drawRoundRect(0, 0, w, h, r, r, paint);
            if (progress > 0f) {
                paint.setColor(fillColor);
                canvas.drawRoundRect(0, 0, Math.max(h, w * progress), h, r, r, paint);
            }
        }
    }

    private void startCfOptimize() {
        if (cfRunning) {
            // 进行中再按 = 取消
            if (cfOpt != null) cfOpt.cancel();
            setBtnText(btnCfStart, "正在取消…");
            return;
        }
        cfRunning = true;
        setBtnText(btnCfStart, "取消优选");
        cfResults.clear();
        if (cfList != null) cfList.resetList();
        if (tvCfPhase != null) tvCfPhase.setText("采样网段");
        if (tvCfPct != null) tvCfPct.setText("0%");
        if (barCf != null) barCf.setProgress(0f);

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
                                    String label = phase.equals("sample") ? "采样网段" :
                                            phase.equals("latency") ? "延迟测试" :
                                            phase.equals("speed") ? "下载测速" : "已完成";
                                    float f = overallProgress(phase, done, total);
                                    if (tvCfPhase != null) tvCfPhase.setText(label);
                                    if (tvCfPct != null) {
                                        tvCfPct.setText(Math.round(f * 100) + "%   " + done + "/" + total);
                                    }
                                    if (barCf != null) barCf.setProgress(f);
                                    tvCfProgress.setText(detail);
                                }
                            });
                        }
                    });
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            cfResults.clear();
                            cfResults.addAll(result);
                            if (cfList != null) cfList.resetList();
                            if (tvCfPhase != null) tvCfPhase.setText(result.isEmpty() ? "没有可用结果" : "优选完成");
                            if (tvCfPct != null) tvCfPct.setText(result.size() + " 个可用");
                            if (barCf != null) barCf.setProgress(1f);
                            tvCfProgress.setText("选中某条按 OK，即可写进指定域名的 hosts。");
                            // 结果出来后焦点直接进列表，少按一次方向键
                            if (!result.isEmpty() && cfList != null) cfList.focusEntered();
                        }
                    });
                } catch (final InterruptedException e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (tvCfPhase != null) tvCfPhase.setText("已取消");
                            if (tvCfPct != null) tvCfPct.setText("—");
                            tvCfProgress.setText("已取消优选，已完成的进度不会写入 hosts。");
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (tvCfPhase != null) tvCfPhase.setText("优选失败");
                            if (tvCfPct != null) tvCfPct.setText("—");
                            tvCfProgress.setText("失败原因：" + e);
                        }
                    });
                } finally {
                    cfRunning = false;
                    cfOpt = null;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            btnCfStart.setEnabled(true);
                            setBtnText(btnCfStart, "开始优选");
                        }
                    });
                }
            }
        }, "cf-optimize").start();
    }

    /**
     * 三段任务折算成一条总进度：采样 15% → 延迟 40% → 测速 45%。
     * 各阶段耗时差很大，直接用 done/total 会让进度条"跳"和"卡"，按权重折算更接近真实等待感。
     */
    private float overallProgress(String phase, int done, int total) {
        float f = total <= 0 ? 0f : Math.min(1f, done / (float) total);
        if (phase.equals("sample")) return 0.15f * f;
        if (phase.equals("latency")) return 0.15f + 0.40f * f;
        if (phase.equals("speed")) return 0.55f + 0.45f * f;
        return 1f;
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

        AlertDialog dlg = newDialog()
                .setTitle("将优选 IP 写入 hosts")
                .setView(box)
                .setPositiveButton("写入", null) // show() 后拦截，校验失败不关闭
                .setNegativeButton("取消", null)
                .create();
        styleDialog(dlg);
        dlg.show();
        // 域名已预填时直接聚焦「写入」，避免遥控器一按 OK 就弹出软键盘挡住半屏
        if (etDomain.getText().toString().trim().isEmpty()) {
            etDomain.requestFocus();
        } else {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        }
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
            chip.setTextColor(COLOR_TEXT);
            chip.setBackground(Skin.button(th));
            chip.setStateListAnimator(null);
            chip.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            chip.setSingleLine(true);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setOnFocusChangeListener(textFlipper(COLOR_TEXT));
            chip.setPadding(dp(12), 0, dp(12), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140));
        lp.topMargin = dp(6);
        sv.setLayoutParams(lp);
        return sv;
    }

    private LinearLayout verticalPanel() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 左右边距已经在根布局统一给了 44dp 安全边距，这里只补上下呼吸空间
        root.setPadding(0, dp(12), 0, dp(4));
        root.setBackgroundColor(COLOR_BG);
        return root;
    }

    /** 焦点变化时翻转文字色：亮块上必须用深色字，否则等于没有焦点提示 */
    private View.OnFocusChangeListener textFlipper(final int normalColor) {
        return new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(hasFocus ? COLOR_ON_FOCUS : normalColor);
                }
            }
        };
    }

    private Button actionButton(String text, View.OnClickListener listener) {
        return actionButton(text, null, listener);
    }

    /** 图标 + 文字的 Spannable：ImageSpan 让图标真正跟着文字居中（compound drawable 会贴边） */
    private static CharSequence iconText(Drawable icon, String text) {
        SpannableString ss = new SpannableString("\u2002" + text);
        if (icon != null) {
            icon.setBounds(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
            ss.setSpan(new android.text.style.ImageSpan(icon, android.text.style.ImageSpan.ALIGN_BOTTOM),
                    0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    /** 带图标的按钮文字更新（setText 会把图标冲掉，必须重建 Spannable） */
    private void setBtnText(Button b, String text) {
        Object tag = b.getTag();
        b.setText(iconText(tag instanceof android.graphics.drawable.Drawable
                ? (android.graphics.drawable.Drawable) tag : null, text));
    }

    /** 带图标的按钮：图标用 Icons 现画，光标上来时和文字一起翻色 */
    private Button actionButton(String text, String iconKind, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(COLOR_TEXT);
        b.setBackground(Skin.button(th));
        b.setStateListAnimator(null);
        if (iconKind != null) {
            android.graphics.drawable.Drawable icon =
                    Icons.drawable(this, iconKind, 18, COLOR_HINT);
            b.setTag(icon);
            b.setText(iconText(icon, text));
        } else {
            b.setText(text);
        }
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                Button bb = (Button) v;
                bb.setTextColor(hasFocus ? COLOR_ON_FOCUS : COLOR_TEXT);
                tintIcons(bb, hasFocus ? COLOR_ON_FOCUS : COLOR_HINT);
                if (hasFocus) setHint("OK 执行 · ←→ 换一个按钮 · ↑ 回列表");
            }
        });
        // 去掉 Button 默认的左右内缩和最小宽度，否则等宽排布时文字会被裁
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        return b;
    }

    /** 图标跟着文字一起翻色（焦点块是亮色，图标还留着灰的就糊了） */
    private void tintIcons(Button b, int color) {
        Object tag = b.getTag();
        if (tag instanceof android.graphics.drawable.Drawable) {
            ((android.graphics.drawable.Drawable) tag).setTint(color);
            b.invalidate();
        }
    }

    /** 主操作按钮：平时是强调色渐变底（唯一"彩色"的按钮），有光标时同样变亮块 */
    private void stylePrimary(Button b) {
        b.setBackground(Skin.primary(th));
        b.setTextColor(COLOR_ON_ACCENT);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tintIcons(b, COLOR_ON_ACCENT);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                Button bb = (Button) v;
                bb.setTextColor(hasFocus ? COLOR_ON_FOCUS : COLOR_ON_ACCENT);
                tintIcons(bb, hasFocus ? COLOR_ON_FOCUS : COLOR_ON_ACCENT);
                if (hasFocus) setHint("OK 开始测速（再次按下可取消）");
            }
        });
    }

    /** 一排等宽按钮，间距一致；电视上竖排按钮容易"看不清点哪个"，等宽更整齐 */
    private void addButtons(LinearLayout row, Button[] buttons) {
        for (int i = 0; i < buttons.length; i++) {
            Button b = buttons[i];
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, dp(52), 1f);
            // 只在按钮之间留缝，左右边缘与内容区对齐
            lp.setMargins(i == 0 ? 0 : dp(10), 0, 0, 0);
            row.addView(b, lp);
        }
    }

    /**
     * 页内标题：主标题 + 一行说明。
     * 字号只有 4 档（26 / 20 / 16 / 12-13），不再出现 17、18、19、21、22 这种"每处都不一样"的碎片化，
     * 远看时层级才立得住。
     */
    private void styleTitle(TextView tv, String main, String sub) {
        android.text.SpannableStringBuilder sb =
                new android.text.SpannableStringBuilder(main + "\n" + sub);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0, main.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.5f),
                main.length() + 1, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.ForegroundColorSpan(COLOR_HINT),
                main.length() + 1, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(sb);
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        tv.setLineSpacing(dp(6), 1f);
        tv.setPadding(0, 0, 0, dp(2));
    }

    private void styleText(TextView tv, int color, int sp) {
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setPadding(0, dp(2), 0, dp(2));
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

    // ==================== 两态列表（电视端核心交互） ====================

    /**
     * 列表有「框态」和「条目态」两种状态，遥控器上下键不会被长长的列表困住：
     *
     *   框态：光标在列表框外框上（描边变强调色），↑ 回页签、↓ 直接到下方按钮行、OK 进入条目态；
     *   条目态：光标落在具体某一行（整行亮块），↑↓ 移动、OK 执行、BACK 或 ← 退回框态。
     *
     * 注意列表本身 setFocusable(false)：所有按键都由这一层显式处理，
     * 不依赖 FocusFinder / ListView 的选中机制，盒子上行为完全确定。
     * 长按↑↓会逐级加速（1→2→4→8 行），几十条 hosts 也不用一格一格挪。
     */
    private abstract class SideList extends android.widget.FrameLayout implements View.OnKeyListener {

        final ListView node;
        int selPos = 0;
        boolean entered = false;

        private boolean hasFocus = false;
        private long lastMoveAt = 0;
        private int streak = 0;
        private boolean lastUp = false;

        private final int mainSp, subSp;

        SideList(int mainSp, int subSp, String emptyText) {
            super(MainActivity.this);
            this.mainSp = mainSp;
            this.subSp = subSp;
            setFocusable(true);
            setFocusableInTouchMode(false);
            setPadding(dp(7), dp(7), dp(7), dp(7));
            setClipToOutline(true);
            setBackground(Skin.card(th));

            node = new ListView(MainActivity.this);
            node.setFocusable(false);
            node.setFocusableInTouchMode(false);
            node.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
            node.setDivider(null);
            node.setDividerHeight(0);
            node.setVerticalScrollBarEnabled(false);
            node.setAdapter(buildAdapter());
            addView(node, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView emptyView = new TextView(MainActivity.this);
            styleText(emptyView, COLOR_HINT, 16);
            emptyView.setGravity(Gravity.CENTER);
            emptyView.setLineSpacing(dp(5), 1f);
            emptyView.setText(emptyText);
            addView(emptyView, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            node.setEmptyView(emptyView);

            // 触摸点击仍然可用（手机/鼠标接盒子时）
            node.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                    selPos = pos;
                    notifyRows();
                    onActivate(pos);
                }
            });
            setOnKeyListener(this);
            setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean f) {
                    hasFocus = f;
                    refreshChrome();
                    updateHint();
                }
            });
        }

        // ---- 子类实现 ----
        abstract int count();
        abstract void bindRow(RowHolder h, int position);
        abstract void onActivate(int position);
        abstract void onLeaveUp();
        abstract void onLeaveDown();
        int idleColorFor(int position) { return COLOR_TEXT; }

        // ---- 焦点进出 ----

        /** 框态：焦点停在列表外框，按 OK 才进条目 */
        void focusBox() {
            entered = false;
            clampSel();
            refreshChrome();
            requestFocus();
            scrollTo(selPos);
        }

        /** 条目态：直接落到记忆的那一行（从下方按钮行按「上」回来时用） */
        void focusEntered() {
            if (count() == 0) { focusBox(); return; }
            entered = true;
            clampSel();
            refreshChrome();
            requestFocus();
            scrollTo(selPos);
        }

        private void exitToBox() {
            entered = false;
            refreshChrome();
            updateHint();
        }

        private void refreshChrome() {
            setBackground(hasFocus && !entered ? Skin.cardFocus(th) : Skin.card(th));
            notifyRows();
        }

        private void notifyRows() {
            android.widget.BaseAdapter a = (android.widget.BaseAdapter) node.getAdapter();
            if (a != null) a.notifyDataSetChanged();
        }

        /** 数据变了（刷新/删除/写入之后）：保持选中位置但纠正越界 */
        void reload() {
            clampSel();
            notifyRows();
        }

        /** 换了一批全新结果：回到第一行、退回框态 */
        void resetList() {
            selPos = 0;
            entered = false;
            refreshChrome();
        }

        private void updateHint() {
            if (!hasFocus) return;
            if (entered) {
                setHint("第 " + (selPos + 1) + " / " + count()
                        + " 条 · ↑↓ 浏览（长按加速）· OK 操作 · 返回 退出列表");
            } else if (count() == 0) {
                setHint(emptyHint());
            } else {
                setHint("OK 进入列表（共 " + count() + " 条）· ↓ 直达按钮 · ↑ " + leaveUpLabel());
            }
        }

        /** 框态按「上」会去哪里：hosts 是回页签，CF 页是回到顶部按钮 */
        String leaveUpLabel() { return "回页签"; }

        /** 空列表时的提示：两个列表的下一步动作不一样 */
        String emptyHint() { return "列表是空的 · OK 添加条目 · ↑ 回页签"; }

        private void clampSel() {
            int n = count();
            if (n == 0) { selPos = 0; return; }
            if (selPos > n - 1) selPos = n - 1;
            if (selPos < 0) selPos = 0;
        }

        private void scrollTo(final int pos) {
            node.post(new Runnable() {
                @Override public void run() { node.setSelection(pos); }
            });
        }

        /** 连续按方向键时逐级加速，长列表不用一格一格挪 */
        private int stepFor(boolean up) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastMoveAt < 300 && up == lastUp) streak++;
            else streak = 0;
            lastMoveAt = now;
            lastUp = up;
            if (streak < 4) return 1;
            if (streak < 9) return 2;
            if (streak < 16) return 4;
            return 8;
        }

        @Override public boolean onKey(View v, int keyCode, KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN: {
                    if (!down) return true;
                    boolean up = keyCode == KeyEvent.KEYCODE_DPAD_UP;
                    if (!entered) {
                        // 框态：上下键直接离开列表，不再逐行穿过整个列表
                        if (up) onLeaveUp(); else onLeaveDown();
                        return true;
                    }
                    int n = count();
                    if (n == 0) { if (up) onLeaveUp(); else onLeaveDown(); return true; }
                    int next = selPos + (up ? -stepFor(true) : stepFor(false));
                    if (next < 0) { selPos = 0; onLeaveUp(); return true; }
                    if (next > n - 1) { selPos = n - 1; onLeaveDown(); return true; }
                    selPos = next;
                    scrollTo(selPos);
                    notifyRows();
                    updateHint();
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER: {
                    if (!down) return true;
                    if (!entered) {
                        if (count() == 0) { onActivate(-1); return true; }
                        entered = true;
                        clampSel();
                        refreshChrome();
                        scrollTo(selPos);
                        updateHint();
                        return true;
                    }
                    if (selPos >= 0 && selPos < count()) onActivate(selPos);
                    return true;
                }
                case KeyEvent.KEYCODE_BACK: {
                    if (!down) return true;
                    if (entered) { exitToBox(); return true; }
                    return false;   // 框态交给 Activity（退出确认）
                }
                case KeyEvent.KEYCODE_DPAD_LEFT: {
                    if (!down) return true;
                    if (entered) { exitToBox(); return true; }
                    switchTabByKey(-1);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    if (!down) return true;
                    if (entered) return true;   // 条目态不切页，避免误触换页
                    switchTabByKey(1);
                    return true;
                }
            }
            return false;
        }

        android.widget.BaseAdapter buildAdapter() {
            return new android.widget.BaseAdapter() {
                @Override public int getCount() { return count(); }
                @Override public Object getItem(int position) { return null; }
                @Override public long getItemId(int position) { return position; }
                @Override public View getView(int position, View convertView, ViewGroup parent) {
                    RowHolder h = convertView == null ? newRowHolder(mainSp, subSp)
                            : (RowHolder) convertView.getTag();
                    h.num.setText(String.valueOf(position + 1));
                    bindRow(h, position);
                    styleRow(h, position == selPos, entered && hasFocus, idleColorFor(position));
                    return h.row;
                }
            };
        }
    }

    private class HostsSideList extends SideList {
        HostsSideList() {
            super(18, 13, "这里还没有 hosts 条目\n\n按下方「添加条目」新增，或用手机扫码批量编辑");
        }
        @Override int count() { return entries.size(); }
        @Override void bindRow(RowHolder h, int position) {
            HostsManager.Entry e = entries.get(position);
            h.main.setText(e.host);
            String sub = e.ip + "   ·   第 " + (e.lineIndex + 1) + " 行";
            if (e.comment != null && !e.comment.isEmpty()) sub += "   ·   " + e.comment;
            h.sub.setText(sub);
        }
        @Override void onActivate(int position) {
            if (position < 0) { showAddDialog(); return; }   // 空列表按 OK = 添加
            if (position < entries.size()) showEntryActions(entries.get(position));
        }
        @Override void onLeaveUp() { if (navHostsBtn != null) navHostsBtn.requestFocus(); }
        @Override void onLeaveDown() { focusButtonRow(hostsButtonRow, lastHostsBtnIdx); }
    }

    private class CfSideList extends SideList {
        CfSideList() {
            super(20, 15, "还没有优选结果\n\n按上方「开始优选」，等延迟和速度都跑完");
        }
        @Override int count() { return cfResults.size(); }
        @Override int idleColorFor(int position) {
            return cfResults.get(position).mbps > 0 ? COLOR_TEXT : COLOR_HINT;
        }
        @Override void bindRow(RowHolder h, int position) {
            CfOptimizer.Candidate c = cfResults.get(position);
            boolean speedOk = c.mbps > 0;
            h.main.setText(c.ip);
            String speed = speedOk ? String.format("%.1f Mbps", c.mbps) : "未测速";
            h.sub.setText("延迟 " + c.rttMs + " ms   ·   " + speed
                    + (speedOk ? "" : "（仅延迟可用，可先试用）"));
        }
        @Override void onActivate(int position) {
            if (position >= 0 && position < cfResults.size()) showApplyDialog(cfResults.get(position));
        }
        @Override void onLeaveUp() { if (btnCfStart != null) btnCfStart.requestFocus(); }
        @Override void onLeaveDown() { if (btnCfStart != null) btnCfStart.requestFocus(); }
        @Override String leaveUpLabel() { return "回到开始按钮"; }
        @Override String emptyHint() { return "还没有优选结果 · ↑ 回到开始按钮"; }
    }

    // ==================== Adapter ====================

    /** 列表行骨架：左侧选中竖条 + 序号徽章 + 两行文字（两个列表共用） */
    private static final class RowHolder {
        LinearLayout row;
        TextView num;
        TextView main;
        TextView sub;
        View bar;
        ImageView chev;
    }

    private RowHolder newRowHolder(int mainSp, int subSp) {
        RowHolder h = new RowHolder();
        h.row = new LinearLayout(this);
        h.row.setOrientation(LinearLayout.HORIZONTAL);
        // 行高下限 60dp：10-foot 场景下 40dp 的行距会让上下两行"粘"在一起
        h.row.setMinimumHeight(dp(56));
        h.row.setGravity(Gravity.CENTER_VERTICAL);

        h.bar = new View(this);
        LinearLayout.LayoutParams lpBar = new LinearLayout.LayoutParams(
                dp(3), dp(34));
        lpBar.setMargins(dp(9), 0, dp(11), 0);
        h.row.addView(h.bar, lpBar);

        // 行首序号：长列表里"第几条"比纯文字更好定位
        h.num = new TextView(this);
        h.num.setGravity(Gravity.CENTER);
        h.num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        h.num.setTextColor(COLOR_HINT);
        h.num.setBackground(Skin.badge(th, false));
        h.num.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams lpNum = new LinearLayout.LayoutParams(dp(26), dp(26));
        lpNum.setMargins(0, 0, dp(11), 0);
        h.row.addView(h.num, lpNum);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), dp(8), dp(8));

        h.main = new TextView(this);
        styleText(h.main, COLOR_TEXT, mainSp);
        h.main.setSingleLine(true);
        h.main.setEllipsize(android.text.TextUtils.TruncateAt.END);

        h.sub = new TextView(this);
        styleText(h.sub, COLOR_HINT, subSp);
        h.sub.setSingleLine(true);
        h.sub.setEllipsize(android.text.TextUtils.TruncateAt.END);

        content.addView(h.main);
        content.addView(h.sub);
        h.row.addView(content, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 行尾箭头：只在"光标真正停在这一行"时出现，暗示"按 OK 还有下一步"
        h.chev = new ImageView(this);
        h.chev.setImageDrawable(Icons.drawable(this, Icons.CHEVRON, 18, COLOR_HINT));
        h.chev.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams lpChev = new LinearLayout.LayoutParams(dp(18), dp(18));
        lpChev.setMargins(0, 0, dp(12), 0);
        h.row.addView(h.chev, lpChev);

        h.row.setTag(h);
        return h;
    }

    /**
     * 行样式：列表有焦点时选中行整体变亮块（光标位置一眼可见），
     * 列表没焦点时只用淡强调色底 + 左侧竖条表示"记住的选中项"。
     */
    private void styleRow(RowHolder h, boolean selected, boolean strong, int idleMainColor) {
        boolean hot = selected && strong;
        if (selected) {
            h.row.setBackground(Skin.row(th, true, strong));
            h.bar.setVisibility(View.VISIBLE);
            h.bar.setBackground(Skin.rowBar(th, true, strong));
            h.main.setTextColor(hot ? COLOR_ON_FOCUS : COLOR_ACCENT);
            h.sub.setTextColor(hot ? Theme.alpha(COLOR_ON_FOCUS, 0.78f) : COLOR_HINT);
            h.num.setBackground(Skin.badge(th, hot));
            h.num.setTextColor(hot ? COLOR_ON_FOCUS : COLOR_ACCENT);
        } else {
            h.row.setBackground(null);
            h.bar.setVisibility(View.INVISIBLE);
            h.main.setTextColor(idleMainColor);
            h.sub.setTextColor(COLOR_HINT);
            h.num.setBackground(Skin.badge(th, false));
            h.num.setTextColor(COLOR_HINT);
        }
        h.chev.setVisibility(hot ? View.VISIBLE : View.INVISIBLE);
        h.chev.setImageDrawable(hot ? chevOn : chevOff);
    }

}
