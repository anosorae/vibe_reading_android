package com.vibereading.app.domain.model

/**
 * 词典条目（ECDICT 精简版，只保留 4 列）。
 *
 * [translation] 为中文释义，多义项用 `\n` 分隔；[pos] 为词性比例
 * （如 "n:46/v:54"，基础版多为空）。字段可空表示源数据缺失。
 */
data class DictEntry(
    val word: String,
    val phonetic: String?,
    val translation: String?,
    val pos: String?
)
