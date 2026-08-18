package com.vibereading.app.domain.model

/** LLM 词条解释结果（由选词「解释」功能调用大模型生成）。 */
data class WordExplanation(
    val lemma: String,              // 规范化词条（基本形式）
    val phonetic: String,           // 音标（英语 IPA / 中文拼音 / 日语罗马字）
    val pos: String,                // 词性（noun, verb, adjective 等）
    val definition: String,         // 释义
    val inflections: String,        // 词形变化（run: runs, running, ran / big: bigger, biggest）
    val synonyms: String,           // 近义词（逗号分隔）
    val antonyms: String,           // 反义词（逗号分隔）
    val collocations: String,       // 常见搭配（短列表）
    val difficulty: String          // CEFR 等级（A1–C2）
)
