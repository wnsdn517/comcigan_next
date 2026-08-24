package dev.rocky.comcitime;

import android.app.Activity;
import android.content.Context;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

// Design system for this app, styled after Apple's "Liquid Glass" material:
// translucent frosted panels over a deep background, a soft top-to-bottom
// specular sheen on every surface to read as glass catching light rather
// than flat color, fully-rounded capsule shapes, and springy/overshooting
// motion instead of a flat linear settle. Every screen routes through the
// helpers below (card()/pillX()/styleXButton()/styleInput()) rather than
// building its own drawables, so this file is the single place the whole
// app's look comes from.
public class UiKit {
    public static final int BG = 0xFF0B0D14;
    // Opaque reference tones -- kept as genuine opaque colors (not the
    // translucent glass fills themselves) because callers elsewhere blend()
    // subject colors toward these for muted dark-mode tinting; blend() mixes
    // raw RGB channels without regard to alpha, so a translucent value here
    // would silently mix everything toward white instead of toward a dark
    // tone. The actual frosted-glass panel fills are GLASS_FILL/
    // GLASS_FILL_DENSE below, used only by this file's own drawable builders.
    public static final int SURFACE = 0xFF1E212B;
    public static final int SURFACE_ALT = 0xFF262A36;
    // Translucent glass fills: low-alpha white over the dark background
    // reads as a lightened, translucent pane without needing a real-time
    // blur of whatever happens to be underneath (Android's View system has
    // no cheap way to blur arbitrary content behind an arbitrary sibling
    // view). The DENSE variant is a touch stronger so a nested element (an
    // input inside a card, say) reads as "more glass stacked on glass"
    // rather than disappearing into its parent.
    private static final int GLASS_FILL = 0x26FFFFFF;
    private static final int GLASS_FILL_DENSE = 0x38FFFFFF;
    public static final int BORDER = 0x40FFFFFF;
    public static final int TEXT_PRIMARY = 0xFFF5F6FA;
    public static final int TEXT_SECONDARY = 0xFFAEB4C4;
    public static final int ACCENT = 0xFFF2B94C;
    public static final int ACCENT_TEXT = 0xFF1B1200;
    public static final int CHANGED = 0xFFFF6B5E;

    private static final float CARD_RADIUS_DP = 22f;
    private static final float INPUT_RADIUS_DP = 14f;
    private static final float PILL_RADIUS_DP = 999f;

    // A translucent rounded panel plus a fading white sheen laid over its
    // top half -- the two-layer trick that makes a flat color read as glass
    // catching light from above instead of a plain tinted rectangle.
    private static Drawable glassPanel(int fillColor, int strokeColor, float radiusDp) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(fillColor);
        base.setCornerRadius(dpf(radiusDp));
        if (strokeColor != 0) base.setStroke(dp2(), strokeColor);

