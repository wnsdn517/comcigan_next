package dev.rocky.comcitime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

// Draws the dead-reckoned movement path (x/y meters from the session's
// starting point) as an isometric "3D-looking" floor plan -- the closest
// a flat Canvas gets to an actual 3D plot without pulling in a GL
// dependency for one small debug view. See MappingCollector for how the
// path itself is computed (steps x heading, not typed in by hand), and
// MappingDb.estimateApPositions for the Wi-Fi AP position estimates
// plotted alongside it. One finger dragged left/right spins the view
// around the vertical axis (userYawDeg); two fingers dragged up/down tilt
// the elevation angle (userPitchDeg); three fingers dragged in any
// direction pan the view (userPanX/userPanY) and pinched apart/together
// zoom it (userZoom). resetView() (wired to a "현위치 보기" button in
// MainActivity) snaps all four back to their defaults.
public class MappingPathView extends View {

    private List<double[]> path = new ArrayList<>();
    private double curX = 0, curY = 0;
    private int floorDelta = 0;
    private List<MappingDb.ApEstimate> apEstimates = new ArrayList<>();

    // View-control state, all driven by touch gestures in onTouchEvent()
    // -- see the class doc above for which gesture controls which field.
    // 0/0/1/0/0 (yaw/pitch/zoom/panX/panY) is the original fixed-angle view.
    private float userYawDeg = 0f;
    private float userPitchDeg = 0f;
    private float userZoom = 1f;
    private float userPanX = 0f;
    private float userPanY = 0f;
    private static final float ROTATE_SENSITIVITY = 0.4f; // degrees rotated per pixel dragged (1 finger)
    private static final float PITCH_SENSITIVITY = 0.3f; // degrees tilted per pixel dragged (2 fingers)
    private static final float MIN_PITCH_OFFSET = -40f, MAX_PITCH_OFFSET = 44f; // keeps elevation in [5, 89] degrees
    private static final float MIN_ZOOM = 0.3f, MAX_ZOOM = 4f;

    // Multi-touch gesture tracking: which gesture the current touch
    // sequence means (decided by how many fingers are down) and the
    // reference values deltas are measured against, re-baselined whenever
    // the pointer count changes so adding/removing a finger never causes
    // a jump.
    private static final int MODE_YAW = 1, MODE_PITCH = 2, MODE_PAN_ZOOM = 3;
    private int gestureMode = MODE_YAW;
    private int lastPointerCount = 0;
    private float lastTouchX = 0f;
    private float lastAvgX = 0f;
    private float lastAvgY = 0f;
    private float lastAvgSpacing = 0f;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint originPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint apPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floorUpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floorDownPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Vertical screen distance drawn per floor of estimated change -- see
    // drawElevatedPosition(). Tuned to read clearly against the 1-meter
    // floor grid without dwarfing it.
    private static final int FLOOR_HEIGHT_DP = 22;

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
        floorUpPaint.setColor(0xFF57C785); // same green as curPaint -- "going up"
        floorUpPaint.setStyle(Paint.Style.STROKE);
        floorUpPaint.setStrokeWidth(UiKit.dp(2));
        floorDownPaint.setColor(0xFFFF7A7A); // warm red -- "going down"
        floorDownPaint.setStyle(Paint.Style.STROKE);
        floorDownPaint.setStrokeWidth(UiKit.dp(2));
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

    // See MappingCollector.getEstimatedFloorDelta() -- a barometer-derived
    // estimate of how many floors above (positive) or below (negative) the
    // session's starting altitude the current position is. Drawn as an
    // arrow next to the current-position dot rather than only being
    // available as text, since "am I going up or down" is exactly the kind
    // of thing a floor plan view should answer at a glance.
    public void setFloorDelta(int floorDelta) {
        if (this.floorDelta == floorDelta) return;
        this.floorDelta = floorDelta;
        invalidate();
    }

