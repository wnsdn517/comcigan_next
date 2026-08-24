package dev.rocky.comcitime

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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import java.util.function.IntConsumer

/**
 * The bottom tab bar, using the ACTUAL Kyant0/AndroidLiquidGlass engine
 * (io.github.kyant0:backdrop + io.github.kyant0:shapes on Maven Central,
 * Apache-2.0) rather than a hand-rolled approximation: real backdrop
 * capture ([rememberLayerBackdrop]/[Modifier.layerBackdrop]) feeding a
 * real AGSL-shader refraction ([lens]), blur, and vibrancy
 * ([Modifier.drawBackdrop]) on a [Capsule]-shaped sliding indicator.
 *
 * This app's rest of the UI is plain Java Views with a fixed dark
 * background, which is why a hand-rolled version of this had nothing
 * visually rich to refract -- so this composable captures its own small
 * gradient band (this app's amber/violet/teal palette, not an unrelated
 * photo) as the backdrop specifically for the glass to bend, the same
 * role the original demo's colorful wallpaper plays.
 */
@Composable
fun LiquidGlassTabBar(
    labels: List<String>,
    icons: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabsBackdrop = rememberLayerBackdrop()
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

        // The real captured backdrop content for the glass to refract.
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(tabsBackdrop)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFFF2B94C), Color(0xFF8B6CF0), Color(0xFF4CD3C2))
                    )
                )
        )

        // The sliding glass capsule: real refraction/blur/vibrancy over
        // the gradient captured above, plus a real shadow + inner shadow.
        Box(
            Modifier
                .padding(4.dp)
                .offset(x = tabWidth * animatedIndex.value)
                .width(tabWidth)
                .fillMaxHeight()
                .drawBackdrop(
                    backdrop = tabsBackdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(2.dp.toPx())
                        lens(16.dp.toPx(), 12.dp.toPx(), chromaticAberration = true)
                    },
                    shadow = { Shadow(alpha = 0.35f) },
                    innerShadow = { InnerShadow(radius = 6.dp, alpha = 0.3f) },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.1f)) }
                )
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
