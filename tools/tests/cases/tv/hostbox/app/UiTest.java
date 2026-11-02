package tv.hostbox.app;

/**
 * 扫码页排版取舍的测试：直接调 MainActivity 里的真方法（qrSideDp / stepsFitWide），
 * 不复制一份逻辑来测自己。
 *
 * 背景：三步说明竖排时右边一大片是空的，改成宽屏横排；但窄屏（手机、分屏、老 4:3）
 * 横排会把每行字挤成两行，必须退回竖排。这个判断错了，界面不是空就是挤。
 */
public class UiTest {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, int expect, int actual) {
        check(name + "（期望 " + expect + " 实际 " + actual + "）", expect == actual);
    }

    /** 竖排时右侧说明列的可用宽度（dp）：屏宽 - 安全边距 - 二维码卡 - 间距 */
    private static int tipsW(int screenWdp) {
        int qr = MainActivity.qrSideDp(screenWdp);
        return screenWdp - 88 - (qr + 36 + 20);
    }

    /** 横排时每一格的宽度（dp） */
    private static int stepW(int screenWdp) {
        return (tipsW(screenWdp) - 2 * 20) / 3;
    }

    public static void main(String[] args) {
        System.out.println("=== 扫码页排版（MainActivity.qrSideDp / stepsFitWide）===");

        // ---- 二维码边长：跟着屏宽长，最多 240dp ----
        eq("1080p 电视（1280dp 宽）二维码取上限", 240, MainActivity.qrSideDp(1280));
        eq("4K（1600dp 宽）不会无限长大", 240, MainActivity.qrSideDp(1600));
        eq("中等屏按 30% 屏宽缩", 210, MainActivity.qrSideDp(700));
        eq("手机（333dp 宽）按 30% 缩到 100dp", 100, MainActivity.qrSideDp(333));

        // ---- 横排 / 竖排：电视横排，手机竖排 ----
        check("1080p 电视横排", MainActivity.stepsFitWide(1280, MainActivity.qrSideDp(1280)));
        check("4K 横排", MainActivity.stepsFitWide(1600, MainActivity.qrSideDp(1600)));
        check("手机竖排", !MainActivity.stepsFitWide(333, MainActivity.qrSideDp(333)));
        check("720x480 老电视（1080dp 宽）竖排",
                !MainActivity.stepsFitWide(1080, MainActivity.qrSideDp(1080)));

        // ---- 边界：说明列 700dp 是横竖分界，±1dp 都要判对 ----
        eq("边界处说明列宽度", 700, tipsW(1084));
        check("说明列 700dp → 横排（边界内）",
                MainActivity.stepsFitWide(1084, MainActivity.qrSideDp(1084)));
        check("说明列 699dp → 竖排（边界外）",
                !MainActivity.stepsFitWide(1083, MainActivity.qrSideDp(1083)));

        // ---- 横排时每格要放得下「圆点 + 13 个汉字 @15sp ≈ 195dp」才不会换行 ----
        check("1080p 每格 " + stepW(1280) + "dp ≥ 220dp（不换行）", stepW(1280) >= 220);
        check("4K 每格 " + stepW(1600) + "dp ≥ 220dp（不换行）", stepW(1600) >= 220);
        check("1083dp 屏每格只有 " + stepW(1083) + "dp < 220dp，所以不横排", stepW(1083) < 220);

        // ---- 卡片本身不能被挤出可用宽度（竖排的窄屏最容易出事）----
        int cardW = MainActivity.qrSideDp(333) + 36;
        check("手机上二维码卡 " + cardW + "dp 装得进 " + (333 - 88) + "dp 的内容区",
                cardW + 20 < 333 - 88);
        cardW = MainActivity.qrSideDp(1080) + 36;
        check("老电视上二维码卡 " + cardW + "dp 装得进 " + (1080 - 88) + "dp 的内容区",
                cardW + 20 < 1080 - 88);

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
