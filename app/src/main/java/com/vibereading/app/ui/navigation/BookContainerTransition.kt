package com.vibereading.app.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale

internal const val BOOK_TRANSITION_MS = 520

/**
 * 对照参考录屏：书架静止，封面与正文从同一矩形放大，封面绕左书脊掀开；返回反向。
 * 直接旋转书架已加载的封面层，避免另解码封面、截图或绘制假的书页。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedTransitionScope.bookContainerBounds(
    bookId: Long,
    visibilityScope: AnimatedVisibilityScope,
    isCover: Boolean = true
): Modifier {
    val sharedContent = rememberSharedContentState(key = "book-container-$bookId")
    val coverAngle = if (isCover) {
        visibilityScope.transition.animateFloat(
            transitionSpec = { tween(BOOK_TRANSITION_MS, easing = LinearEasing) },
            label = "书脊开合角度"
        ) { state -> if (state == EnterExitState.Visible) 0f else -90f }
    } else null
    return Modifier.sharedBounds(
        sharedContentState = sharedContent,
        animatedVisibilityScope = visibilityScope,
        boundsTransform = { _, _ ->
            tween(durationMillis = BOOK_TRANSITION_MS, easing = CubicBezierEasing(0.2f, 0f, 0.4f, 1f))
        },
        enter = EnterTransition.None,
        exit = ExitTransition.None,
        zIndexInOverlay = if (isCover) 2f else 1f,
        // 保持正文最终布局，只缩放绘制层；不在每帧重新分页或改变正文换行。
        resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(ContentScale.FillBounds)
    ).then(if (isCover) Modifier.graphicsLayer {
        rotationY = if (sharedContent.isMatchFound) coverAngle?.value ?: 0f else 0f
        transformOrigin = TransformOrigin(0f, 0.5f)
        cameraDistance = size.width * 2.5f
    } else Modifier)
}
