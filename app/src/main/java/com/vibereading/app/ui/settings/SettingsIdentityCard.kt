package com.vibereading.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.BuildConfig
import com.vibereading.app.R
import com.vibereading.app.ui.components.SoftCard

@Composable
internal fun SettingsIdentityCard(
    onClick: (() -> Unit)? = null,
    showWaveDecoration: Boolean = false
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val cardModifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)

    if (onClick != null) {
        SoftCard(
            onClick = onClick,
            modifier = cardModifier,
            // 波浪装饰要通栏铺满卡片，内容内边距由 Row 自带（24/20）
            contentPadding = PaddingValues(0.dp)
        ) {
            IdentityCardContent(
                primary,
                secondary,
                onPrimary,
                showChevron = true,
                showWaveDecoration = showWaveDecoration
            )
        }
    } else {
        SoftCard(
            modifier = cardModifier,
            contentPadding = PaddingValues(0.dp)
        ) {
            IdentityCardContent(
                primary,
                secondary,
                onPrimary,
                showChevron = false,
                showWaveDecoration = showWaveDecoration
            )
        }
    }
}

@Composable
private fun IdentityCardContent(
    primary: androidx.compose.ui.graphics.Color,
    secondary: androidx.compose.ui.graphics.Color,
    onPrimary: androidx.compose.ui.graphics.Color,
    showChevron: Boolean,
    showWaveDecoration: Boolean
) {
    Box {
        if (showWaveDecoration) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(24.dp))
            ) {
                val wave = Path().apply {
                    moveTo(0f, size.height * 0.9f)
                    cubicTo(
                        size.width * 0.25f, size.height * 0.65f,
                        size.width * 0.45f, size.height * 0.98f,
                        size.width * 0.68f, size.height * 0.78f
                    )
                    cubicTo(
                        size.width * 0.84f, size.height * 0.62f,
                        size.width * 0.92f, size.height * 0.68f,
                        size.width, size.height * 0.55f
                    )
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(wave, color = primary.copy(alpha = 0.10f))
            }
        }
        Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                drawRoundRect(
                    brush = Brush.linearGradient(listOf(primary, secondary)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx())
                )
                val wave = Path().apply {
                    moveTo(0f, size.height * 0.72f)
                    cubicTo(
                        size.width * 0.28f, size.height * 0.54f,
                        size.width * 0.58f, size.height * 0.92f,
                        size.width, size.height * 0.62f
                    )
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(wave, color = onPrimary.copy(alpha = 0.22f))
            }
            Icon(
                painter = painterResource(R.drawable.ic_brand_mark),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = onPrimary
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "译读",
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "用 AI 让阅读没有语言的边界",
                fontSize = 13.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(2.dp))
            Text(
                "v${BuildConfig.VERSION_NAME}",
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showChevron) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        }
    }
}
