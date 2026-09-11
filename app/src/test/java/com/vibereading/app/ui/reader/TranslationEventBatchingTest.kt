package com.vibereading.app.ui.reader

import com.vibereading.app.data.remote.TranslationEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationEventBatchingTest {
    @Test
    fun `rapid tokens preserve content with bounded display updates`() = runTest {
        val events = flow {
            emit(TranslationEvent.Started)
            repeat(1000) {
                emit(TranslationEvent.Chunk("字"))
                emit(TranslationEvent.Progress(it + 1))
                delay(1)
            }
            emit(TranslationEvent.Done("字".repeat(1000)))
        }.batchForDisplay().toList()
        val chunks = events.filterIsInstance<TranslationEvent.Chunk>()
        assertEquals("字".repeat(1000), chunks.joinToString("") { it.text })
        assertTrue("展示更新次数=${chunks.size}，应不超过 25", chunks.size <= 25)
        assertEquals(TranslationEvent.Done("字".repeat(1000)), events.last())
        assertEquals(TranslationEvent.Progress(1000), events[events.lastIndex - 1])
    }

    @Test
    fun `first token is immediate and pending text flushes during network silence`() = runTest {
        val events = mutableListOf<TranslationEvent>()
        val job = launch {
            flow {
                emit(TranslationEvent.Chunk("首"))
                emit(TranslationEvent.Chunk("尾"))
                awaitCancellation()
            }.batchForDisplay().collect { events.add(it) }
        }
        runCurrent()
        assertEquals(listOf(TranslationEvent.Chunk("首")), events)
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf(TranslationEvent.Chunk("首"), TranslationEvent.Chunk("尾")), events)
        job.cancelAndJoin()
    }

    @Test
    fun `phase switch and failure flush without mixing reasoning and content`() = runTest {
        val events = flow {
            emit(TranslationEvent.Thinking("想"))
            emit(TranslationEvent.Thinking("清楚"))
            emit(TranslationEvent.Chunk("译"))
            emit(TranslationEvent.Chunk("文"))
            emit(TranslationEvent.Error("中断"))
        }.batchForDisplay().toList()
        assertEquals(listOf(
            TranslationEvent.Thinking("想"), TranslationEvent.Thinking("清楚"),
            TranslationEvent.Chunk("译"), TranslationEvent.Chunk("文"),
            TranslationEvent.Error("中断")
        ), events)
    }

    @Test
    fun `normal close flushes pending text without inventing done`() = runTest {
        val events = flow {
            emit(TranslationEvent.Chunk("a"))
            emit(TranslationEvent.Chunk("b"))
            emit(TranslationEvent.Chunk("c"))
        }.batchForDisplay().toList()
        assertEquals(listOf(TranslationEvent.Chunk("a"), TranslationEvent.Chunk("bc")), events)
    }

    @Test
    fun `cancellation discards pending updates and stops timer`() = runTest {
        val events = mutableListOf<TranslationEvent>()
        val job = launch {
            flow {
                emit(TranslationEvent.Chunk("a"))
                emit(TranslationEvent.Chunk("b"))
                awaitCancellation()
            }.batchForDisplay().collect { events.add(it) }
        }
        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(TranslationEvent.Chunk("a")), events)
    }
}
