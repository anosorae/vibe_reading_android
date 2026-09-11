package com.vibereading.app.ui.reader

import com.vibereading.app.data.remote.TranslationEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * 只合并展示增量，不丢弃 token；首 token、阶段切换和终态立即送达。
 * 单一消费协程持有缓冲，避免定时任务与 SSE 并发修改文本；输入通道有界。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<TranslationEvent>.batchForDisplay(): Flow<TranslationEvent> = flow {
    coroutineScope {
        val input = this@batchForDisplay.produceIn(this)
        val ticks = Channel<Unit>(Channel.CONFLATED)
        val timer = launch {
            while (true) {
                delay(50)
                ticks.send(Unit)
            }
        }
        val text = StringBuilder()
        var thinking: Boolean? = null
        var progress: Int? = null
        suspend fun flush() {
            if (text.isNotEmpty()) {
                emit(if (thinking == true) TranslationEvent.Thinking(text.toString())
                    else TranslationEvent.Chunk(text.toString()))
                text.setLength(0)
            }
            progress?.let { emit(TranslationEvent.Progress(it)) }
            progress = null
        }
        var finished = false
        try {
            while (!finished) {
                select<Unit> {
                    ticks.onReceive { flush() }
                    input.onReceiveCatching { result ->
                        val event = result.getOrNull()
                        if (event == null) {
                            currentCoroutineContext().ensureActive()
                            flush()
                            // 异常继续交由协调器统一落日志并标记失败。
                            result.exceptionOrNull()?.let { throw it }
                            finished = true
                        } else when (event) {
                            is TranslationEvent.Thinking, is TranslationEvent.Chunk -> {
                                val nextThinking = event is TranslationEvent.Thinking
                                val phaseChanged = thinking != nextThinking
                                if (phaseChanged) flush()
                                thinking = nextThinking
                                text.append(when (event) {
                                    is TranslationEvent.Thinking -> event.text
                                    is TranslationEvent.Chunk -> event.text
                                    else -> error("非文本事件")
                                })
                                if (phaseChanged) flush()
                            }
                            is TranslationEvent.Progress -> progress = maxOf(progress ?: 0, event.chars)
                            else -> {
                                flush()
                                emit(event)
                            }
                        }
                    }
                }
            }
        } finally {
            timer.cancel()
            ticks.cancel()
            input.cancel()
        }
    }
}
