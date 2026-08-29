package com.vibereading.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.vibereading.app.domain.model.LlmSettings
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repository: SettingsRepository
    private lateinit var file: File

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = File.createTempFile("vibe-settings", ".preferences_pb")
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        repository = SettingsRepository(RuntimeEnvironment.getApplication(), store)
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun `migrateLlmKeysToProfile returns null when no keys exist`() = runBlocking {
        val result = repository.migrateLlmKeysToProfile()
        assertNull(result)
    }

    @Test
    fun `migrateLlmKeysToProfile reads existing DataStore keys`() = runBlocking {
        // 先写入旧格式的 LLM 键
        store.updateData { prefs ->
            val mutable = prefs.toMutablePreferences()
            mutable[stringPreferencesKey("api_key")] = "  test-key  "
            mutable[stringPreferencesKey("api_base")] = "  https://api.example.com/v1/  "
            mutable[stringPreferencesKey("model")] = "  gpt-4  "
            mutable[booleanPreferencesKey("enable_thinking")] = true
            mutable.toPreferences()
        }
        val result = repository.migrateLlmKeysToProfile()
        assertNotNull(result)
        val settings = result!!
        assertEquals("test-key", settings.apiKey)
        assertEquals("https://api.example.com/v1", settings.apiBase) // 去尾斜线
        assertEquals("gpt-4", settings.model)
        assertEquals(true, settings.enableThinking)
    }

    @Test
    fun `clearMigratedLlmKeys removes old keys and sets migrated flag`() = runBlocking {
        // 先写入旧键
        store.updateData { prefs ->
            val mutable = prefs.toMutablePreferences()
            mutable[stringPreferencesKey("api_key")] = "key"
            mutable.toPreferences()
        }
        repository.clearMigratedLlmKeys()
        // 再次迁移应该返回 null（已标记迁移）
        val result = repository.migrateLlmKeysToProfile()
        assertNull(result)
    }

    @Test
    fun `readingSettings defaults fontFamily to system default`() = runBlocking {
        val settings = repository.readingSettings.first()
        assertEquals("default", settings.fontFamily)
    }

    @Test
    fun `readingSettings normalizes legacy serif to system default`() = runBlocking {
        store.updateData { prefs ->
            val mutable = prefs.toMutablePreferences()
            mutable[stringPreferencesKey("font_family")] = "serif"
            mutable.toPreferences()
        }
        val settings = repository.readingSettings.first()
        assertEquals("default", settings.fontFamily)
    }

    @Test
    fun `readingSettings keeps explicit fontFamily value`() = runBlocking {
        store.updateData { prefs ->
            val mutable = prefs.toMutablePreferences()
            mutable[stringPreferencesKey("font_family")] = "monospace"
            mutable.toPreferences()
        }
        val settings = repository.readingSettings.first()
        assertEquals("monospace", settings.fontFamily)
    }
}

private fun stringPreferencesKey(name: String) = androidx.datastore.preferences.core.stringPreferencesKey(name)
private fun booleanPreferencesKey(name: String) = androidx.datastore.preferences.core.booleanPreferencesKey(name)
