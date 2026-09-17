package com.vibereading.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计系统的标准内容卡（Modern Minimal + Soft Card UI）：
 * 纯白卡面 + 24dp 大圆角 + 柔和低对比度投影 + 默认 24dp 内容内边距
 * （内边距保证文字/图标不贴圆角；需要通栏装饰时传 `PaddingValues(0.dp)`，如身份卡波浪）。
 */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    corner: Dp = 24.dp,
    contentPadding: PaddingValues = PaddingValues(24.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    softCardBox(modifier, corner) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

/** 可点击变体（整卡点击，如「关于」身份卡）。 */
@Composable
fun SoftCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = 24.dp,
    contentPadding: PaddingValues = PaddingValues(24.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    clickableSoftCardBox(onClick, modifier, corner) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

@Composable
private fun softCardBox(
    modifier: Modifier,
    corner: Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(corner)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.05f),
                spotColor = Color.Black.copy(alpha = 0.06f)
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        content = content
    )
}

@Composable
private fun clickableSoftCardBox(
    onClick: () -> Unit,
    modifier: Modifier,
    corner: Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(corner)
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.05f),
                spotColor = Color.Black.copy(alpha = 0.06f)
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        content = content
    )
}

