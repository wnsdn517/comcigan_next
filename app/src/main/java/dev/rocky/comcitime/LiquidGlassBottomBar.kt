package dev.rocky.comcitime

import android.content.Context
import android.widget.LinearLayout

/**
 * The bottom tab bar as a genuine Liquid Glass surface: [UiKit.glassBar]'s
 * translucent fill + sheen, with [LiquidGlassShader]'s real AGSL refraction
 * layered on top (API 33+; a plain translucent bar below that, same as
 * everywhere else in this design system).
 *
 * First Kotlin class in this otherwise-Java codebase -- new components are
 * being written in Kotlin going forward rather than attempting a big-bang
 * rewrite of the existing Java, which stays untouched and keeps
 * interoperating normally (this class is constructed directly from
 * [MainActivity.buildBottomNav], no bridging needed).
 *
 * Deliberately scoped to just this bar plus buttons/toggles
 * ([UiKit.stylePrimaryButton]/[UiKit.styleSecondaryButton]) rather than
 * every card on screen: each shader-refracted view needs its own GPU compositing
 * layer, a real cost that isn't worth paying for large, mostly-flat
 * surfaces where the effect barely reads anyway.
 */
class LiquidGlassBottomBar(context: Context) : LinearLayout(context) {
    init {
        orientation = LinearLayout.HORIZONTAL
        background = UiKit.glassBar()
        val pad = UiKit.dp(10)
        val padH = UiKit.dp(8)
        setPadding(padH, pad, padH, pad)
        LiquidGlassShader.attach(this, 0f, UiKit.dp(26).toFloat(), UiKit.dp(22).toFloat())
    }
}
