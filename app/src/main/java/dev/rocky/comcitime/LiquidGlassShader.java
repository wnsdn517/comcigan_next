package dev.rocky.comcitime;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;

// Real GPU-accelerated Liquid Glass refraction, ported verbatim (see
// REFRACTION_AGSL below) from Kyant0/AndroidLiquidGlass
// (https://github.com/Kyant0/AndroidLiquidGlass,
// backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/Shaders.kt,
// RoundedRectRefractionShaderString), licensed Apache License 2.0. That
// project is Compose Multiplatform-only; this app is plain Java Views
// with zero other dependencies, so rather than pulling in Kotlin/Compose
// just for this, the same AGSL (Android Graphics Shading Language) source
// is driven directly through android.graphics.RuntimeShader/RenderEffect
// -- both plain framework APIs, no third-party code involved.
//
// Applied via View.setRenderEffect(), this bends a view's own rendered
// pixels (UiKit's translucent fill + sheen) inward near its rounded
// corners/edges, the way a real curved-edge glass pane refracts whatever
// is directly beneath its own surface -- as opposed to a flat tint. It
// does NOT refract content behind/underneath the view in z-order (a true
// backdrop capture -- what Kyant's library does via a Compose
// GraphicsLayer snapshot -- has no equivalent in the classic View system
// without a custom per-frame bitmap-capture pipeline, which is a much
// larger and riskier change than this app's build/test setup can safely
// absorb blind). RuntimeShader requires API 33 (Tiramisu); below that,
// attach() is a no-op and UiKit's translucent+sheen look stands on its
// own, which is exactly the same graceful-degradation shape the rest of
// this design system already uses.
public class LiquidGlassShader {

    // Package-visible (not private): LiquidGlassTabBar.kt reuses this exact
    // same verbatim shader string for the Compose-hosted sliding tab
    // indicator via buildRenderEffect() below, instead of duplicating it.
    static final String REFRACTION_AGSL =
            "uniform shader content;\n" +
            "\n" +
            "uniform float2 size;\n" +
            "uniform float2 offset;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float refractionHeight;\n" +
            "uniform float refractionAmount;\n" +
            "uniform float depthEffect;\n" +
            "\n" +
            "float radiusAt(float2 coord, float4 radii) {\n" +
            "    if (coord.x >= 0.0) {\n" +
            "        if (coord.y <= 0.0) return radii.y;\n" +
            "        else return radii.z;\n" +
            "    } else {\n" +
            "        if (coord.y <= 0.0) return radii.x;\n" +
            "        else return radii.w;\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "    float outside = length(max(cornerCoord, 0.0)) - radius;\n" +
            "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n" +
            "    return outside + inside;\n" +
            "}\n" +
            "\n" +
            "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n" +
            "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n" +
            "    } else {\n" +
            "        float gradX = step(cornerCoord.y, cornerCoord.x);\n" +
            "        return sign(coord) * float2(gradX, 1.0 - gradX);\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "float circleMap(float x) {\n" +
            "    return 1.0 - sqrt(1.0 - x * x);\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 centeredCoord = (coord + offset) - halfSize;\n" +
            "    float radius = radiusAt(coord, cornerRadii);\n" +
            "\n" +
            "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n" +
            "    if (-sd >= refractionHeight) {\n" +
            "        return content.eval(coord);\n" +
            "    }\n" +
            "    sd = min(sd, 0.0);\n" +
            "\n" +
            "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n" +
            "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));\n" +
            "\n" +
            "    float2 refractedCoord = coord + d * grad;\n" +
            "    return content.eval(refractedCoord);\n" +
            "}\n";

    // Keeps the shader's "size"/"cornerRadii" uniforms in sync with the
    // view's actual laid-out bounds (0x0 at construction time, and
    // changing again on rotation/resize) -- a stale size would either show
    // no visible bend at all or refract the wrong rectangle entirely.
    public static void attach(View view, float cornerRadiusPx, float refractionHeightPx, float refractionAmountPx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        view.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int w = right - left, h = bottom - top;
            if (w <= 0 || h <= 0) return;
            if (w == oldRight - oldLeft && h == oldBottom - oldTop) return;
            apply(v, w, h, cornerRadiusPx, refractionHeightPx, refractionAmountPx);
        });
    }

    private static void apply(View view, int w, int h, float cornerRadiusPx, float refractionHeightPx, float refractionAmountPx) {
        RenderEffect effect = buildRenderEffect(w, h, cornerRadiusPx, refractionHeightPx, refractionAmountPx);
        if (effect != null) view.setRenderEffect(effect);
    }

    // Same shader/uniform setup as apply() above, factored out so
    // LiquidGlassTabBar.kt's Compose-hosted sliding indicator can drive the
    // identical real effect through Modifier.graphicsLayer's renderEffect
    // (via android.graphics.RenderEffect.asComposeRenderEffect()) instead
    // of View.setRenderEffect(). Returns null on API<33 or any driver
    // failure -- callers just leave their normal fill in place then.
    public static RenderEffect buildRenderEffect(int w, int h, float cornerRadiusPx, float refractionHeightPx, float refractionAmountPx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null;
        if (w <= 0 || h <= 0) return null;
        try {
            RuntimeShader rs = new RuntimeShader(REFRACTION_AGSL);
            rs.setFloatUniform("size", (float) w, (float) h);
            rs.setFloatUniform("offset", 0f, 0f);
            // Matches GradientDrawable's own auto-clamp for an oversized
            // corner radius (e.g. UiKit's fully-rounded capsule buttons
            // pass a deliberately huge radius) -- past half the shorter
            // side, the rounded-rect SDF below already degenerates into a
            // capsule/stadium shape on its own, but clamping keeps the
            // radiusAt() quadrant math well-behaved instead of relying on
            // that degeneracy.
            float radius = Math.min(cornerRadiusPx, Math.min(w, h) / 2f);
            rs.setFloatUniform("cornerRadii", radius, radius, radius, radius);
            rs.setFloatUniform("refractionHeight", refractionHeightPx);
            rs.setFloatUniform("refractionAmount", refractionAmountPx);
            rs.setFloatUniform("depthEffect", 0.3f);
            return RenderEffect.createRuntimeShaderEffect(rs, "content");
        } catch (Exception ignored) {
            // A driver/device quirk here just leaves UiKit's flat
            // translucent+sheen look in place -- never worth crashing over.
            return null;
        }
    }
}
