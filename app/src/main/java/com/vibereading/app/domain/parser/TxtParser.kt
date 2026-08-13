package com.vibereading.app.domain.parser

import com.vibereading.app.domain.model.Chapter
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Ported from services/parser.py — 1:1 logic port.
 * Parses Chinese TXT novels into chapters by regex pattern matching.
 */
object TxtParser {

    // ── Regex patterns (ported from Python) ──

    // Standalone marker only: "第一章", "Chapter 5", "1:"
    private val MARKER_ONLY = Regex(
        """^第(?:[\u4e00-\u9fa5零一二三四五六七八九十百千万]+|\d+)[章回节卷]$""" +
        """|^Chapter\s+\d+$""" +
        """|^CHAPTER\s+\d+$""" +
        """|^\d+(?:\.\d+)?[：:]$""",
        RegexOption.IGNORE_CASE
    )

    // Chapter boundary: "第一章 风起云涌", "Chapter 5 The Beginning", "1.2：标题"
    private val CHAPTER_PATTERN = Regex(
        """^\s*(第(?:[\u4e00-\u9fa5零一二三四五六七八九十百千万]+|\d+)[章回节卷](?:\s+.+?)?)\s*$""" +
        """|^\s*(Chapter\s+\d+.*)$""" +
        """|^\s*(CHAPTER\s+\d+.*)$""" +
        """|^\s*(\d+(?:\.\d+)?[：:].+)$""",
        RegexOption.IGNORE_CASE
    )

    // Section/volume: "第一篇 红楼梦", "第二卷 风云", "卷三"
    private val SECTION_PATTERN = Regex(
        """^\s*(第(?:[\u4e00-\u9fa5零一二三四五六七八九十百千万]+|\d+)[篇卷](?:[：:].+|[\s\u4e00-\u9fa5].+)?)\s*$""" +
        """|^\s*(卷(?:[\u4e00-\u9fa5零一二三四五六七八九十百千万]+|\d+)(?:[：:].+|[\s\u4e00-\u9fa5].+)?)\s*$""",
        RegexOption.IGNORE_CASE
    )

    data class ChapterDict(
        val title: String,
        val section: String? = null,
        val content: String
    )

    /**
     * Parse raw text into a list of ChapterDict.
     * Mirrors the Python parse_text() state machine exactly.
     */
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
                // Blank line
                if (currentTitle != null) {
                    currentLines.add(line)
                } else {
                    preambleLines.add(line)
                }
                i++
                continue
            }

            val chapterMatch = CHAPTER_PATTERN.find(trimmed)
            val sectionMatch = SECTION_PATTERN.find(trimmed)

            if (chapterMatch != null) {
                // Save previous chapter
                if (currentTitle != null) {
                    saveChapter(currentTitle, currentSection, currentLines)
                } else if (preambleLines.any { it.isNotBlank() }) {
                    saveChapter("序章", null, preambleLines)
                }
                preambleLines.clear()

                // Extract title — take the first non-null group
                var title = chapterMatch.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: trimmed

                // If title is marker-only (e.g., "第一章"), peek ahead for subtitle
                if (MARKER_ONLY.containsMatchIn(title)) {
                    val nextI = i + 1
                    if (nextI < lines.size) {
                        val nextLine = lines[nextI].trim()
                        if (nextLine.isNotEmpty()
                            && nextLine.length <= 20
                            && !nextLine.startsWith(" ") && !nextLine.startsWith("\t")
                            && !CHAPTER_PATTERN.containsMatchIn(nextLine)
                            && !SECTION_PATTERN.containsMatchIn(nextLine)
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

            if (sectionMatch != null) {
                // Section marker — record but don't split
                pendingSection = sectionMatch.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: trimmed
                i++
                continue
            }

            // Normal text
            if (currentTitle != null) {
                currentLines.add(line)
            } else {
                preambleLines.add(line)
            }
            i++
        }

        // Save last chapter
        if (currentTitle != null) {
            saveChapter(currentTitle, currentSection, currentLines)
        } else if (preambleLines.any { it.isNotBlank() }) {
            // No chapter markers found — save everything as single chapter
            saveChapter("全文", null, preambleLines)
        }

        return chapters
    }

    /**
     * Join non-empty lines with double newlines, mirroring Python _join_paragraphs.
     */
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
        if (current.isNotEmpty()) {
            paragraphs.add(current.toString())
        }

        return paragraphs.joinToString("\n\n")
    }

    /**
     * Decode bytes to string, trying UTF-8 first, then GBK, then UTF-8 with errors.
     * Mirrors the Python upload flow encoding detection.
     *
     * 注意：`String(bytes, UTF_8)` 对非法 UTF-8 序列不会抛异常（而是替换为 U+FFFD），
     * 会导致 GBK 文件被静默解码成乱码。这里用严格解码器（REPORT）让非法序列抛异常，
     * 从而正确回退到 GB18030（兼容 GBK）。
     */
    fun decodeBytes(bytes: ByteArray): String {
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

    /**
     * Convert parsed ChapterDict list to Chapter domain objects for a given bookId.
     */
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
