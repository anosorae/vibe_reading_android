package com.vibereading.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vibereading.app.data.local.AppDatabase
import com.vibereading.app.domain.model.LlmProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * LlmProfileRepository 单测：真实 Room 内存库验证 isActive 语义与默认档案迁移。
 */
@RunWith(RobolectricTestRunner::class)
class LlmProfileRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: LlmProfileRepository
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var storeFile: File

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        storeFile = File.createTempFile("vibe-llm-profiles", ".preferences_pb")
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { storeFile })
        repo = LlmProfileRepository(db.llmProfileDao(), SettingsRepository(context, store))
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        storeFile.delete()
    }

    private suspend fun insertProfile(name: String, isActive: Boolean): Long =
        db.llmProfileDao().insert(LlmProfile(name = name).let {
            com.vibereading.app.data.local.entity.LlmProfileEntity(
                name = it.name,
                apiKey = it.apiKey,
                apiBase = it.apiBase,
                model = it.model,
                chapterMaxChars = it.chapterMaxChars,
                maxOutputTokens = it.maxOutputTokens,
                enableThinking = it.enableThinking,
                enableExplainThinking = it.enableExplainThinking,
                autoTranslateNext = it.autoTranslateNext,
                temperature = it.temperature,
                topP = it.topP,
                isActive = isActive
            )
        })

    @Test
    fun `updateProfile keeps isActive unchanged`() = runBlocking {
        // 档案 A 活跃、B 非活跃；编辑 A 的模型名不应把 A 打回非活跃（否则翻译管线失去生效配置）
        val activeId = insertProfile("A", isActive = true)
        insertProfile("B", isActive = false)

        val edited = LlmProfile(
            id = activeId, name = "A", apiKey = "k2", model = "model-edited"
        )
        repo.updateProfile(edited)

        val stillActive = repo.activeProfile.first()
        assertNotNull("更新后活跃配置不应丢失", stillActive)
        assertEquals("A", stillActive!!.name)
        assertEquals("model-edited", stillActive.model)

        // 编辑非活跃档案也不应把它变成活跃
        val inactiveId = db.llmProfileDao().getAll().first().first { it.name == "B" }.id
        repo.updateProfile(LlmProfile(id = inactiveId, name = "B", model = "b-model"))
        assertEquals("A", repo.activeProfile.first()!!.name)
        val bRow = db.llmProfileDao().getAll().first().first { it.id == inactiveId }
        assertFalse(bRow.isActive)
        assertEquals("b-model", bRow.model)
    }

    @Test
    fun `ensureDefaultProfile migrates from DataStore once`() = runBlocking {
        assertNull(repo.activeProfile.first())
        repo.ensureDefaultProfile()
        val created = repo.activeProfile.first()
        assertNotNull("空表应创建默认档案", created)
        assertEquals("默认配置", created!!.name)

        // 表非空时幂等：不再重复创建，也不会清掉已有档案
        repo.ensureDefaultProfile()
        assertEquals(1, db.llmProfileDao().count())
    }
}
