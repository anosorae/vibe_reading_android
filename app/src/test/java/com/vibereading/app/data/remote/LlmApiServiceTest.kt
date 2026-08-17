package com.vibereading.app.data.remote

import com.vibereading.app.domain.model.LlmSettings
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmApiServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: LlmApiService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = LlmApiService()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun settings(
        apiBase: String = server.url("/v1").toString(),
        enableThinking: Boolean = false
    ) = LlmSettings(
        apiKey = "test-key",
        apiBase = apiBase,
        model = "test-model",
        enableThinking = enableThinking
    )

    @Test
    fun `stream parses chunks and emits one progress per chunk`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )

        val events = service.translateStream(settings(), "Title", "Paragraph").toList()
        assertEquals(listOf(TranslationEvent.Started, TranslationEvent.Chunk("Hello"), TranslationEvent.Progress(5), TranslationEvent.Chunk(" world"), TranslationEvent.Progress(11), TranslationEvent.Done("Hello world")), events)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertTrue(request.body.readUtf8().contains("\"model\":\"test-model\""))
    }

    @Test
    fun `reasoning delta is ignored when thinking is disabled`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data:{\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )

        val events = service.translateStream(settings(enableThinking = false), "Title", "Paragraph").toList()
        assertTrue(events.none { it is TranslationEvent.Thinking })
        assertTrue(events.contains(TranslationEvent.Chunk("answer")))
        assertTrue(events.contains(TranslationEvent.Done("answer")))
    }

    @Test
    fun `reasoning delta is surfaced only when thinking is enabled`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data:{\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )

        val events = service.translateStream(settings(enableThinking = true), "Title", "Paragraph").toList()
        assertTrue(events.contains(TranslationEvent.Thinking("thinking")))
        assertTrue(events.contains(TranslationEvent.Chunk("answer")))
        assertTrue(events.contains(TranslationEvent.Done("answer")))
    }

    @Test
    fun `finish reason length rejects incomplete response`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )

        val events = service.translateStream(settings(), "Title", "Paragraph").toList()
        assertTrue(events.none { it is TranslationEvent.Done })
        assertEquals(
            TranslationEvent.Error("模型输出达到长度上限，译文未完整生成"),
            events.last()
        )
    }

    @Test
    fun `long content chunks are all included until done`() = runTest {
        val chunks = (1..40).map { "segment-$it " }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    chunks.joinToString("") { chunk ->
                        "data: {\"choices\":[{\"delta\":{\"content\":\"$chunk\"}}]}\n\n"
                    } + "data: [DONE]\n\n"
                )
        )

        val events = service.translateStream(settings(), "Title", "Paragraph").toList()
        assertEquals(chunks.joinToString(""), events.filterIsInstance<TranslationEvent.Chunk>().joinToString("") { it.text })
        assertEquals(TranslationEvent.Done(chunks.joinToString("")), events.last())
    }

    @Test
    fun `empty done emits one error`() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))

        val errors = service.translateStream(settings(), "Title", "Paragraph").toList().filterIsInstance<TranslationEvent.Error>()
        assertEquals(listOf(TranslationEvent.Error("翻译结果为空")), errors)
    }

    @Test
    fun `eof before done emits an error`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")
        )

        val events = service.translateStream(settings(), "Title", "Paragraph").toList()
        assertTrue(events.last() == TranslationEvent.Error("流式响应提前结束"))
    }

    @Test
    fun `invalid api base becomes an error event`() = runTest {
        val events = service.translateStream(settings(apiBase = "not a url"), "Title", "Paragraph").toList()
        assertTrue(events.last() is TranslationEvent.Error)
    }

    @Test
    fun `test connection returns failure for invalid api base`() = runTest {
        val result = service.testConnection(settings(apiBase = ""))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("API Base") == true)
    }
}
