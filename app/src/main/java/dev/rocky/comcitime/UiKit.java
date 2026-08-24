package dev.rocky.comcitime;

import android.content.Context;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

// Small, deliberate design system for this app: a dim "blackboard navy"
// background with a single chalk-yellow accent, reserved coral for
// schedule-change alerts only. Visual language leans simple/retro on
// purpose -- squarer corners, visible chunky borders instead of soft
// blended glow, flat blocks instead of shadows, snappier ease-out motion
// instead of springy overshoot -- rather than a glossy "modern SaaS" look.
public class UiKit {
    // Liquid Glass Palette v12 (True Kyant "Ultimate" Style)
    public static final int BG = 0xFF000000; 
    public static final int SURFACE = 0x14FFFFFF; // 8% White - Kyant's base glass
    public static final int SURFACE_ALT = 0x22FFFFFF; // 13% White
    public static final int BORDER = 0x26FFFFFF; // 15% White - Sharp glass edge
    public static final int TEXT_PRIMARY = 0xFFFFFFFF; 
    public static final int TEXT_SECONDARY = 0x80FFFFFF; // 50% White - Vibrancy effect
    public static final int ACCENT = 0xFF007AFF; // Modern iOS Blue
    public static final int ACCENT_TEXT = 0xFFFFFFFF;
    public static final int CHANGED = 0xB3FF3B30;




    public static GradientDrawable card() {
        GradientDrawable d = new GradientDrawable();
        // Use a slightly darker center for better legibility on blurred backgrounds
        d.setColor(SURFACE);
        d.setCornerRadius(dp(32)); 
        d.setStroke(dp(1), BORDER);
        return d;
    }

    public static GradientDrawable glassPanel(int cornerRadiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(SURFACE);
        d.setCornerRadius(dp(cornerRadiusDp));
        d.setStroke(dp(1), BORDER);
        return d;
    }

