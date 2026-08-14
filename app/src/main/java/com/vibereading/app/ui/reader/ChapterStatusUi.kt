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
