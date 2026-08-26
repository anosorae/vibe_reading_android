package com.vibereading.app.ui.reader.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.data.image.BookImageStore
import com.vibereading.app.domain.parser.IllustrationLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 正文插图块（ADR-002 D3/D5）：双语两侧共用同一张图，无气泡，不参与选词。
 *
 * - 滚动模式：按内容宽等比缩放取自然高度；
 * - 分页模式：排版期已把整图适配到单页（[fixedDisplayHeightPx]），渲染按该高度铺排，
 *   与 ChapterPaginator.fitImage 的口径严格一致；
 * - 加载失败/文件缺失显示占位框；[onClick] 非空时点击进入全屏预览。
 */
@Composable
fun ReadingIllustrationBlock(
    link: IllustrationLink,
    modifier: Modifier = Modifier,
    showSpacer: Boolean = true,
    fixedDisplayHeightPx: Float? = null,
    onClick: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    val spacerBottom = if (showSpacer) 10.dp else 0.dp

    // 正文宽内降采样解码（约 1080px 宽足够屏显），预览层再取全尺寸；
    // Pair(已加载?, 位图)：null=加载中，(true,null)=文件缺失/解码失败
    val loadState = produceState<Pair<Boolean, android.graphics.Bitmap?>?>(initialValue = null, key1 = link.path) {
        value = withContext(Dispatchers.IO) {
            try {
                true to BookImageStore.loadBitmap(link.path, targetWidth = 1080)
            } catch (_: Exception) {
                true to null
            }
        }
    }

    val clickModifier = if (onClick != null) {
        Modifier.clickable { onClick() }
    } else Modifier

    val baseModifier = modifier
        .fillMaxWidth()
        .then(clickModifier)
        .padding(bottom = spacerBottom)
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))

    if (fixedDisplayHeightPx != null) {
        // 分页模式：高度来自排版器，保证与分页测量一致
        Box(
            baseModifier.height(with(density) { fixedDisplayHeightPx.toDp() })
                .background(Color.Gray.copy(alpha = 0.12f))
        ) {
            IllustrationImage(loadState.value, Modifier.fillMaxSize())
        }
    } else {
        // 滚动模式：链接内声明比例的自然高度
        Box(
            baseModifier
                .fillMaxWidth()
                .aspectRatio(
                    (link.widthPx.toFloat() / link.heightPx.toFloat()).coerceIn(0.2f, 5f)
                )
                .background(Color.Gray.copy(alpha = 0.12f))
        ) {
            IllustrationImage(loadState.value, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun IllustrationImage(
    loadState: Pair<Boolean, android.graphics.Bitmap?>?,
    modifier: Modifier = Modifier
) {
    val (loaded, bitmap) = loadState ?: (false to null)
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = modifier
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                if (loaded) "插图无法加载" else "插图加载中…",
                fontSize = 12.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 全屏插图预览（ADR-002 D5）：黑幕 + 双指缩放/拖动，双击复位，单击关闭。
 * 视觉叠加层，不参与排版测量。加载全尺寸位图（targetWidth=0）。
 */
@Composable
fun IllustrationPreviewOverlay(
    path: String,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val bitmap = produceState<android.graphics.Bitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            try {
                BookImageStore.loadBitmap(path, targetWidth = 0)
                    ?: BookImageStore.loadBitmap(path, targetWidth = 1080)
            } catch (_: Exception) {
                null
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 8f)
                    offset += pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onDismiss() },
                    onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    }
                )
            }
    ) {
        val bmp = bitmap.value
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "插图预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
        } else {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        Text(
            "单击关闭 · 双指缩放 · 双击复位",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )
    }
}
