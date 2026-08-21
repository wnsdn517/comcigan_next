package dev.rocky.comcitime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

// Draws the dead-reckoned movement path (x/y meters from the session's
// starting point) as an isometric "3D-looking" floor plan -- the closest
// a flat Canvas gets to an actual 3D plot without pulling in a GL
// dependency for one small debug view. See MappingCollector for how the
// path itself is computed (steps x heading, not typed in by hand), and
// MappingDb.estimateApPositions for the Wi-Fi AP position estimates
// plotted alongside it.
public class MappingPathView extends View {

    private List<double[]> path = new ArrayList<>();
    private double curX = 0, curY = 0;
    private List<MappingDb.ApEstimate> apEstimates = new ArrayList<>();

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint originPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint apPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MappingPathView(Context ctx) {
        super(ctx);
        gridPaint.setColor(UiKit.BORDER);
        gridPaint.setStrokeWidth(2f);
        pathPaint.setColor(UiKit.ACCENT);
        pathPaint.setStrokeWidth(5f);
        pathPaint.setStyle(Paint.Style.STROKE);
        pathPaint.setStrokeCap(Paint.Cap.ROUND);
        originPaint.setColor(UiKit.TEXT_SECONDARY);
        originPaint.setStyle(Paint.Style.FILL);
        curPaint.setColor(0xFF57C785);
        curPaint.setStyle(Paint.Style.FILL);
        apPaint.setColor(0xFFB57BFF);
        apPaint.setStyle(Paint.Style.FILL);
        emptyPaint.setColor(UiKit.TEXT_SECONDARY);
        emptyPaint.setTextSize(28f);
    }

    public void setPath(List<double[]> path, double curX, double curY) {
        this.path = path != null ? path : new ArrayList<>();
        this.curX = curX;
        this.curY = curY;
        invalidate();
    }

    public void setApEstimates(List<MappingDb.ApEstimate> apEstimates) {
        this.apEstimates = apEstimates != null ? apEstimates : new ArrayList<>();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (path.isEmpty()) {
            canvas.drawText("아직 이동 기록이 없어요.", UiKit.dp(12), h / 2f, emptyPaint);
            return;
        }

        double minX = curX, maxX = curX, minY = curY, maxY = curY;
        for (double[] p : path) {
            minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
        }
        for (MappingDb.ApEstimate ap : apEstimates) {
            minX = Math.min(minX, ap.x); maxX = Math.max(maxX, ap.x);
            minY = Math.min(minY, ap.y); maxY = Math.max(maxY, ap.y);
        }
        minX = Math.min(minX, 0); maxX = Math.max(maxX, 0);
        minY = Math.min(minY, 0); maxY = Math.max(maxY, 0);
        double midX = (minX + maxX) / 2, midY = (minY + maxY) / 2;

        // Fit scale from the projected bounding box of the four corners,
        // so the whole path stays on-screen regardless of how far it's
        // wandered from the origin.
        double isoMinX = Double.MAX_VALUE, isoMaxX = -Double.MAX_VALUE;
        double isoMinY = Double.MAX_VALUE, isoMaxY = -Double.MAX_VALUE;
        double[][] corners = {{minX, minY}, {minX, maxY}, {maxX, minY}, {maxX, maxY}};
        for (double[] c : corners) {
            double dx = c[0] - midX, dy = c[1] - midY;
            double isoX = (dx - dy) * 0.866;
            double isoY = (dx + dy) * 0.5;
            isoMinX = Math.min(isoMinX, isoX); isoMaxX = Math.max(isoMaxX, isoX);
            isoMinY = Math.min(isoMinY, isoY); isoMaxY = Math.max(isoMaxY, isoY);
        }
        double spanIsoX = Math.max(1, isoMaxX - isoMinX);
        double spanIsoY = Math.max(1, isoMaxY - isoMinY);
        double scale = Math.min((w - UiKit.dp(32)) / spanIsoX, (h - UiKit.dp(32)) / spanIsoY);
        scale = Math.max(8, Math.min(scale, 80));

        float cx = w / 2f, cy = h / 2f;

        // Faint isometric floor grid every meter, for a sense of scale/depth.
        int gridExtent = (int) Math.min(40, Math.ceil(Math.max(maxX - minX, maxY - minY) / 2) + 2);
        for (int i = -gridExtent; i <= gridExtent; i++) {
            drawIsoLine(canvas, i, -gridExtent, i, gridExtent, cx, cy, scale, midX, midY, gridPaint);
            drawIsoLine(canvas, -gridExtent, i, gridExtent, i, cx, cy, scale, midX, midY, gridPaint);
        }

        float prevSx = 0, prevSy = 0;
        boolean first = true;
        for (double[] p : path) {
            float[] s = project(p[0], p[1], cx, cy, scale, midX, midY);
            if (!first) canvas.drawLine(prevSx, prevSy, s[0], s[1], pathPaint);
            prevSx = s[0]; prevSy = s[1];
            first = false;
        }

        for (MappingDb.ApEstimate ap : apEstimates) {
            float[] s = project(ap.x, ap.y, cx, cy, scale, midX, midY);
            drawDiamond(canvas, s[0], s[1], UiKit.dp(6), apPaint);
        }

        float[] origin = project(0, 0, cx, cy, scale, midX, midY);
        canvas.drawCircle(origin[0], origin[1], UiKit.dp(5), originPaint);

        float[] cur = project(curX, curY, cx, cy, scale, midX, midY);
        canvas.drawCircle(cur[0], cur[1], UiKit.dp(7), curPaint);
    }

    private void drawDiamond(Canvas canvas, float cx, float cy, float r, Paint paint) {
        Path diamond = new Path();
        diamond.moveTo(cx, cy - r);
        diamond.lineTo(cx + r, cy);
        diamond.lineTo(cx, cy + r);
        diamond.lineTo(cx - r, cy);
        diamond.close();
        canvas.drawPath(diamond, paint);
    }

    private float[] project(double x, double y, float cx, float cy, double scale, double midX, double midY) {
        double dx = x - midX, dy = y - midY;
        double isoX = (dx - dy) * 0.866;
        double isoY = (dx + dy) * 0.5;
        return new float[]{(float) (cx + isoX * scale), (float) (cy + isoY * scale)};
    }

    private void drawIsoLine(Canvas canvas, double x1, double y1, double x2, double y2,
                              float cx, float cy, double scale, double midX, double midY, Paint paint) {
        float[] a = project(x1, y1, cx, cy, scale, midX, midY);
        float[] b = project(x2, y2, cx, cy, scale, midX, midY);
        canvas.drawLine(a[0], a[1], b[0], b[1], paint);
    }
}