    public static GradientDrawable inputBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x11FFFFFF);
        d.setCornerRadius(dp(16));
        d.setStroke(dp(1), 0x22FFFFFF);
        return d;
    }

    public static GradientDrawable pillOutline() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(SURFACE_ALT);
        d.setCornerRadius(dp(6));
        d.setStroke(dp2(), BORDER);
        return d;
    }

    public static GradientDrawable pillFilled(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(6));
        return d;
    }

    public static StateListDrawable primaryButtonBg() {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(ACCENT);
        normal.setCornerRadius(dp(999));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(darken(ACCENT, 0.8f));
        pressed.setCornerRadius(dp(999));
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    public static StateListDrawable secondaryButtonBg() {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x11FFFFFF);
        normal.setCornerRadius(dp(999));
        normal.setStroke(dp(1), 0x22FFFFFF);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0x22FFFFFF);
        pressed.setCornerRadius(dp(999));
        pressed.setStroke(dp(1), 0x44FFFFFF);
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    public static void stylePrimaryButton(Button b) {
        b.setBackground(primaryButtonBg());
        b.setTextColor(ACCENT_TEXT);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setAllCaps(false);
        b.setPadding(dp(24), dp(14), dp(24), dp(14));
        b.setElevation(0);
        b.setStateListAnimator(null);
    }

    public static void styleSecondaryButton(Button b) {
        b.setBackground(secondaryButtonBg());
        b.setTextColor(TEXT_PRIMARY);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setAllCaps(false);
        b.setPadding(dp(24), dp(14), dp(24), dp(14));
        b.setElevation(0);
        b.setStateListAnimator(null);
    }

    public static void styleInput(EditText e) {
        e.setBackground(inputBg());
        e.setTextColor(TEXT_PRIMARY);
        e.setHintTextColor(TEXT_SECONDARY);
        e.setPadding(dp(16), dp(14), dp(16), dp(14));
        e.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    }

    public static void styleEyebrow(TextView t) {
        t.setTextColor(ACCENT);
        t.setTextSize(12);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.05f);
        t.setAllCaps(true);
    }

    public static void styleBody(TextView t) {
        t.setTextColor(TEXT_PRIMARY);
        t.setTextSize(15);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        t.setLineSpacing(0, 1.1f);
    }

    public static void styleCaption(TextView t) {
        t.setTextColor(TEXT_SECONDARY);
        t.setTextSize(12);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
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
        dialog.show();
    }

    // ---------- simple, snappy micro-animations (no spring/overshoot) ----------
    public static void attachBouncyPress(View v) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(100)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    break;
            }
            return false; // don't consume -- let the real click listener still fire
        });
    }

    // Snappy spring-like interpolator for physics-based feel
    public static class SpringInterpolator implements android.view.animation.Interpolator {
        private double factor = 0.4;
        public SpringInterpolator(double factor) { this.factor = factor; }
        @Override
        public float getInterpolation(float input) {
            return (float) (Math.pow(2, -10 * input) * Math.sin((input - factor / 4) * (2 * Math.PI) / factor) + 1);
        }
    }

    public static void popIn(View v) {
        v.setAlpha(0f);
        v.setScaleX(0.92f);
        v.setScaleY(0.92f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(400)
                .setInterpolator(new SpringInterpolator(0.4)).start();
    }

    public static void telegramBouncy(View v) {
        v.setScaleX(0.7f);
        v.setScaleY(0.7f);
        v.setAlpha(0f);
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(500)
                .setInterpolator(new SpringInterpolator(0.45)).start();
    }

    public static void crossFade(View outView, View inView, Runnable onSwapped) {
        outView.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(200)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    onSwapped.run();
                    inView.setAlpha(0f);
                    inView.setScaleX(1.05f);
                    inView.setScaleY(1.05f);
                    inView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300)
                            .setInterpolator(new SpringInterpolator(0.5)).start();
                }).start();
    }

    public static void slideAndFadeIn(View v, float fromDx) {
        v.setTranslationX(fromDx);
        v.setAlpha(0f);
        v.animate().translationX(0f).alpha(1f).setDuration(450)
                .setInterpolator(new SpringInterpolator(0.6)).start();
    }

    // AGSL Shader Strings from Kyant's AndroidLiquidGlass
    private static final String ROUNDED_RECT_SDF = 
        "float radiusAt(float2 coord, float4 radii) {" +
        "    if (coord.x >= 0.0) { if (coord.y <= 0.0) return radii.y; else return radii.z; }" +
        "    else { if (coord.y <= 0.0) return radii.x; else return radii.w; }" +
        "}" +
        "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {" +
        "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));" +
        "    float outside = length(max(cornerCoord, 0.0)) - radius;" +
        "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);" +
        "    return outside + inside;" +
        "}" +
        "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {" +
        "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));" +
        "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) { return sign(coord) * normalize(max(cornerCoord, 0.0)); }" +
        "    else { float gradX = step(cornerCoord.y, cornerCoord.x); return sign(coord) * float2(gradX, 1.0 - gradX); }" +
        "}";

    private static final String REFRACTION_SHADER = 
        "uniform shader content;" +
        "uniform float2 size;" +
        "uniform float4 cornerRadii;" +
        "uniform float refractionHeight;" +
        "uniform float refractionAmount;" +
        ROUNDED_RECT_SDF +
        "float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }" +
        "half4 main(float2 coord) {" +
        "    float2 halfSize = size * 0.5;" +
        "    float2 centeredCoord = coord - halfSize;" +
        "    float radius = radiusAt(centeredCoord, cornerRadii);" +
        "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);" +
        "    if (-sd >= refractionHeight) return content.eval(coord);" +
        "    sd = min(sd, 0.0);" +
        "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;" +
        "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));" +
        "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius));" +
        "    return content.eval(coord + d * grad);" +
        "}";

    private static final String HIGHLIGHT_SHADER = 
        "uniform shader content;" +
        "uniform float2 size;" +
        "uniform float4 cornerRadii;" +
        "layout(color) uniform half4 color;" +
        "uniform float angle;" +
        "uniform float falloff;" +
        ROUNDED_RECT_SDF +
        "half4 main(float2 coord) {" +
        "    float2 halfSize = size * 0.5;" +
        "    float2 centeredCoord = coord - halfSize;" +
        "    float radius = radiusAt(centeredCoord, cornerRadii);" +
        "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));" +
        "    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);" +
        "    float2 normal = float2(cos(angle), sin(angle));" +
        "    float d = dot(grad, normal);" +
        "    float intensity = pow(abs(d), falloff);" +
        "    return content.eval(coord) + color * intensity;" +
        "}";

    public static void applyLiquidGlass(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.post(() -> {
                try {
                    int w = v.getWidth(), h = v.getHeight();
                    if (w <= 0 || h <= 0) return;
                    
                    RuntimeShader refractShader = new RuntimeShader(REFRACTION_SHADER);
                    refractShader.setFloatUniform("size", (float) w, (float) h);
                    float r = (float) dp(32);
                    refractShader.setFloatUniform("cornerRadii", r, r, r, r);
                    refractShader.setFloatUniform("refractionHeight", (float) dp(20));
                    refractShader.setFloatUniform("refractionAmount", (float) dp(12));
                    
                    RuntimeShader highShader = new RuntimeShader(HIGHLIGHT_SHADER);
                    highShader.setFloatUniform("size", (float) w, (float) h);
                    highShader.setFloatUniform("cornerRadii", r, r, r, r);
                    highShader.setColorUniform("color", 0x33FFFFFF); // Kyant style highlight
                    highShader.setFloatUniform("angle", (float) (-Math.PI / 4.0));
                    highShader.setFloatUniform("falloff", 3.0f);
                    
                    RenderEffect blur = RenderEffect.createBlurEffect(32f, 32f, Shader.TileMode.MIRROR);
                    RenderEffect refract = RenderEffect.createRuntimeShaderEffect(refractShader, "content");
                    RenderEffect high = RenderEffect.createRuntimeShaderEffect(highShader, "content");
                    
                    // Add subtle Vibrancy boost
                    android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                    cm.setSaturation(1.6f);
                    RenderEffect vibrant = RenderEffect.createColorFilterEffect(new android.graphics.ColorMatrixColorFilter(cm));

                    // Final chain: High -> Vibrant -> Refract -> Blur
                    RenderEffect chain = RenderEffect.createChainEffect(high, 
                        RenderEffect.createChainEffect(vibrant, 
                        RenderEffect.createChainEffect(refract, blur)));
                    
                    v.setRenderEffect(chain);
                } catch (Exception ignored) {}
            });
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.setRenderEffect(RenderEffect.createBlurEffect(25f, 25f, Shader.TileMode.MIRROR));
        }
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
    private static int dp2() { return Math.max(2, (int) (2 * density)); }
}
