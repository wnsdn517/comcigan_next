package dev.rocky.comcitime

import android.graphics.Color
import kotlin.math.sign

/**
 * Ported from Kyant0/AndroidLiquidGlass's AdaptiveLuminanceGlassContent
 * demo (Apache-2.0): the same Rec. 709 relative-luminance formula, the
 * same sign(x) * x * x smoothing curve, and the same >0.5 black/white
 * threshold that demo uses to keep label/icon contrast readable against
 * whatever the glass is tinted with.
 *
 * The original samples a live capture of the screen behind the glass (a
 * Compose GraphicsLayer downscaled to a 5x5 thumbnail and averaged) --
 * this app's whole UI is one fixed dark theme with no varying backdrop to
 * capture in the first place, so the same formula is fed the glass
 * surface's own tint color instead (e.g. [UiKit.ACCENT] on the selected
 * bottom-tab indicator), which is the value that actually varies here.
 */
object AdaptiveLuminanceGlass {
    fun luminance(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    fun contrastColor(tintColor: Int): Int {
        val raw = luminance(tintColor) * 2f - 1f // remap [0,1] to [-1,1]
        val smoothed = sign(raw) * raw * raw
        val adjusted = (smoothed + 1f) / 2f
        return if (adjusted > 0.5f) Color.BLACK else Color.WHITE
    }
}
