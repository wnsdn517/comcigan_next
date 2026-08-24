package dev.rocky.comcitime

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.function.IntConsumer

/**
 * Liquid Glass bottom tab bar, ported from Kyant0/AndroidLiquidGlass's
 * `LiquidBottomTabs` (Apache-2.0): a single frosted-glass capsule slides
 * (spring-damped) to sit behind whichever tab is selected, instead of each
 * tab drawing its own static highlight -- the same idea as iOS/Android
 * Liquid Glass tab bars. The capsule's own real AGSL refraction
 * ([LiquidGlassShader]) plus [UiKit.pillFilled]'s translucent+sheen fill
 * stand in for that source's blur/lens/highlight/inner-shadow stack:
 * porting the exact Compose-only rendering pipeline (GraphicsLayer-based
 * backdrop capture, used there so the pill can distort what's visually
 * behind it) has no equivalent in the classic View system without a much
 * larger custom compositing pipeline, but the physical sliding motion and
 * the adaptive-tint idea (see [AdaptiveLuminanceGlass]) are ported
 * directly.
 *
 * Owns all three tabs' visual state (label color/weight, indicator
 * position) so callers only need [setActiveTab] -- see
 * `MainActivity.showPage()`.
 */
class LiquidGlassBottomTabs(
    context: Context,
    private val labels: Array<String>,
    private val icons: Array<String>,
    // IntConsumer rather than a Kotlin (Int) -> Unit function type: this
    // class is constructed from Java (MainActivity), and a Java method
    // reference to a void method can satisfy IntConsumer's void accept(int)
    // directly, but cannot satisfy a Kotlin Function1<Integer, Unit> (Java
    // has no notion of automatically producing a Unit return value).
    private val onTabSelected: IntConsumer
) : FrameLayout(context) {

    private val indicator: View = View(context)
    private val tabRow: LinearLayout = LinearLayout(context)
    private val tabViews: Array<TextView> = Array(labels.size) { TextView(context) }
    private var activeIndex = 0
    private var indicatorAnimator: ValueAnimator? = null

    init {
        background = UiKit.glassBar()
        LiquidGlassShader.attach(this, 0f, UiKit.dp(26).toFloat(), UiKit.dp(22).toFloat())

        val padV = UiKit.dp(10)
        val padH = UiKit.dp(8)
        setPadding(padH, padV, padH, padV)

        indicator.background = UiKit.pillFilled(UiKit.ACCENT)
        addView(indicator, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))

        tabRow.orientation = LinearLayout.HORIZONTAL
        addView(tabRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        for (i in labels.indices) {
            val tv = tabViews[i]
            tv.text = icons[i] + "  " + labels[i]
            tv.textSize = 13f
            tv.gravity = Gravity.CENTER
            tv.setTextColor(UiKit.TEXT_SECONDARY)
            val idx = i
            tv.setOnClickListener { view ->
                UiKit.popIn(view)
                setActiveTab(idx, true)
                onTabSelected.accept(idx)
            }
            tabRow.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        // Re-place the indicator whenever the bar's own width becomes known
        // (first layout) or changes (rotation) -- same pattern as
        // LiquidGlassShader.attach's own layout listener.
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) setActiveTab(activeIndex, false)
        }
    }

    fun setActiveTab(index: Int, animate: Boolean) {
        activeIndex = index
        val innerWidth = width - paddingLeft - paddingRight
        if (innerWidth > 0) {
            val tabWidth = innerWidth / labels.size
            val targetX = (paddingLeft + index * tabWidth).toFloat()

            val lp = indicator.layoutParams
            lp.width = tabWidth
            indicator.layoutParams = lp

            indicatorAnimator?.cancel()
            if (animate) {
                indicatorAnimator = ValueAnimator.ofFloat(indicator.translationX, targetX).apply {
                    duration = 420
                    interpolator = UiKit.SpringInterpolator(0.5)
                    addUpdateListener { indicator.translationX = it.animatedValue as Float }
                    start()
                }
            } else {
                indicator.translationX = targetX
            }
        }

        // The selected label sits on top of the glass pill, tinted with
        // UiKit.ACCENT -- see AdaptiveLuminanceGlass's class doc for why
        // this, rather than a screen-backdrop capture, is what actually
        // varies in this app.
        val activeTextColor = AdaptiveLuminanceGlass.contrastColor(UiKit.ACCENT)
        for (i in tabViews.indices) {
            val active = i == index
            tabViews[i].setTextColor(if (active) activeTextColor else UiKit.TEXT_SECONDARY)
            tabViews[i].setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
