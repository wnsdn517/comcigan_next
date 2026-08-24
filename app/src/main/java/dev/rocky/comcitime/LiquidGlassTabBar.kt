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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
 * [LiquidGlassShader.REFRACTION_AGSL]) -- driven through the real
 * `android.graphics.RuntimeShader`/`RenderEffect` platform APIs, not a
 * hand-rolled approximation. It's already used for buttons via
 * [LiquidGlassShader.attach] (View-based, self-content only).
 *
 * The pill here goes further and does real BACKDROP capture -- the same
 * "bend what's behind the glass" technique the actual demo uses -- via
 * Compose's own native `GraphicsLayer` capture API (`rememberGraphicsLayer`
 * / `drawLayer`, stable since Compose UI 1.7.0): the gradient band below
 * is recorded into an offscreen layer as it draws, and the pill redraws
 * that same captured layer (translated to align under itself) as its OWN
 * content before the real shader's RenderEffect bends it. Refracting a
 * flat self-color (an earlier version of this file) produces no visible
 * distortion at all -- there's nothing in a uniform fill for the shader to
 * bend -- which is why this capture step matters, not just the shader.
 *
 * Kyant0's own published library (`io.github.kyant0:backdrop`/`shapes`)
 * does the same capture internally, but every released version requires
 * compileSdk 36+ (2.0.0 needs 37, needing Android Gradle Plugin 9.x) --
 * see this file's git log for the earlier attempt at wiring it in.
 * Compose's own native capture API gets the same real effect without that
 * dependency, at this app's current compileSdk 34 / AGP 8.5.2.
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

    val backdropLayer = rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
    var pillOrigin by remember { mutableStateOf(Offset.Zero) }
    var pillSize by remember { mutableStateOf(IntSize.Zero) }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        val tabWidth = maxWidth / labels.size

        // The real backdrop content for the pill to bend: this app's
        // amber/violet/teal palette, recorded into backdropLayer as it
        // draws (drawWithContent runs every frame, before the pill below
        // reads it back) -- the same role the demo's colorful wallpaper
        // plays for its own backdrop capture.
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(32.dp))
                .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
                .drawWithContent {
                    backdropLayer.record { this@drawWithContent.drawContent() }
                    drawContent()
                }
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFFF2B94C), Color(0xFF8B6CF0), Color(0xFF4CD3C2))
                    )
                )
        )

        // The sliding glass capsule: redraws the captured backdrop
        // (translated to align under its own position) as its own
        // content, then the real shader bends that -- not a flat fill.
        Box(
            Modifier
                .padding(4.dp)
                .offset(x = tabWidth * animatedIndex.value)
                .width(tabWidth)
                .fillMaxHeight()
                .shadow(6.dp, RoundedCornerShape(percent = 50))
                .onGloballyPositioned {
                    pillOrigin = it.positionInRoot()
                    pillSize = it.size
                }
                .graphicsLayer {
                    shape = RoundedCornerShape(percent = 50)
                    clip = true
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
                .drawWithContent {
                    val delta = pillOrigin - backdropOrigin
                    translate(-delta.x, -delta.y) {
                        drawLayer(backdropLayer)
                    }
                    // A thin glass-surface highlight on top of the bent
                    // backdrop, matching the sheen buttons already use.
                    drawRect(Color.White.copy(alpha = 0.18f))
                    drawContent()
                }
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
