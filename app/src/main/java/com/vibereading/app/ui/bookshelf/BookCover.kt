package com.vibereading.app.ui.bookshelf

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import com.vibereading.app.data.image.BookImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 书架封面：有 [coverPath]（EPUB 内置封面落盘，ADR-002 D6）时加载真实封面，
 * 失败/为空回退程序化渐变占位（按书名 hash 选色 → 横排书名 → 书脊高光）。
 */
@Composable
fun BookCover(
    title: String,
    modifier: Modifier = Modifier,
    coverPath: String? = null
) {
    val bitmap = coverPath?.let { path ->
        produceState<android.graphics.Bitmap?>(initialValue = null, key1 = path) {
            value = withContext(Dispatchers.IO) {
                try {
                    BookImageStore.coverFile(path).takeIf { it.exists() }
                        ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                } catch (_: Exception) {
                    null
                }
            }
        }.value
    }

    Box(modifier = modifier) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(2.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
            )
        } else {
            GradientCover(title = title, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 渐变占位封面：按书名 hash 从色板选一组渐变色 → 横排书名 → 书脊高光。 */
@Composable
private fun GradientCover(title: String, modifier: Modifier = Modifier) {
    val palette = CoverPalettes.PALETTE
    val (from, to) = palette[(title.hashCode() % palette.size + palette.size) % palette.size]

    Box(
        modifier = modifier
            .shadow(2.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(from, to),
                    start = Offset(0f, 0f),
                    end = Offset(400f, 520f)
                )
            )
    ) {
        // 书脊高光（左侧窄条，仿硬壳书脊反光）
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(6.dp)
                .fillMaxHeight(0.5f)
                .background(Color.White.copy(alpha = 0.18f))
        )
        // 顶/底暗角，增强立体感
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.10f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.12f)
                        )
                    )
                )
        )
        // 横排书名：居中、超长省略
        Text(
            text = title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/** 封面渐变色板：暖棕 / 冷绿 / 蓝灰 / 墨青 / 酒红 等低饱和组合。 */
private object CoverPalettes {
    val PALETTE = listOf(
        Color(0xFF8A5A44) to Color(0xFF5C3A2B),   // 暖棕
        Color(0xFF6B8F71) to Color(0xFF3E5543),   // 冷绿
        Color(0xFF5B7FA8) to Color(0xFF384F6B),   // 蓝灰
        Color(0xFF4A7B7D) to Color(0xFF2F5354),   // 墨青
        Color(0xFF9A5B5B) to Color(0xFF663A3A),   // 酒红
        Color(0xFF7A6BA0) to Color(0xFF4E4270),   // 紫灰
        Color(0xFFA07A4E) to Color(0xFF6B4F2F),   // 驼金
        Color(0xFF5E7A6B) to Color(0xFF3C5247)    // 松绿
    )
}
