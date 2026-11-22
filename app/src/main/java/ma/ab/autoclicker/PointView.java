package ma.ab.autoclicker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/**
 * نقطة النقر: هدف صغير (دائرة + صليب). النقرة كتجي بالضبط فالوسط ديالو.
 * فوق على اليسار: رقم الخطوة. تحت على اليمين: عدد النقرات إلا كان كثر من 1.
 */
public class PointView extends View {

    int step = 1;
    int count = 1;
    int color = 0xFFD93025;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    public PointView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.2f * d);
        cross.setColor(Color.WHITE);
        cross.setStrokeWidth(1.4f * d);
        cross.setShadowLayer(1.5f * d, 0, 0, Color.BLACK);
        dot.setColor(0xFFFFD43B);
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(9f * d);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // باش يخدم الظل ديال الصليب
    }

    void set(int step, int count, int color) {
        this.step = step;
        this.count = count;
        this.color = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = 10f * d;

        fill.setColor((color & 0x00FFFFFF) | 0x40000000);
        c.drawCircle(cx, cy, r, fill);
        ring.setColor(color | 0xFF000000);
        c.drawCircle(cx, cy, r, ring);

        float gap = 2.5f * d, len = 15f * d;
        c.drawLine(cx - len, cy, cx - gap, cy, cross);
        c.drawLine(cx + gap, cy, cx + len, cy, cross);
        c.drawLine(cx, cy - len, cx, cy - gap, cross);
        c.drawLine(cx, cy + gap, cx, cy + len, cross);
        c.drawCircle(cx, cy, 1.6f * d, dot);

        drawBadge(c, 7f * d, 7f * d, String.valueOf(step), color | 0xFF000000);
        if (count > 1) {
            drawBadge(c, getWidth() - 9f * d, getHeight() - 7f * d, "×" + count, 0xFF1E1B4B);
        }
    }

    private void drawBadge(Canvas c, float x, float y, String s, int bg) {
        float w = Math.max(12f * d, text.measureText(s) + 6f * d);
        float h = 12f * d;
        badge.setColor(bg);
        c.drawRoundRect(x - w / 2, y - h / 2, x + w / 2, y + h / 2, h / 2, h / 2, badge);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, text);
    }
}
