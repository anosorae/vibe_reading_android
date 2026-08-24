package com.vibereading.app.domain.model

data class ReadingSettings(
    val fontSize: Int = 17,
    val fontFamily: String = "default", // 系统默认字体族："default"=跟随系统 UI 字体（非安卓默认 Roboto）；另有 serif/sans-serif/monospace
    val bgColorIndex: Int = 0,
    val lineSpacing: Int = 8,          // extra sp added on top of the base line height
    val paragraphSpacing: Int = 16,    // dp between paragraphs
    val pageFlipMode: String = FLIP_PAGER,
    // ── B 类参数（对齐 Legado ReadBookConfig） ──
    val paddingH: Int = 22,            // 页内左右边距（dp，Legado 微信读书预设 22）
    val paddingV: Int = 20,            // 页内上下边距（dp，正文区上下留白）
    val headerContentGap: Int = 20,    // 页眉与正文内容区间距（dp）
    val footerContentGap: Int = 20,    // 页脚与正文内容区间距（dp）
    val letterSpacing: Float = 0f,     // 字间距（em，-0.5~0.5，与字号成比例；Legado 预设 0.1）
    val justify: Boolean = true,       // 两端对齐（对齐 Legado textFullJustify 全局默认开）
    val indentEm: Float = 2f,          // 首行缩进（em，两全角空格 ≈ 2em）
    val titleMode: Int = 0,            // 章节标题：0 左对齐 / 1 居中 / 2 隐藏（默认值，不进面板）
    val bottomJustify: Boolean = true, // 底部对齐：页内行距重分布使末行沉底（默认值，不进面板）
    val oneHandMode: Boolean = false,  // 单手模式：分页模式下点击左右两侧均翻下一页（默认关）
    val customFontUri: String? = null, // SAF 导入的自定义字体 content:// URI（null=未导入）
    val fontId: String? = null,        // 内置开源字体 id（null=未选）；渲染优先级 customFontUri > fontId
    val enCustomFontUri: String? = null, // 英文字体自定义导入 URI（null=未导入）
    val enFontId: String? = null,      // 英文字体 id（null=跟随中文字体）；区别于中文 fontId，用于中英分体
    // ── 沉浸式（对齐 Legado ReadBookConfig hideStatusBar / hideNavigationBar） ──
    val hideStatusBar: Boolean = true,     // 阅读时隐藏状态栏（默认开）
    val hideNavigationBar: Boolean = true  // 阅读时隐藏导航栏（默认开）
) {
    companion object {
        // 翻页类型（对齐 Legado PageAnim）：上下滚动 / 平移 / 覆盖 / 无动画 / 仿真
        const val FLIP_SCROLL = "scroll"
        const val FLIP_PAGER = "pager"
        const val FLIP_COVER = "cover"
        const val FLIP_NO_ANIM = "no_anim"
        const val FLIP_SIMULATION = "simulation"

        // 标题模式（对齐 Legado titleMode）
        const val TITLE_MODE_LEFT = 0
        const val TITLE_MODE_CENTER = 1
        const val TITLE_MODE_HIDDEN = 2
    }
}

data class LlmSettings(
    val apiKey: String = "",
    val apiBase: String = "https://api.deepseek.com",
    val model: String = "deepseek-v4-flash",
    val chapterMaxChars: Int = 30000,
    val enableContextBoost: Boolean = false,
    val contextChapters: Int = 1,
    val contextMaxChars: Int = 50000,
    val enableThinking: Boolean = false,
    val enableExplainThinking: Boolean = false,
    val autoTranslateNext: Boolean = false, // 英文阅读时预译下一章
    val temperature: Float = 0.6f,
    val topP: Float = 1f
)