        GradientDrawable sheen = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x33FFFFFF, 0x00FFFFFF});
        sheen.setCornerRadius(dpf(radiusDp));

        return new LayerDrawable(new Drawable[]{base, sheen});
    }

    public static Drawable card() {
        return glassPanel(GLASS_FILL, BORDER, CARD_RADIUS_DP);
    }

    public static Drawable inputBg() {
        return glassPanel(GLASS_FILL_DENSE, BORDER, INPUT_RADIUS_DP);
    }

    public static Drawable pillOutline() {
        return glassPanel(GLASS_FILL_DENSE, BORDER, PILL_RADIUS_DP);
    }

    public static Drawable pillFilled(int color) {
        return glassPanel(color, 0, PILL_RADIUS_DP);
    }

    // A flat (unrounded) glass panel for a full-width, edge-to-edge bar --
    // e.g. the bottom tab bar -- where fully rounded corners would look odd
    // flush against the screen edge.
    public static Drawable glassBar() {
        return glassPanel(GLASS_FILL_DENSE, 0, 0f);
    }

    public static StateListDrawable primaryButtonBg() {
        Drawable normal = glassPanel(ACCENT, darken(ACCENT, 0.7f), PILL_RADIUS_DP);
        Drawable pressed = glassPanel(darken(ACCENT, 0.85f), darken(ACCENT, 0.7f), PILL_RADIUS_DP);
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    public static StateListDrawable secondaryButtonBg() {
        Drawable normal = glassPanel(GLASS_FILL_DENSE, BORDER, PILL_RADIUS_DP);
        Drawable pressed = glassPanel(darken(GLASS_FILL_DENSE, 0.8f), BORDER, PILL_RADIUS_DP);
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    public static void stylePrimaryButton(Button b) {
        b.setBackground(primaryButtonBg());
        b.setTextColor(ACCENT_TEXT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setPadding(dp(20), dp(12), dp(20), dp(12));
        b.setElevation(0);
        b.setStateListAnimator(null);
    }

    public static void styleSecondaryButton(Button b) {
        b.setBackground(secondaryButtonBg());
        b.setTextColor(TEXT_PRIMARY);
        b.setAllCaps(false);
        b.setPadding(dp(20), dp(12), dp(20), dp(12));
        b.setElevation(0);
        b.setStateListAnimator(null);
    }

    public static void styleInput(EditText e) {
        e.setBackground(inputBg());
        e.setTextColor(TEXT_PRIMARY);
        e.setHintTextColor(TEXT_SECONDARY);
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
    }

    public static void styleEyebrow(TextView t) {
        t.setTextColor(ACCENT);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.06f);
    }

    public static void styleBody(TextView t) {
        t.setTextColor(TEXT_PRIMARY);
        t.setTextSize(15);
    }

    public static void styleCaption(TextView t) {
        t.setTextColor(TEXT_SECONDARY);
        t.setTextSize(12);
    }

    public static int darken(int color, float factor) {
        int a = (color >> 24) & 0xFF;
        int r = (int) (((color >> 16) & 0xFF) * factor);
        int g = (int) (((color >> 8) & 0xFF) * factor);
        int b = (int) ((color & 0xFF) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static final int[] SWATCHES = {
            0xFF5B8CFF, 0xFFFF7A7A, 0xFF57C785, 0xFFF2B94C, 0xFFB57BFF,
            0xFF4CD3C2, 0xFFFF8FB1, 0xFFC9D14C, 0xFF7A93FF, 0xFFFF9F5B,
            0xFF6EE7B7, 0xFFFFD166, 0xFFEF476F, 0xFF9B5DE5, 0xFF00BBF9, 0xFFEDEEF3
    };

    public interface ColorPickCallback { void onPicked(int color); }

    public static void showColorPicker(Context ctx, ColorPickCallback cb) {
        android.app.Dialog dialog = new android.app.Dialog(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(card());
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(ctx);
        title.setText("색상 선택");
        title.setTextColor(TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        android.widget.GridLayout grid = new android.widget.GridLayout(ctx);
        grid.setColumnCount(4);
        for (int color : SWATCHES) {
            View swatch = new View(ctx);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color);
            bg.setCornerRadius(dp(6));
            swatch.setBackground(bg);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = dp(56);
            lp.height = dp(56);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            swatch.setLayoutParams(lp);
            swatch.setOnClickListener(v -> {
                cb.onPicked(color);
                dialog.dismiss();
            });
            grid.addView(swatch);
        }
        root.addView(grid);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        }

        // A real frosted-glass backdrop blur behind the dialog, matching how
        // Liquid Glass sheets sit over a blurred rest of the screen -- only
        // possible via RenderEffect (API 31+); older devices just fall back
        // to the dialog's normal dim scrim with no blur.
        View decor = (ctx instanceof Activity) ? ((Activity) ctx).getWindow().getDecorView() : null;
        if (decor != null && Build.VERSION.SDK_INT >= 31) {
            decor.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP));
            dialog.setOnDismissListener(d -> decor.setRenderEffect(null));
        }
        dialog.show();
    }

    // ---------- fluid, springy micro-animations ----------
    public static void attachBouncyPress(View v) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
                    break;
            }
            return false; // don't consume -- let the real click listener still fire
        });
    }

    public static void popIn(View v) {
        v.setAlpha(0f);
        v.setScaleX(0.92f);
        v.setScaleY(0.92f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
    }

    public static void crossFade(View outView, View inView, Runnable onSwapped) {
        outView.animate().alpha(0f).setDuration(100)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    onSwapped.run();
                    popIn(inView);
                }).start();
    }

    public static void slideAndFadeIn(View v, float fromDx) {
        v.setTranslationX(fromDx);
        v.setAlpha(0f);
        v.animate().translationX(0f).alpha(1f).setDuration(280)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
    }

    private static float density = 2.75f; // overwritten by init()
    private static boolean inited = false;

    public static void init(Context ctx) {
        if (!inited) {
            density = ctx.getResources().getDisplayMetrics().density;
            inited = true;
        }
    }

    public static int dp(int v) { return (int) (v * density); }
    private static float dpf(float v) { return v * density; }
    private static int dp2() { return Math.max(2, (int) (2 * density)); }
}
