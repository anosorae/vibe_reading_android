package com.vibereading.app.ui.bookshelf

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 书架专属的字号刻度（设计稿实测值，见 `docs/ADR-006-bookshelf-visual-baseline.md`）。
 *
 * 字号是**从字形墨迹反推**的：拿纯汉字的卡片标题按「n 个汉字 = n em，墨迹约 0.92 em」
 * 解出 em 大小，再用墨迹高度交叉验证。所以取值都是 0.5sp 精度：
 * 卡片标题 ≈9.4sp、次要行 ≈8.5sp、底栏标签 ≈10sp、品牌名 ≈28.6sp。
 * 这里统一**向上取整半档**（标题 10 / 次要 9），因为再往下会踩到平台可读性下限，
 * 而 0.5sp 在 2x 屏上只有 1px，与设计稿肉眼无法区分。
 *
 * **为什么不并进 `AppTypography`**：设计基线的 3 列密排卡片需要 10sp / 9sp 两档，
 * 而 `AppTypography` 是全局共享刻度 —— 把 `titleSmall` 或 `labelSmall` 压到 9sp 会
 * 连带缩小设置页分区标题、阅读器章节标签和各类对话框里的文字。共享刻度只承载
 * 「全 App 应该一致」的字号；书架这种「密度驱动的局部刻度」单独放这里，改动不外溢。
 */
internal object ShelfTypography {
    /** 品牌名：设计稿墨迹 106×51px（两个汉字） */
    val brand = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)

    /** 品牌副标题：11 个全角字符 264px 宽 */
    val tagline = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)

    /** 排序行标签 */
    val sortLabel = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)

    /** 网格卡片书名（设计稿 4 个汉字 72px 宽 → em ≈ 9.4sp，汉字墨迹高 18px 交叉验证 ≈9.7sp） */
    val cardTitle = TextStyle(fontSize = 9.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold)

    /** 卡片次要行（「已读 N / M 章」、列表行的章节名与统计） */
    val cardMeta = TextStyle(fontSize = 8.5.sp, lineHeight = 14.sp)

    /** 卡片百分比与封面徽标 */
    val cardNumeric = TextStyle(fontSize = 9.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)

    /** 列表行书名 */
    val rowTitle = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)

    /** 底栏图标下的标签 */
    val navLabel = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
}
