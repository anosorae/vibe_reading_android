package com.vibereading.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.vibereading.app.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LlmLegacyKeysStoreTest {

    @Before
    fun setUp() {
        AppLog.clear()
    }

    @After
    fun tearDown() {
        AppLog.clear()
    }

    @Test
    fun `data store read failure is logged and rethrown`() = runTest {
        val failure = IllegalStateException("broken store")
        val legacyStore = LlmLegacyKeysStore(failingStore(failure))

        val thrown = runCatching { legacyStore.migrateToProfile() }.exceptionOrNull()

        assertSame(failure, thrown)
        assertTrue(
            AppLog.logs.any { (_, message, throwable) ->
                message == "读取旧 LLM 配置失败" && throwable === failure
            }
        )
    }

    @Test
    fun `cancellation stays transparent and is not logged`() = runTest {
        val cancellation = CancellationException("cancelled")
        val legacyStore = LlmLegacyKeysStore(failingStore(cancellation))

        val thrown = runCatching { legacyStore.migrateToProfile() }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertTrue(AppLog.logs.isEmpty())
    }

    private fun failingStore(failure: Throwable): DataStore<Preferences> =
        object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw failure }

            override suspend fun updateData(
                transform: suspend (Preferences) -> Preferences
            ): Preferences = error("not used")
        }
}
