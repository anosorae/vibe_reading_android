package com.vibereading.app.ui.reader.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.ui.reader.pagination.PageStyle
import com.vibereading.app.ui.theme.VibeColors

/**
 * A single bilingual paragraph pair: English text (tap to reveal Chinese).
 * 滚动模式（FLIP_SCROLL）使用；与分页模式共享同一 [PageStyle]
 * （行高/字号/首行缩进/字间距/两端对齐一致，ADR-001 D6）。
 */
@Composable
fun BilingualParagraph(
    englishText: String,
    chineseText: String,
    pageStyle: PageStyle,
    isDark: Boolean = false
) {
    var showOriginal by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    val textColor = if (isDark) VibeColors.Cream.copy(alpha = 0.9f) else VibeColors.Charcoal
    val originalColor = if (isDark) VibeColors.Stone else VibeColors.WarmGray
    val borderColor = if (isDark) VibeColors.Sand.copy(alpha = 0.3f) else VibeColors.Sand

    Column(modifier = Modifier.fillMaxWidth()) {
        // English paragraph
        Text(
            text = englishText,
            style = pageStyle.body,
            color = textColor,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showOriginal = !showOriginal }
                .padding(top = 4.dp, bottom = 4.dp)
        )

        // Chinese original (hidden by default, tap to reveal)
        AnimatedVisibility(
            visible = showOriginal,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                text = chineseText,
                style = pageStyle.cn,
                color = originalColor,
                fontStyle = FontStyle.Italic,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawLine(
                            color = borderColor,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                    // 排版已带首行缩进时不再叠加缩进 padding，否则中文会双重缩进
                    .then(
                        if (pageStyle.cn.textIndent != null) Modifier.padding(top = 2.dp, bottom = 8.dp)
                        else Modifier.padding(start = 16.dp, top = 2.dp, bottom = 8.dp)
                    )
            )
        }

        Spacer(Modifier.height(with(density) { pageStyle.paragraphSpacingPx.toDp() }))
    }
}

/**
 * Parse [N] markers from translated text into paired paragraphs.
 * Returns list of (englishText, chineseText) pairs.
 */
fun parseBilingualParagraphs(
    translatedContent: String,
    originalContent: String
): List<Pair<String, String>> {
    val originalParagraphs = originalContent
        .split("\n\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    // Split translation by [N] markers
    val markerRegex = Regex("""\[(\d+)]\s*""")
    val englishParts = translatedContent.split(markerRegex).filter { it.isNotBlank() }

    // After split by [N], we get alternating: empty/marker, text, empty/marker, text...
    // The split produces: ["", "1", "english text", "2", "english text", ...]
    val pairs = mutableListOf<Pair<String, String>>()
    var i = 0
    while (i < englishParts.size) {
        val part = englishParts[i].trim()
        if (part.all { it.isDigit() } && part.isNotEmpty()) {
            // This is a marker number
            val num = part.toIntOrNull() ?: 0
            i++
            if (i < englishParts.size) {
                val enText = englishParts[i].trim()
                val cnText = if (num in 1..originalParagraphs.size) {
                    originalParagraphs[num - 1]
                } else ""
                pairs.add(enText to cnText)
            }
        } else if (part.isNotEmpty()) {
            // No marker — treat as single block
            pairs.add(part to originalParagraphs.getOrElse(pairs.size) { "" })
        }
        i++
    }

    // Fallback: if no [N] markers found, split by double newlines
    if (pairs.isEmpty()) {
        val enParagraphs = translatedContent.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        enParagraphs.forEachIndexed { idx, en ->
            val cn = originalParagraphs.getOrElse(idx) { "" }
            pairs.add(en to cn)
        }
    }

    return pairs
}
