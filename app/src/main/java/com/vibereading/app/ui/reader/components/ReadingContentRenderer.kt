package com.vibereading.app.ui.reader.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vibereading.app.ui.reader.ReaderPalette
import com.vibereading.app.ui.reader.pagination.PageStyle
import com.vibereading.app.ui.reader.pagination.ReaderMetrics
import com.vibereading.app.ui.reader.content.ReadingParagraph
import com.vibereading.app.domain.model.ReadingSettings
import java.util.Locale

@Composable
fun ReadingChapterTitle(
    section: String?,
    title: String,
    palette: ReaderPalette,
    pageStyle: PageStyle
) {
    if (pageStyle.titleMode == ReadingSettings.TITLE_MODE_HIDDEN) return
    val align = when (pageStyle.titleMode) {
        ReadingSettings.TITLE_MODE_CENTER -> TextAlign.Center
        else -> TextAlign.Start
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ReaderMetrics.TITLE_TOP_DP.dp)
    ) {
        if (section != null) {
            Text(
                text = section,
                style = pageStyle.cn.copy(textAlign = align),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = ReaderMetrics.SECTION_TITLE_GAP_DP.dp)
            )
        }
        Text(
            text = title,
            style = pageStyle.title.copy(textAlign = align),
            color = palette.titleText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = ReaderMetrics.TITLE_BOTTOM_DP.dp)
        )
    }
}

@Composable
fun ReadingParagraphItem(
    paragraph: ReadingParagraph,
    mode: String,
    pageStyle: PageStyle,
    palette: ReaderPalette,
    showSpacer: Boolean = true,
    selectionState: TextSelectionState? = null,
    paragraphKey: Any? = null
) {
    val translated = paragraph.translatedText?.takeIf { it.isNotBlank() }
    if (mode == "en" && translated != null) {
        BilingualParagraph(
            englishText = translated,
            chineseText = paragraph.sourceText,
            pageStyle = pageStyle,
            palette = palette,
            showSpacer = showSpacer,
            selectionState = selectionState,
            paragraphKey = paragraphKey
        )
    } else {
        // zh 模式或未翻译段落显示中文原文：按中文分词选词（词典为英→中，查词会提示仅支持英文）
        SelectableParagraphText(
            text = if (mode == "en") translated ?: paragraph.sourceText else paragraph.sourceText,
            style = pageStyle.body,
            color = palette.bodyText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (showSpacer) {
                    with(androidx.compose.ui.platform.LocalDensity.current) { pageStyle.paragraphSpacingPx.toDp() }
                } else 0.dp),
            selectionState = selectionState,
            paragraphKey = paragraphKey,
            locale = Locale.CHINESE,
            highlightColor = palette.selectionHighlight
        )
    }
}
