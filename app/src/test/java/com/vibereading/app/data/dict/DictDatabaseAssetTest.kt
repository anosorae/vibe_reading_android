package com.vibereading.app.data.dict

import androidx.test.core.app.ApplicationProvider
import com.vibereading.app.domain.model.DictEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 内嵌词典资产（assets/dict/ecdict.dict）端到端验证：
 * gzip 头解析期望尺寸 → 首次解压到 databases/ecdict.db → 只读打开 → 查询。
 * 覆盖真实验证构建产物可被运行时正确解压使用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictDatabaseAssetTest {

    @Test
    fun `asset extracts and serves lookups`() {
        val dict = DictDatabase.open(ApplicationProvider.getApplicationContext())

        // 首次 lookup 触发解压 + 打开
        val abandon: DictEntry? = dict.lookup("abandon")
        assertNotNull("应能查到 abandon", abandon)
        assertEquals("abandon", abandon!!.word)
        assertTrue("abandon 应有音标", !abandon.phonetic.isNullOrBlank())
        assertTrue("abandon 应有中文释义", !abandon.translation.isNullOrBlank())

        // 大小写与常见词
        assertEquals("world", dict.lookup("WORLD")!!.word)
        assertEquals("abandoned", dict.lookup("Abandoned")!!.word)
        assertEquals("children", dict.lookup("children")!!.word)
        assertEquals("well-known", dict.lookup("well-known")!!.word)

        // 未收录
        assertEquals(null, dict.lookup("zzzznope"))

        dict.close()
    }
}
