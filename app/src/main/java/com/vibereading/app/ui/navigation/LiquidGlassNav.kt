package com.vibereading.app.ui.navigation

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vibereading.app.ui.bookshelf.ShelfMetrics
import com.vibereading.app.ui.bookshelf.ShelfTypography
import com.vibereading.app.ui.theme.LocalIsDarkTheme

/**
 * 液态玻璃悬浮 chrome（底栏 + 加号按钮）的实现。
 *
 * 材质口径（重要，别和另外两种模糊混为一谈）：
 * - 这里做的是「同一页面内容的**背景模糊**」：内容层每帧先 record 进离屏
 *   [GraphicsLayer]（Compose 1.7 `rememberGraphicsLayer`），玻璃容器再 `drawLayer`
 *   把这份离屏内容平移回本地坐标、施加 `RenderEffect` 模糊后垫在自己下面。
 * - 不是组件自模糊（`Modifier.blur` 模糊自己），也不是跨窗口模糊。
 * - `RenderEffect` 仅 API 31+ 生效；26–30 走降级：不绘制透出内容、色调加深，
 *   保证导航在任何设备上文字图标始终清晰。
 * - 真实的曲面折射无法用公开 API 稳定实现，用「背景模糊 + 半透明色调 +
 *   顶部镜面高光 + 边缘高光 + 柔和阴影」近似，不宣称完整复刻。
 *
 * [captureTick] 是内容层每帧自增的失效信号：玻璃节点在 draw 阶段读它，
 * 内容滚动/变化后下一帧同步重绘模糊底衬。
 */
@Composable
internal fun LiquidGlassSurface(
    cornerRadius: Dp,
    isDark: Boolean,
    captureLayer: GraphicsLayer,
    captureTick: State<Int>,
    modifier: Modifier = Modifier,
    elevation: Dp = 12.dp,
    blurRadius: Dp = 20.dp,
    accentTint: Color? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val blurSupported = Build.VERSION.SDK_INT >= 31
    // 无背景模糊时把色调加深，避免锐利透底与文字图标打架（宁可实一点也不能看不清）
    val fallbackBoost = if (blurSupported) 0f else 0.26f
    val surfaceColor = MaterialTheme.colorScheme.surface
    val layers = when {
        accentTint != null -> GlassLayers(
            tintTop = accentTint.copy(alpha = (0.80f + fallbackBoost / 2).coerceAtMost(0.96f)),
            tintBottom = accentTint.copy(alpha = (0.90f + fallbackBoost / 3).coerceAtMost(0.97f)),
            sheen = Color.White.copy(alpha = 0.30f),
            borderTop = Color.White.copy(alpha = 0.55f),
            borderBottom = Color.White.copy(alpha = 0.32f)
        )
        isDark -> GlassLayers(
            tintTop = surfaceColor.copy(alpha = (0.50f + fallbackBoost).coerceAtMost(0.95f)),
            tintBottom = surfaceColor.copy(alpha = (0.68f + fallbackBoost).coerceAtMost(0.96f)),
            sheen = Color.White.copy(alpha = 0.09f),
            borderTop = Color.White.copy(alpha = 0.30f),
            borderBottom = Color.White.copy(alpha = 0.16f)
        )
        else -> GlassLayers(
            tintTop = Color.White.copy(alpha = (0.52f + fallbackBoost).coerceAtMost(0.95f)),
            tintBottom = Color.White.copy(alpha = (0.68f + fallbackBoost).coerceAtMost(0.96f)),
            sheen = Color.White.copy(alpha = 0.26f),
            borderTop = Color.White.copy(alpha = 0.90f),
            borderBottom = Color.White.copy(alpha = 0.45f)
        )
    }
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    // 玻璃节点相对组合根的位置：全屏捕获层要按它平移回本地坐标，才能取到「栏后那一片」
    var nodePosition by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .onGloballyPositioned { nodePosition = it.positionInRoot() }
            .shadow(elevation = elevation, shape = shape, clip = false)
            .clip(shape)
    ) {
        // 第 1 层：模糊后的页面内容（真实背景模糊，仅 API 31+ 绘制）
        if (blurSupported) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val blurPx = blurRadius.toPx()
                        renderEffect = BlurEffect(blurPx, blurPx, TileMode.Decal)
                    }
                    .drawBehind {
                        val tick = captureTick.value
                        if (tick <= 0) return@drawBehind
                        drawIntoCanvas { canvas ->
                            val path = when (val outline =
                                shape.createOutline(size, layoutDirection, this)) {
                                is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                                else -> return@drawIntoCanvas
                            }
                            canvas.save()
                            canvas.clipPath(path)
                            translate(-nodePosition.x, -nodePosition.y) { drawLayer(captureLayer) }
                            canvas.restore()
                        }
                    }
            )
        }
        // 第 2 层：乳白色调 + 顶部镜面高光 + 边缘高光（不模糊，永远清晰）
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val radiusPx = cornerRadius.toPx()
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(layers.tintTop, layers.tintBottom)),
                        cornerRadius = CornerRadius(radiusPx)
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(layers.sheen, Color.Transparent),
                            startY = 0f,
                            endY = size.height * 0.6f
                        ),
                        cornerRadius = CornerRadius(radiusPx)
                    )
                    val strokeW = 1.dp.toPx()
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(
                                layers.borderTop,
                                layers.borderTop.copy(alpha = layers.borderTop.alpha * 0.22f),
                                layers.borderBottom
                            )
                        ),
                        topLeft = Offset(strokeW / 2f, strokeW / 2f),
                        size = Size(size.width - strokeW, size.height - strokeW),
                        style = Stroke(width = strokeW),
                        cornerRadius = CornerRadius((radiusPx - strokeW / 2f).coerceAtLeast(0f))
                    )
                }
        )
        content()
    }
}

