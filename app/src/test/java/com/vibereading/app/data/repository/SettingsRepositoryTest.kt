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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
    fun `empty api base falls back to default`() = runBlocking {
        repository.saveLlmSettings(LlmSettings(apiKey = " key ", apiBase = " / ", model = " model "))
        val settings = repository.llmSettings.first()
        assertEquals("key", settings.apiKey)
        assertTrue(settings.apiBase.startsWith("http://") || settings.apiBase.startsWith("https://"))
        assertEquals("model", settings.model)
    }

    @Test
    fun `llm settings round trip clamps context chapters`() = runBlocking {
        repository.saveLlmSettings(
            LlmSettings(
                apiKey = "key",
                apiBase = "https://example.com/v1/",
                model = "model",
                contextChapters = 99,
                enableThinking = true
            )
        )
        val settings = repository.llmSettings.first()
        assertEquals("https://example.com/v1", settings.apiBase)
        assertEquals(3, settings.contextChapters)
        assertEquals(true, settings.enableThinking)
    }

    @Test
    fun `repositories can use isolated stores`() = runBlocking {
        val otherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val otherFile = File.createTempFile("vibe-settings-other", ".preferences_pb")
        try {
            val otherStore = PreferenceDataStoreFactory.create(scope = otherScope, produceFile = { otherFile })
            val other = SettingsRepository(RuntimeEnvironment.getApplication(), otherStore)
            repository.saveLlmSettings(LlmSettings(apiKey = "first"))
            assertNotEquals("first", other.llmSettings.first().apiKey)
        } finally {
            otherScope.cancel()
            otherFile.delete()
        }
    }
}
