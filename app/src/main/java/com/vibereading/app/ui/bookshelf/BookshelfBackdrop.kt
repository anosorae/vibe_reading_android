package com.vibereading.app.ui.bookshelf

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.vibereading.app.R
import com.vibereading.app.ui.theme.LocalIsDarkTheme

/**
 * 书架背景插画：固定在屏幕底部、通栏铺满，书卡从上面滚过去。
 *
 * 它是**背景**而不是列表末尾的一张图：资产宽高比 1.87 很扁，画面上半是墙面与窗光、
 * 下半是桌面上一本摊开的书和一枝植物，所以贴底铺满时构图完整（410dp 屏上 219dp 高，
 * 6 本书两行时插画完全不被遮挡）。**不要改成 `ContentScale.Crop` 铺满整屏**：按屏高缩放
 * 后横向只剩中间约 24%，书和植物都在右侧，会被裁光。
 *
 * 上下缘的 alpha 渐隐是资产自带的，所以它上边接得住任何强调色的页面底色 —— 这也是
 * 它只存一份位图就能跟着 6 套主题走的原因。
 *
 * 深色模式不画：这是一整幅浅色水彩，套在深底上只会变成一块发灰的雾。引文文字已随
 * 页脚一起移除（用户要求），所以深色档就是干净的页面底色。
 */
@Composable
internal fun ShelfBackdrop(modifier: Modifier = Modifier) {
    if (LocalIsDarkTheme.current) return
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val height = backdropHeight(maxWidth)
        Image(
            painter = painterResource(R.drawable.shelf_backdrop),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(height),
            // Crop + 底部对齐：正常比例下正好 1:1 不裁；被 heightIn 夹住时（横屏/矮屏）
            // 裁掉的是上方的墙面，保住画面下半的书和植物
            contentScale = ContentScale.Crop,
            alignment = Alignment.BottomCenter
        )
    }
}
