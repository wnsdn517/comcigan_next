package dev.rocky.comcitime

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.function.IntConsumer

/**
 * The bottom tab bar's sliding glass indicator.
 *
 * This app's Liquid Glass effect is the ACTUAL AGSL refraction shader
 * ported verbatim from Kyant0/AndroidLiquidGlass (see
 * [LiquidGlassShader.REFRACTION_AGSL]) -- driven directly through the real
 * `android.graphics.RuntimeShader`/`RenderEffect` platform APIs, not a
 * hand-rolled approximation. It's already used for buttons via
 * [LiquidGlassShader.attach] (View-based). The pill here reuses the exact
 * same shader/uniform setup ([LiquidGlassShader.buildRenderEffect]) through
 * Compose's own `Modifier.graphicsLayer { renderEffect = ... }`, which is
 * how it also gets applied to a Compose node instead of a plain View.
 *
 * Kyant0's own published library (`io.github.kyant0:backdrop`/`shapes`,
 * the Compose Multiplatform package that also wraps this same shader) was
 * tried first, but every released version -- including its oldest,
 * 1.0.1 -- requires compileSdk 36+, and the latest (2.0.0) requires 37,
 * which in turn requires Android Gradle Plugin 9.x. That's a major-version
 * AGP jump with wide-reaching, hard-to-verify-blind fallout for the rest
 * of this build (Gradle version, other plugin compat, DSL changes), far
 * outside the scope of a tab bar's visual effect -- so this reuses the
 * real shader directly instead of taking on that migration.
 */
@Composable
fun LiquidGlassTabBar(
    labels: List<String>,
    icons: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    val animatedIndex = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex) {
        animatedIndex.animateTo(selectedIndex.toFloat(), spring(dampingRatio = 0.7f, stiffness = 380f))
    }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        val tabWidth = maxWidth / labels.size

        // Decorative backing gradient (this app's amber/violet/teal
        // palette) for the sliding pill's own self-refraction to bend --
        // the same role UiKit's translucent fill + sheen plays under
        // buttons' glass shader.
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(32.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFFF2B94C), Color(0xFF8B6CF0), Color(0xFF4CD3C2))
                    )
                )
        )

        var pillSize by remember { mutableStateOf(IntSize.Zero) }
        Box(
            Modifier
                .padding(4.dp)
                .offset(x = tabWidth * animatedIndex.value)
                .width(tabWidth)
                .fillMaxHeight()
                .shadow(6.dp, RoundedCornerShape(percent = 50))
                .onSizeChanged { pillSize = it }
                .graphicsLayer {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        pillSize.width > 0 && pillSize.height > 0
                    ) {
                        renderEffect = LiquidGlassShader.buildRenderEffect(
                            pillSize.width,
                            pillSize.height,
                            32.dp.toPx(),
                            10.dp.toPx(),
                            8.dp.toPx()
                        )?.asComposeRenderEffect()
                    }
                }
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.White.copy(alpha = 0.22f))
        )

        Row(Modifier.fillMaxSize()) {
            for (i in labels.indices) {
                val interactionSource = remember { MutableInteractionSource() }
                val active = i == selectedIndex
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = interactionSource, indication = null) {
                            onTabSelected(i)
                        },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Same adaptive-contrast algorithm as the tab bar's own
                    // tint -- see AdaptiveLuminanceGlass's class doc.
                    val textColor = if (active) Color(AdaptiveLuminanceGlass.contrastColor(UiKit.ACCENT)) else Color.White
                    BasicText(icons[i], style = TextStyle(fontSize = 16.sp))
                    BasicText(
                        labels[i],
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = textColor,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }
            }
        }
    }
}

/**
 * Java-facing bridge: [LiquidGlassTabBar] is a `@Composable` function and
 * can't be called directly from Java (the compiler-injected `Composer`
 * parameter has no Java equivalent), so this plain class hosts it inside
 * a [ComposeView] and exposes a Java-callable [setSelectedIndex] --
 * constructed from `MainActivity.buildBottomNav()`, updated from
 * `MainActivity.showPage()`.
 */
class LiquidGlassTabBarController(
    composeView: ComposeView,
    labels: Array<String>,
    icons: Array<String>,
    initialIndex: Int,
    private val onTabSelected: IntConsumer
) {
    private val selectedIndex = mutableStateOf(initialIndex)

    init {
        val labelList = labels.toList()
        val iconList = icons.toList()
        composeView.setContent {
            LiquidGlassTabBar(labelList, iconList, selectedIndex.value) { i ->
                selectedIndex.value = i
                onTabSelected.accept(i)
            }
        }
    }

    fun setSelectedIndex(index: Int) {
        selectedIndex.value = index
    }
}
