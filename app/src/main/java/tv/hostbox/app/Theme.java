package tv.hostbox.app;

/**
 * 主题调色板。所有颜色集中在这里，界面代码不再写死色值。
 *
 * 视觉约定（这决定「光标在哪」能不能一眼看出来）：
 *   1. 所有可聚焦但**没有**光标的控件 = 暗块 + 细描边（和背景同一个调子，视觉上"沉下去"）；
 *   2. 光标所在控件 = 与背景反差最大的**实心块**（深色主题用近白，浅色主题用深蓝），
 *      配 2.5dp 强调色描边，文字色同时反转；
 *   3. 强调色（accent）只用来表示状态：当前页签、选中行、可点的链接文字。
 * 这样「光标」和「选中」是两种完全不同的视觉语言，不会再糊在一起。
 */
public final class Theme {

    public final int id;
    public final String name;
    /** 浅色主题：描边、透明度、焦点色都要反过来处理 */
    public final boolean light;

    public final int bg;          // 页面底色
    public final int surface;     // 顶栏 / 未激活页签
    public final int card;        // 卡片（列表、说明块）
    public final int cardAlt;     // 普通按钮底
    public final int border;      // 细描边
    public final int text;        // 主文字
    public final int hint;        // 次要文字
    public final int accent;      // 强调色
    public final int accentDeep;  // 渐变暗端 / 主按钮
    public final int onAccent;    // 强调色上的文字
    public final int focusBg;     // 光标块底色
    public final int focusRing;   // 光标块描边
    public final int onFocus;     // 光标块上的文字
    public final int danger;
    public final int ok;

    private Theme(int id, String name, boolean light,
                  int bg, int surface, int card, int cardAlt, int border,
                  int text, int hint, int accent, int accentDeep, int onAccent,
                  int focusBg, int focusRing, int onFocus, int danger, int ok) {
        this.id = id;
        this.name = name;
        this.light = light;
        this.bg = bg;
        this.surface = surface;
        this.card = card;
        this.cardAlt = cardAlt;
        this.border = border;
        this.text = text;
        this.hint = hint;
        this.accent = accent;
        this.accentDeep = accentDeep;
        this.onAccent = onAccent;
        this.focusBg = focusBg;
        this.focusRing = focusRing;
        this.onFocus = onFocus;
        this.danger = danger;
        this.ok = ok;
    }

    public static final Theme[] ALL = {

            // 0. 深海蓝（默认）
            new Theme(0, "深海蓝", false,
                    0xFF0A1120, 0xFF101A2C, 0xFF16223A, 0xFF16223A, 0xFF26374F,
                    0xFFE9EFF9, 0xFF93A6C2, 0xFF4FC3F7, 0xFF1976D2, 0xFF06121E,
                    0xFFFFFFFF, 0xFF4FC3F7, 0xFF091120, 0xFFFF6B6B, 0xFF4ADE80),

            // 1. 石墨橙：低饱和中性灰 + 暖橙强调，晚上看不刺眼
            new Theme(1, "石墨橙", false,
                    0xFF111114, 0xFF1A1A1F, 0xFF222329, 0xFF222329, 0xFF35373F,
                    0xFFF0F0F3, 0xFF9C9DA8, 0xFFFFA726, 0xFFE07800, 0xFF1E1200,
                    0xFFFFFFFF, 0xFFFFA726, 0xFF131316, 0xFFFF6B6B, 0xFF66D98C),

            // 2. 午夜紫
            new Theme(2, "午夜紫", false,
                    0xFF0F0B1C, 0xFF171128, 0xFF211A38, 0xFF211A38, 0xFF352C56,
                    0xFFEEE9FB, 0xFFA9A1C6, 0xFFB98CFF, 0xFF6D3BE0, 0xFF16082E,
                    0xFFFFFFFF, 0xFFB98CFF, 0xFF0F0B1C, 0xFFFF7391, 0xFF63E6A8),

            // 3. 松林绿
            new Theme(3, "松林绿", false,
                    0xFF071410, 0xFF0E1F19, 0xFF12291F, 0xFF12291F, 0xFF21402F,
                    0xFFE7F4ED, 0xFF8FB2A0, 0xFF4ADEA0, 0xFF0E9F63, 0xFF03150C,
                    0xFFFFFFFF, 0xFF4ADEA0, 0xFF071410, 0xFFFF6B6B, 0xFF4ADEA0),

            // 4. 纯黑 OLED：黑底省电，适合 OLED / 暗房
            new Theme(4, "纯黑 OLED", false,
                    0xFF000000, 0xFF0B0B0E, 0xFF141418, 0xFF141418, 0xFF26262D,
                    0xFFF6F6F8, 0xFF90909B, 0xFF64D2FF, 0xFF0A84C9, 0xFF00121A,
                    0xFFFFFFFF, 0xFF64D2FF, 0xFF000000, 0xFFFF5F5F, 0xFF4ADE80),

            // 5. 日间浅色：白天/亮环境下黑底反光严重时用
            new Theme(5, "日间浅色", true,
                    0xFFEDF1F7, 0xFFF7F9FC, 0xFFFFFFFF, 0xFFF3F6FA, 0xFFD5DEE9,
                    0xFF17212E, 0xFF5E6D82, 0xFF1565C0, 0xFF0D47A1, 0xFFFFFFFF,
                    0xFF11243A, 0xFF1565C0, 0xFFFFFFFF, 0xFFD32F2F, 0xFF2E7D32),
    };

    public static Theme get(int id) {
        if (id < 0 || id >= ALL.length) return ALL[0];
        return ALL[id];
    }

    public static String[] names() {
        String[] out = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) out[i] = ALL[i].name;
        return out;
    }

    /** 半透明叠加：给强调色算出「浅底」，比直接调深色更贴合当前主题 */
    public static int alpha(int color, float a) {
        int al = Math.round(255 * a);
        if (al < 0) al = 0;
        if (al > 255) al = 255;
        return (color & 0x00FFFFFF) | (al << 24);
    }
}
