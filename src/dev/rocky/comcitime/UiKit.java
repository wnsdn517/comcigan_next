package dev.rocky.comcitime;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

// Small, deliberate design system for this app: a dim "blackboard navy"
// background with a single chalk-yellow accent, reserved coral for
// schedule-change alerts only. Everything else stays quiet.
public class UiKit {
    public static final int BG = 0xFF14161D;
    public static final int SURFACE = 0xFF1E212B;
    public static final int SURFACE_ALT = 0xFF262A36;
    public static final int BORDER = 0xFF2E3341;
    public static final int TEXT_PRIMARY = 0xFFEDEEF3;
    public static final int TEXT_SECONDARY = 0xFF8B90A0;
    public static final int ACCENT = 0xFFF2B94C;
    public static final int ACCENT_TEXT = 0xFF14161D;
    public static final int CHANGED = 0xFFFF6B5E;

    public static GradientDrawable card() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(SURFACE);
        d.setCornerRadius(dp(14));
        d.setStroke(dp1(), BORDER);
        return d;
    }

    public static GradientDrawable inputBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(SURFACE_ALT);
        d.setCornerRadius(dp(10));
        d.setStroke(dp1(), BORDER);
        return d;
    }

    public static GradientDrawable pillOutline() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(SURFACE_ALT);
        d.setCornerRadius(dp(999));
        d.setStroke(dp1(), BORDER);
        return d;
    }

    public static GradientDrawable pillFilled(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(999));
        return d;
    }

    public static StateListDrawable primaryButtonBg() {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(ACCENT);
        normal.setCornerRadius(dp(10));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(darken(ACCENT, 0.85f));
        pressed.setCornerRadius(dp(10));
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    public static StateListDrawable secondaryButtonBg() {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(SURFACE_ALT);
        normal.setStroke(dp1(), BORDER);
        normal.setCornerRadius(dp(10));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(darken(SURFACE_ALT, 0.8f));
        pressed.setStroke(dp1(), BORDER);
        pressed.setCornerRadius(dp(10));
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
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setElevation(0);
        b.setStateListAnimator(null);
    }

    public static void styleSecondaryButton(Button b) {
        b.setBackground(secondaryButtonBg());
        b.setTextColor(TEXT_PRIMARY);
        b.setAllCaps(false);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setElevation(0);
        b.setStateListAnimator(null);
    }

    public static void styleInput(EditText e) {
        e.setBackground(inputBg());
        e.setTextColor(TEXT_PRIMARY);
        e.setHintTextColor(TEXT_SECONDARY);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
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

    private static int darken(int color, float factor) {
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
            bg.setCornerRadius(dp(10));
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
        dialog.show();
    }

    // ---------- juicy micro-animations ----------
    public static void attachBouncyPress(View v) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(6f)).start();
                    break;
            }
            return false; // don't consume -- let the real click listener still fire
        });
    }

    public static void popIn(View v) {
        v.setScaleX(0.85f); v.setScaleY(0.85f); v.setAlpha(0f);
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260)
                .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
    }

    public static void crossFade(View outView, View inView, Runnable onSwapped) {
        outView.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(140)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    onSwapped.run();
                    inView.setAlpha(0f);
                    inView.setScaleX(0.96f);
                    inView.setScaleY(0.96f);
                    inView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                }).start();
    }

    public static void slideAndFadeIn(View v, float fromDx) {
        v.setTranslationX(fromDx);
        v.setAlpha(0f);
        v.animate().translationX(0f).alpha(1f).setDuration(260)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.5f)).start();
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
    private static int dp1() { return Math.max(1, (int) (1 * density)); }
}
