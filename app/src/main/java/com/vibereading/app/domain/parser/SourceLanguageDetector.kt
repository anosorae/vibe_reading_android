package com.vibereading.app.domain.parser

/**
 * 书籍原文语言判定（ADR-003）：对样本文本抽前 [SAMPLE_PARAGRAPHS] 段，
 * CJK 字符占比 ≥ [CJK_RATIO_THRESHOLD] 判中文、否则判英文。
 * 日韩等非中英书不在支持范围，按占比天然归入中文分支，接受方向误判（可在书架修正）。
 *
 * EPUB 常见「卷首」空章节（纯封面页无正文）——检测必须跳过空/过小样本，
 * 从首个达到 [MIN_SAMPLE_CHARS] 的章节取样，否则空首章会被保守判成中文。
 */
object SourceLanguageDetector {

    const val ZH = "zh"
    const val EN = "en"

    /** 抽样段落数：取章节前若干段，避免遍历全章。 */
    const val SAMPLE_PARAGRAPHS = 20

    /** CJK 占比阈值：≥30% 判中文。 */
    const val CJK_RATIO_THRESHOLD = 0.30

    /** 抽样最小字符量：不足时继续取后续章节，避免空首章/纯封面页误判。 */
    const val MIN_SAMPLE_CHARS = 60

    // 中文汉字 + 扩展区 + 兼容区 + 日文假名 + 韩文谚文
    private val CJK_REGEX = Regex("[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF\u3040-\u30FF\uAC00-\uD7AF]")

    /** 判定单段章节文本的原文语言。样本为空时保守返回中文。 */
    fun detect(text: String): String {
        val sample = sampleOf(text)
        if (sample.isEmpty()) return ZH
        return ratioOf(sample)
    }

    /**
     * 按章节顺序判定一本书的原文语言：取首个抽样量达到 [MIN_SAMPLE_CHARS] 的章节
     * 判定；全部章节样本过小（极短书/纯插图书）时退化取首个非空章节；
     * 全书无文本时保守返回中文。
     */
    fun detectFirstNonBlank(chapterTexts: List<String>): String {
        val qualified = chapterTexts.firstOrNull { sampleOf(it).length >= MIN_SAMPLE_CHARS }
            ?: chapterTexts.firstOrNull { it.isNotBlank() }
            ?: return ZH
        return detect(qualified)
    }

    private fun sampleOf(text: String): String =
        text.split("\n\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(SAMPLE_PARAGRAPHS)
            .joinToString("")

    private fun ratioOf(sample: String): String {
        val cjkCount = CJK_REGEX.findAll(sample).count()
        val ratio = cjkCount.toDouble() / sample.length
        return if (ratio >= CJK_RATIO_THRESHOLD) ZH else EN
    }
}