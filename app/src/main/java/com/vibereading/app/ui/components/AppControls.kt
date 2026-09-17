package com.vibereading.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp

/**
 * iOS 风胶囊开关：无描边、灰色/主色轨道 + 白色圆钮。
 * 设计规范的 Toggle 标准形，设置页与 LLM 面板共用，不要再各自调 `SwitchDefaults`。
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = appSwitchColors()
    )
}

@Composable
private fun appSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedThumbColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
    uncheckedThumbColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    checkedBorderColor = Color.Transparent,
    uncheckedBorderColor = Color.Transparent
)

/**
 * 线性图标 + 浅色圆形底：设计规范的图标标准形（分区图标、统计磁贴共用）。
 * 圆底色 = 图标色的 12% 透明度。
 */
@Composable
fun IconCircle(
    icon: ImageVector,
    tint: Color,
    circleSize: Dp,
    iconSize: Dp
) {
    Box(
        modifier = Modifier
            .size(circleSize)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

