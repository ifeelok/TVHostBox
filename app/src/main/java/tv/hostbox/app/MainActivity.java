package tv.hostbox.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import java.io.File;
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
    /** 跑完之后在「结果列表 / 测试过程」之间来回切 */
    private Button btnCfPeek;
    private boolean cfViewLive = false;   // 当前正看着测试过程表（回顾态）
    private boolean cfHasRun = false;     // 至少跑过一次，才有过程可回看
    private boolean cfFinished = false;   // 本次已结束（完成 / 取消 / 失败）
    private TextView tvCfProgress;
    private TextView tvCfPhase;
    private TextView tvCfPct;
    private Bar barCf;
    private SideList cfList;
    private List<CfOptimizer.Candidate> cfResults = new ArrayList<CfOptimizer.Candidate>();
    private volatile boolean cfRunning = false;
    private volatile CfOptimizer cfOpt = null; // 当前优选任务（用于取消）

    // ---- CF 实时测试面板：跑的时候用来「看见过程」，跑完再切回结果列表 ----
    private LinearLayout cfLive;
    private TextView cfLiveTitle;
    private TextView cfLiveStat;
    private TextView cfLiveFoot;
    private final List<LiveRow> liveRows = new ArrayList<LiveRow>();
    private float uiScaleCache = -1f;                  // uiScale() 的缓存（每次 onCreate 归零）
    private android.widget.ScrollView liveScroll;      // 过程表的滚动容器（行数多于可见高度时用）
    private String lastPhaseRendered = "";             // 阶段切换时把表滚回顶部
    /** 延迟阶段：已通的那些 IP（按延迟升序展示） */
    private final List<CfOptimizer.Candidate> liveLat = new ArrayList<CfOptimizer.Candidate>();
    /** 测速阶段：候选池，mbps = -2 表示"正在测"，-1 失败，>0 为实测值 */
    private final List<CfOptimizer.Candidate> liveSpeed = new ArrayList<CfOptimizer.Candidate>();
    private final Object liveLock = new Object();
    private String livePhase = "";
    private int liveDone = 0, liveTotal = 0;
    private String liveLast = "";
    private boolean liveRenderQueued = false;

    /** 主线程渲染句柄：过程回调非常密集（采样 320 个 IP），必须限流后再画 */
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable liveRenderTask = new Runnable() {
        @Override public void run() {
            liveRenderQueued = false;
            renderLiveBoard();
        }
    };

    // 设置项 key（CF 参数）
    private static final String PREF_CF_TOPN = "cfTopN";        // 保留前几名，默认 8
    private static final String PREF_CF_SAMPLE = "cfSample";    // 采样数量，默认 320
    private static final String PREF_CF_DOWNLOAD_MB = "cfDownloadMB"; // 测速大小 MB，默认 6
    private static final String PREF_AUTO_UPDATE = "autoUpdate"; // 启动时自动检查升级，默认开

    // 升级
    private TextView tvUpdatePill;
    private volatile boolean updateChecking = false;
    private AlertDialog updateDlg;

    private SharedPreferences prefs;
    private int currentTab = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppContextHolder.init(this);
        uiScaleCache = -1f;
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
        applyLayoutScale();
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
        imgRootIcon.setImageDrawable(Icons.drawable(this, Icons.DOT, iconDp(14), th.hint));

        findViewById(R.id.hair_line).setBackground(Skin.hairline(th));

        // 顶栏「有新版本」小胶囊：只在新版可用时显示，起提示作用（入口在「检查更新」按钮）
        tvUpdatePill = findViewById(R.id.update_pill);
        tvUpdatePill.setTextColor(COLOR_ACCENT);
        tvUpdatePill.setBackground(Skin.pill(th, th.accent));

        // 底部按键提示：键帽 + 说明，遥控器不用猜现在能按什么
        hintRow = findViewById(R.id.hint_row);
        setHint("←→ 切换页签 · ↓ 进入内容 · OK 选中");
        chevOn = Icons.drawable(this, Icons.CHEVRON, iconDp(18), Theme.alpha(th.onFocus, 0.9f));
        chevOff = Icons.drawable(this, Icons.CHEVRON, iconDp(18), th.hint);

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

        // 自动检查升级：延后几秒，先让界面和 hosts 读完，别和启动抢网络/CPU
        if (prefs.getBoolean(PREF_AUTO_UPDATE, true)) {
            ui.postDelayed(new Runnable() {
                @Override public void run() { checkUpdateAsync(true); }
            }, 3500);
        }
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
            // 回看中：回到过程表本身（它有框态焦点），↑ 一次是表、再一次才是页签
            if (cfViewLive && cfLive != null && cfLive.getVisibility() == View.VISIBLE) {
                return cfLive.requestFocus();
            }
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
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(12));
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextColor(th.text);
        tv.setLetterSpacing(0.04f);
        tv.setBackground(Skin.keycap(th));
        tv.setPadding(dp(9), dp(3), dp(9), dp(3));
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
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(14));
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
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(16));
        tv.setTypeface(null, active ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
        // Button 自带 88dp 最小宽/高，会把紧凑页签重新撑大，必须清掉
        tv.setMinWidth(0);
        tv.setMinimumWidth(0);
        tv.setMinHeight(0);
        tv.setMinimumHeight(0);
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
        wireButtonRow(row, upTarget, null);
    }

    /** upOverride 非空时，「上」交给它决定去哪（CF 页要按回看/结果/运行中分流） */
    private void wireButtonRow(final Button[] row, final View upTarget, final Runnable upOverride) {
        for (int i = 0; i < row.length; i++) {
            final int idx = i;
            row[i].setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        if (upOverride != null) { upOverride.run(); return true; }
                        if (upTarget == null) return false;
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
                    // 隐藏的按钮（比如跑任务时收起的「查看测试过程」）不能吃焦点
                    if (row[next].getVisibility() != View.VISIBLE) return false;
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
        // 主按钮的提示条读 contentDescription：以前没设，光标停在「添加条目」上会显示
        // "OK 开始测速…"（从 CF 页抄过来的默认文案），现在改成这一页该说的话
        btnAdd.setContentDescription("OK 添加条目 · ←→ 换一个按钮 · ↑ 回列表");
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
        // 「检查更新」挪进设置弹窗（整页重排后它有了固定座位）：按钮行只留这一页最高频的四件事
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
        imgRootIcon.setImageDrawable(Icons.drawable(this, ok ? Icons.CHECK : Icons.ALERT, iconDp(14), c));
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
    /**
     * 设置页：整版重排成「分区标题 + 设置行」的 TV 列表，不再是一列等宽纯文字。
     * 每行左侧是设置名、右侧是当前值，光标停上 = 亮块（和页内按钮同一套视觉语言），
     * OK 进入对应的单选/动作。「检查更新」也从 Hosts 页按钮行挪到了这里——
     * 更新是低频动作，占按钮行一整格太奢侈。
     */
    private void showSettingsDialog() {
        int keep = prefs.getInt(HostsManager.PREF_MAX_BACKUPS, HostsManager.DEFAULT_MAX_BACKUPS);
        int topN = prefs.getInt(PREF_CF_TOPN, 8);
        int sample = prefs.getInt(PREF_CF_SAMPLE, 320);
        int mb = prefs.getInt(PREF_CF_DOWNLOAD_MB, 6);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(us(36), us(6), us(36), us(20));

        // 7 个设置项在电视上一屏放不下：弹窗高度按屏幕比例限死，内容自己滚，
        // 否则底部行和「关闭」会被压出屏幕（真机截图上就是这么糊掉的）
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(box, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        settingsSection(box, "外观", true);
        View first = settingRow(box, "主题", th.name, new View.OnClickListener() {
            @Override public void onClick(View v) { pickTheme(); }
        });
        settingRow(box, "备份保留数量",
                keep <= 0 ? "不限制" : keep + " 份", new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickFromOptions("备份保留数量（写入时自动清理）",
                        new String[]{"保留最近 10 份", "保留最近 20 份", "保留最近 50 份", "不限制"},
                        new int[]{10, 20, 50, 0},
                        HostsManager.PREF_MAX_BACKUPS, HostsManager.DEFAULT_MAX_BACKUPS);
            }
        });

        settingsSection(box, "Cloudflare 优选", false);
        settingRow(box, "结果保留条数", "前 " + topN + " 名", new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickFromOptions("CF 优选结果保留前几名",
                        new String[]{"前 5 名", "前 8 名", "前 10 名", "前 15 名"},
                        new int[]{5, 8, 10, 15},
                        PREF_CF_TOPN, 8);
            }
        });
        settingRow(box, "网段采样数量",
                sample + (sample == 160 ? "（快）" : sample == 320 ? "（标准）" : "（充分）"),
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickFromOptions("CF 网段采样数量（越多越准越慢）",
                        new String[]{"160 个（快）", "320 个（标准）", "640 个（充分）"},
                        new int[]{160, 320, 640},
                        PREF_CF_SAMPLE, 320);
            }
        });
        settingRow(box, "单 IP 测速流量", mb + " MB", new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickFromOptions("每个候选 IP 的测速下载量",
                        new String[]{"2 MB（快）", "6 MB（标准）", "12 MB（精确）"},
                        new int[]{2, 6, 12},
                        PREF_CF_DOWNLOAD_MB, 6);
            }
        });

        settingsSection(box, "应用", false);
        settingRow(box, "检查更新", "当前 v" + appVersion(), new View.OnClickListener() {
            @Override public void onClick(View v) { checkUpdateAsync(false); }
        });
        settingRow(box, "启动时自动检查更新",
                prefs.getBoolean(PREF_AUTO_UPDATE, true) ? "已开启" : "已关闭",
                new View.OnClickListener() {
            @Override public void onClick(View v) { kickAutoUpdate(); }
        });

        AlertDialog dlg = newDialog()
                .setTitle("设置")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .create();
        styleDialog(dlg);
        dlg.show();
        // 尺寸完全跟屏走：宽 78%、高 90%——电视上够大气，手机上就是常规全宽弹窗，
        // 换任何分辨率/密度都不用再调。tv 720dp 高 → 宽约 998dp；手机 640dp 高 → 约全屏宽的 78%
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w.setLayout(Math.round(dm.widthPixels * 0.78f),
                    Math.round(dm.heightPixels * 0.90f));
        }
        // 初焦点给第一行设置项，而不是底部的「关闭」按钮
        if (first != null) first.post(new Runnable() {
            @Override public void run() { if (first != null) first.requestFocus(); }
        });
    }

    /** 设置弹窗里的分区标题：小字号强调色，把 7 个设置项拆成三组好扫读 */
    private void settingsSection(LinearLayout box, String title, boolean first) {
        TextView tv = new TextView(this);
        styleText(tv, COLOR_ACCENT, Math.round(13 * uiScale()));
        tv.setText(title);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = first ? us(8) : us(22);
        lp.bottomMargin = us(2);
        box.addView(tv, lp);
    }

    /**
     * 设置弹窗里的一行：左侧设置名，右侧当前值 + 「›」。
     * 背景用 Skin.button（暗块描边 → 光标停上变实心亮块），和页内按钮同一套语言；
     * 聚焦时文字色翻成亮块上的深色，不然白底上还是浅字就看不清了。
     */
    private View settingRow(LinearLayout box, String label, String value,
                            View.OnClickListener onClick) {
        final TextView tvLabel = new TextView(this);
        styleText(tvLabel, COLOR_TEXT, Math.round(17 * uiScale()));
        tvLabel.setText(label);

        final TextView tvValue = new TextView(this);
        styleText(tvValue, COLOR_HINT, Math.round(15 * uiScale()));
        tvValue.setText(value);
        tvValue.setGravity(Gravity.END);
        tvValue.setSingleLine(true);

        final TextView chev = new TextView(this);
        styleText(chev, COLOR_ACCENT, Math.round(18 * uiScale()));
        chev.setText("›");
        chev.setGravity(Gravity.CENTER);
        chev.setPadding(us(10), 0, 0, 0);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(us(62));
        row.setPadding(us(20), 0, us(20), 0);
        row.setBackground(Skin.button(th));
        row.setFocusable(true);
        // 移动端适配预留：这行本来就是可点击的（setOnClickListener），触摸设备
        // 不走焦点高亮也能正常操作；到时只需在无触摸屏的盒子上隐藏触摸提示即可
        row.setFocusableInTouchMode(false);
        row.setOnClickListener(onClick);
        row.addView(tvLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lpV = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpV.setMargins(us(16), 0, us(4), 0);
        row.addView(tvValue, lpV);
        row.addView(chev, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                tvLabel.setTextColor(hasFocus ? COLOR_ON_FOCUS : COLOR_TEXT);
                tvValue.setTextColor(hasFocus
                        ? Theme.alpha(COLOR_ON_FOCUS, 0.75f) : COLOR_HINT);
                chev.setTextColor(hasFocus ? COLOR_ON_FOCUS : COLOR_ACCENT);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = us(10);
        box.addView(row, lp);
        return row;
    }

    /** 自动检查升级开关：改完回到设置总览，方便连续调 */
    private void kickAutoUpdate() {
        final boolean on = prefs.getBoolean(PREF_AUTO_UPDATE, true);
        newDialog()
                .setTitle("启动时自动检查更新")
                .setSingleChoiceItems(new String[]{"开启（推荐）", "关闭"}, on ? 0 : 1,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                prefs.edit().putBoolean(PREF_AUTO_UPDATE, w == 0).apply();
                                d.dismiss();
                                toast(w == 0 ? "已开启：启动时会自动检查新版本" : "已关闭自动检查更新");
                                ui.postDelayed(new Runnable() {
                                    @Override public void run() { showSettingsDialog(); }
                                }, 240);
                            }
                        })
                .setNegativeButton("取消", null)
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

    // ==================== 检查升级 ====================

    /**
     * @param silent true = 启动时的后台自动检查（不打扰，只在有新版本时冒个提示）；
     *               false = 用户主动点「检查更新」，所有结果都要有反馈
     */
    private void checkUpdateAsync(final boolean silent) {
        if (updateChecking) {
            if (!silent) toast("正在检查中…");
            return;
        }
        updateChecking = true;
        if (!silent) toast("正在检查新版本…");

        new Thread(new Runnable() {
            @Override public void run() {
                UpdateChecker.Info found = null;
                String err = null;
                try {
                    found = UpdateChecker.fetchLatest();
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final UpdateChecker.Info info = found;
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        updateChecking = false;
                        if (fErr != null) {
                            // 网络不通/被墙在电视盒子上很常见，说清原因而不是干等；
                            // 启动时的后台检查失败则不打扰（国内网络访问 GitHub 本来就不稳）
                            if (!silent) {
                                showInfoDialog("检查更新失败",
                                        "直连和 3 个国内镜像都没连上：\n" + fErr
                                                + "\n\n可以在浏览器里打开发布页手动下载："
                                                + "\nhttps://github.com/ifeelok/TVHostBox/releases");
                            }
                            return;
                        }
                        if (!UpdateChecker.isNewer(info.tag, appVersion())) {
                            hideUpdatePill();
                            if (!silent) {
                                showInfoDialog("已是最新版本",
                                        "当前 v" + appVersion() + "\n远端最新 " + info.tag);
                            }
                            return;
                        }
                        showUpdatePill(info.tag);
                        if (silent) {
                            toast("发现新版本 " + info.tag + "，可用「检查更新」升级");
                        } else {
                            showUpdateDialog(info);
                        }
                    }
                });
            }
        }, "check-update").start();
    }

    private void showInfoDialog(String title, String msg) {
        newDialog().setTitle(title).setMessage(msg).setPositiveButton("知道了", null).show();
    }

    /**
     * 更新走不下去时的收尾。
     *
     * 电视上没有地址栏也没有复制粘贴，弹一段带 URL 的文字等于让用户干瞪眼；
     * 这里给一枚「打开发布页」，能唤起系统浏览器/下载器就直接开，不能就退回提示。
     */
    private void showUpdateFailDialog(String title, String msg, final String url) {
        if (updateDlg != null && updateDlg.isShowing()) updateDlg.dismiss();
        newDialog()
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("打开发布页", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { openUrl(url); }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openUrl(String url) {
        if (url == null || url.trim().isEmpty()) { toast("没有可打开的地址"); return; }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("盒子里没有浏览器，可以用手机或电脑打开这个地址");
        }
    }

    /** 有新版本时顶栏右侧出现一枚小胶囊；传 null 表示"检查失败"，用警告色 */
    private void showUpdatePill(String tag) {
        if (tvUpdatePill == null) return;
        if (tag == null) {
            tvUpdatePill.setText("检查更新失败");
            tvUpdatePill.setTextColor(COLOR_HINT);
            tvUpdatePill.setBackground(Skin.pill(th, th.hint));
        } else {
            tvUpdatePill.setText("可升级 " + tag);
            tvUpdatePill.setTextColor(COLOR_ACCENT);
            tvUpdatePill.setBackground(Skin.pill(th, th.accent));
        }
        tvUpdatePill.setVisibility(View.VISIBLE);
    }

    private void hideUpdatePill() {
        if (tvUpdatePill != null) tvUpdatePill.setVisibility(View.GONE);
    }

    private void showUpdateDialog(final UpdateChecker.Info info) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前 v").append(appVersion()).append("    →    最新 ").append(info.tag);
        if (info.size > 0) {
            sb.append("\n安装包 ").append(String.format("%.1f MB", info.size / 1048576.0));
        }
        String notes = releaseNotes(info.body);
        if (notes.length() > 0) sb.append("\n\n").append(notes);
        if (info.noNotes) sb.append("\n（镜像只同步到版本号，发布说明请看发布页）");
        if (info.source.length() > 0) sb.append("\n更新源：").append(info.source);
        sb.append("\n\n更新会覆盖安装，hosts 与设置都保留。");

        newDialog()
                .setTitle("发现新版本")
                .setMessage(sb.toString())
                .setPositiveButton("立即更新", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        startUpdateDownload(info);
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 发布说明是 Markdown：去掉符号、压成两三行，电视上一屏能读完 */
    private String releaseNotes(String body) {
        if (body == null) return "";
        String s = body.replace("\r", "")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replace("**", "").replace("`", "")
                .replaceAll("(?m)^[-*]\\s+", "· ")
                .replaceAll("\n{2,}", "\n")
                .trim();
        if (s.length() > 260) s = s.substring(0, 260) + "…";
        return s;
    }

    /** 下载 → root 静默安装（本项目只在已 root 的盒子上用，非 root 退回浏览器下载） */
    private void startUpdateDownload(final UpdateChecker.Info info) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(22);
        box.setPadding(pad, dp(14), pad, 0);

        final TextView tvState = new TextView(this);
        styleText(tvState, COLOR_TEXT, 15);
        tvState.setText("准备下载…");
        box.addView(tvState);

        final Bar bar = new Bar(this, Theme.alpha(th.text, th.light ? 0.14f : 0.16f), COLOR_ACCENT);
        LinearLayout.LayoutParams lpBar = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(7));
        lpBar.topMargin = dp(12);
        box.addView(bar, lpBar);

        final TextView tvNote = new TextView(this);
        styleText(tvNote, COLOR_HINT, 12);
        tvNote.setLineSpacing(dp(3), 1f);
        tvNote.setText("装好之后应用会自动重启，hosts 与设置不受影响。");
        LinearLayout.LayoutParams lpNote = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpNote.topMargin = dp(10);
        box.addView(tvNote, lpNote);

        if (updateDlg != null && updateDlg.isShowing()) updateDlg.dismiss();
        updateDlg = newDialog()
                .setTitle("更新到 " + info.tag)
                .setView(box)
                .setNegativeButton("取消", null)
                .create();
        updateDlg.show();

        new Thread(new Runnable() {
            @Override public void run() {
                final File apk = new File(getCacheDir(), "update.apk");
                try {
                    // 自动安装全靠 root（pm install）；没有 root 下载下来也装不上，
                    // 早点说清楚并给出发布页，比让用户等到 100% 再报错强
                    if (!RootShell.isRootAvailable()) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                showUpdateFailDialog("需要 root 才能自动安装",
                                        "这台盒子还没拿到 root，应用内自动更新用不了。\n\n"
                                                + "可以用手机或电脑打开发布页下载 APK，再用 U 盘拷进盒子安装；"
                                                + "hosts 与设置都会保留。",
                                        info.htmlUrl);
                            }
                        });
                        return;
                    }
                    UpdateChecker.download(info.apkUrl, apk, info.size,
                            new UpdateChecker.DownloadProgress() {
                        @Override public void onProgress(final long done, final long total) {
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    long known = total > 0 ? total : info.size;
                                    if (known > 0) bar.setProgress(done / (float) known);
                                    tvState.setText(known > 0
                                            ? "正在下载 " + (done * 100 / known) + "%   （"
                                                    + mbText(done) + " / " + mbText(known) + "）"
                                            : "正在下载 " + mbText(done));
                                }
                            });
                        }
                    }, new UpdateChecker.SourceListener() {
                        @Override public void onSource(final String desc) {
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    // 直连不通时会把这条改成「正在通过镜像 xx 下载」，别让用户以为卡死
                                    tvState.setText("正在通过 " + desc + " 下载…");
                                    bar.setProgress(0f);
                                }
                            });
                        }
                    });

                    ui.post(new Runnable() {
                        @Override public void run() {
                            bar.setProgress(1f);
                            tvState.setText("下载完成，正在安装…");
                            tvNote.setText("安装时应用会短暂关闭并自动重启。");
                        }
                    });
                    Thread.sleep(700);   // 让上面这行字先显示出来

                    // root 静默安装：先落到 /data/local/tmp（adb install 用的同一路径，
                    // 系统包管理器对这个位置有读权限），装完再自启
                    String cmd = "cp '" + apk.getAbsolutePath() + "' /data/local/tmp/tvhostbox_update.apk\n"
                            + "chmod 644 /data/local/tmp/tvhostbox_update.apk\n"
                            + "pm install -r /data/local/tmp/tvhostbox_update.apk\n"
                            + "RC=$?\n"
                            + "rm -f /data/local/tmp/tvhostbox_update.apk\n"
                            + "if [ $RC -eq 0 ]; then sleep 1; am start -n tv.hostbox.app/.MainActivity >/dev/null 2>&1; fi\n"
                            + "echo hostbox_rc=$RC";
                    final RootShell.Result r = RootShell.run(cmd, 180000);
                    final boolean ok = r.ok && r.output.contains("Success");

                    ui.post(new Runnable() {
                        @Override public void run() {
                            try { apk.delete(); } catch (Exception ignored) {}
                            if (ok) {
                                hideUpdatePill();
                                tvState.setText("更新完成，应用正在重启…");
                                tvNote.setText("若没有自动打开，请手动启动「电视Host管家」。");
                            } else {
                                showUpdatePill(info.tag);
                                showUpdateFailDialog("自动安装失败",
                                        "安装包已经下载好，但系统拒绝了安装。\n\n"
                                                + (r.output.length() > 220
                                                        ? r.output.substring(0, 220) + "…" : r.output),
                                        info.htmlUrl);
                            }
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            try { apk.delete(); } catch (Exception ignored) {}
                            showUpdateFailDialog("更新失败",
                                    "下载或安装没走通：\n" + e
                                            + "\n\n可以稍后重试，或到发布页手动下载。",
                                    info.htmlUrl);
                        }
                    });
                }
            }
        }, "update-download").start();
    }

    private static String mbText(long bytes) {
        if (bytes >= 1048576) return String.format("%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024) return (bytes / 1024) + " KB";
        return bytes + " B";
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
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(12));
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
        num.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13));
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
        tvMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(15));
        tvMain.setPadding(0, dp(3), 0, 0);

        TextView tvSub = new TextView(this);
        tvSub.setText(sub);
        tvSub.setTextColor(COLOR_HINT);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(12));
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
        tvCfPhase.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(16));
        tvCfPhase.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        tvCfPct = new TextView(this);
        tvCfPct.setText("—");
        tvCfPct.setTextColor(COLOR_HINT);
        tvCfPct.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13));
        tvCfPct.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvCfPct.setGravity(Gravity.END);

        progHead.addView(tvCfPhase, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        progHead.addView(tvCfPct, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        progCard.addView(progHead);

        // 轨道用 text 色低透明度：border 色在卡片上几乎隐形，没开始测速时用户找不到"条"在哪
        barCf = new Bar(this, Theme.alpha(th.text, th.light ? 0.14f : 0.16f), COLOR_ACCENT);
        LinearLayout.LayoutParams lpBar = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(7));
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

        // 结果列表：跑完之后才出现（跑的时候用上面的实时面板占这块位置）
        cfList = new CfSideList();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(12);
        cfList.setLayoutParams(lp);

        // 实时测试面板：和结果列表占同一块区域，同一时刻只显示一个
        cfLive = buildCfLiveBoard();

        // 主 CTA 放底部按钮行（与 Hosts 页「添加条目」同构）：
        // 之前挤在标题行右侧，标题比按钮矮，按钮上沿被父容器裁掉——层级和高度都不可控
        btnCfStart = actionButton("开始优选", Icons.PLAY, new View.OnClickListener() {
            @Override public void onClick(View v) { startCfOptimize(); }
        });
        stylePrimary(btnCfStart);

        // 第二枚按钮：跑完之后才出现。测试过程表跑完就消失，用户想回头看看哪个 IP 抖，
        // 就再也找不回来了——这里给一个可反复切换的入口，两套视图随时能对调。
        btnCfPeek = actionButton("查看测试过程", Icons.CLOCK, new View.OnClickListener() {
            @Override public void onClick(View v) { toggleCfProcessView(); }
        });
        btnCfPeek.setVisibility(View.GONE);
        btnCfStart.setContentDescription("OK 开始优选 · ←→ 换一个按钮 · ↑ 回列表");
        btnCfPeek.setContentDescription("OK 查看测试过程 · ←→ 换一个按钮 · ↑ 回列表");

        LinearLayout buttons = new LinearLayout(this);
        addButtons(buttons, new Button[]{btnCfPeek, btnCfStart});
        wireButtonRow(new Button[]{btnCfPeek, btnCfStart}, navCfBtn, new Runnable() {
            @Override public void run() { cfButtonRowUp(); }
        });

        root.addView(title);
        root.addView(progCard);
        root.addView(cfLive);
        root.addView(cfList);
        root.addView(buttons);
        return root;
    }

    /** 实时面板的一行：序号 + IP + 质量条 + 指标 */
    private static final class LiveRow {
        LinearLayout root;
        TextView rank;
        TextView ip;
        TextView metric;
        Bar bar;
    }

    /**
     * 实时测试面板。
     *
     * 之前跑优选时，中间一大块只有一句「TCP 延迟测试中…」，每个 IP 的结果一行闪过、
     * 跑完就没了——用户既看不出在测什么，也判断不了质量分布。这里把过程本身画成一张表：
     * 序号 / IP / 质量条 / 延迟或速度，按「当前最好的排前面」实时刷新。
     */
    private LinearLayout buildCfLiveBoard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Skin.card(th));
        card.setPadding(dp(16), dp(12), dp(16), dp(10));
        card.setVisibility(View.GONE);
        LinearLayout.LayoutParams lpCard = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lpCard.topMargin = dp(12);
        card.setLayoutParams(lpCard);

        // 回看时这张卡是可以"进入"的（框态）：按钮行按 ↑ 回到表上，表里 ↑ 才是页签。
        // 以前它只是块不可聚焦的展示板，按钮行按 ↑ 直接跳到页签，回不到 IP 框子里。
        card.setFocusable(true);
        card.setFocusableInTouchMode(false);
        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(hasFocus ? Skin.cardFocus(th) : Skin.card(th));
                if (hasFocus) setHint("OK 返回结果列表 · ↑ 回页签 · ↓ 回按钮行");
            }
        });
        card.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    return navCfBtn != null && navCfBtn.requestFocus();
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (btnCfPeek != null && btnCfPeek.getVisibility() == View.VISIBLE) {
                        return btnCfPeek.requestFocus();
                    }
                    return btnCfStart != null && btnCfStart.requestFocus();
                }
                // OK = 和「返回结果列表」按钮同一个动作，少绕一步
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    toggleCfProcessView();
                    return true;
                }
                return keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            }
        });

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        cfLiveTitle = new TextView(this);
        cfLiveTitle.setText("实时测试");
        cfLiveTitle.setTextColor(COLOR_TEXT);
        cfLiveTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(16));
        cfLiveTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        cfLiveStat = new TextView(this);
        cfLiveStat.setText("");
        cfLiveStat.setTextColor(COLOR_HINT);
        cfLiveStat.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13));
        cfLiveStat.setTypeface(android.graphics.Typeface.MONOSPACE);
        cfLiveStat.setGravity(Gravity.END);

        head.addView(cfLiveTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(cfLiveStat, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(head);

        View line = new View(this);
        line.setBackgroundColor(Theme.alpha(th.text, th.light ? 0.12f : 0.14f));
        LinearLayout.LayoutParams lpLine = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lpLine.topMargin = dp(8);
        lpLine.bottomMargin = dp(2);
        card.addView(line, lpLine);

        // 行数跟随「CF 优选保留条数」设置：回顾表和结果列表一一对应，不再固定 6 行。
        // 套一层 ScrollView，设置调成 10/15 名时也能全部看到。
        int rows = Math.max(4, Math.min(15, prefs.getInt(PREF_CF_TOPN, 8)));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(rowsBox, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(sv);
        liveScroll = sv;

        liveRows.clear();
        for (int i = 0; i < rows; i++) {
            LiveRow r = new LiveRow();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

            r.rank = new TextView(this);
            r.rank.setGravity(Gravity.CENTER);
            r.rank.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(11));
            r.rank.setTypeface(android.graphics.Typeface.MONOSPACE);
            r.rank.setTextColor(COLOR_HINT);
            r.rank.setBackground(Skin.badge(th, false));
            row.addView(r.rank, new LinearLayout.LayoutParams(dp(24), dp(24)));

            r.ip = new TextView(this);
            r.ip.setTextColor(COLOR_TEXT);
            r.ip.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(16));
            r.ip.setTypeface(android.graphics.Typeface.MONOSPACE);
            r.ip.setSingleLine(true);
            r.ip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lpIp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lpIp.setMargins(dp(12), 0, dp(10), 0);
            row.addView(r.ip, lpIp);

            r.bar = new Bar(this, Theme.alpha(th.text, th.light ? 0.12f : 0.14f), COLOR_ACCENT);
            LinearLayout.LayoutParams lpBar = new LinearLayout.LayoutParams(dp(88), dp(6));
            lpBar.setMargins(0, 0, dp(12), 0);
            row.addView(r.bar, lpBar);

            r.metric = new TextView(this);
            r.metric.setTextColor(COLOR_HINT);
            r.metric.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(15));
            r.metric.setTypeface(android.graphics.Typeface.MONOSPACE);
            r.metric.setGravity(Gravity.END);
            r.metric.setSingleLine(true);
            row.addView(r.metric, new LinearLayout.LayoutParams(dp(104),
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            r.root = row;
            rowsBox.addView(row);
            liveRows.add(r);
        }

        cfLiveFoot = new TextView(this);
        cfLiveFoot.setTextColor(COLOR_HINT);
        cfLiveFoot.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(12));
        cfLiveFoot.setSingleLine(true);
        cfLiveFoot.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lpFoot = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpFoot.topMargin = dp(8);
        card.addView(cfLiveFoot, lpFoot);
        return card;
    }

    /** 极简进度条：圆角轨道 + 强调色填充，比一行数字更直观 */
    private static class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int trackColor;
        private int fillColor;
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

        /** 实时面板里每行的质量条要按数值换色（好=绿 / 一般=强调色 / 差=灰） */
        void setFillColor(int c) {
            if (this.fillColor != c) { this.fillColor = c; invalidate(); }
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
        cfFinished = false;
        cfViewLive = false;
        setBtnText(btnCfStart, "取消优选");
        // 跑的时候只留主按钮：过程自动展示，没有"回看"这回事
        if (btnCfPeek != null) btnCfPeek.setVisibility(View.GONE);
        btnCfStart.setContentDescription("OK 取消优选（再按一次中止）· ←→ 换一个按钮");
        refreshBtnHint(btnCfStart);
        cfResults.clear();
        // 跑的时候：藏结果列表、亮实时面板——同一块区域，不让用户看到两套东西
        if (cfList != null) { cfList.resetList(); cfList.setVisibility(View.GONE); }
        if (cfLive != null) cfLive.setVisibility(View.VISIBLE);
        setLivePhase("sample");
        renderLiveBoard();
        if (tvCfPhase != null) tvCfPhase.setText("采样网段");
        if (tvCfPct != null) tvCfPct.setText("0%");
        if (barCf != null) barCf.setProgress(0f);

        // 设置里的 CF 参数
        int topN = prefs.getInt(PREF_CF_TOPN, 8);
        int sample = prefs.getInt(PREF_CF_SAMPLE, 320);
        int mb = prefs.getInt(PREF_CF_DOWNLOAD_MB, 6);
        final CfOptimizer opt = new CfOptimizer(sample, Math.max(16, sample / 13), topN, mb * 1024 * 1024);
        cfOpt = opt;
        // 逐 IP 回调：把「每个 IP 测得怎么样」交给实时面板，而不是只留一行汇总
        opt.setIpListener(new CfOptimizer.IpListener() {
            @Override public void onLatencyTested(String ip, int rttMs) {
                synchronized (liveLock) {
                    setLivePhase("latency");   // 内部判重，第一次调用才清表
                    liveDone++;
                    if (rttMs >= 0) {
                        CfOptimizer.Candidate c = new CfOptimizer.Candidate(ip);
                        c.rttMs = rttMs;
                        liveLat.add(c);
                    }
                    liveLast = ip + (rttMs >= 0 ? "  →  " + rttMs + " ms" : "  →  不通");
                }
                scheduleLiveRender();
            }

            @Override public void onSpeedStarted(String ip) {
                synchronized (liveLock) {
                    setLivePhase("speed");
                    CfOptimizer.Candidate c = new CfOptimizer.Candidate(ip);
                    c.mbps = -2;    // -2 = 正在测速
                    liveSpeed.add(c);
                    liveLast = ip + "  →  测速中…";
                }
                scheduleLiveRender();
            }

            @Override public void onSpeedMeasured(String ip, double mbps) {
                synchronized (liveLock) {
                    liveDone++;
                    for (int i = 0; i < liveSpeed.size(); i++) {
                        if (liveSpeed.get(i).ip.equals(ip)) { liveSpeed.get(i).mbps = mbps; break; }
                    }
                    liveLast = ip + (mbps > 0 ? String.format("  →  %.1f Mbps", mbps) : "  →  测速失败");
                }
                scheduleLiveRender();
            }
        });

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<CfOptimizer.Candidate> result = opt.optimize(new CfOptimizer.Progress() {
                        @Override public void onProgress(final String phase, final int done, final int total, final String detail) {
                            // 工作线程：先记过程数据（界面按 110ms 限流重绘），再更新顶部进度
                            synchronized (liveLock) {
                                liveTotal = total;
                                if (!"done".equals(phase)) setLivePhase(phase);
                            }
                            scheduleLiveRender();
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
                            showCfResults();
                            cfFinished = true;
                            cfHasRun = true;
                            showPeekButton("查看测试过程", Icons.CLOCK);
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
                        @Override                         public void run() {
                            showCfResults();
                            cfFinished = true;
                            cfHasRun = true;
                            showPeekButton("查看测试过程", Icons.CLOCK);
                            if (tvCfPhase != null) tvCfPhase.setText("已取消");
                            if (tvCfPct != null) tvCfPct.setText("—");
                            tvCfProgress.setText("已取消优选，已完成的进度不会写入 hosts。");
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override                         public void run() {
                            showCfResults();
                            cfFinished = true;
                            cfHasRun = true;
                            showPeekButton("查看测试过程", Icons.CLOCK);
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
                            setBtnText(btnCfStart, cfHasRun ? "重新优选" : "开始优选");
                            btnCfStart.setContentDescription(cfHasRun
                                    ? "OK 重新优选 · ←→ 换一个按钮 · ↑ 回列表"
                                    : "OK 开始优选 · ←→ 换一个按钮 · ↑ 回列表");
                            refreshBtnHint(btnCfStart);
                        }
                    });
                }
            }
        }, "cf-optimize").start();
    }

    // ==================== CF 实时面板的渲染 ====================

    /** 切阶段：只有真的换了阶段才清表，避免误清掉正在累积的数据 */
    private void setLivePhase(String phase) {
        synchronized (liveLock) {
            if (phase.equals(livePhase)) return;
            livePhase = phase;
            if ("latency".equals(phase) || "speed".equals(phase) || "sample".equals(phase)) {
                liveLat.clear();
                liveSpeed.clear();
                liveDone = 0;
                liveLast = "";
            }
        }
    }

    /** 跑完/取消/失败：把实时面板收起来，让结果列表接管这块区域 */
    private void showCfResults() {
        cfViewLive = false;
        if (cfLive != null) cfLive.setVisibility(View.GONE);
        if (cfList != null) cfList.setVisibility(View.VISIBLE);
        if (btnCfPeek != null) setBtnIcon(btnCfPeek, Icons.CLOCK, "查看测试过程");
    }

    /** 亮出「查看测试过程」这枚按钮（跑完才有） */
    private void showPeekButton(String text, String iconKind) {
        if (btnCfPeek == null) return;
        setBtnIcon(btnCfPeek, iconKind, text);
        btnCfPeek.setVisibility(View.VISIBLE);
    }

    /**
     * 结果列表 ⇄ 测试过程表，随时对调。
     *
     * 两套视图占的是内容区同一块位置（上下布局完全一致，切换时页面不跳），
     * 所以这里只是把谁可见翻一下，不重建任何控件——盒子上零开销。
     */
    private void toggleCfProcessView() {
        if (cfRunning || !cfHasRun) return;
        cfViewLive = !cfViewLive;
        if (cfViewLive) {
            if (cfList != null) { cfList.resetList(); cfList.setVisibility(View.GONE); }
            if (cfLive != null) cfLive.setVisibility(View.VISIBLE);
            setBtnIcon(btnCfPeek, Icons.LIST, "返回结果列表");
            btnCfPeek.setContentDescription("OK 返回结果列表 · ←→ 换一个按钮 · ↑ 看过程表");
            refreshBtnHint(btnCfPeek);
            renderLiveBoard();   // 用最后一轮的过程数据重画（标题自动变成「测试回顾」）
            // 进度卡底下那行字两种视图关心的东西不一样，要跟着一起换
            if (tvCfProgress != null) tvCfProgress.setText(
                    "这是刚才那次任务的完整过程（按速度从高到低），不是正在跑。");
            setHint("OK 返回结果列表 · ←→ 换一个按钮 · ↑ 看过程表");
        } else {
            if (cfLive != null) cfLive.setVisibility(View.GONE);
            if (cfList != null) cfList.setVisibility(View.VISIBLE);
            setBtnIcon(btnCfPeek, Icons.CLOCK, "查看测试过程");
            btnCfPeek.setContentDescription("OK 查看测试过程 · ←→ 换一个按钮 · ↑ 回列表");
            refreshBtnHint(btnCfPeek);
            if (tvCfProgress != null) tvCfProgress.setText(cfResults.isEmpty()
                    ? "这次没有可用的 IP，可以把采样数量调大，或换个时段再试。"
                    : "选中某条按 OK，即可写进指定域名的 hosts。");
            if (cfList != null && !cfResults.isEmpty()) cfList.focusEntered();
            else {
                if (btnCfPeek != null) btnCfPeek.requestFocus();
                setHint("OK 重新优选 · ←→ 换一个按钮 · ↑ 回列表");
            }
        }
    }

    /**
     * CF 按钮行按「上」去哪：
     *   回看中 → 过程表（表里 ↑ 才是页签）；有结果 → 结果列表；运行中/空态 → 页签。
     * 以前一律跳页签，真机上被吐槽"按上应该回到 IP 框子里"。
     */
    private void cfButtonRowUp() {
        if (cfViewLive && cfLive != null && cfLive.getVisibility() == View.VISIBLE) {
            cfLive.requestFocus();
            return;
        }
        if (!cfRunning && cfList != null && cfList.getVisibility() == View.VISIBLE
                && !cfResults.isEmpty()) {
            cfList.focusEntered();
            return;
        }
        if (navCfBtn != null) navCfBtn.requestFocus();
    }

    /** 过程回调密度很高（320 个 IP），限流到约 9 fps，避免盒子卡在重绘上 */
    private void scheduleLiveRender() {
        if (liveRenderQueued) return;
        liveRenderQueued = true;
        ui.postDelayed(liveRenderTask, 110);
    }

    private static String pad2(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private void renderLiveBoard() {
        if (cfLive == null || cfLive.getVisibility() != View.VISIBLE) return;
        final String renderPhase = cfFinished ? "review" : livePhase;
        // 跑完后再打开这张表就是「回看」，标题换成回顾口吻，别让人以为还在跑
        final String pfx = cfFinished ? "测试回顾 · " : "实时测试 · ";
        String phase;
        int done, total;
        String last;
        List<CfOptimizer.Candidate> lat = null, sp = null;
        synchronized (liveLock) {
            phase = livePhase;
            done = liveDone;
            total = liveTotal;
            last = liveLast;
            if ("latency".equals(phase)) lat = new ArrayList<CfOptimizer.Candidate>(liveLat);
            else if ("speed".equals(phase)) sp = new ArrayList<CfOptimizer.Candidate>(liveSpeed);
        }

        // 换了阶段（或切入回顾）就把表滚回第一行，别让人接着看上一次滚到一半的位置
        if (liveScroll != null && !renderPhase.equals(lastPhaseRendered)) {
            lastPhaseRendered = renderPhase;
            liveScroll.post(new Runnable() {
                @Override public void run() { if (liveScroll != null) liveScroll.scrollTo(0, 0); }
            });
        }

        if (lat != null) {
            Collections.sort(lat, new java.util.Comparator<CfOptimizer.Candidate>() {
                @Override public int compare(CfOptimizer.Candidate a, CfOptimizer.Candidate b) {
                    return Integer.compare(a.rttMs, b.rttMs);
                }
            });
            cfLiveTitle.setText(pfx + "延迟");
            cfLiveStat.setText((total > 0 ? "已测 " + done + " / " + total + "   ·   " : "")
                    + "通 " + lat.size() + "   ·   不通 " + Math.max(0, done - lat.size()));
            for (int i = 0; i < liveRows.size(); i++) {
                LiveRow r = liveRows.get(i);
                if (i < lat.size()) {
                    CfOptimizer.Candidate c = lat.get(i);
                    // 三重编码：数字 + 颜色 + 条长，别让用户只靠颜色判断好坏
                    int color = c.rttMs <= 80 ? COLOR_OK
                            : c.rttMs <= 180 ? COLOR_ACCENT : COLOR_HINT;
                    r.rank.setText(pad2(i + 1));
                    r.rank.setTextColor(i < 3 ? COLOR_ACCENT : COLOR_HINT);
                    r.ip.setText(c.ip);
                    r.ip.setTextColor(COLOR_TEXT);
                    r.metric.setText(c.rttMs + " ms");
                    r.metric.setTextColor(color);
                    r.bar.setFillColor(color);
                    r.bar.setProgress(Math.max(0.05f, 1f - Math.min(1f, c.rttMs / 400f)));
                } else {
                    blankLiveRow(r);
                }
            }
            cfLiveFoot.setText(last.isEmpty()
                    ? (cfFinished ? "这次没有测到通的 IP" : "正在并发测试 443 端口，结果会逐条落在这张表里")
                    : (cfFinished ? "最后一条    " : "刚刚    ") + last);
        } else if (sp != null) {
            Collections.sort(sp, new java.util.Comparator<CfOptimizer.Candidate>() {
                @Override public int compare(CfOptimizer.Candidate a, CfOptimizer.Candidate b) {
                    return Double.compare(sortKey(b.mbps), sortKey(a.mbps));
                }
                /** 实测值按大小排；"测速中"(-2) 排在有结果之后，失败(-1) 垫底 */
                private double sortKey(double v) { return v == -2 ? -0.5 : v; }
            });
            double fastest = 0;
            for (CfOptimizer.Candidate c : sp) if (c.mbps > fastest) fastest = c.mbps;
            cfLiveTitle.setText(pfx + "下载速度");
            cfLiveStat.setText("已测 " + done + (total > 0 ? " / " + total : "")
                    + (fastest > 0 ? "   ·   最快 " + String.format("%.1f Mbps", fastest) : ""));
            for (int i = 0; i < liveRows.size(); i++) {
                LiveRow r = liveRows.get(i);
                if (i < sp.size()) {
                    CfOptimizer.Candidate c = sp.get(i);
                    r.rank.setText(pad2(i + 1));
                    r.rank.setTextColor(i < 3 ? COLOR_ACCENT : COLOR_HINT);
                    r.ip.setText(c.ip);
                    r.ip.setTextColor(COLOR_TEXT);
                    if (c.mbps == -2) {
                        r.metric.setText("测速中…");
                        r.metric.setTextColor(COLOR_HINT);
                        r.bar.setFillColor(COLOR_HINT);
                        r.bar.setProgress(0f);
                    } else if (c.mbps > 0) {
                        r.metric.setText(String.format("%.1f Mbps", c.mbps));
                        r.metric.setTextColor(COLOR_OK);
                        r.bar.setFillColor(COLOR_OK);
                        r.bar.setProgress(Math.min(1f, (float) (c.mbps / 40f)));
                    } else {
                        r.metric.setText("失败");
                        r.metric.setTextColor(COLOR_HINT);
                        r.bar.setFillColor(COLOR_HINT);
                        r.bar.setProgress(0f);
                    }
                } else {
                    blankLiveRow(r);
                }
            }
            cfLiveFoot.setText(last.isEmpty()
                    ? (cfFinished ? "这次没有测速结果" : "并发测速中，结果逐条落在这张表里")
                    : "并发测速    ·    " + (cfFinished ? "最后一条 " : "刚刚 ") + last);
        } else {
            cfLiveTitle.setText(pfx + "采样");
            cfLiveStat.setText("");
            for (LiveRow r : liveRows) blankLiveRow(r);
            cfLiveFoot.setText(cfFinished
                    ? "这次在采样阶段就结束了，没有可展示的测试记录"
                    : "从 Cloudflare 官方网段按大小加权随机取点…");
        }
    }

    private void blankLiveRow(LiveRow r) {
        r.rank.setText("–");
        r.rank.setTextColor(COLOR_HINT);
        r.ip.setText("");
        r.metric.setText("");
        r.bar.setProgress(0f);
        r.bar.setFillColor(COLOR_HINT);
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
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(14));
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

    /**
     * 按钮 contentDescription 变了就重放提示条。
     * 提示条只在获得焦点那一刻读取 CD；按钮正被光标停着时换语义（开始→取消），
     * onFocusChange 不会再触发，底部会一直显示旧动作——真机上抓到过这个错。
     */
    private void refreshBtnHint(Button b) {
        if (b != null && b.hasFocus()) {
            CharSequence cd = b.getContentDescription();
            setHint(cd != null && cd.length() > 0 ? cd.toString() : "");
        }
    }

    /** 带图标的按钮文字更新（setText 会把图标冲掉，必须重建 Spannable） */
    private void setBtnText(Button b, String text) {
        Object tag = b.getTag();
        b.setText(iconText(tag instanceof android.graphics.drawable.Drawable
                ? (android.graphics.drawable.Drawable) tag : null, text));
    }

    /** 连图标一起换（「查看测试过程」和「返回结果列表」是两种动作，图标要跟着变） */
    private void setBtnIcon(Button b, String iconKind, String text) {
        if (b == null || iconKind == null) return;
        android.graphics.drawable.Drawable icon = Icons.drawable(this, iconKind, 18,
                b.hasFocus() ? COLOR_ON_FOCUS : COLOR_HINT);
        b.setTag(icon);
        b.setText(iconText(icon, text));
    }

    /** 带图标的按钮：图标用 Icons 现画，光标上来时和文字一起翻色 */
    private Button actionButton(String text, String iconKind, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(16));
        b.setTextColor(COLOR_TEXT);
        b.setBackground(Skin.button(th));
        b.setStateListAnimator(null);
        if (iconKind != null) {
            android.graphics.drawable.Drawable icon =
                    Icons.drawable(this, iconKind, iconDp(18), COLOR_HINT);
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
                // 按钮的说明文字可能随状态变（开始/取消/重新、查看/返回），
                // 所以提示条优先读 contentDescription，没设才用通用文案
                if (hasFocus) {
                    CharSequence cd = v.getContentDescription();
                    setHint(cd != null && cd.length() > 0
                            ? cd.toString() : "OK 执行 · ←→ 换一个按钮 · ↑ 回列表");
                }
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
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(17));
        tintIcons(b, COLOR_ON_ACCENT);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                Button bb = (Button) v;
                bb.setTextColor(hasFocus ? COLOR_ON_FOCUS : COLOR_ON_ACCENT);
                tintIcons(bb, hasFocus ? COLOR_ON_FOCUS : COLOR_ON_ACCENT);
                // 主按钮会换语义（开始 / 取消 / 重新），提示条跟着 contentDescription 走
                if (hasFocus) {
                    CharSequence cd = v.getContentDescription();
                    setHint(cd != null && cd.length() > 0
                            ? cd.toString() : "OK 开始测速（再次按下可取消）");
                }
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
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(26));
        tv.setLineSpacing(dp(6), 1f);
        tv.setPadding(0, 0, 0, dp(2));
    }

    private void styleText(TextView tv, int color, int sizeSp) {
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(sizeSp));
        tv.setPadding(0, dp(2), 0, dp(2));
    }

    /**
     * XML 里写死的 dp/sp 也统一过一遍比例尺。
     *
     * XML 没法读 uiScale()，所以在 setContentView 之后按 id 覆盖一次——
     * 否则会出现"代码里的控件缩了、XML 里的顶栏和页签没缩"的比例错乱。
     * 覆盖的只是尺寸，颜色和样式仍在各自的主题逻辑里设置。
     */
    private void applyLayoutScale() {
        findViewById(R.id.root).setPadding(dp(44), dp(16), dp(44), dp(6));

        TextView logo = (TextView) findViewById(R.id.brand_logo);
        LinearLayout.LayoutParams lpLogo =
                (LinearLayout.LayoutParams) logo.getLayoutParams();
        lpLogo.width = dp(40);
        lpLogo.height = dp(40);
        logo.setLayoutParams(lpLogo);
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(20));

        ((TextView) findViewById(R.id.brand_title))
                .setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(20));
        ((TextView) findViewById(R.id.brand_sub))
                .setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(12));

        LinearLayout pill = (LinearLayout) findViewById(R.id.root_pill);
        pill.setPadding(dp(12), dp(7), dp(14), dp(7));
        View rootIcon = findViewById(R.id.root_icon);
        LinearLayout.LayoutParams lpIcon =
                (LinearLayout.LayoutParams) rootIcon.getLayoutParams();
        lpIcon.width = dp(14);
        lpIcon.height = dp(14);
        lpIcon.setMarginEnd(dp(7));
        rootIcon.setLayoutParams(lpIcon);
        ((TextView) findViewById(R.id.root_text))
                .setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13));

        View pillUp = findViewById(R.id.update_pill);
        LinearLayout.LayoutParams lpUp = (LinearLayout.LayoutParams) pillUp.getLayoutParams();
        lpUp.setMarginStart(dp(10));
        pillUp.setLayoutParams(lpUp);
        ((TextView) pillUp).setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13));
        ((TextView) pillUp).setPadding(dp(12), dp(7), dp(12), dp(7));

        LinearLayout.LayoutParams lpHair = (LinearLayout.LayoutParams)
                findViewById(R.id.hair_line).getLayoutParams();
        lpHair.topMargin = dp(10);
        findViewById(R.id.hair_line).setLayoutParams(lpHair);

        LinearLayout nav = (LinearLayout) findViewById(R.id.nav_bar);
        nav.setPadding(nav.getPaddingLeft(), dp(10), nav.getPaddingRight(), dp(6));
        int[] navIds = new int[]{R.id.nav_hosts, R.id.nav_qr, R.id.nav_cf};
        for (int i = 0; i < navIds.length; i++) {
            Button b = (Button) findViewById(navIds[i]);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
            lp.height = dp(40);
            lp.setMarginEnd(dp(10));
            b.setLayoutParams(lp);
            b.setPadding(dp(20), 0, dp(20), 0);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(16));
        }

        LinearLayout hintBar = (LinearLayout) findViewById(R.id.hint_bar);
        hintBar.setPadding(hintBar.getPaddingLeft(), dp(8), hintBar.getPaddingRight(), dp(6));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /**
     * dp：所有尺寸的唯一出口，已经乘过 uiScale()。
     * 这样全工程的 44dp 安全边距、50dp 行高、62dp 设置行…在任何屏上都按同一比例缩放，
     * 不需要每处再手动换算；1dp 的细线缩放后仍四舍五入回 1px，不受影响。
     */
    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                v * uiScale(), getResources().getDisplayMetrics()));
    }

    /**
     * 自适应比例：以 720dp 屏高（标准 1080p 电视）为基准 1.0。
     * 手机/平板屏高更小、平板更大，同一套代码的弹窗、行高、字号都会随之缩放，
     * 不用为每种设备写一套尺寸。后续适配移动端时直接复用这套比例即可。
     */
    private float uiScale() {
        // dp() 调用极密集（每个控件都要算），这里缓存；配置变化会重建 Activity 自然失效
        if (uiScaleCache < 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            float hdp = dm.heightPixels / dm.density;
            uiScaleCache = Math.max(0.62f, Math.min(1.2f, hdp / 720f));
        }
        return uiScaleCache;
    }

    /** 按自适应比例取尺寸（dp 已经自带比例尺，这里只做 float 入口） */
    private int us(float v) {
        return dp(Math.round(v));
    }

    /** 字号同样跟屏走：以 720dp 屏高为基准，小屏自动收、大屏自动放 */
    private float sp(float v) {
        return v * uiScale();
    }

    /** 图标按 dp 出图，同样吃比例尺 */
    private int iconDp(float v) {
        return Math.max(1, Math.round(v * uiScale()));
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
            // 上下留 5dp：选中行的高亮块不顶到卡片圆角和描边上
            setPadding(0, dp(5), 0, dp(5));
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
            // 副行紧凑排版：以前用三个空格隔开 "   ·   "，浪费横向空间还显得松散
            String sub = e.ip + " · " + (e.lineIndex + 1) + " 行";
            if (e.comment != null && !e.comment.isEmpty()) sub += " · " + e.comment;
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
            super(20, 15, "还没有优选结果\n\n按下方「开始优选」，等延迟和速度都跑完");
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
        /** ↑ 和 ↓ 都落到按钮行左枚：两枚按钮并排，落在固定一枚比"看方向猜"稳 */
        @Override void onLeaveUp() { onLeaveDown(); }
        /** ↓ 落到「查看测试过程」而不是「重新优选」：后者会清空现有结果，当默认落点太危险 */
        @Override void onLeaveDown() {
            if (btnCfPeek != null && btnCfPeek.getVisibility() == View.VISIBLE) btnCfPeek.requestFocus();
            else if (btnCfStart != null) btnCfStart.requestFocus();
        }
        @Override String leaveUpLabel() { return "回到按钮行"; }
        @Override String emptyHint() { return "还没有优选结果 · ↑ 回到按钮行"; }
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
        // 行高下限 50dp：既保证 3 米外两行文字不粘连，又让 720dp 高的屏能看到 6~7 行
        h.row.setMinimumHeight(dp(50));
        h.row.setGravity(Gravity.CENTER_VERTICAL);

        h.bar = new View(this);
        LinearLayout.LayoutParams lpBar = new LinearLayout.LayoutParams(
                dp(3), dp(30));
        lpBar.setMargins(dp(9), 0, dp(11), 0);
        h.row.addView(h.bar, lpBar);

        // 行首序号：长列表里"第几条"比纯文字更好定位
        h.num = new TextView(this);
        h.num.setGravity(Gravity.CENTER);
        h.num.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(11));
        h.num.setTextColor(COLOR_HINT);
        h.num.setBackground(Skin.badge(th, false));
        h.num.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams lpNum = new LinearLayout.LayoutParams(dp(24), dp(24));
        lpNum.setMargins(0, 0, dp(11), 0);
        h.row.addView(h.num, lpNum);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(6), dp(8), dp(6));

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
        h.chev.setImageDrawable(Icons.drawable(this, Icons.CHEVRON, iconDp(18), COLOR_HINT));
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
