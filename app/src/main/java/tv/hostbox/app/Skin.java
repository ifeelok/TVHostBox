package tv.hostbox.app;

import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;

/**
 * 按主题在运行时生成控件背景。
 *
 * 为什么不用 XML drawable：颜色写死在 <shape> 里，换主题就得换一整套资源。
 * 全部改成代码构建后，换主题只要换一个 {@link Theme} 调色板。
 *
 * 这一版的设计语言（10-foot UI，3 米外看）：
 *   1. 卡片 = 极浅的上下渐变 + 1dp 描边，模拟"受光面"，避免大片死平色；
 *   2. 焦点 = 与背景反差最大的实心块 + 3dp 强调色环，全屏只有一处这么亮；
 *   3. 选中 = 强调色淡底 + 左侧竖条，永远比焦点暗一档，两种状态不会混；
 *   4. 键帽（keycap）= 半透明底 + 细描边，像遥控器按键，比一行灰字好扫。
 */
public final class Skin {

    private Skin() {}

    private static int px(float dp) {
        return Math.round(Resources.getSystem().getDisplayMetrics().density * dp);
    }

    /** 纯色圆角块（可选描边） */
    private static GradientDrawable solid(int fill, int strokeColor, float strokeDp, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(px(radiusDp));
        if (strokeDp > 0) d.setStroke(px(strokeDp), strokeColor);
        return d;
    }

