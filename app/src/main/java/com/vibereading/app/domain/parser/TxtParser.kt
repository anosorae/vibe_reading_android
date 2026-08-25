package com.vibereading.app.domain.parser

import com.vibereading.app.domain.model.Chapter
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * TXT 小说解析：解码 + 按章切分（对齐 Legado TextFile / txtTocRule 的成熟思路）。
 *
 * - 解码：BOM（UTF-8 / UTF-16LE / UTF-16BE）→ UTF-8 严格 → GB18030，避免乱码；
 * - 分章：多组按优先级排列的标题规则（第N章/回/节、序章/楔子/番外等、Chapter/序号标题），
 *   单位字带负向断言排除正文误命中（第三节课/第一回合/第三部分…，对齐 Legado txtTocRule）；
 *   「正文」等易混淆特殊章名只在后接空白/冒号/序号时生效；
 *   卷/部/篇/集/册识别为 section（卷），挂到后续章节；支持两行式卷头合并与
 *   【第一章】式包裹括号；卷尾标记（第N卷 完）不产生幽灵分组；
 * - 段落：空行分段，段内换行拼接（保留原 Python 移植逻辑）。
 */
object TxtParser {

    // ── 标题规则（对齐 Legado txtTocRule 默认规则，按优先级排列） ──

    // 章节序号：阿拉伯数字或中文数字（含大写壹贰…）
    private const val NUM = "[0-9〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,8}"

    // 章节单位带负向断言（对齐 Legado）：排除「第三节课」「第一回合」「第一场电影」等正文行
    private const val UNIT_CH =
        "(?:章|回(?![合来事去])|节(?!课)|话|场(?![和合比电是])|幕)"

    // 卷单位带负向断言（对齐 Legado）：排除「第三部分」「第二篇张」「第一集合」等正文行
    private const val UNIT_SEC = "(?:卷|部(?![分赛游])|篇(?!张)|集(?![合和])|册)"

    // 低风险特殊章名：几乎不会出现在正文行首，可直接跟副标题
    private const val SPECIAL_LOOSE = "序章|序言|楔子|终章|后记|尾声|番外"

    // 高风险特殊章名：常出现在正文行首（如「正文内容…」曾把整章正文吞成假章节），
    // 只在后面是空白/冒号/序号/行尾时才算标题（对齐 Legado「正文 标题/序号」的严格口径）
    private const val SPECIAL_STRICT_BODY = "正文|前言|文案|(?:内容|文章)?简介"
    // 注意：BODY 本身是 | 交替式，必须先包一组再接前瞻，否则前瞻只约束最后一个分支
    private const val SPECIAL_STRICT = """(?:$SPECIAL_STRICT_BODY)(?=$|\s|[：:]|$NUM)"""

    // 特殊章名前缀：这类章节「路过」时不消费 pendingSection（见 parseText）
    private val SPECIAL_PREFIX = Regex("""^(?:$SPECIAL_LOOSE|$SPECIAL_STRICT_BODY)""")

    // 章节标题模式（组1 = 完整标题）
    private val CHAPTER_PATTERNS = listOf(
        // 1. 第N章/回/节/话/场/幕 + 可选副标题
        Regex("""^\s{0,4}(第\s{0,4}$NUM\s{0,4}$UNIT_CH[^\n]{0,30})$"""),
        // 2. 特殊章名 + 可选副标题
        Regex("""^\s{0,4}((?:$SPECIAL_LOOSE|$SPECIAL_STRICT)[^\n]{0,30})$"""),
        // 3. Chapter/Section/Part/Episode/No. + 序号
        Regex("""^\s{0,4}((?:[Cc]hapter|[Ss]ection|[Pp]art|[Ee]pisode|[Nn][Oo]\.?)\s{0,4}\d{1,4}[^\n]{0,30})$"""),
        // 4. 数字 + 分隔符 + 标题（1、标题 / 1. 标题 / 1: 标题）；分隔符后紧跟数字多为日期/小数，排除
        Regex("""^\s{0,4}(\d{1,5}\s{0,4}[：:、.,，\-—](?!\d)[^\n]{1,30})$""")
    )

    // 卷/部/篇/集/册（作为 section，不拆章）
    private val SECTION_PATTERNS = listOf(
        Regex("""^\s{0,4}(第\s{0,4}$NUM\s{0,4}$UNIT_SEC[^\n]{0,30})$"""),
        Regex("""^\s{0,4}(卷\s{0,4}$NUM[^\n]{0,30})$""")
    )

    // 纯标记章节标题（如「第一章」无副标题）：需要 peek 下一行取副标题
    private val MARKER_ONLY = Regex(
        """^(?:第\s{0,4}$NUM\s{0,4}$UNIT_CH)$""" +
            """|^(?:[Cc]hapter\s{1,4}\d{1,4})$""" +
            """|^(?:$SPECIAL_LOOSE|$SPECIAL_STRICT_BODY)$""",
        RegexOption.IGNORE_CASE
    )

