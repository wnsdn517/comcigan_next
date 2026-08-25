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
// starting point, plus a barometer-derived floor/z at each point) as an
// isometric "3D-looking" floor plan -- the closest a flat Canvas gets to
// an actual 3D plot without pulling in a GL dependency for one small
// debug view. See MappingCollector for how the path itself is computed
// (steps x heading, not typed in by hand), and MappingDb.estimateApPositions
// for the Wi-Fi AP position estimates plotted alongside it. Touch
// controls follow the Google Maps convention:
// one finger dragged in any direction pans the view (userPanX/userPanY);
// two fingers pinched apart/together zoom (userZoom), twisted around each
// other rotate the view (userYawDeg), and dragged vertically together
// tilt the elevation angle (userPitchDeg) -- all three two-finger
// components are read continuously off the same two pointers rather than
// picking one exclusive gesture per touch-down. resetView() (wired to a
// "현위치 보기" button in MainActivity) snaps all four back to their
// defaults.
public class MappingPathView extends View {

    // Meters of vertical extrusion per floor of estimated change -- z is a
    // real third axis in isoCoords()/project() below (it rotates/tilts/
    // zooms along with x and y, unlike an earlier version of this view
    // that only ever bolted a fixed-pixel riser onto the current-position
    // dot). A typical school floor-to-floor height, so the extrusion reads
    // at a sensible scale against the 1-meter floor grid.
    private static final double METERS_PER_FLOOR = 3.5;

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
    private static final float ROTATE_SENSITIVITY = 1f; // degrees rotated per degree the 2-finger angle changes
    private static final float PITCH_SENSITIVITY = 0.3f; // degrees tilted per pixel the 2-finger centroid moves
    private static final float MIN_PITCH_OFFSET = -40f, MAX_PITCH_OFFSET = 44f; // keeps elevation in [5, 89] degrees
    private static final float MIN_ZOOM = 0.3f, MAX_ZOOM = 4f;

    // Google Maps-style gesture tracking: one finger pans; two or more
    // read pinch-zoom/rotate/tilt continuously off pointer indices 0 and
    // 1 specifically (not an average over every active pointer), matching
    // how a real two-finger gesture is defined -- a 3rd+ finger is simply
    // ignored rather than folded into a separate pan/zoom mode the way an
    // earlier version of this view used 3 fingers for. Re-baselined
    // whenever the pointer count changes so adding/removing a finger
    // never causes a jump.
    private static final int MODE_PAN = 1, MODE_MULTI = 2;
    private int gestureMode = MODE_PAN;
    private int lastPointerCount = 0;
    private float lastTouchX = 0f;
    private float lastTouchY = 0f;
    private float lastCentroidY = 0f;
    private float lastSpacing = 0f;
    private float lastAngleDeg = 0f;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint originPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint apPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floorUpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floorDownPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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