    // Snaps back to the default view (no rotation/tilt/zoom/pan) -- wired
    // to the "현위치 보기" button, since dragging away from it is
    // otherwise the only way to change what this view shows.
    public void resetView() {
        userYawDeg = 0f;
        userPitchDeg = 0f;
        userZoom = 1f;
        userPanX = 0f;
        userPanY = 0f;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                // This view sits inside a vertically scrolling Settings
                // page -- claim the gesture instead of letting the
                // ScrollView steal it as a page scroll.
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                resetGestureBaseline(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                // A finger was added/removed since the last MOVE (the
                // POINTER_DOWN/UP branch above already re-baselined for
                // it, but the pointer index MotionEvent reports for
                // POINTER_UP still includes the lifting finger) -- treat
                // this frame as a fresh baseline too, so no delta is
                // computed off mismatched finger counts.
                if (event.getPointerCount() != lastPointerCount) {
                    resetGestureBaseline(event);
                    return true;
                }
                applyGestureDelta(event);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lastPointerCount = 0;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    // Picks which gesture the current finger count means, and captures
    // the reference values applyGestureDelta() will measure change
    // against -- called on every finger added/removed so transitions
    // don't jump.
    private void resetGestureBaseline(MotionEvent event) {
        int n = event.getPointerCount();
        lastPointerCount = n;
        if (n <= 1) {
            gestureMode = MODE_YAW;
            lastTouchX = event.getX(0);
        } else if (n == 2) {
            gestureMode = MODE_PITCH;
            lastAvgY = averageY(event);
        } else {
            gestureMode = MODE_PAN_ZOOM;
            lastAvgX = averageX(event);
            lastAvgY = averageY(event);
            lastAvgSpacing = centroidSpread(event);
        }
    }

    // Directions below are all inverted from the naive "delta follows the
    // finger" mapping -- reported as feeling backwards once actually used
    // on a device, so every axis here is negated relative to the raw
    // touch delta.
    private void applyGestureDelta(MotionEvent event) {
        switch (gestureMode) {
            case MODE_YAW: {
                float x = event.getX(0);
                float dx = x - lastTouchX;
                lastTouchX = x;
                userYawDeg = (userYawDeg - dx * ROTATE_SENSITIVITY) % 360f;
                break;
            }
            case MODE_PITCH: {
                float avgY = averageY(event);
                float dy = avgY - lastAvgY;
                lastAvgY = avgY;
                userPitchDeg = clamp(userPitchDeg + dy * PITCH_SENSITIVITY, MIN_PITCH_OFFSET, MAX_PITCH_OFFSET);
                break;
            }
            case MODE_PAN_ZOOM: {
                float avgX = averageX(event);
                float avgY = averageY(event);
                float dx = avgX - lastAvgX;
                float dy = avgY - lastAvgY;
                lastAvgX = avgX;
                lastAvgY = avgY;
                userPanX -= dx;
                userPanY -= dy;

                float spacing = centroidSpread(event);
                if (lastAvgSpacing > 1f && spacing > 1f) {
                    float ratio = clamp(spacing / lastAvgSpacing, 0.85f, 1.18f);
                    userZoom = clamp(userZoom * ratio, MIN_ZOOM, MAX_ZOOM);
                }
                lastAvgSpacing = spacing;
                break;
            }
        }
    }

    private static float averageX(MotionEvent e) {
        int n = e.getPointerCount();
        float sum = 0;
        for (int i = 0; i < n; i++) sum += e.getX(i);
        return sum / n;
    }

    private static float averageY(MotionEvent e) {
        int n = e.getPointerCount();
        float sum = 0;
        for (int i = 0; i < n; i++) sum += e.getY(i);
        return sum / n;
    }

    // Average distance of each active pointer from their shared centroid
    // -- a pinch-distance measure that generalizes cleanly to 3+ fingers
    // (unlike a single pairwise distance, which only works for exactly 2).
    private static float centroidSpread(MotionEvent e) {
        int n = e.getPointerCount();
        float cx = 0, cy = 0;
        for (int i = 0; i < n; i++) { cx += e.getX(i); cy += e.getY(i); }
        cx /= n; cy /= n;
        float sum = 0;
        for (int i = 0; i < n; i++) {
            float dx = e.getX(i) - cx, dy = e.getY(i) - cy;
            sum += (float) Math.sqrt(dx * dx + dy * dy);
        }
        return sum / n;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
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
        // wandered from the origin -- computed at the CURRENT yaw/pitch
        // (via isoCoords()) so rotating/tilting the view never clips
        // content that was on-screen before the gesture. userZoom is
        // applied afterward, as an explicit override on top of this fit,
        // not folded into it.
        double isoMinX = Double.MAX_VALUE, isoMaxX = -Double.MAX_VALUE;
        double isoMinY = Double.MAX_VALUE, isoMaxY = -Double.MAX_VALUE;
        double[][] corners = {{minX, minY}, {minX, maxY}, {maxX, minY}, {maxX, maxY}};
        for (double[] c : corners) {
            double[] iso = isoCoords(c[0], c[1], midX, midY);
            isoMinX = Math.min(isoMinX, iso[0]); isoMaxX = Math.max(isoMaxX, iso[0]);
            isoMinY = Math.min(isoMinY, iso[1]); isoMaxY = Math.max(isoMaxY, iso[1]);
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
        if (floorDelta != 0) {
            drawElevatedPosition(canvas, cur[0], cur[1]);
        } else {
            canvas.drawCircle(cur[0], cur[1], UiKit.dp(7), curPaint);
        }
    }

    // Renders floor change as actual vertical displacement in the
    // isometric space, the way an isometric game shows height, instead of
    // a flat badge/number bolted onto a 2D dot: a faint ring stays at
    // ground level (cur[0], cur[1]) as a shadow reference, a riser line
    // climbs (or drops) from it by floorDelta floors, and the real
    // position dot sits at the far end of that riser -- so "went up two
    // floors" reads as the dot visibly floating two floor-heights above
    // its own ground shadow, not as a "+2" label.
    private void drawElevatedPosition(Canvas canvas, float groundX, float groundY) {
        boolean up = floorDelta > 0;
        float riserPx = Math.abs(floorDelta) * UiKit.dp(FLOOR_HEIGHT_DP);
        float elevatedY = up ? groundY - riserPx : groundY + riserPx;
        canvas.drawCircle(groundX, groundY, UiKit.dp(4), originPaint);
        canvas.drawLine(groundX, groundY, groundX, elevatedY, up ? floorUpPaint : floorDownPaint);
        canvas.drawCircle(groundX, elevatedY, UiKit.dp(7), curPaint);
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

    // Unscaled isometric (x, y) for one world point, at the current view
    // rotation (userYawDeg) and elevation (userPitchDeg). At the defaults
    // (yaw=0, pitch=0) this is exactly the original fixed formula:
    // rotating (dx, dy) by 45 degrees gives ((dx-dy), (dx+dy)) * cos(45),
    // and the 1.22474/0.70711 factors (sqrt(1.5) and 1/sqrt(2) = sin(45))
    // are the same fixed horizontal-stretch/vertical-squash that made
    // that look isometric. Elevation only varies the vertical squash
    // (sin of the elevation angle: 90 degrees = flat top-down, no squash;
    // smaller = more edge-on) -- horizontal spacing stays fixed regardless
    // of tilt, a common simplification in 2.5D renderers.
    private double[] isoCoords(double x, double y, double midX, double midY) {
        double dx = x - midX, dy = y - midY;
        double theta = Math.toRadians(45 + userYawDeg);
        double rx = dx * Math.cos(theta) - dy * Math.sin(theta);
        double ry = dx * Math.sin(theta) + dy * Math.cos(theta);
        double elevationDeg = clamp((float) (45 + userPitchDeg), 5f, 89f);
        double isoX = rx * 1.22474; // sqrt(1.5)
        double isoY = ry * Math.sin(Math.toRadians(elevationDeg));
        return new double[]{isoX, isoY};
    }

    // Final screen pixels for one world point: the rotated/tilted
    // isoCoords() above, scaled by the auto-fit `scale` times the user's
    // pinch-zoom (userZoom), then panned in screen space by userPanX/userPanY.
    private float[] project(double x, double y, float cx, float cy, double scale, double midX, double midY) {
        double[] iso = isoCoords(x, y, midX, midY);
        float sx = (float) (cx + iso[0] * scale * userZoom) + userPanX;
        float sy = (float) (cy + iso[1] * scale * userZoom) + userPanY;
        return new float[]{sx, sy};
    }

    private void drawIsoLine(Canvas canvas, double x1, double y1, double x2, double y2,
                              float cx, float cy, double scale, double midX, double midY, Paint paint) {
        float[] a = project(x1, y1, cx, cy, scale, midX, midY);
        float[] b = project(x2, y2, cx, cy, scale, midX, midY);
        canvas.drawLine(a[0], a[1], b[0], b[1], paint);
    }
}