    /** 上下渐变圆角块：上亮下暗，给卡片一点体积感 */
    private static GradientDrawable vertical(int top, int bottom, float radiusDp) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(px(radiusDp));
        return d;
    }

    /** 渐变圆角块（主按钮 / logo 用） */
    private static GradientDrawable gradient(int from, int to, float radiusDp) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{from, to});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(px(radiusDp));
        return d;
    }

    /** 卡片"受光面"：深色主题往上提亮一点，浅色主题几乎不提（提了会发灰） */
    private static int cardTop(Theme t) {
        return Theme.mix(t.card, 0xFFFFFFFF, t.light ? 0.35f : 0.055f);
    }

    /** 光标块：整个界面里最高对比的一块，保证「光标在哪」一眼可见 */
    private static GradientDrawable focusBlock(Theme t, float radiusDp) {
        return solid(t.focusBg, t.focusRing, 3f, radiusDp);
    }

    // ==================== 卡片 ====================

    public static Drawable card(Theme t) {
        GradientDrawable d = vertical(cardTop(t), t.card, 16);
        d.setStroke(px(1), t.border);
        return d;
    }

    /**
     * 「框态」卡片：焦点落在整个列表框上时用。
     * 描边换成强调色而不是整块涂亮 —— 列表里还有内容要读，整块反白就看不清了。
     */
    public static Drawable cardFocus(Theme t) {
        GradientDrawable d = vertical(cardTop(t), t.card, 16);
        d.setStroke(px(2.5f), t.accent);
        return d;
    }

    /** 顶栏下方的细分隔线（左浓右淡，一点点视觉层次） */
    public static Drawable hairline(Theme t) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Theme.alpha(t.accent, 0.5f), Theme.alpha(t.border, 0.1f)});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(px(1));
        return d;
    }

    /** 行首序号徽章 */
    public static Drawable badge(Theme t, boolean strong) {
        if (strong) return solid(t.focusBg, 0, 0, 7);
        return solid(Theme.alpha(t.border, t.light ? 0.65f : 0.5f), 0, 0, 7);
    }

    /**
     * 亮块（光标行）上的序号徽章。
     *
     * badge(t, true) 用的是 focusBg——放在同色的亮块行上就是「白底白胶囊」，
     * 整块糊成一片。这里改用 onFocus 的半透明底/描边：白块上是淡灰胶囊，
     * 浅色主题的深块上是淡白胶囊，两种情况都还看得出这是一枚徽章。
     */
    public static Drawable badgeOnFocus(Theme t) {
        return solid(Theme.alpha(t.onFocus, 0.13f), Theme.alpha(t.onFocus, 0.20f), 1, 7);
    }

    /** 提示条 / 说明块底 */
    public static Drawable strip(Theme t) {
        return solid(Theme.alpha(t.surface, t.light ? 0.75f : 0.45f), Theme.alpha(t.border, 0.9f), 1, 12);
    }

    /** 按键提示里的键帽：像遥控器上的实体键，扫一眼就知道"现在能按什么" */
    public static Drawable keycap(Theme t) {
        return solid(Theme.alpha(t.text, t.light ? 0.06f : 0.09f),
                Theme.alpha(t.text, t.light ? 0.28f : 0.22f), 1, 6);
    }

    /** 域名快捷芯片（对话框里从现有条目挑域名） */
    public static Drawable chip(Theme t) {
        return solid(Theme.alpha(t.cardAlt, 0.9f), Theme.alpha(t.border, 0.9f), 1, 9);
    }

    // ==================== 按钮 ====================

    /** 普通操作按钮：暗块描边 → 光标上来变实心亮块 */
    public static Drawable button(Theme t) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, focusBlock(t, 12));
        s.addState(new int[]{android.R.attr.state_focused}, focusBlock(t, 12));
        s.addState(new int[]{-android.R.attr.state_enabled},
                solid(Theme.alpha(t.cardAlt, 0.45f), Theme.alpha(t.border, 0.5f), 1, 12));
        s.addState(new int[]{}, solid(t.cardAlt, t.border, 1.2f, 12));
        return s;
    }

    /** 主按钮：平时是强调色渐变（全屏唯一"彩色"的按钮），光标上来同样变亮块 */
    public static Drawable primary(Theme t) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, focusBlock(t, 12));
        s.addState(new int[]{android.R.attr.state_focused}, focusBlock(t, 12));
        s.addState(new int[]{-android.R.attr.state_enabled},
                solid(Theme.alpha(t.accentDeep, 0.35f), Theme.alpha(t.accentDeep, 0.5f), 1, 12));
        s.addState(new int[]{}, gradient(t.accentDeep, t.accent, 12));
        return s;
    }

    // ==================== 页签 ====================

    /**
     * 页签三态（这才是"层级"）：当前页 = 淡强调底 + 底部短横 + 强调色字；
     * 光标所在 = 亮块（和按钮同一套语言）。两者一眼能分开，不像以前
     * 「当前页=满色强调、光标=再加点白」那样糊成一片。
     */
    public static Drawable tab(Theme t, boolean active) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, focusBlock(t, 12));
        s.addState(new int[]{android.R.attr.state_focused}, focusBlock(t, 12));
        s.addState(new int[]{}, active ? tabActive(t) : tabIdle(t));
        return s;
    }

    private static Drawable tabActive(Theme t) {
        GradientDrawable base = solid(Theme.alpha(t.accent, t.light ? 0.12f : 0.15f),
                Theme.alpha(t.accent, 0.5f), 1.2f, 12);
        GradientDrawable bar = solid(t.accent, 0, 0, 2);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{base, bar});
        ld.setLayerGravity(1, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ld.setLayerWidth(1, px(30));
        ld.setLayerHeight(1, px(3));
        // setLayerInset(五参版) 从 API 1 就有；setLayerInsetBottom 要 API 23，别为它抬高 minSdk
        ld.setLayerInset(1, 0, 0, 0, px(6));
        return ld;
    }

    private static Drawable tabIdle(Theme t) {
        // 非激活页签也要有"按钮形态"：暗块 + 细描边，不再是一行漂在空白里的灰字
        return solid(Theme.alpha(t.surface, t.light ? 0.85f : 0.7f),
                Theme.alpha(t.border, 0.9f), 1.2f, 12);
    }

    // ==================== 列表行 ====================

    /**
     * @param selected 是否是选中行
     * @param strong   列表本身有焦点（此时选中行用最高对比的亮块）
     */
    public static Drawable row(Theme t, boolean selected, boolean strong) {
        if (!selected) return new ColorDrawable(0x00000000);
        if (strong) return solid(t.focusBg, Theme.alpha(t.focusRing, 0.95f), 2f, 11);
        return solid(Theme.alpha(t.accent, t.light ? 0.11f : 0.14f),
                Theme.alpha(t.accent, 0.45f), 1.2f, 11);
    }

    /** 选中行左侧的强调色竖条 */
    public static Drawable rowBar(Theme t, boolean selected, boolean strong) {
        if (!selected) return new ColorDrawable(0x00000000);
        return solid(strong ? t.accentDeep : t.accent, 0, 0, 2.5f);
    }

    // ==================== 其它 ====================

    /** 状态胶囊（root 状态、服务状态） */
    public static Drawable pill(Theme t, int fg) {
        return solid(Theme.alpha(fg, t.light ? 0.10f : 0.15f), Theme.alpha(fg, 0.42f), 1, 13);
    }

    /**
     * 对话框底：系统 AlertDialog 默认是一块 Material 灰，和这套配色放在一起像两个 App。
     * 换成同一张"卡片"，弹窗才像是这一个应用里的东西。
     */
    public static Drawable dialog(Theme t) {
        return solid(t.card, Theme.alpha(t.text, 0.12f), 1, 18);
    }

    /** 二维码白底框：二维码必须保持黑白高对比，只在外框上做主题化 */
    public static Drawable qrFrame(Theme t) {
        return solid(0xFFFFFFFF, Theme.alpha(t.accent, 0.5f), 2, 18);
    }

    /** 左上角品牌 logo */
    public static Drawable logo(Theme t) {
        return gradient(t.accent, t.accentDeep, 12);
    }

    /** 步骤序号圆点（扫码页用） */
    public static Drawable stepDot(Theme t) {
        return solid(Theme.alpha(t.accent, t.light ? 0.16f : 0.2f), Theme.alpha(t.accent, 0.6f), 1.2f, 20);
    }
}
