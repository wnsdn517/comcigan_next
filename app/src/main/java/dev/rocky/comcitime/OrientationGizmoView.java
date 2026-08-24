package dev.rocky.comcitime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.Locale;

// Live 3D wireframe of the phone's current attitude (heading/pitch/roll
// from the fused rotation-vector sensor), redrawn on every
// setOrientation() call. Plain Canvas + manual rotation/projection math --
// simple enough not to need a GL surface for one small live indicator.
public class OrientationGizmoView extends View {

    private float headingDeg = 0f, pitchDeg = 0f, rollDeg = 0f;
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint topPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Phone modeled as a thin box (arbitrary drawing units), long axis
    // along Y so "top of phone" is +Y.
    private static final float HW = 46f, HL = 90f, HD = 8f;
    private static final int[][] EDGES = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0}, // bottom face
            {4, 5}, {5, 6}, {6, 7}, {7, 4}, // top face
            {0, 4}, {1, 5}, {2, 6}, {3, 7}, // verticals
    };

    public OrientationGizmoView(Context ctx) {
        super(ctx);
        edgePaint.setColor(UiKit.ACCENT);
        edgePaint.setStrokeWidth(4f);
        edgePaint.setStyle(Paint.Style.STROKE);
        topPaint.setColor(Color.argb(160, 255, 255, 255));
        topPaint.setStrokeWidth(3f);
        topPaint.setStyle(Paint.Style.STROKE);
        labelPaint.setColor(UiKit.TEXT_SECONDARY);
        labelPaint.setTextSize(24f);
    }

    public void setOrientation(float headingDeg, float pitchDeg, float rollDeg) {
        this.headingDeg = headingDeg;
        this.pitchDeg = pitchDeg;
        this.rollDeg = rollDeg;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        float[][] local = {
                {-HW, -HL, -HD}, {HW, -HL, -HD}, {HW, HL, -HD}, {-HW, HL, -HD},
                {-HW, -HL, HD}, {HW, -HL, HD}, {HW, HL, HD}, {-HW, HL, HD},
        };
        float[] sx = new float[8], sy = new float[8];
        for (int i = 0; i < 8; i++) {
            float[] r = rotate(local[i][0], local[i][1], local[i][2]);
            float focal = 320f;
            float scale = focal / (focal + r[2] + 240f);
            sx[i] = cx + r[0] * scale;
            sy[i] = cy - r[1] * scale;
        }
        for (int[] e : EDGES) {
            canvas.drawLine(sx[e[0]], sy[e[0]], sx[e[1]], sy[e[1]], edgePaint);
        }
        // Highlights the two verticals at the phone's +Y (top/speaker) end
        // so heading is readable straight from the drawing.
        canvas.drawLine(sx[2], sy[2], sx[6], sy[6], topPaint);
        canvas.drawLine(sx[3], sy[3], sx[7], sy[7], topPaint);

        String label = String.format(Locale.KOREA, "heading %.0f°  pitch %.0f°  roll %.0f°",
                headingDeg, pitchDeg, rollDeg);
        canvas.drawText(label, UiKit.dp(4), getHeight() - UiKit.dp(8), labelPaint);
    }

    private float[] rotate(float x, float y, float z) {
        // Android Azimuth (headingDeg) is CW positive. Math rotation is CCW.
        // We negate h to match the 3D projection's CCW expectation.
        double h = Math.toRadians(-headingDeg);
        double p = Math.toRadians(pitchDeg);
        double r = Math.toRadians(rollDeg);

        // 1. Roll around Y axis
        double x1 = x * Math.cos(r) + z * Math.sin(r);
        double z1 = -x * Math.sin(r) + z * Math.cos(r);

        // 2. Pitch around X axis
        double y2 = y * Math.cos(p) - z1 * Math.sin(p);
        double z2 = y * Math.sin(p) + z1 * Math.cos(p);

        // 3. Heading (Azimuth) around Z axis
        double x3 = x1 * Math.cos(h) - y2 * Math.sin(h);
        double y3 = x1 * Math.sin(h) + y2 * Math.cos(h);

        return new float[]{(float) x3, (float) y3, (float) z2};
    }
}
