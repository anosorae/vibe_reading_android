package com.vibereading.app.ui.reader

import androidx.compose.ui.graphics.Color
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.ui.theme.ChapterStatusDarkColors
import com.vibereading.app.ui.theme.VibeColors

/**
 * 一组章节状态色（亮/暗各一套，字段一一对应）。
 *
 * 为什么必须分两套：浅色档要满足「当正文用 ≥4.5:1」，深色档要满足「当图形用 ≥3:1」，
 * 而这两个亮度区间**不相交**（对米白底要对到很暗、对深底又要够亮），一个中间调无法兼顾。
 * `python tools/check_theme_contrast.py` 会核对两套取值。
 */
private data class StatusHues(
    val done: Color,
    val inProgress: Color,
    val failed: Color,
    val tooLong: Color,
    val pending: Color,
    /** 面板里的弱化说明文字（比状态点更强调可读性）。 */
    val mutedText: Color
)

private val LightStatusHues = StatusHues(
    done = VibeColors.Sage,
    inProgress = VibeColors.BlueMuted,
    failed = VibeColors.RedMuted,
    tooLong = VibeColors.Amber,
    pending = VibeColors.Outline,
    mutedText = VibeColors.WarmGray
)

private val DarkStatusHues = StatusHues(
    done = ChapterStatusDarkColors.Done,
    inProgress = ChapterStatusDarkColors.InProgress,
    failed = ChapterStatusDarkColors.Failed,
    tooLong = ChapterStatusDarkColors.TooLong,
    pending = ChapterStatusDarkColors.Pending,
    mutedText = ChapterStatusDarkColors.TextMuted
)

private fun hues(dark: Boolean) = if (dark) DarkStatusHues else LightStatusHues

/**
 * 章节状态 → 状态点/徽章颜色（顶栏圆点、目录、状态徽章共用同一映射）。
 *
 * [dark] 由调用方按**自己所在的那层表面**传入，不要全局统一取一个：阅读器正文上的圆点用
 * `ReaderBgPresets.isDark`（阅读背景），目录抽屉这类 Material 表面用 `LocalIsDarkTheme`。
 * 用错会出现「深色阅读背景上顶栏圆点几乎看不见」。
 */
fun chapterStatusColor(status: Int, dark: Boolean): Color {
    val h = hues(dark)
    return when (status) {
        Chapter.STATUS_DONE -> h.done
        Chapter.STATUS_IN_PROGRESS -> h.inProgress
        Chapter.STATUS_FAILED -> h.failed
        Chapter.STATUS_TOO_LONG -> h.tooLong
        else -> h.pending
    }
}

/**
 * 章节状态 → 翻译状态面板的提示文案与颜色。
 * 与 [chapterStatusColor] 语义不同（这里给的是「等待翻译」而非「待译」这类面板措辞），
 * 但同属状态到呈现的映射，集中在此处维护。
 */
fun chapterStatusHint(status: Int, dark: Boolean): Pair<String, Color> {
    val h = hues(dark)
    return when (status) {
        Chapter.STATUS_FAILED -> "翻译失败" to h.failed
        Chapter.STATUS_IN_PROGRESS -> "翻译中…" to h.inProgress
        Chapter.STATUS_TOO_LONG -> "章节过长" to h.tooLong
        else -> "等待翻译" to h.mutedText
    }
}

/** 该状态是否携带可展示的失败原因（[Chapter.STATUS_FAILED] / [Chapter.STATUS_TOO_LONG]）。 */
fun chapterStatusHasReason(status: Int): Boolean =
    status == Chapter.STATUS_FAILED || status == Chapter.STATUS_TOO_LONG
