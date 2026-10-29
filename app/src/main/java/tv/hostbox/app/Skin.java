package tv.hostbox.app;

import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

/**
 * 按主题在运行时生成控件背景。
 *
 * 为什么不用 XML drawable：颜色写死在 <shape> 里，换主题就得换一整套资源。
 * 全部改成代码构建后，换主题只要换一个 {@link Theme} 调色板。
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

    /** 渐变圆角块（主按钮 / logo 用） */
    private static GradientDrawable gradient(int from, int to, float radiusDp) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{from, to});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(px(radiusDp));
        return d;
    }

    /** 光标块：整个界面里最高对比的一块，保证「光标在哪」一眼可见 */
    private static GradientDrawable focusBlock(Theme t, float radiusDp) {
        return solid(t.focusBg, t.focusRing, 2.5f, radiusDp);
    }

    // ==================== 卡片 ====================

    public static Drawable card(Theme t) {
        return solid(t.card, t.border, 1, 16);
    }

    /**
     * 「框态」卡片：焦点落在整个列表框上时用。
     * 描边换成强调色而不是直接用亮块 —— 列表里还要显示行，整块涂白会看不清内容。
     */
    public static Drawable cardFocus(Theme t) {
        return solid(t.card, t.accent, 2, 16);
    }

    /** 顶栏下方的细分隔线（左浓右淡，一点点视觉层次） */
    public static Drawable hairline(Theme t) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Theme.alpha(t.accent, 0.45f), Theme.alpha(t.border, 0.15f)});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(px(1));
        return d;
    }

    /** 行首序号徽章 */
    public static Drawable badge(Theme t, boolean strong) {
        if (strong) return solid(Theme.alpha(t.accentDeep, 0.85f), 0, 0, 6);
        return solid(Theme.alpha(t.accent, t.light ? 0.12f : 0.16f), 0, 0, 6);
    }

    /** 提示条 / 说明块底 */
    public static Drawable strip(Theme t) {
        return solid(Theme.alpha(t.surface, t.light ? 0.9f : 0.55f), t.border, 1, 11);
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

    /** 主按钮：平时是强调色渐变（唯一"彩色"的按钮），光标上来同样变亮块 */
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
     * @param active 是否是当前页签（强调色实心）
     * 光标落在页签上时一定是当前页签（左右移动焦点即切页），此时用亮块表示"光标在这里"。
     */
    public static Drawable tab(Theme t, boolean active) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, focusBlock(t, 12));
        s.addState(new int[]{android.R.attr.state_focused}, focusBlock(t, 12));
        if (active) {
            s.addState(new int[]{}, solid(t.accent, Theme.alpha(t.onAccent, 0.25f), 1.5f, 12));
        } else {
            s.addState(new int[]{}, solid(t.surface, t.border, 1.2f, 12));
        }
        return s;
    }

    // ==================== 列表行 ====================

    /**
     * @param selected 是否是选中行
     * @param strong   列表本身有焦点（此时选中行用最高对比的亮块）
     */
    public static Drawable row(Theme t, boolean selected, boolean strong) {
        if (!selected) return new ColorDrawable(0x00000000);
        if (strong) return solid(t.focusBg, Theme.alpha(t.focusRing, 0.9f), 1.5f, 10);
        return solid(Theme.alpha(t.accent, t.light ? 0.13f : 0.17f),
                Theme.alpha(t.accent, 0.5f), 1.2f, 10);
    }

    /** 选中行左侧的强调色竖条 */
    public static Drawable rowBar(Theme t, boolean selected, boolean strong) {
        if (!selected) return new ColorDrawable(0x00000000);
        return solid(strong ? t.accentDeep : t.accent, 0, 0, 2.5f);
    }

    // ==================== 其它 ====================

    /** 状态胶囊（root 状态、服务状态） */
    public static Drawable pill(Theme t, int fg) {
        return solid(Theme.alpha(fg, t.light ? 0.10f : 0.16f), Theme.alpha(fg, 0.45f), 1, 13);
    }

    /** 二维码白底框：二维码必须保持黑白高对比，只在外框上做主题化 */
    public static Drawable qrFrame(Theme t) {
        return solid(0xFFFFFFFF, Theme.alpha(t.accent, 0.55f), 2, 16);
    }

    /** 左上角品牌 logo */
    public static Drawable logo(Theme t) {
        return gradient(t.accent, t.accentDeep, 11);
    }
}
