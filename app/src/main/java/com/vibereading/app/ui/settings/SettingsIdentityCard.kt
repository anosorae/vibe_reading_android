package com.vibereading.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.vibereading.app.BuildConfig

@Composable
internal fun SettingsIdentityCard(
    onClick: (() -> Unit)? = null,
    showWaveDecoration: Boolean = false
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val cardModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 10.dp)
    val cardShape = RoundedCornerShape(24.dp)
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            shape = cardShape,
            colors = cardColors
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
        Card(
            modifier = cardModifier,
            shape = cardShape,
            colors = cardColors
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
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                drawRoundRect(
                    brush = Brush.linearGradient(listOf(primary, secondary)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx())
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
                Icons.Filled.AutoStories,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = onPrimary
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("译读", style = MaterialTheme.typography.headlineSmall)
            Text(
                "用 AI 让阅读没有语言的边界",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(4.dp))
            Text(
                "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
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
