package com.vibereading.app.domain.model

/**
 * LLM 配置默认值的唯一来源。
 *
 * 此前同一组默认值在实体、领域模型、仓库、设置仓库与设置页共 6 处各写一遍，
 * 改一处漏一处；新增默认值请加在这里。
 *
 * **不要**在 `AppDatabase` 的迁移链里引用本对象：那些 SQL 字面量是历史 schema 快照，
 * 必须保持当时的值，否则老库升级路径会被改写。
 */
object LlmDefaults {
    const val API_BASE = "https://api.deepseek.com"
    const val MODEL = "deepseek-v4-flash"
    const val CHAPTER_MAX_CHARS = 60_000
    const val MAX_OUTPUT_TOKENS = 32_768
    const val TEMPERATURE = 0.6f
    const val TOP_P = 1f
    const val ENABLE_THINKING = false
    const val ENABLE_EXPLAIN_THINKING = false
    const val AUTO_TRANSLATE_NEXT = false
}
