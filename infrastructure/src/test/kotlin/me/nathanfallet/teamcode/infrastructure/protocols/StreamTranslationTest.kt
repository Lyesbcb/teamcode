package me.nathanfallet.teamcode.infrastructure.protocols

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class StreamTranslationTest {
    private val translator = StreamTranslation()
    private val frames = listOf(
        """{"type":"message_start","message":{"id":"msg_a","usage":{"input_tokens":12,"output_tokens":0,"cache_read_input_tokens":8,"cache_creation_input_tokens":3}}}""",
        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Café ☕"}}""",
        """{"type":"content_block_stop","index":0}""",
        """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call_1","name":"calculate","input":{}}}""",
        """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"x\":"}}""",
        """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"2}"}}""",
        """{"type":"content_block_stop","index":1}""",
        """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"call_2","name":"clock","input":{}}}""",
        """{"type":"content_block_stop","index":2}""",
        """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":7}}""",
        """{"type":"message_stop"}""",
    )
    private fun source(values: List<String>) = flow {
        // One-byte chunks exercise split UTF-8, CRLF and arbitrary transport boundaries.
        values.forEach { value -> "data: $value\r\n\r\n".toByteArray().forEach { emit(byteArrayOf(it)) } }
    }
    private suspend fun collect(flow: Flow<ByteArray>): List<JsonObject> {
        val parser = SseFrames()
        return flow.toList().flatMap(parser::accept)
    }
    @Test fun translatesTextAndMultipleToolsWithUsageAndEmptyArguments() = runTest {
        val converted = translator.translate(source(frames), MESSAGES, RESPONSES, "gpt-test").toList()
        val values = collect(converted.asFlow())
        val completed = values.last()["response"]!!.jsonObject
        assertEquals("completed", completed["status"]!!.jsonPrimitive.content)
        assertEquals("gpt-test", completed["model"]!!.jsonPrimitive.content)
        assertEquals(23, completed["usage"]!!.jsonObject["input_tokens"]!!.jsonPrimitive.int)
        assertEquals(7, completed["usage"]!!.jsonObject["output_tokens"]!!.jsonPrimitive.int)
        val output = completed["output"]!!.jsonArray
        assertEquals("Café ☕", output[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("call_1", output[1].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("{\"x\":2}", output[1].jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals("{}", output[2].jsonObject["arguments"]!!.jsonPrimitive.content)
        val metadata = """data: {"type":"response.metadata","headers":{"x-codex-turn-state":"synthetic-private-state"}}""" + "\n\n"
        val withMetadata = converted.dropLast(1) + listOf(metadata.toByteArray()) + converted.takeLast(1)
        val back = collect(translator.translate(withMetadata.asFlow(), RESPONSES, MESSAGES, "claude-test"))
        assertFalse(back.toString().contains("synthetic-private-state"))
        assertEquals("message_stop", back.last()["type"]!!.jsonPrimitive.content)
        assertEquals("tool_use", back[back.lastIndex - 1]["delta"]!!.jsonObject["stop_reason"]!!.jsonPrimitive.content)
        assertEquals(15, back[back.lastIndex - 1]["usage"]!!.jsonObject["input_tokens"]!!.jsonPrimitive.int)
        assertEquals(8, back[back.lastIndex - 1]["usage"]!!.jsonObject["cache_read_input_tokens"]!!.jsonPrimitive.int)
    }
    @Test fun disconnectedAndMalformedStreamsNeverSynthesizeSuccess() = runTest {
        for (input in listOf(frames.dropLast(1), frames.filterNot { "message_start" in it }, listOf("{bad json"))) {
            val seen = mutableListOf<JsonObject>()
            val parser = SseFrames()
            assertFailsWith<TeamFailure> {
                translator.translate(source(input), MESSAGES, RESPONSES, "gpt-test").collect { seen += parser.accept(it) }
            }
            assertEquals("response.failed", seen.last()["type"]!!.jsonPrimitive.content)
            assertFalse(seen.any { it["type"] == JsonPrimitive("response.completed") })
        }
    }
    @Test fun downstreamFailureAndCancellationDoNotEmitReplacementEvents() = runTest {
        for (failure in listOf(IllegalStateException("client gone"), CancellationException("cancelled"))) {
            var received = 0
            val actual = assertFailsWith<Exception> {
                translator.translate(source(frames), MESSAGES, RESPONSES, "gpt-test").collect { received++; throw failure }
            }
            assertSame(failure, actual)
            assertEquals(1, received)
        }
    }
    @Test fun unsupportedReasoningCitationsAndRefusalsFailExplicitly() = runTest {
        val invalid = listOf(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","encrypted_content":"opaque"}}""",
            """{"type":"response.content_part.added","output_index":0,"content_index":0,"part":{"type":"output_text","text":"","annotations":[{"type":"url_citation"}]}}""",
            """{"type":"response.refusal.delta","output_index":0,"content_index":0,"delta":"refusal"}""",
            """{"type":"response.completed","response":{"id":"resp_a","usage":null}}""",
        )
        invalid.forEach { value ->
            assertFailsWith<TeamFailure> { collect(translator.translate(source(listOf(value)), RESPONSES, MESSAGES, "claude-test")) }
        }
    }
}
