package com.vibereading.app.domain.parser

/**
 * 插图链接（ADR-002 D3）：插图在章节原文中的唯一文本表示。
 *
 * 语法：`![插图](vrimg://{bookId}/{fileName} {width}x{height})`
 * - 链接作为真实字符存在于 [Chapter.content] 中，段落 offset 语义自动成立；
 * - 尺寸在导入期解码 bitmap bounds 后写入链接本身，排版无需二次探测；
 * - 分页、渲染、全屏预览均从本类派生，不存在独立的图片尺寸数据源。
 */
data class IllustrationLink(
    /** 包内资源键，形如 "42/a1b2c3.jpg"（bookId/文件名），解析到应用私有目录。 */
    val path: String,
    val widthPx: Int,
    val heightPx: Int
) {
    companion object {
        const val SCHEME = "vrimg"

        /**
         * 整段匹配插图链接。只有「整段就是一个链接」才算插图段——
         * 正文里恰好出现的方括号/圆括号文本绝不能误判成图片。
         */
        fun parse(paragraphText: String): IllustrationLink? {
            val m = FORMAT.matchEntire(paragraphText.trim()) ?: return null
            val path = m.groupValues[1]
            val w = m.groupValues[2].toIntOrNull()
            val h = m.groupValues[3].toIntOrNull()
            if (path.isEmpty() || w == null || h == null || w <= 0 || h <= 0) return null
            return IllustrationLink(path, w, h)
        }

        fun build(path: String, widthPx: Int, heightPx: Int): String =
            "![插图]($SCHEME://$path ${widthPx.coerceAtLeast(1)}x${heightPx.coerceAtLeast(1)})"
    }
}

/** 宽松整段匹配：alt 固定为「插图」，URL 部分不含空白，尺寸以 `WxH` 收尾。 */
private val FORMAT = Regex("""^!\[[^\]]*]\(vrimg://(\S+) (\d+)x(\d+)\)$""")
