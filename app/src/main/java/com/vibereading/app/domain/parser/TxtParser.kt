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
 *   卷/部/篇/集/册识别为 section（卷），挂到后续章节；
 * - 段落：空行分段，段内换行拼接（保留原 Python 移植逻辑）。
 */
object TxtParser {

    // ── 标题规则（对齐 Legado txtTocRule 主规则，按优先级排列） ──

    // 章节序号：阿拉伯数字或中文数字（含大写壹贰…）
    private const val NUM = "[0-9〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,8}"

    // 特殊章节名（无序号）
    private const val SPECIAL =
        "(?:序章|序言|楔子|前言|文案|简介|内容简介|文章简介|终章|后记|尾声|番外|正文(?!完|结))"

    // 章节标题模式（组1 = 完整标题）
    private val CHAPTER_PATTERNS = listOf(
        // 1. 第N章/回/节/话/场/幕 + 可选副标题
        Regex("""^\s{0,4}(第\s{0,4}$NUM\s{0,4}[章回节话场幕][^\n]{0,30})$"""),
        // 2. 特殊章名 + 可选副标题
        Regex("""^\s{0,4}($SPECIAL[^\n]{0,30})$"""),
        // 3. Chapter/Section/Part/Episode/No. + 序号
        Regex("""^\s{0,4}((?:[Cc]hapter|[Ss]ection|[Pp]art|[Ee]pisode|[Nn][Oo]\.?)\s{0,4}\d{1,4}[^\n]{0,30})$"""),
        // 4. 数字 + 分隔符 + 标题（1、标题 / 1. 标题 / 1: 标题）
        Regex("""^\s{0,4}(\d{1,5}\s{0,4}[：:、.,，\-—][^\n]{0,30})$""")
    )

    // 卷/部/篇/集/册（作为 section，不拆章）
    private val SECTION_PATTERNS = listOf(
        Regex("""^\s{0,4}(第\s{0,4}$NUM\s{0,4}[卷部篇集册][^\n]{0,30})$"""),
        Regex("""^\s{0,4}(卷\s{0,4}$NUM[^\n]{0,30})$""")
    )

    // 纯标记标题（如「第一章」无副标题）：需要 peek 下一行取副标题
    private val MARKER_ONLY = Regex(
        """^(?:第\s*$NUM\s*[章回节话场幕])$""" +
            """|^(?:Chapter\s+\d+)$""" +
            """|^(?:$SPECIAL)$""",
        RegexOption.IGNORE_CASE
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

            val chapterTitle = matchFirst(CHAPTER_PATTERNS, trimmed)
            val sectionTitle = matchFirst(SECTION_PATTERNS, trimmed)

            if (chapterTitle != null) {
                if (currentTitle != null) {
                    saveChapter(currentTitle, currentSection, currentLines)
                } else if (preambleLines.any { it.isNotBlank() }) {
                    saveChapter("序章", null, preambleLines)
                }
                preambleLines.clear()

                var title = chapterTitle.trim()
                // 纯标记标题（如「第一章」）：peek 下一行作副标题
                if (MARKER_ONLY.containsMatchIn(title)) {
                    val nextI = i + 1
                    if (nextI < lines.size) {
                        val nextLine = lines[nextI].trim()
                        if (nextLine.isNotEmpty()
                            && nextLine.length <= 20
                            && matchFirst(CHAPTER_PATTERNS, nextLine) == null
                            && matchFirst(SECTION_PATTERNS, nextLine) == null
                        ) {
                            title = "$title $nextLine"
                            i = nextI + 1
                        } else {
                            i++
                        }
                    } else {
                        i++
                    }
                } else {
                    i++
                }

                currentTitle = title
                currentSection = pendingSection
                pendingSection = null
                currentLines = mutableListOf()
                continue
            }

            if (sectionTitle != null) {
                pendingSection = sectionTitle.trim()
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
