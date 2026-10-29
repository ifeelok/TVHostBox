package tv.hostbox.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * 轻量线性图标：全部用 Canvas 现画，不引入任何图片资源。
 *
 * 为什么要自己画：按钮上只有文字在电视上很难「扫一眼找到」，加个图标识别成本低很多；
 * 但引入 PNG/SVG 资源会让整套主题换色变麻烦（浅色主题需要另一套深色图标）。
 * 这里统一按 24x24 逻辑网格绘制，颜色在调用时传入，随主题/焦点态切换。
 */
public final class Icons {

    private Icons() {}

    public static final String REFRESH = "refresh";
    public static final String PLUS = "plus";
    public static final String CLOCK = "clock";
    public static final String SLIDERS = "sliders";
    public static final String QR = "qr";
    public static final String POWER = "power";
    public static final String PLAY = "play";
    public static final String LIST = "list";
    public static final String PHONE = "phone";
    public static final String INFO = "info";
    public static final String CHECK = "check";
    public static final String ALERT = "alert";
    public static final String CHEVRON = "chevron";
    public static final String DOT = "dot";
    public static final String WIFI = "wifi";
    public static final String PENCIL = "pencil";

    /** @param sizeDp 图标视觉尺寸（dp）；@param color ARGB 颜色 */
    public static Drawable drawable(Context c, String kind, int sizeDp, int color) {
        int px = Math.max(1, Math.round(sizeDp * c.getResources().getDisplayMetrics().density));
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        // 让系统按 dp 还原尺寸，避免高密度屏上图标被放大成马赛克
        bmp.setDensity(c.getResources().getDisplayMetrics().densityDpi);
        Canvas cv = new Canvas(bmp);
        float s = px / 24f;
        cv.scale(s, s);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);

        Paint fill = new Paint(p);
        fill.setStyle(Paint.Style.FILL);

        Path path = new Path();

        if (REFRESH.equals(kind)) {
            // 开口圆环 + 箭头
            RectF r = new RectF(4.2f, 4.2f, 19.8f, 19.8f);
            cv.drawArc(r, -55, 290, false, p);
            path.moveTo(17.4f, 2.4f);
            path.lineTo(20.8f, 5.4f);
            path.lineTo(17.0f, 8.0f);
            path.close();
            cv.drawPath(path, fill);
        } else if (PLUS.equals(kind)) {
            cv.drawLine(12, 5, 12, 19, p);
            cv.drawLine(5, 12, 19, 12, p);
        } else if (CLOCK.equals(kind)) {
            cv.drawCircle(12, 12, 8.2f, p);
            cv.drawLine(12, 12, 12, 7.4f, p);
            cv.drawLine(12, 12, 16.0f, 13.8f, p);
        } else if (SLIDERS.equals(kind)) {
            // 三条滑杆：比齿轮在小尺寸下清晰得多
            float[] ys = {7f, 12f, 17f};
            float[] xs = {9f, 15f, 11f};
            for (int i = 0; i < 3; i++) {
                cv.drawLine(4, ys[i], 20, ys[i], p);
                cv.drawCircle(xs[i], ys[i], 2.3f, fill);
            }
        } else if (QR.equals(kind)) {
            // 三个定位角 + 右下一个点
            drawCorner(cv, p, 3.6f, 3.6f);
            drawCorner(cv, p, 14.4f, 3.6f);
            drawCorner(cv, p, 3.6f, 14.4f);
            cv.drawCircle(17.2f, 17.2f, 1.7f, fill);
        } else if (POWER.equals(kind)) {
            cv.drawArc(new RectF(4.6f, 5.4f, 19.4f, 20.2f), -60, 300, false, p);
            cv.drawLine(12, 2.6f, 12, 11.4f, p);
        } else if (PLAY.equals(kind)) {
            path.moveTo(8, 5);
            path.lineTo(19, 12);
            path.lineTo(8, 19);
            path.close();
            cv.drawPath(path, fill);
        } else if (LIST.equals(kind)) {
            for (int i = 0; i < 3; i++) {
                float y = 7 + i * 5;
                cv.drawCircle(5.4f, y, 1.5f, fill);
                cv.drawLine(9.6f, y, 20, y, p);
            }
        } else if (PHONE.equals(kind)) {
            cv.drawRoundRect(new RectF(7, 2.6f, 17, 21.4f), 2.4f, 2.4f, p);
            cv.drawLine(10.6f, 18.6f, 13.4f, 18.6f, p);
        } else if (INFO.equals(kind)) {
            cv.drawCircle(12, 12, 8.4f, p);
            cv.drawCircle(12, 7.8f, 1.15f, fill);
            cv.drawLine(12, 11.2f, 12, 16.6f, p);
        } else if (CHECK.equals(kind)) {
            // 状态「可用」：对勾比文字 ✔ 更像界面元素（也不会因字体缺失变成豆腐块）
            path.moveTo(4.6f, 12.4f);
            path.lineTo(9.4f, 17.2f);
            path.lineTo(19.2f, 6.8f);
            cv.drawPath(path, p);
        } else if (ALERT.equals(kind)) {
            // 状态「异常」：三角 + 感叹号
            path.moveTo(12, 3.4f);
            path.lineTo(21.4f, 19.6f);
            path.lineTo(2.6f, 19.6f);
            path.close();
            cv.drawPath(path, p);
            cv.drawLine(12, 9.4f, 12, 14.2f, p);
            cv.drawCircle(12, 16.9f, 1.15f, fill);
        } else if (CHEVRON.equals(kind)) {
            // 行尾「可进入」提示，只在光标所在行出现
            path.moveTo(9.6f, 5.4f);
            path.lineTo(16.2f, 12f);
            path.lineTo(9.6f, 18.6f);
            cv.drawPath(path, p);
        } else if (DOT.equals(kind)) {
            cv.drawCircle(12, 12, 4.2f, fill);
        } else if (WIFI.equals(kind)) {
            // 三段弧 + 圆点：家庭网络场景里"连同一个 Wi-Fi"用图形说更省字
            cv.drawArc(new RectF(2.4f, 5.2f, 21.6f, 24.4f), 215, 110, false, p);
            cv.drawArc(new RectF(6.2f, 9.0f, 17.8f, 20.6f), 215, 110, false, p);
            cv.drawArc(new RectF(10.0f, 12.8f, 14.0f, 16.8f), 215, 110, false, p);
            cv.drawCircle(12, 19.4f, 1.5f, fill);
        } else if (PENCIL.equals(kind)) {
            path.moveTo(5.2f, 18.8f);
            path.lineTo(6.4f, 14.2f);
            path.lineTo(16.2f, 4.4f);
            path.lineTo(19.6f, 7.8f);
            path.lineTo(9.8f, 17.6f);
            path.close();
            cv.drawPath(path, p);
        }
        return new BitmapDrawable(c.getResources(), bmp);
    }

    /** 二维码定位角：外框 + 内点 */
    private static void drawCorner(Canvas cv, Paint p, float x, float y) {
        cv.drawRoundRect(new RectF(x, y, x + 6f, y + 6f), 1.4f, 1.4f, p);
        Paint f = new Paint(p);
        f.setStyle(Paint.Style.FILL);
        cv.drawCircle(x + 3f, y + 3f, 1.35f, f);
    }
}