private data class GlassLayers(
    val tintTop: Color,
    val tintBottom: Color,
    val sheen: Color,
    val borderTop: Color,
    val borderBottom: Color
)

/**
 * 液态玻璃悬浮底栏。
 *
 * 与旧「悬浮实底条」的差别：容器是半透明玻璃，背后的页面内容由
 * [LiquidGlassSurface] 每帧模糊后垫在下面，滚动时透出的背景随之变化；
 * 选中态是一枚整体平滑滑动的浅色胶囊（260ms 补间），不是每格各画各的。
 */
@Composable
internal fun GlassBottomBar(
    stableInsets: WindowInsets,
    selectedTab: AppTab,
    onSelect: (AppTab) -> Unit,
    captureLayer: GraphicsLayer,
    captureTick: State<Int>,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(stableInsets.only(WindowInsetsSides.Bottom))
            .padding(horizontal = ShelfMetrics.PagePadding)
            .padding(bottom = ShelfMetrics.NavBarBottomGap),
        contentAlignment = Alignment.BottomCenter
    ) {
        LiquidGlassSurface(
            cornerRadius = ShelfMetrics.NavBarCorner,
            isDark = isDark,
            captureLayer = captureLayer,
            captureTick = captureTick,
            modifier = Modifier
                .fillMaxWidth()
                .height(ShelfMetrics.NavBarHeight)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val tabs = AppTab.entries
                val cellWidth = maxWidth / tabs.size
                val inset = ShelfMetrics.NavItemInset
                // 选中胶囊滑动：260ms 补间（建议 200-300ms 区间的中点）；
                // 系统关闭动画时 Compose 会随 MotionDurationScale 直接落定
                val pillX by animateDpAsState(
                    targetValue = cellWidth * selectedTab.ordinal,
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                    label = "glassPillX"
                )
                Box(
                    modifier = Modifier
                        .offset(x = inset + pillX, y = inset)
                        .width(cellWidth - inset * 2)
                        .height(maxHeight - inset * 2)
                        .clip(RoundedCornerShape(ShelfMetrics.NavItemCorner))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(
                                        alpha = if (isDark) 0.28f else 0.20f
                                    ),
                                    MaterialTheme.colorScheme.primary.copy(
                                        alpha = if (isDark) 0.16f else 0.10f
                                    )
                                )
                            )
                        )
                )
                Row(modifier = Modifier.fillMaxSize()) {
                    tabs.forEach { tab ->
                        GlassTabCell(
                            tab = tab,
                            selected = tab == selectedTab,
                            onClick = { onSelect(tab) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.GlassTabCell(
    tab: AppTab,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 轻微按压形变；indication = null 关掉水波纹，玻璃上不做夸张反馈
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "glassTabPress"
    )
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 200),
        label = "glassTabTint"
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                tab.icon,
                contentDescription = tab.label,
                modifier = Modifier.size(22.dp),
                tint = tint
            )
            Text(tab.label, style = ShelfTypography.navLabel, color = tint)
        }
    }
}

/**
 * 与底栏同材质的蓝色玻璃加号按钮（上传书籍入口）。
 *
 * 为保证加号清晰，蓝色覆盖比中性玻璃更浓（alpha 0.8+），允许和底栏透明度不同；
 * 图标色用 `onPrimary`：浅色主题下是白色加号，深色主题自动切到深色，保证对比度。
 */
@Composable
internal fun GlassAddBookFab(
    onClick: () -> Unit,
    isDark: Boolean,
    captureLayer: GraphicsLayer,
    captureTick: State<Int>,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "glassFabPress"
    )
    Box(
        modifier = modifier
            .size(ShelfMetrics.NavFabSize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
    ) {
        LiquidGlassSurface(
            cornerRadius = ShelfMetrics.NavFabCorner,
            isDark = isDark,
            captureLayer = captureLayer,
            captureTick = captureTick,
            elevation = 14.dp,
            blurRadius = 16.dp,
            accentTint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(ShelfMetrics.NavFabSize)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "上传书籍",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}
