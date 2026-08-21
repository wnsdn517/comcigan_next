package dev.rocky.comcitime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

// A small auto-scaling line chart of one raw sensor value's recent
// history -- pushed once a second from MappingCollector's ring buffers
// while the mapping settings section is open. Kept generic (one buffer +
// label + unit + color) so the same view class covers accelerometer,
// gyroscope, magnetometer, barometer and Wi-Fi RSSI trends without five
// near-identical copies.
public class SparklineView extends View {

    private float[] values = new float[0];
    private int count = 0;
    private final String label;
    private final String unit;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SparklineView(Context ctx, String label, String unit, int lineColor) {
        super(ctx);
        this.label = label;
        this.unit = unit;
        linePaint.setColor(lineColor);
        linePaint.setStrokeWidth(4f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        gridPaint.setColor(UiKit.BORDER);
        gridPaint.setStrokeWidth(2f);
        labelPaint.setColor(UiKit.TEXT_SECONDARY);
        labelPaint.setTextSize(22f);
        valuePaint.setColor(UiKit.TEXT_PRIMARY);
        valuePaint.setTypeface(Typeface.DEFAULT_BOLD);
        valuePaint.setTextSize(24f);
    }

    public void setData(float[] values, int count) {
        this.values = values;
        this.count = count;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        int padTop = UiKit.dp(26), padBottom = UiKit.dp(6), padSide = UiKit.dp(4);

        canvas.drawText(label, padSide, UiKit.dp(18), labelPaint);

        if (count < 2) return;

        int n = values.length;
        int start = n - count;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int i = start; i < n; i++) {
            min = Math.min(min, values[i]);
            max = Math.max(max, values[i]);
        }
        if (max - min < 0.001f) { max += 0.5f; min -= 0.5f; }
        float range = max - min;

        float plotH = h - padTop - padBottom;
        float plotW = w - padSide * 2f;

        canvas.drawLine(padSide, padTop + plotH / 2f, w - padSide, padTop + plotH / 2f, gridPaint);

        float prevX = 0, prevY = 0;
        boolean first = true;
        for (int i = start; i < n; i++) {
            float t = (float) (i - start) / (count - 1);
            float x = padSide + t * plotW;
            float y = padTop + plotH - ((values[i] - min) / range) * plotH;
            if (!first) canvas.drawLine(prevX, prevY, x, y, linePaint);
            prevX = x; prevY = y;
            first = false;
        }

        String cur = String.format(Locale.KOREA, "%.1f%s", values[n - 1], unit);
        float textW = valuePaint.measureText(cur);
        canvas.drawText(cur, w - textW - padSide, UiKit.dp(18), valuePaint);
    }
}
