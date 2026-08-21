package dev.rocky.comcitime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

// A small auto-scaling line chart of one or more raw sensor value
// histories -- pushed from MappingCollector's ring buffers while the
// mapping settings section is open. A single Series with no axisLabel
// draws a plain scalar trend (pressure, RSSI, gyro-integrated heading);
// three Series (X/Y/Z, one color each) draws a vector sensor's axes
// separately instead of collapsing them into one magnitude number.
public class SparklineView extends View {

    public static class Series {
        final float[] values;
        final int color;
        final String axisLabel; // "X"/"Y"/"Z", or null for an unlabeled single series
        public Series(float[] values, int color, String axisLabel) {
            this.values = values;
            this.color = color;
            this.axisLabel = axisLabel;
        }
    }

    private List<Series> series = Collections.emptyList();
    private int count = 0;
    private final String label;
    private final String unit;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SparklineView(Context ctx, String label, String unit) {
        super(ctx);
        this.label = label;
        this.unit = unit;
        linePaint.setStrokeWidth(4f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        gridPaint.setColor(UiKit.BORDER);
        gridPaint.setStrokeWidth(2f);
        labelPaint.setColor(UiKit.TEXT_SECONDARY);
        labelPaint.setTextSize(22f);
        valuePaint.setTypeface(Typeface.DEFAULT_BOLD);
        valuePaint.setTextSize(21f);
    }

    public void setSeries(List<Series> series, int count) {
        this.series = series != null ? series : Collections.emptyList();
        this.count = count;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        int padTop = UiKit.dp(26), padBottom = UiKit.dp(6), padSide = UiKit.dp(4);

        canvas.drawText(label, padSide, UiKit.dp(18), labelPaint);

        if (count < 2 || series.isEmpty()) return;

        int n = series.get(0).values.length;
        int start = n - count;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (Series s : series) {
            for (int i = start; i < n; i++) {
                min = Math.min(min, s.values[i]);
                max = Math.max(max, s.values[i]);
            }
        }
        if (max - min < 0.001f) { max += 0.5f; min -= 0.5f; }
        float range = max - min;

        float plotH = h - padTop - padBottom;
        float plotW = w - padSide * 2f;

        canvas.drawLine(padSide, padTop + plotH / 2f, w - padSide, padTop + plotH / 2f, gridPaint);

        float legendRight = w - padSide;
        for (Series s : series) {
            linePaint.setColor(s.color);
            float prevX = 0, prevY = 0;
            boolean first = true;
            for (int i = start; i < n; i++) {
                float t = (float) (i - start) / (count - 1);
                float x = padSide + t * plotW;
                float y = padTop + plotH - ((s.values[i] - min) / range) * plotH;
                if (!first) canvas.drawLine(prevX, prevY, x, y, linePaint);
                prevX = x; prevY = y;
                first = false;
            }
            String text = String.format(Locale.KOREA, "%s%.1f%s",
                    s.axisLabel != null ? s.axisLabel + " " : "", s.values[n - 1], unit);
            valuePaint.setColor(s.color);
            float textW = valuePaint.measureText(text);
            legendRight -= textW;
            canvas.drawText(text, legendRight, UiKit.dp(18), valuePaint);
            legendRight -= UiKit.dp(12);
        }
    }
}