    // Each path point is {x, y, floorDelta} -- see MappingDb.recentPath().
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
            gestureMode = MODE_PAN;
            lastTouchX = event.getX(0);
            lastTouchY = event.getY(0);
        } else {
            gestureMode = MODE_MULTI;
            lastCentroidY = centroidY(event);
            lastSpacing = spacing(event);
            lastAngleDeg = angleDeg(event);
        }
    }

    // One finger pans (content follows the finger, like dragging a map --
    // this exact sign was already the tuned/verified pan mapping from
    // this view's earlier 3-finger pan mode, just retriggered off 1
    // finger now). Two or more read pinch-zoom, twist-to-rotate, and
    // vertical-drag-to-tilt continuously off the same two pointers --
    // tilt reuses the exact sign this view's earlier 2-finger tilt mode
    // already had verified on a real device; rotate's sign (twisting the
    // same direction visually rotates the view) is this rewrite's one
    // genuinely new, unverified guess -- flip it if it reads backwards on
    // a device, the same way pan/tilt's signs were originally tuned.
    private void applyGestureDelta(MotionEvent event) {
        if (gestureMode == MODE_PAN) {
            float x = event.getX(0), y = event.getY(0);
            float dx = x - lastTouchX, dy = y - lastTouchY;
            lastTouchX = x;
            lastTouchY = y;
            userPanX -= dx;
            userPanY -= dy;
            return;
        }

        float spacing = spacing(event);
        if (lastSpacing > 1f && spacing > 1f) {
            float ratio = clamp(spacing / lastSpacing, 0.85f, 1.18f);
            userZoom = clamp(userZoom * ratio, MIN_ZOOM, MAX_ZOOM);
        }
        lastSpacing = spacing;

        float angleDeg = angleDeg(event);
        float dAngle = angleDeltaDeg(angleDeg, lastAngleDeg);
        lastAngleDeg = angleDeg;
        userYawDeg = ((userYawDeg + dAngle * ROTATE_SENSITIVITY) % 360f + 360f) % 360f;

        float centroidY = centroidY(event);
        float dy = centroidY - lastCentroidY;
        lastCentroidY = centroidY;
        userPitchDeg = clamp(userPitchDeg + dy * PITCH_SENSITIVITY, MIN_PITCH_OFFSET, MAX_PITCH_OFFSET);
    }

    // Distance/angle/vertical-center of pointer indices 0 and 1
    // specifically, not an average over every active pointer -- see
    // MODE_MULTI's doc above for why.
    private static float spacing(MotionEvent e) {
        float dx = e.getX(1) - e.getX(0), dy = e.getY(1) - e.getY(0);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float angleDeg(MotionEvent e) {
        float dx = e.getX(1) - e.getX(0), dy = e.getY(1) - e.getY(0);
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    private static float centroidY(MotionEvent e) {
        return (e.getY(0) + e.getY(1)) / 2f;
    }

    // Shortest signed change from `to` to `from` in degrees, wrapped to
    // [-180, 180] -- a naive `from - to` breaks the instant the two-finger
    // angle crosses the +-180 boundary (e.g. 179 degrees to -179 degrees
    // is really a 2-degree rotation, not a 358-degree one).
    private static float angleDeltaDeg(float from, float to) {
        float d = (from - to) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
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
        double minZ = Math.min(0, floorDelta), maxZ = Math.max(0, floorDelta);
        for (double[] p : path) {
            minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
            if (p.length > 2) { minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]); }
        }
        for (MappingDb.ApEstimate ap : apEstimates) {
            minX = Math.min(minX, ap.x); maxX = Math.max(maxX, ap.x);
            minY = Math.min(minY, ap.y); maxY = Math.max(maxY, ap.y);
        }
        minX = Math.min(minX, 0); maxX = Math.max(maxX, 0);
        minY = Math.min(minY, 0); maxY = Math.max(maxY, 0);
        double midX = (minX + maxX) / 2, midY = (minY + maxY) / 2;

        // Fit scale from the projected bounding box of the four corners
        // (crossed with the min/max floor seen, so a route that changed
        // floors isn't clipped vertically), so the whole path stays
        // on-screen regardless of how far it's wandered from the origin
        // -- computed at the CURRENT yaw/pitch (via isoCoords()) so
        // rotating/tilting the view never clips content that was
        // on-screen before the gesture. userZoom is applied afterward, as
        // an explicit override on top of this fit, not folded into it.
        double isoMinX = Double.MAX_VALUE, isoMaxX = -Double.MAX_VALUE;
        double isoMinY = Double.MAX_VALUE, isoMaxY = -Double.MAX_VALUE;
        double[][] corners = {{minX, minY}, {minX, maxY}, {maxX, minY}, {maxX, maxY}};
        for (double[] c : corners) {
            for (double z : new double[]{minZ, maxZ}) {
                double[] iso = isoCoords(c[0], c[1], z, midX, midY);
                isoMinX = Math.min(isoMinX, iso[0]); isoMaxX = Math.max(isoMaxX, iso[0]);
                isoMinY = Math.min(isoMinY, iso[1]); isoMaxY = Math.max(isoMaxY, iso[1]);
            }
        }
        double spanIsoX = Math.max(1, isoMaxX - isoMinX);
        double spanIsoY = Math.max(1, isoMaxY - isoMinY);
        double scale = Math.min((w - UiKit.dp(32)) / spanIsoX, (h - UiKit.dp(32)) / spanIsoY);
        scale = Math.max(8, Math.min(scale, 80));

        float cx = w / 2f, cy = h / 2f;

        // Faint isometric floor grid every meter (at z=0, ground level),
        // for a sense of scale/depth.
        int gridExtent = (int) Math.min(40, Math.ceil(Math.max(maxX - minX, maxY - minY) / 2) + 2);
        for (int i = -gridExtent; i <= gridExtent; i++) {
            drawIsoLine(canvas, i, -gridExtent, i, gridExtent, 0, cx, cy, scale, midX, midY, gridPaint);
            drawIsoLine(canvas, -gridExtent, i, gridExtent, i, 0, cx, cy, scale, midX, midY, gridPaint);
        }

        float prevSx = 0, prevSy = 0;
        boolean first = true;
        for (double[] p : path) {
            double z = p.length > 2 ? p[2] : 0;
            float[] s = project(p[0], p[1], z, cx, cy, scale, midX, midY);
            if (!first) canvas.drawLine(prevSx, prevSy, s[0], s[1], pathPaint);
            prevSx = s[0]; prevSy = s[1];
            first = false;
        }

        for (MappingDb.ApEstimate ap : apEstimates) {
            float[] s = project(ap.x, ap.y, 0, cx, cy, scale, midX, midY);
            drawDiamond(canvas, s[0], s[1], UiKit.dp(6), apPaint);
        }

        float[] origin = project(0, 0, 0, cx, cy, scale, midX, midY);
        canvas.drawCircle(origin[0], origin[1], UiKit.dp(5), originPaint);

        float[] curGround = project(curX, curY, 0, cx, cy, scale, midX, midY);
        if (floorDelta != 0) {
            float[] curElevated = project(curX, curY, floorDelta, cx, cy, scale, midX, midY);
            // A faint ring stays at ground level as a shadow reference, a
            // riser line climbs (or drops) to the real position dot at its
            // actual floor -- both ends now driven by the same 3D
            // projection as everything else, so this riser rotates/tilts/
            // zooms consistently with the rest of the view.
            canvas.drawCircle(curGround[0], curGround[1], UiKit.dp(4), originPaint);
            canvas.drawLine(curGround[0], curGround[1], curElevated[0], curElevated[1],
                    floorDelta > 0 ? floorUpPaint : floorDownPaint);
            canvas.drawCircle(curElevated[0], curElevated[1], UiKit.dp(7), curPaint);
        } else {
            canvas.drawCircle(curGround[0], curGround[1], UiKit.dp(7), curPaint);
        }
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

    // Unscaled isometric (x, y) for one world point at height z (floors),
    // at the current view rotation (userYawDeg) and elevation
    // (userPitchDeg). At the defaults (yaw=0, pitch=0, z=0) this is
    // exactly the original fixed formula: rotating (dx, dy) by 45 degrees
    // gives ((dx-dy), (dx+dy)) * cos(45), and the 1.22474/0.70711 factors
    // (sqrt(1.5) and 1/sqrt(2) = sin(45)) are the same fixed horizontal-
    // stretch/vertical-squash that made that look isometric. Elevation
    // varies the vertical squash of BOTH the horizontal-plane component
    // (sin of the elevation angle: 90 degrees = flat top-down, no squash;
    // smaller = more edge-on) and how much height shows on screen (cos of
    // the same angle: edge-on shows height fully, top-down flattens it to
    // nothing) -- the same relationship a real isometric drawing has
    // between its ground plane and vertical axis. Horizontal spacing
    // itself stays fixed regardless of tilt, a common simplification in
    // 2.5D renderers.
    private double[] isoCoords(double x, double y, double z, double midX, double midY) {
        double dx = x - midX, dy = y - midY;
        double theta = Math.toRadians(45 + userYawDeg);
        double rx = dx * Math.cos(theta) - dy * Math.sin(theta);
        double ry = dx * Math.sin(theta) + dy * Math.cos(theta);
        double elevationRad = Math.toRadians(clamp((float) (45 + userPitchDeg), 5f, 89f));
        double isoX = rx * 1.22474; // sqrt(1.5)
        double isoY = ry * Math.sin(elevationRad) - z * METERS_PER_FLOOR * Math.cos(elevationRad);
        return new double[]{isoX, isoY};
    }

    // Final screen pixels for one world point: the rotated/tilted
    // isoCoords() above, scaled by the auto-fit `scale` times the user's
    // pinch-zoom (userZoom), then panned in screen space by userPanX/userPanY.
    private float[] project(double x, double y, double z, float cx, float cy, double scale, double midX, double midY) {
        double[] iso = isoCoords(x, y, z, midX, midY);
        float sx = (float) (cx + iso[0] * scale * userZoom) + userPanX;
        float sy = (float) (cy + iso[1] * scale * userZoom) + userPanY;
        return new float[]{sx, sy};
    }

    private void drawIsoLine(Canvas canvas, double x1, double y1, double x2, double y2, double z,
                              float cx, float cy, double scale, double midX, double midY, Paint paint) {
        float[] a = project(x1, y1, z, cx, cy, scale, midX, midY);
        float[] b = project(x2, y2, z, cx, cy, scale, midX, midY);
        canvas.drawLine(a[0], a[1], b[0], b[1], paint);
    }
}
