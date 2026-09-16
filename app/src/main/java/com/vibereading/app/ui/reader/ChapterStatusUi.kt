package com.vibereading.app.ui.reader

import androidx.compose.ui.graphics.Color
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.ui.theme.VibeColors

/** 章节状态 → 状态点/徽章颜色（顶栏圆点、目录、状态徽章共用同一映射）。 */
fun chapterStatusColor(status: Int): Color = when (status) {
    Chapter.STATUS_DONE -> VibeColors.Sage
    Chapter.STATUS_IN_PROGRESS -> VibeColors.BlueMuted
    Chapter.STATUS_FAILED -> VibeColors.RedMuted
    Chapter.STATUS_TOO_LONG -> VibeColors.Amber
    else -> VibeColors.Sand
}

/**
 * 章节状态 → 翻译状态面板的提示文案与颜色。
 * 与 [chapterStatusColor] 语义不同（这里给的是「等待翻译」而非「待译」这类面板措辞），
 * 但同属状态到呈现的映射，集中在此处维护。
 */
fun chapterStatusHint(status: Int): Pair<String, Color> = when (status) {
    Chapter.STATUS_FAILED -> "翻译失败" to VibeColors.RedMuted
    Chapter.STATUS_IN_PROGRESS -> "翻译中…" to VibeColors.BlueMuted
    Chapter.STATUS_TOO_LONG -> "章节过长" to VibeColors.Amber
    else -> "等待翻译" to VibeColors.WarmGray
}

/** 该状态是否携带可展示的失败原因（[Chapter.STATUS_FAILED] / [Chapter.STATUS_TOO_LONG]）。 */
fun chapterStatusHasReason(status: Int): Boolean =
    status == Chapter.STATUS_FAILED || status == Chapter.STATUS_TOO_LONG