    // 光杆卷标题（如「第二卷」无卷名）：需要 peek 下一行取卷名（两行式卷头）
    private val BARE_SECTION = Regex("""^(?:第\s{0,4}$NUM\s{0,4}[卷部篇集册]|卷\s{0,4}$NUM)$""")

    // 以全角句末标点/引号收尾的行更像中文正文语句（真实书例：「第一部天庭律法完成。」），
    // 不作副标题/卷名合并。注意不含半角 !? 等：「039：你愿意战斗吗?」这类
    // 半角标点收尾的编号标题是刻意章节名（发条新娘），不能误伤
    private val SENTENCE_END = Regex("""[。，！？…；：)”』」】〕〗］]$""")

    // 标记单位后有空格分隔：「第15章 买不买？」是刻意标题；
    // 无分隔且句末标点收尾的是正文语句（真实书例：「第一场大雪纷纷扬扬落下…」「第七场。」）
    private val UNIT_THEN_SPACE = Regex("""[章回节话场幕]\s""")

    // 包裹式括号标题（对齐 Legado「特殊符号」规则）：【第一章】出发 / 【第一卷】风起云涌
    private val BRACKET_CLOSE = mapOf(
        '【' to '】', '［' to '］', '〔' to '〕', '〖' to '〗',
        '[' to ']', '（' to '）', '《' to '》'
    )

    data class ChapterDict(
        val title: String,
        val section: String? = null,
        val content: String
    )

    /** 逐行解析：状态机分章。 */
    fun parseText(text: String): List<ChapterDict> {
        val lines = text.lines()
        val chapters = mutableListOf<ChapterDict>()

        var currentTitle: String? = null
        var currentSection: String? = null
        var currentLines = mutableListOf<String>()
        var preambleLines = mutableListOf<String>()
        var pendingSection: String? = null

        fun saveChapter(title: String, section: String?, lines: List<String>) {
            val content = joinParagraphs(lines)
            if (content.isNotBlank()) {
                chapters.add(ChapterDict(title = title, section = section, content = content))
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                if (currentTitle != null) currentLines.add(line) else preambleLines.add(line)
                i++
                continue
            }

            // 包裹式括号标题先剥壳再匹配（【第一章】出发）
            val probe = unwrapBrackets(trimmed)
            var chapterTitle = matchFirst(CHAPTER_PATTERNS, probe)
            // 句末标点收尾且标记后无空格分隔的行是正文语句
            // （「第一场大雪纷纷扬扬落下…」「2.能量传输系统线路升级…」），回退为正文段落
            if (chapterTitle != null && isProseLike(chapterTitle)) {
                chapterTitle = null
            }
            val sectionTitle = if (chapterTitle == null) matchFirst(SECTION_PATTERNS, probe) else null

            if (chapterTitle != null) {
                if (currentTitle != null) {
                    saveChapter(currentTitle, currentSection, currentLines)
                } else if (preambleLines.any { it.isNotBlank() }) {
                    saveChapter("序章", null, preambleLines)
                }
                preambleLines.clear()

                var title = chapterTitle.trim()
                // 纯标记标题（如「第一章」）：peek 下一行作副标题；
                // 句末标点收尾的行更像正文首句，不吞并
                if (MARKER_ONLY.matches(title)) {
                    val merged = peekMergeableTitle(lines, i + 1, skipBlanks = false)
                    if (merged != null) {
                        title = "$title ${merged.first}"
                        i = merged.second
                    } else {
                        i++
                    }
                } else {
                    i++
                }

                currentTitle = title
                // 卷归属持续到下一个卷头：没有新卷头时继承上一章的 section；
                // 特殊章（序章/楔子/番外…）只是路过，不消费 pendingSection，
                // 让卷头后的特殊章与正式章节都归该卷所有
                val incomingSection = pendingSection
                if (incomingSection != null) {
                    currentSection = incomingSection
                    if (!SPECIAL_PREFIX.containsMatchIn(title)) {
                        pendingSection = null
                    }
                }
                currentLines = mutableListOf()
                continue
            }

            // 卷头判定：句末标点收尾的是正文语句（真实书例：「第一部天庭律法完成。」），
            // 不消费该行、回退为正文段落；「第N卷 完」这类卷尾分隔标记直接丢弃，
            // 两者都不产生幽灵分组
            if (sectionTitle != null && !SENTENCE_END.containsMatchIn(sectionTitle)) {
                if (!sectionTitle.endsWith("完") && !sectionTitle.endsWith("终")) {
                    var section = sectionTitle
                    // 两行式卷头：「第二卷」（允许隔空行）后紧跟短行卷名时合并
                    if (BARE_SECTION.matches(section)) {
                        val merged = peekMergeableTitle(lines, i + 1, skipBlanks = true)
                        if (merged != null) {
                            section = "$section ${merged.first}"
                            i = merged.second
                        }
                    }
                    pendingSection = section
                }
                i++
                continue
            }

            if (currentTitle != null) currentLines.add(line) else preambleLines.add(line)
            i++
        }

        if (currentTitle != null) {
            saveChapter(currentTitle, currentSection, currentLines)
        } else if (preambleLines.any { it.isNotBlank() }) {
            saveChapter("全文", null, preambleLines)
        }

        return chapters
    }

    private fun matchFirst(patterns: List<Regex>, line: String): String? {
        for (p in patterns) {
            val m = p.find(line) ?: continue
            val g = m.groupValues.getOrNull(1)?.trim()
            if (!g.isNullOrEmpty()) return g
        }
        return null
    }

    /** 句末标点收尾、且标记单位后没有空格分隔的候选标题，按正文语句处理。 */
    private fun isProseLike(title: String): Boolean =
        SENTENCE_END.containsMatchIn(title) && !UNIT_THEN_SPACE.containsMatchIn(title)

    /**
     * 剥掉行首成对括号再匹配（对齐 Legado「特殊符号」规则）：
     * 「【第一章】出发」→「第一章 出发」，「【第一卷】风起云涌」→「第一卷 风起云涌」，
     * 整行包裹「【第一章 出发】」同样支持。「」『』等引号不剥，避免误伤对话行。
     */
    private fun unwrapBrackets(line: String): String {
        if (line.length < 3) return line
        val close = BRACKET_CLOSE[line[0]] ?: return line
        val endIdx = line.indexOf(close)
        if (endIdx < 0) return line
        val inner = line.substring(1, endIdx).trim()
        val rest = line.substring(endIdx + 1).trim()
        return when {
            inner.isEmpty() -> rest
            rest.isEmpty() -> inner
            else -> "$inner $rest"
        }
    }

    /**
     * 从 fromIndex 起寻找可合并为副标题/卷名的行。
     * 行必须非空、不超过 20 字、不以句末标点收尾、且自身不是章节/卷标题。
     * [skipBlanks] 为 true 时允许跳过最多两行空行（两行式卷头），否则只看紧邻一行
     * （「第一章」后隔空行的短行通常是正文首句，不能吞）。
     */
    private fun peekMergeableTitle(
        lines: List<String>,
        fromIndex: Int,
        skipBlanks: Boolean
    ): Pair<String, Int>? {
        var idx = fromIndex
        var blanksSkipped = 0
        while (idx < lines.size) {
            val nextLine = lines[idx].trim()
            if (nextLine.isEmpty()) {
                if (!skipBlanks || ++blanksSkipped > 2) return null
                idx++
                continue
            }
            if (nextLine.length > 20) return null
            if (SENTENCE_END.containsMatchIn(nextLine)) return null
            val probe = unwrapBrackets(nextLine)
            if (matchFirst(CHAPTER_PATTERNS, probe) != null) return null
            if (matchFirst(SECTION_PATTERNS, probe) != null) return null
            return nextLine to idx + 1
        }
        return null
    }

    /** 空行分段，段内换行拼接（原 Python 移植逻辑保留）。 */
    private fun joinParagraphs(lines: List<String>): String {
        val paragraphs = mutableListOf<String>()
        var current = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (current.isNotEmpty()) {
                    paragraphs.add(current.toString())
                    current = StringBuilder()
                }
            } else {
                if (current.isNotEmpty()) current.append("\n")
                current.append(trimmed)
            }
        }
        if (current.isNotEmpty()) paragraphs.add(current.toString())

        return paragraphs.joinToString("\n\n")
    }

    /**
     * 解码字节：BOM（UTF-8 / UTF-16LE / UTF-16BE）→ UTF-8 严格 → GB18030。
     * 注意：`String(bytes, UTF_8)` 对非法 UTF-8 不抛异常（替换为 U+FFFD），
     * 会导致 GBK 文件静默乱码；这里用严格解码器（REPORT）让非法序列抛异常，
     * 从而正确回退到 GB18030（兼容 GBK）。UTF-16 无 BOM 会被 UTF-8 严格解码拒掉
     * 后落入 GB18030，中文小说几乎不带无 BOM 的 UTF-16，可接受。
     */
    fun decodeBytes(bytes: ByteArray): String {
        // UTF-8 BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        // UTF-16 BOM
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
            }
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            try {
                String(bytes, Charset.forName("GB18030"))
            } catch (_: Exception) {
                String(bytes, StandardCharsets.UTF_8)
            }
        }
    }

    /** 解析结果转 Chapter 领域对象。 */
    fun toChapters(bookId: Long, chapterDicts: List<ChapterDict>): List<Chapter> {
        return chapterDicts.mapIndexed { index, dict ->
            Chapter(
                bookId = bookId,
                title = dict.title,
                section = dict.section,
                chapterIndex = index,
                content = dict.content,
                status = Chapter.STATUS_PENDING
            )
        }
    }
}
